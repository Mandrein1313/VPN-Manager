package com.example.vpn.util;

import android.content.Context;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ตรวจเน็ตผ่านเส้นทางจริงของ VPN:
 * - ถ้ามี SOCKS local (1080/1081) → CONNECT ผ่าน SOCKS
 * - ไม่ใช้ socket ตรงของ process ที่ถูก exclude จาก VPN (ผลลัพธ์หลอก)
 */
public class ConnectivityChecker {

    public interface Callback {
        void onReady();
        void onFailed(String reason);
    }

    private final Context ctx;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public ConnectivityChecker(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void check(Callback cb) {
        cancelled.set(false);
        exec.execute(() -> {
            boolean ok = false;
            String reason = "unknown";
            try {
                for (int attempt = 1; attempt <= 5 && !cancelled.get(); attempt++) {
                    VpnLogger.i("ConnectivityChecker", "Attempt " + attempt + "/5");
                    Result r = runOnce();
                    if (r.ok) {
                        ok = true;
                        reason = "DNS + TCP OK";
                        VpnLogger.i("ConnectivityChecker", "✅ Connectivity verified (" + reason + ")");
                        break;
                    }
                    VpnLogger.w("ConnectivityChecker",
                            "Attempt " + attempt + " failed — DNS=" + r.dnsOk + " TCP=" + r.tcpOk);
                    if (attempt < 5) {
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                    } else {
                        reason = "Connection not verified";
                        VpnLogger.e("ConnectivityChecker", "❌ Failed after 5 attempts");
                    }
                }
            } catch (Exception e) {
                reason = e.getMessage() != null ? e.getMessage() : "error";
            }
            final boolean fOk = ok;
            final String fReason = reason;
            if (cb != null) {
                if (fOk) cb.onReady();
                else cb.onFailed(fReason);
            }
        });
    }

    private static class Result {
        boolean ok;
        boolean dnsOk;
        boolean tcpOk;
    }

    private Result runOnce() {
        Result r = new Result();
        // DNS (ระบบ) — แค่ข้อมูลเสริม
        try {
            long t0 = System.currentTimeMillis();
            InetAddress[] a = InetAddress.getAllByName("google.com");
            if (a != null && a.length > 0) {
                r.dnsOk = true;
                VpnLogger.d("ConnectivityChecker",
                        "DNS google.com → " + a[0].getHostAddress()
                                + " (" + (System.currentTimeMillis() - t0) + "ms)");
            }
        } catch (Exception e) {
            VpnLogger.w("ConnectivityChecker", "DNS google.com failed: " + e.getMessage());
        }

        // เส้นทางหลัก: SOCKS ของแอป (1081 V2Ray / 1080 SSH)
        int[] ports = new int[] { 1081, 1080 };
        for (int port : ports) {
            if (trySocksConnect(port, "1.1.1.1", 80)) {
                r.tcpOk = true;
                r.ok = r.dnsOk || true; // TCP ผ่าน SOCKS = เน็ตใช้ได้จริง
                r.ok = true;
                VpnLogger.d("ConnectivityChecker", "TCP via SOCKS :" + port + " → 1.1.1.1:80 ✅");
                return r;
            }
            if (trySocksConnect(port, "8.8.8.8", 53)) {
                r.tcpOk = true;
                r.ok = true;
                VpnLogger.d("ConnectivityChecker", "TCP via SOCKS :" + port + " → 8.8.8.8:53 ✅");
                return r;
            }
        }

        // fallback: ตรง (มักหลอกเพราะ app ถูก exclude จาก VPN)
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("1.1.1.1", 80), 4000);
            r.tcpOk = s.isConnected();
            VpnLogger.d("ConnectivityChecker",
                    "TCP direct 1.1.1.1:80 " + (r.tcpOk ? "✅ (อาจนอก VPN)" : "❌"));
        } catch (Exception e) {
            VpnLogger.d("ConnectivityChecker", "TCP direct fail: " + e.getMessage());
        }

        // ถ้ามีแค่ direct ผ่าน อย่านับว่า VPN OK
        r.ok = false;
        return r;
    }

    private boolean trySocksConnect(int socksPort, String host, int port) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", socksPort), 1500);
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write(new byte[] { 0x05, 0x01, 0x00 });
            out.flush();
            byte[] resp = new byte[2];
            if (in.read(resp) < 2 || resp[0] != 0x05 || resp[1] != 0x00) return false;

            // IPv4 CONNECT
            byte[] ip = InetAddress.getByName(host).getAddress();
            if (ip.length != 4) return false;
            byte[] req = new byte[10];
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01;
            System.arraycopy(ip, 0, req, 4, 4);
            req[8] = (byte) ((port >> 8) & 0xff);
            req[9] = (byte) (port & 0xff);
            out.write(req);
            out.flush();

            byte[] crep = new byte[10];
            int n = in.read(crep);
            return n >= 2 && crep[0] == 0x05 && crep[1] == 0x00;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) {}
        }
    }
}
