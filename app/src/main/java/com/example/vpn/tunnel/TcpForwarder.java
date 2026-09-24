package com.example.vpn.tunnel;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TcpForwarder — อ่าน TCP packet จาก TUN แล้วส่งผ่าน SOCKS5 → SSH → Internet
 *
 * หมายเหตุ: นี่เป็น TcpForwarder แบบง่าย ทำได้แค่ TCP
 *           UDP (DNS, QUIC) จะไม่ทำงานด้วย implementations นี้
 */
public class TcpForwarder {

    private static final String TAG = "TcpForwarder";
    private static final String SOCKS_HOST = "127.0.0.1";
    private static final int SOCKS_PORT = Socks5Server.LOCAL_PORT;
    private static final int MAX_PACKET = 32767;

    /** Interface สำหรับ protect socket (VpnService.protect) */
    public interface SocketProtector {
        boolean protect(Socket socket);
    }

    private final ParcelFileDescriptor tunFd;
    private final SocketProtector protector;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService pool = Executors.newCachedThreadPool();

    /** เก็บ session ของ TCP ที่กำลังทำงานอยู่ — key = "srcIP:srcPort-dstIP:dstPort" */
    private final ConcurrentHashMap<String, TcpSession> sessions = new ConcurrentHashMap<>();

    public TcpForwarder(ParcelFileDescriptor tunFd, SocketProtector protector) {
        this.tunFd = tunFd;
        this.protector = protector;
    }

    public void start() {
        running.set(true);
        Thread t = new Thread(this::loop, "tcp-forwarder");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        Log.i(TAG, "TcpForwarder started");
        try (FileInputStream in = new FileInputStream(tunFd.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tunFd.getFileDescriptor())) {

            byte[] buffer = new byte[MAX_PACKET];

            while (running.get()) {
                int length = in.read(buffer);
                if (length <= 0) continue;

                try {
                    handlePacket(buffer, length, out);
                } catch (Exception e) {
                    Log.w(TAG, "handlePacket error", e);
                }
            }
        } catch (IOException e) {
            if (running.get()) Log.e(TAG, "TcpForwarder error", e);
        } finally {
            Log.i(TAG, "TcpForwarder stopped");
            stop();
        }
    }

    /**
     * จัดการ 1 packet ที่อ่านจาก TUN
     * รองรับ: TCP (SYN, PSH, FIN) — ส่งต่อผ่าน SOCKS5
     * ข้าม: UDP, ICMP, IPv6
     */
    private void handlePacket(byte[] buf, int len, OutputStream tunOut) {
        if (len < 20) return;

        // ---- Parse IP header ----
        int version = (buf[0] >> 4) & 0x0F;
        if (version != 4) return;   // ข้าม IPv6

        int ipHeaderLen = (buf[0] & 0x0F) * 4;
        int protocol = buf[9] & 0xFF;
        if (protocol != 6) return;  // ข้าม UDP (17), ICMP (1), อื่นๆ

        int totalLen = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        if (totalLen > len) totalLen = len;

        // ---- Parse TCP header ----
        int tcpOffset = ipHeaderLen;
        if (totalLen < tcpOffset + 20) return;

        int srcPort = ((buf[tcpOffset] & 0xFF) << 8) | (buf[tcpOffset + 1] & 0xFF);
        int dstPort = ((buf[tcpOffset + 2] & 0xFF) << 8) | (buf[tcpOffset + 3] & 0xFF);
        int tcpHeaderLen = ((buf[tcpOffset + 12] >> 4) & 0x0F) * 4;
        int flags = buf[tcpOffset + 13] & 0xFF;

        byte[] srcIp = new byte[4];
        byte[] dstIp = new byte[4];
        System.arraycopy(buf, 12, srcIp, 0, 4);
        System.arraycopy(buf, 16, dstIp, 0, 4);

        int payloadOffset = tcpOffset + tcpHeaderLen;
        int payloadLen = totalLen - payloadOffset;

        String srcIpStr = (srcIp[0] & 0xFF) + "." + (srcIp[1] & 0xFF) + "."
                + (srcIp[2] & 0xFF) + "." + (srcIp[3] & 0xFF);
        String dstIpStr = (dstIp[0] & 0xFF) + "." + (dstIp[1] & 0xFF) + "."
                + (dstIp[2] & 0xFF) + "." + (dstIp[3] & 0xFF);

        String sessionKey = srcIpStr + ":" + srcPort + "-" + dstIpStr + ":" + dstPort;

        boolean isSyn = (flags & 0x02) != 0;
        boolean isFin = (flags & 0x01) != 0;
        boolean isRst = (flags & 0x04) != 0;
        boolean isAck = (flags & 0x10) != 0;

        if (isSyn && !isAck) {
            // ---- เริ่ม connection ใหม่ ----
            TcpSession session = new TcpSession(sessionKey, srcIp, srcPort,
                    dstIp, dstPort, tunOut, protector);
            sessions.put(sessionKey, session);
            pool.execute(session);
        } else {
            // ---- ส่ง payload ไปใน session ที่มีอยู่ ----
            TcpSession session = sessions.get(sessionKey);
            if (session != null) {
                if (payloadLen > 0) {
                    byte[] payload = new byte[payloadLen];
                    System.arraycopy(buf, payloadOffset, payload, 0, payloadLen);
                    session.sendToRemote(payload);
                }
                if (isFin || isRst) {
                    session.close();
                    sessions.remove(sessionKey);
                }
            }
        }
    }

    public void stop() {
        running.set(false);
        for (TcpSession s : sessions.values()) s.close();
        sessions.clear();
        pool.shutdownNow();
    }

    // ============================================================
    // TcpSession — จัดการ 1 connection
    // ============================================================
    private static class TcpSession implements Runnable {
        private final String key;
        private final byte[] srcIp;
        private final int srcPort;
        private final byte[] dstIp;
        private final int dstPort;
        private final OutputStream tunOut;
        private final SocketProtector protector;

        private Socket socket;
        private InputStream remoteIn;
        private OutputStream remoteOut;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        TcpSession(String key, byte[] srcIp, int srcPort, byte[] dstIp, int dstPort,
                   OutputStream tunOut, SocketProtector protector) {
            this.key = key;
            this.srcIp = srcIp;
            this.srcPort = srcPort;
            this.dstIp = dstIp;
            this.dstPort = dstPort;
            this.tunOut = tunOut;
            this.protector = protector;
        }

        @Override
        public void run() {
            try {
                // ---- 1. ต่อ SOCKS5 ----
                socket = new Socket();
                if (protector != null) protector.protect(socket);
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(SOCKS_HOST, SOCKS_PORT), 15000);

                InputStream sockIn = socket.getInputStream();
                OutputStream sockOut = socket.getOutputStream();

                // ---- 2. SOCKS5 handshake (no auth) ----
                sockOut.write(new byte[]{0x05, 0x01, 0x00});
                sockOut.flush();

                byte[] resp = new byte[2];
                readFully(sockIn, resp);
                if (resp[0] != 0x05 || resp[1] != 0x00) {
                    Log.w(TAG, "SOCKS5 handshake failed");
                    close();
                    return;
                }

                // ---- 3. SOCKS5 CONNECT request ----
                byte[] req = new byte[4 + 4 + 2];
                req[0] = 0x05; // VER
                req[1] = 0x01; // CONNECT
                req[2] = 0x00; // RSV
                req[3] = 0x01; // ATYP = IPv4
                System.arraycopy(dstIp, 0, req, 4, 4);
                req[8] = (byte) ((dstPort >> 8) & 0xFF);
                req[9] = (byte) (dstPort & 0xFF);
                sockOut.write(req);
                sockOut.flush();

                // ---- 4. อ่าน response ----
                byte[] head = new byte[4];
                readFully(sockIn, head);
                if (head[1] != 0x00) {
                    Log.w(TAG, "SOCKS5 connect failed: " + (head[1] & 0xFF));
                    close();
                    return;
                }
                int addrLen;
                switch (head[3]) {
                    case 0x01: addrLen = 4; break;
                    case 0x04: addrLen = 16; break;
                    case 0x03: {
                        int n = sockIn.read();
                        addrLen = n + 1;
                        break;
                    }
                    default: addrLen = 4;
                }
                byte[] skip = new byte[addrLen + 2];
                readFully(sockIn, skip);

                remoteIn = sockIn;
                remoteOut = sockOut;

                Log.i(TAG, "Session open: " + key);

                // ---- 5. อ่านข้อมูลจาก remote แล้วเขียนกลับเข้า TUN ----
                byte[] remoteBuf = new byte[8192];
                while (!closed.get()) {
                    int n = remoteIn.read(remoteBuf);
                    if (n <= 0) break;
                    synchronized (tunOut) {
                        writeTcpPacket(tunOut, remoteBuf, n);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Session error: " + e.getMessage());
            } finally {
                close();
            }
        }

        /** ส่ง payload ที่ได้จาก client ไปยัง remote (ผ่าน SOCKS5) */
        void sendToRemote(byte[] data) {
            try {
                if (remoteOut != null) {
                    remoteOut.write(data);
                    remoteOut.flush();
                }
            } catch (IOException e) {
                close();
            }
        }

        /** สร้าง IPv4+TCP packet แล้วเขียนกลับเข้า TUN */
        private void writeTcpPacket(OutputStream tunOut, byte[] payload, int payloadLen)
                throws IOException {
            int ipHeaderLen = 20;
            int tcpHeaderLen = 20;
            int totalLen = ipHeaderLen + tcpHeaderLen + payloadLen;

            byte[] packet = new byte[totalLen];

            // ---- IPv4 header ----
            packet[0] = 0x45;                    // version 4, IHL 5
            packet[1] = 0x00;                    // DSCP/ECN
            packet[2] = (byte) ((totalLen >> 8) & 0xFF);
            packet[3] = (byte) (totalLen & 0xFF);
            packet[4] = 0; packet[5] = 0;        // ID
            packet[6] = 0x40;                    // flags=DF
            packet[7] = 0;                       // fragment offset
            packet[8] = 64;                      // TTL
            packet[9] = 6;                       // protocol = TCP
            packet[10] = 0; packet[11] = 0;      // checksum (0 = ok for TUN)

            // src = dst ของฝั่ง remote (สลับ)
            System.arraycopy(dstIp, 0, packet, 12, 4);
            System.arraycopy(srcIp, 0, packet, 16, 4);

            // ---- TCP header ----
            int t = ipHeaderLen;
            packet[t]     = (byte) ((dstPort >> 8) & 0xFF);
            packet[t + 1] = (byte) (dstPort & 0xFF);
            packet[t + 2] = (byte) ((srcPort >> 8) & 0xFF);
            packet[t + 3] = (byte) (srcPort & 0xFF);
            packet[t + 4] = 0; packet[t + 5] = 0; packet[t + 6] = 0; packet[t + 7] = 0; // seq
            packet[t + 8] = 0; packet[t + 9] = 0; packet[t + 10] = 0; packet[t + 11] = 0; // ack
            packet[t + 12] = 0x50;               // data offset 5
            packet[t + 13] = 0x18;               // PSH + ACK
            packet[t + 14] = 0; packet[t + 15] = 0; // window
            packet[t + 16] = 0; packet[t + 17] = 0; // checksum
            packet[t + 18] = 0; packet[t + 19] = 0; // urgent

            // ---- Payload ----
            System.arraycopy(payload, 0, packet, ipHeaderLen + tcpHeaderLen, payloadLen);

            tunOut.write(packet);
            tunOut.flush();
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        }

        private static void readFully(InputStream in, byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) throw new IOException("EOF");
                off += n;
            }
        }
    }
}