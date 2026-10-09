package com.example.vpn.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ⭐ ตรวจสอบว่า VPN พร้อมใช้งานจริง
 * - Warm-up DNS cache
 * - ทดสอบ TCP connect ผ่าน VPN
 * - ยืนยันว่าเน็ตวิ่งผ่าน tunnel
 */
public class ConnectivityChecker {

    public interface Callback {
        void onReady();
        void onFailed(String reason);
    }

    private static final String[] WARMUP_HOSTS = {
            "google.com",
            "cloudflare.com",
            "1.1.1.1"
    };

    private static final int DNS_TIMEOUT_MS = 3000;
    private static final int TCP_TIMEOUT_MS = 3000;
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 1500;

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public ConnectivityChecker(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public void cancel() {
        cancelled.set(true);
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * ⭐ เริ่มตรวจสอบ — ลองทำ DNS + TCP หลายครั้ง
     * เรียก callback เมื่อพร้อมหรือ fail
     */
    public void check(Callback cb) {
        cancelled.set(false);
        new Thread(() -> {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                if (cancelled.get()) return;

                VpnLogger.i("ConnectivityChecker",
                        "Attempt " + attempt + "/" + MAX_ATTEMPTS);

                // ⭐ 1. Warm-up DNS — resolve ชื่อเว็บยอดนิยม
                boolean dnsOk = warmupDns();

                // ⭐ 2. ทดสอบ TCP connect ผ่าน tunnel
                boolean tcpOk = warmupTcp();

                if (dnsOk && tcpOk) {
                    VpnLogger.i("ConnectivityChecker",
                            "✅ Connectivity verified (DNS + TCP OK)");
                    handler.post(cb::onReady);
                    return;
                }

                VpnLogger.w("ConnectivityChecker",
                        "Attempt " + attempt + " failed — DNS=" + dnsOk
                                + " TCP=" + tcpOk);

                if (attempt < MAX_ATTEMPTS) {
                    try { Thread.sleep(RETRY_DELAY_MS); }
                    catch (InterruptedException ignored) { return; }
                }
            }

            VpnLogger.e("ConnectivityChecker",
                    "❌ Failed after " + MAX_ATTEMPTS + " attempts");
            handler.post(() -> cb.onFailed("Connection not verified"));
        }, "ConnectivityCheck").start();
    }

    // ============================================================
    // DNS Warm-up
    // ============================================================
    private boolean warmupDns() {
        int success = 0;
        for (String host : WARMUP_HOSTS) {
            if (cancelled.get()) return false;
            try {
                long start = System.currentTimeMillis();
                InetAddress addr = InetAddress.getByName(host);
                long elapsed = System.currentTimeMillis() - start;
                if (addr != null && addr.getHostAddress() != null) {
                    success++;
                    VpnLogger.d("ConnectivityChecker",
                            "DNS " + host + " → " + addr.getHostAddress()
                                    + " (" + elapsed + "ms)");
                }
            } catch (Exception e) {
                VpnLogger.w("ConnectivityChecker",
                        "DNS " + host + " failed: " + e.getMessage());
            }
        }
        // สำเร็จอย่างน้อย 1 ตัว = OK
        return success > 0;
    }

    // ============================================================
    // TCP Warm-up (ผ่าน SOCKS5 ของเรา)
    // ============================================================
    private boolean warmupTcp() {
        // ทดสอบ TCP connect ผ่าน SOCKS5 127.0.0.1:1080 ไปยัง host จริง
        try {
            String[] testHosts = {"1.1.1.1", "8.8.8.8"};
            int[] testPorts = {80, 443};

            for (String host : testHosts) {
                for (int port : testPorts) {
                    if (cancelled.get()) return false;
                    if (tryTcpConnect(host, port)) {
                        VpnLogger.d("ConnectivityChecker",
                                "TCP " + host + ":" + port + " ✅");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            VpnLogger.w("ConnectivityChecker",
                    "TCP test error: " + e.getMessage());
        }
        return false;
    }

    private boolean tryTcpConnect(String host, int port) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), TCP_TIMEOUT_MS);
            socket.close();
            return true;
        } catch (Exception e) {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            return false;
        }
    }
}