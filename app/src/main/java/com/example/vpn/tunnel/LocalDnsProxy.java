package com.example.vpn.tunnel;

import com.example.vpn.util.VpnLogger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DNS proxy บน TUN IP (เช่น 10.0.0.2:53)
 * รับ UDP DNS จากแอป → ส่งต่อแบบ DNS-over-TCP ผ่าน SOCKS5 ของ V2Ray
 *
 * แก้เคส: ConnectivityChecker ผ่าน (แอป VPN ถูก exclude) แต่ Chrome/YouTube
 * resolve ชื่อไม่ได้เพราะ UDP DNS ผ่าน hev พัง
 */
public class LocalDnsProxy {

    private static final String TAG = "LocalDnsProxy";

    private final String bindHost;
    private final int bindPort;
    private final String socksHost;
    private final int socksPort;
    private final String upstreamDns;
    private final int upstreamPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket udpSocket;
    private ExecutorService pool;
    private Thread acceptThread;

    public LocalDnsProxy(String bindHost, int bindPort,
                         String socksHost, int socksPort,
                         String upstreamDns) {
        this.bindHost = bindHost;
        this.bindPort = bindPort;
        this.socksHost = socksHost;
        this.socksPort = socksPort;
        this.upstreamDns = upstreamDns != null ? upstreamDns : "8.8.8.8";
        this.upstreamPort = 53;
    }

    public void start() throws Exception {
        if (running.get()) return;
        InetAddress bind = InetAddress.getByName(bindHost);
        udpSocket = new DatagramSocket(null);
        udpSocket.setReuseAddress(true);
        udpSocket.bind(new InetSocketAddress(bind, bindPort));
        udpSocket.setSoTimeout(1000);
        pool = Executors.newFixedThreadPool(4);
        running.set(true);
        acceptThread = new Thread(this::loop, "local-dns-proxy");
        acceptThread.setDaemon(true);
        acceptThread.start();
        VpnLogger.i(TAG, "DNS proxy listening on " + bindHost + ":" + bindPort
                + " → SOCKS " + socksHost + ":" + socksPort
                + " → " + upstreamDns + ":53 (TCP)");
    }

    public void stop() {
        running.set(false);
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
        if (pool != null) pool.shutdownNow();
        if (acceptThread != null) {
            try { acceptThread.join(500); } catch (Exception ignored) {}
        }
        VpnLogger.i(TAG, "DNS proxy stopped");
    }

    private void loop() {
        byte[] buf = new byte[4096];
        while (running.get()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                udpSocket.receive(pkt);
                byte[] query = new byte[pkt.getLength()];
                System.arraycopy(pkt.getData(), pkt.getOffset(), query, 0, pkt.getLength());
                InetAddress srcAddr = pkt.getAddress();
                int srcPort = pkt.getPort();
                pool.execute(() -> handle(query, srcAddr, srcPort));
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (running.get()) {
                    VpnLogger.w(TAG, "recv: " + e.getMessage());
                }
            }
        }
    }

    private void handle(byte[] query, InetAddress srcAddr, int srcPort) {
        try {
            byte[] response = resolveViaSocksTcp(query);
            if (response == null || response.length < 12) return;
            DatagramPacket out = new DatagramPacket(response, response.length, srcAddr, srcPort);
            synchronized (udpSocket) {
                udpSocket.send(out);
            }
        } catch (Exception e) {
            VpnLogger.d(TAG, "handle fail: " + e.getMessage());
        }
    }

    /** DNS-over-TCP ผ่าน SOCKS5 CONNECT */
    private byte[] resolveViaSocksTcp(byte[] udpQuery) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(socksHost, socksPort), 4000);
            s.setSoTimeout(8000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            // SOCKS5 greeting
            out.write(new byte[] { 0x05, 0x01, 0x00 });
            out.flush();
            byte[] greet = readFully(in, 2);
            if (greet[0] != 0x05 || greet[1] != 0x00) {
                throw new Exception("SOCKS auth rejected");
            }

            // CONNECT upstreamDns:53
            byte[] ip = InetAddress.getByName(upstreamDns).getAddress();
            if (ip.length != 4) throw new Exception("upstream not IPv4");
            byte[] req = new byte[10];
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01;
            System.arraycopy(ip, 0, req, 4, 4);
            req[8] = (byte) ((upstreamPort >> 8) & 0xff);
            req[9] = (byte) (upstreamPort & 0xff);
            out.write(req);
            out.flush();
            byte[] crep = readFully(in, 10);
            if (crep[1] != 0x00) {
                throw new Exception("SOCKS CONNECT failed code=" + (crep[1] & 0xff));
            }

            // DNS-over-TCP: 2-byte length + message
            int len = udpQuery.length;
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
            out.write(udpQuery);
            out.flush();

            int rlen = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
            if (rlen <= 0 || rlen > 4096) throw new Exception("bad DNS tcp len=" + rlen);
            return readFully(in, rlen);
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static byte[] readFully(InputStream in, int n) throws Exception {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new Exception("EOF");
            off += r;
        }
        return b;
    }
}
