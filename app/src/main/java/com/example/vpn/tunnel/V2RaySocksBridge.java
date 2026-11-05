package com.example.vpn.tunnel;

import com.example.vpn.util.VpnLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SOCKS5 หน้าบ้านให้ hev ต่อ (พอร์ต 1080) — แบบเดียวกับ SSH ที่พิสูจน์แล้วว่าเน็ตออก
 * - TCP CONNECT → รีเลย์เข้า V2Ray SOCKS (เช่น 10808)
 * - UDP (DNS) → DNS-over-TCP ผ่าน V2Ray SOCKS ไป 8.8.8.8:53
 */
public class V2RaySocksBridge {

    private static final String TAG = "V2RaySocksBridge";

    private final int listenPort;
    private final String v2raySocksHost;
    private final int v2raySocksPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private ExecutorService pool;
    private Thread acceptThread;

    public V2RaySocksBridge(int listenPort, String v2raySocksHost, int v2raySocksPort) {
        this.listenPort = listenPort;
        this.v2raySocksHost = v2raySocksHost;
        this.v2raySocksPort = v2raySocksPort;
    }

    public void start() throws IOException {
        if (running.get()) return;
        server = new ServerSocket();
        server.setReuseAddress(true);
        try {
            // ลด TIME_WAIT ตอนสลับ SSH ↔ V2Ray เร็ว ๆ
            server.setSoTimeout(0);
        } catch (Exception ignored) {}
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 50);
        pool = Executors.newCachedThreadPool();
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "v2ray-socks-bridge");
        acceptThread.setDaemon(true);
        acceptThread.start();
        VpnLogger.i(TAG, "Bridge SOCKS 127.0.0.1:" + listenPort
                + " → V2Ray " + v2raySocksHost + ":" + v2raySocksPort);
    }

    public void stop() {
        running.set(false);
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        if (acceptThread != null) {
            try { acceptThread.interrupt(); } catch (Exception ignored) {}
            try { acceptThread.join(300); } catch (Exception ignored) {}
            acceptThread = null;
        }
        VpnLogger.i(TAG, "Bridge stopped");
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = server.accept();
                pool.execute(() -> handleClient(client));
            } catch (Exception e) {
                if (running.get()) VpnLogger.w(TAG, "accept: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(120000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // greeting
            int ver = in.read();
            if (ver != 0x05) { client.close(); return; }
            int n = in.read();
            if (n < 0) { client.close(); return; }
            for (int i = 0; i < n; i++) in.read();
            out.write(new byte[] { 0x05, 0x00 });
            out.flush();

            // request
            int v = in.read();
            int cmd = in.read();
            in.read(); // rsv
            int atyp = in.read();
            if (v != 0x05) { client.close(); return; }

            if (cmd == 0x01) { // CONNECT
                byte[] addr = readAddr(in, atyp);
                int port = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
                // reply success then pipe through V2Ray SOCKS
                out.write(new byte[] {
                        0x05, 0x00, 0x00, 0x01,
                        0, 0, 0, 0, 0, 0
                });
                out.flush();
                relayConnect(client, addr, atyp, port);
            } else if (cmd == 0x03) { // UDP ASSOCIATE
                // bind a local UDP port for hev
                DatagramSocket udp = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
                byte[] ip = InetAddress.getByName("127.0.0.1").getAddress();
                int up = udp.getLocalPort();
                out.write(new byte[] {
                        0x05, 0x00, 0x00, 0x01,
                        ip[0], ip[1], ip[2], ip[3],
                        (byte) ((up >> 8) & 0xff), (byte) (up & 0xff)
                });
                out.flush();
                handleUdpAssociate(client, udp);
            } else {
                out.write(new byte[] { 0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0 });
                out.flush();
                client.close();
            }
        } catch (Exception e) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void relayConnect(Socket client, byte[] addr, int atyp, int port) {
        Socket remote = null;
        try {
            remote = openViaV2Ray(addr, atyp, port);
            Socket c = client;
            Socket r = remote;
            pool.execute(() -> pipe(c, r));
            pipe(r, c);
        } catch (Exception e) {
            VpnLogger.d(TAG, "relayConnect: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception ignored) {}
            try { if (remote != null) remote.close(); } catch (Exception ignored) {}
        }
    }

    private Socket openViaV2Ray(byte[] addr, int atyp, int port) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(v2raySocksHost, v2raySocksPort), 8000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(120000);
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();

        out.write(new byte[] { 0x05, 0x01, 0x00 });
        out.flush();
        byte[] g = readFully(in, 2);
        if (g[0] != 0x05 || g[1] != 0x00) throw new IOException("v2ray socks auth");

        // build CONNECT
        if (atyp == 0x01 && addr.length == 4) {
            byte[] req = new byte[10];
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01;
            System.arraycopy(addr, 0, req, 4, 4);
            req[8] = (byte) ((port >> 8) & 0xff);
            req[9] = (byte) (port & 0xff);
            out.write(req);
        } else if (atyp == 0x03) {
            // domain: addr[0]=len
            int dlen = addr[0] & 0xff;
            byte[] req = new byte[5 + dlen + 2];
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03;
            System.arraycopy(addr, 0, req, 4, 1 + dlen);
            req[5 + dlen] = (byte) ((port >> 8) & 0xff);
            req[6 + dlen] = (byte) (port & 0xff);
            out.write(req);
        } else {
            throw new IOException("unsupported atyp=" + atyp);
        }
        out.flush();
        byte[] resp = readFully(in, 4);
        if (resp[1] != 0x00) throw new IOException("v2ray CONNECT fail " + (resp[1] & 0xff));
        int ratyp = resp[3] & 0xff;
        if (ratyp == 0x01) readFully(in, 4 + 2);
        else if (ratyp == 0x03) {
            int l = in.read();
            readFully(in, l + 2);
        } else if (ratyp == 0x04) readFully(in, 16 + 2);
        return s;
    }

    private void handleUdpAssociate(Socket control, DatagramSocket udp) {
        try {
            udp.setSoTimeout(500);
            byte[] buf = new byte[4096];
            while (running.get() && !control.isClosed()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    udp.receive(pkt);
                    // SOCKS5 UDP request header
                    byte[] data = Arrays.copyOfRange(pkt.getData(), pkt.getOffset(), pkt.getOffset() + pkt.getLength());
                    if (data.length < 10 || data[0] != 0x00 || data[1] != 0x00) continue;
                    int frag = data[2] & 0xff;
                    if (frag != 0) continue;
                    int atyp = data[3] & 0xff;
                    int idx = 4;
                    byte[] hostBytes;
                    if (atyp == 0x01) {
                        hostBytes = Arrays.copyOfRange(data, idx, idx + 4);
                        idx += 4;
                    } else if (atyp == 0x03) {
                        int l = data[idx] & 0xff;
                        hostBytes = Arrays.copyOfRange(data, idx, idx + 1 + l);
                        idx += 1 + l;
                    } else continue;
                    int dstPort = ((data[idx] & 0xff) << 8) | (data[idx + 1] & 0xff);
                    idx += 2;
                    byte[] payload = Arrays.copyOfRange(data, idx, data.length);

                    // only handle DNS-like (port 53) via DNS-over-TCP through V2Ray
                    if (dstPort != 53) continue;
                    byte[] dnsResp = dnsOverTcpViaV2Ray(hostBytes, atyp, payload);
                    if (dnsResp == null) continue;

                    // build SOCKS UDP reply
                    byte[] header = Arrays.copyOfRange(data, 0, idx); // same header up to port
                    byte[] out = new byte[header.length + dnsResp.length];
                    System.arraycopy(header, 0, out, 0, header.length);
                    System.arraycopy(dnsResp, 0, out, header.length, dnsResp.length);
                    DatagramPacket resp = new DatagramPacket(out, out.length, pkt.getAddress(), pkt.getPort());
                    udp.send(resp);
                } catch (java.net.SocketTimeoutException ignored) {
                    // check control socket
                    if (control.isClosed()) break;
                }
            }
        } catch (Exception e) {
            VpnLogger.d(TAG, "udp associate end: " + e.getMessage());
        } finally {
            try { udp.close(); } catch (Exception ignored) {}
            try { control.close(); } catch (Exception ignored) {}
        }
    }

    private byte[] dnsOverTcpViaV2Ray(byte[] hostBytes, int atyp, byte[] udpQuery) {
        Socket s = null;
        try {
            // CONNECT to 8.8.8.8:53 via V2Ray (ignore original DNS IP — always use 8.8.8.8)
            byte[] eight = InetAddress.getByName("8.8.8.8").getAddress();
            s = openViaV2Ray(eight, 0x01, 53);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            int len = udpQuery.length;
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
            out.write(udpQuery);
            out.flush();
            int rlen = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
            if (rlen <= 0 || rlen > 4096) return null;
            return readFully(in, rlen);
        } catch (Exception e) {
            VpnLogger.d(TAG, "dnsOverTcp: " + e.getMessage());
            return null;
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) {}
        }
    }

    private static void pipe(Socket a, Socket b) {
        try {
            InputStream in = a.getInputStream();
            OutputStream out = b.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { a.close(); } catch (Exception ignored) {}
            try { b.close(); } catch (Exception ignored) {}
        }
    }

    private static byte[] readAddr(InputStream in, int atyp) throws IOException {
        if (atyp == 0x01) {
            return readFully(in, 4);
        } else if (atyp == 0x03) {
            int l = in.read();
            if (l < 0) throw new IOException("EOF");
            byte[] d = new byte[1 + l];
            d[0] = (byte) l;
            int off = 1;
            while (off < d.length) {
                int r = in.read(d, off, d.length - off);
                if (r < 0) throw new IOException("EOF");
                off += r;
            }
            return d;
        } else if (atyp == 0x04) {
            return readFully(in, 16);
        }
        throw new IOException("atyp");
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("EOF");
            off += r;
        }
        return b;
    }
}
