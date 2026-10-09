package com.example.vpn.tunnel;

import com.example.vpn.util.VpnLogger;
import com.jcraft.jsch.ChannelDirectTCPIP;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * badvpn-udpgw client ผ่าน SSH direct-tcpip ไป 127.0.0.1:udpgwPort
 *
 * แพ็กเก็ต (client → server):
 *   uint16_be len   (ความยาวหลังฟิลด์นี้)
 *   uint16_be id
 *   uint8     flags (0 = IPv4)
 *   4 bytes   ipv4
 *   uint16_be port
 *   payload
 */
public class UdpgwClient {

    private static final String TAG = "UdpgwClient";

    public interface PacketListener {
        void onPacket(InetAddress src, int port, byte[] data, int offset, int length);
    }

    private final SshTunnel ssh;
    private final int udpgwPort;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger nextId = new AtomicInteger(1);

    private ChannelDirectTCPIP channel;
    private OutputStream out;
    private InputStream in;
    private Thread readerThread;
    private volatile PacketListener listener;

    public UdpgwClient(SshTunnel ssh, int udpgwPort) {
        this.ssh = ssh;
        this.udpgwPort = udpgwPort;
    }

    public boolean isEnabled() {
        return udpgwPort > 0;
    }

    public void setListener(PacketListener listener) {
        this.listener = listener;
    }

    public synchronized void start() throws Exception {
        if (!isEnabled()) {
            VpnLogger.i(TAG, "udpgw disabled (port=0)");
            return;
        }
        if (running.get()) return;

        VpnLogger.i(TAG, "Opening SSH channel to 127.0.0.1:" + udpgwPort);
        channel = ssh.openTcp("127.0.0.1", udpgwPort);
        out = channel.getOutputStream();
        in = channel.getInputStream();
        running.set(true);

        readerThread = new Thread(this::readLoop, "udpgw-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        VpnLogger.i(TAG, "UdpgwClient started");
    }

    public synchronized void send(InetAddress dst, int port, byte[] data, int offset, int len)
            throws IOException {
        if (!running.get() || out == null) {
            throw new IOException("udpgw not running");
        }
        if (dst == null || data == null || len <= 0) return;

        byte[] ip = dst.getAddress();
        if (ip.length != 4) {
            // IPv6 ยังไม่รองรับในเฟสนี้
            throw new IOException("udpgw IPv4 only");
        }

        int id = nextId.getAndIncrement() & 0xFFFF;
        // len = id(2)+flags(1)+ip(4)+port(2)+payload
        int bodyLen = 2 + 1 + 4 + 2 + len;
        ByteBuffer buf = ByteBuffer.allocate(2 + bodyLen);
        buf.putShort((short) bodyLen);
        buf.putShort((short) id);
        buf.put((byte) 0); // IPv4
        buf.put(ip);
        buf.putShort((short) (port & 0xFFFF));
        buf.put(data, offset, len);

        synchronized (out) {
            out.write(buf.array());
            out.flush();
        }
    }

    private void readLoop() {
        byte[] header = new byte[2];
        try {
            while (running.get() && in != null) {
                if (!readFully(in, header, 0, 2)) break;
                int bodyLen = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
                if (bodyLen < 9 || bodyLen > 65535) {
                    VpnLogger.w(TAG, "invalid udpgw bodyLen=" + bodyLen);
                    break;
                }
                byte[] body = new byte[bodyLen];
                if (!readFully(in, body, 0, bodyLen)) break;

                // id(2) flags(1) ip(4) port(2) data
                int flags = body[2] & 0xFF;
                if ((flags & 1) != 0) {
                    // IPv6 skip for now
                    continue;
                }
                byte[] ipBytes = new byte[]{body[3], body[4], body[5], body[6]};
                int port = ((body[7] & 0xFF) << 8) | (body[8] & 0xFF);
                int dataOff = 9;
                int dataLen = bodyLen - dataOff;
                if (dataLen <= 0) continue;

                InetAddress src = InetAddress.getByAddress(ipBytes);
                PacketListener l = listener;
                if (l != null) {
                    l.onPacket(src, port, body, dataOff, dataLen);
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                VpnLogger.w(TAG, "readLoop: " + e.getMessage());
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] buf, int off, int len)
            throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) return false;
            got += n;
        }
        return true;
    }

    public synchronized void stop() {
        running.set(false);
        try {
            if (channel != null) channel.disconnect();
        } catch (Exception ignored) {}
        channel = null;
        in = null;
        out = null;
        VpnLogger.i(TAG, "UdpgwClient stopped");
    }
}
