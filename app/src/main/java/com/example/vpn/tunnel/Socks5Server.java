package com.example.vpn.tunnel;

import android.util.Log;

import com.jcraft.jsch.ChannelDirectTCPIP;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SOCKS5 ผ่าน SSH
 * - TCP CONNECT → SSH direct-tcpip
 * - UDP ASSOCIATE → badvpn-udpgw (ถ้า udpgwPort > 0)
 */
public class Socks5Server {

    private static final String TAG = "Socks5Server";
    public static final int LOCAL_PORT = 1080;

    private static final int VER = 0x05;
    private static final int CMD_CONNECT = 0x01;
    private static final int CMD_UDP_ASSOCIATE = 0x03;
    private static final int ATYP_IPV4 = 0x01;
    private static final int ATYP_DOMAIN = 0x03;
    private static final int ATYP_IPV6 = 0x04;
    private static final int REP_SUCCESS = 0x00;
    private static final int REP_GENERAL_FAIL = 0x01;
    private static final int REP_CMD_NOT_SUPPORTED = 0x07;

    private final SshTunnel ssh;
    private final boolean bindLocalOnly;
    private final int udpgwPort;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private Thread acceptThread;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private String listenAddress = "127.0.0.1";

    private UdpgwClient udpgw;

    public Socks5Server(SshTunnel ssh) {
        this(ssh, true, 0);
    }

    public Socks5Server(SshTunnel ssh, boolean bindLocalOnly) {
        this(ssh, bindLocalOnly, 0);
    }

    public Socks5Server(SshTunnel ssh, boolean bindLocalOnly, int udpgwPort) {
        this.ssh = ssh;
        this.bindLocalOnly = bindLocalOnly;
        this.udpgwPort = udpgwPort > 0 ? udpgwPort : 0;
    }

    public void start() throws IOException {
        String host = bindLocalOnly ? "127.0.0.1" : "0.0.0.0";
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName(host), LOCAL_PORT), 50);
        running.set(true);
        listenAddress = host;

        if (udpgwPort > 0) {
            try {
                udpgw = new UdpgwClient(ssh, udpgwPort);
                udpgw.start();
                Log.i(TAG, "Udpgw enabled on remote 127.0.0.1:" + udpgwPort);
            } catch (Exception e) {
                Log.w(TAG, "Udpgw start failed: " + e.getMessage());
                udpgw = null;
            }
        } else {
            Log.i(TAG, "Udpgw disabled");
        }

        acceptThread = new Thread(this::acceptLoop, "socks5-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        Log.i(TAG, "SOCKS5 listening on " + host + ":" + LOCAL_PORT
                + (bindLocalOnly ? " (local)" : " (shared / LAN)")
                + " udp=" + (udpgw != null));
    }

    public boolean isBindLocalOnly() {
        return bindLocalOnly;
    }

    public String getListenAddress() {
        return listenAddress;
    }

    public int getPort() {
        return LOCAL_PORT;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = server.accept();
                client.setTcpNoDelay(true);
                pool.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "accept: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void handleClient(Socket client) {
        ChannelDirectTCPIP channel = null;
        try {
            client.setSoTimeout(60_000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            DataInputStream din = new DataInputStream(in);

            // greeting
            int ver = din.readUnsignedByte();
            if (ver != VER) return;
            int nMethods = din.readUnsignedByte();
            din.readFully(new byte[nMethods]);
            out.write(new byte[]{(byte) VER, 0x00}); // NO AUTH
            out.flush();

            // request
            ver = din.readUnsignedByte();
            int cmd = din.readUnsignedByte();
            din.readUnsignedByte(); // rsv
            int atyp = din.readUnsignedByte();

            String destHost;
            if (atyp == ATYP_IPV4) {
                byte[] addr = new byte[4];
                din.readFully(addr);
                destHost = InetAddress.getByAddress(addr).getHostAddress();
            } else if (atyp == ATYP_DOMAIN) {
                int len = din.readUnsignedByte();
                byte[] dom = new byte[len];
                din.readFully(dom);
                destHost = new String(dom);
            } else if (atyp == ATYP_IPV6) {
                byte[] addr = new byte[16];
                din.readFully(addr);
                destHost = InetAddress.getByAddress(addr).getHostAddress();
            } else {
                sendReply(out, REP_GENERAL_FAIL);
                return;
            }
            int destPort = din.readUnsignedShort();

            if (cmd == CMD_CONNECT) {
                try {
                    channel = ssh.openTcp(destHost, destPort);
                    sendReply(out, REP_SUCCESS);
                    client.setSoTimeout(0);

                    final ChannelDirectTCPIP ch = channel;
                    Thread t1 = new Thread(() -> pipe(client, ch), "socks-up");
                    Thread t2 = new Thread(() -> pipe(ch, client), "socks-down");
                    t1.start();
                    t2.start();
                    t1.join();
                    t2.join();
                } catch (Exception e) {
                    Log.w(TAG, "CONNECT " + destHost + ":" + destPort + " fail: " + e.getMessage());
                    sendReply(out, REP_GENERAL_FAIL);
                }
            } else if (cmd == CMD_UDP_ASSOCIATE) {
                handleUdpAssociate(client, out);
            } else {
                sendReply(out, REP_CMD_NOT_SUPPORTED);
            }

        } catch (Exception e) {
            Log.w(TAG, "handleClient: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            if (channel != null) channel.disconnect();
        }
    }

    /**
     * SOCKS5 UDP ASSOCIATE → relay ผ่าน udpgw
     */
    private void handleUdpAssociate(Socket tcpControl, OutputStream out) throws Exception {
        if (udpgw == null || !udpgw.isEnabled()) {
            sendReply(out, REP_CMD_NOT_SUPPORTED);
            return;
        }

        DatagramSocket udpSock = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        udpSock.setSoTimeout(300_000);

        // reply: bind 127.0.0.1:port
        int bindPort = udpSock.getLocalPort();
        byte[] rep = new byte[]{
                (byte) VER, (byte) REP_SUCCESS, 0x00, (byte) ATYP_IPV4,
                127, 0, 0, 1,
                (byte) ((bindPort >> 8) & 0xFF), (byte) (bindPort & 0xFF)
        };
        out.write(rep);
        out.flush();

        Log.i(TAG, "UDP ASSOCIATE relay on 127.0.0.1:" + bindPort);

        final AtomicBoolean alive = new AtomicBoolean(true);

        // udpgw → client UDP
        udpgw.setListener((src, port, data, offset, length) -> {
            try {
                // SOCKS5 UDP header: RSV RSV FRAG ATYP ADDR PORT DATA
                byte[] ip = src.getAddress();
                byte[] packet = new byte[4 + 4 + 2 + length];
                packet[0] = 0;
                packet[1] = 0;
                packet[2] = 0; // frag
                packet[3] = ATYP_IPV4;
                System.arraycopy(ip, 0, packet, 4, 4);
                packet[8] = (byte) ((port >> 8) & 0xFF);
                packet[9] = (byte) (port & 0xFF);
                System.arraycopy(data, offset, packet, 10, length);
                DatagramPacket dp = new DatagramPacket(
                        packet, packet.length,
                        InetAddress.getByName("127.0.0.1"), 0);
                // ส่งกลับหา last client — ใช้ connected peer ถ้ามี
                // เก็บ remote จากแพ็กเก็ตล่าสุดด้านล่าง
            } catch (Exception ignored) {}
        });

        // เก็บที่อยู่ client ล่าสุด
        final InetSocketAddress[] clientAddr = {null};

        udpgw.setListener((src, port, data, offset, length) -> {
            try {
                if (clientAddr[0] == null) return;
                byte[] ip = src.getAddress();
                if (ip.length != 4) return;
                byte[] packet = new byte[10 + length];
                packet[0] = 0;
                packet[1] = 0;
                packet[2] = 0;
                packet[3] = ATYP_IPV4;
                System.arraycopy(ip, 0, packet, 4, 4);
                packet[8] = (byte) ((port >> 8) & 0xFF);
                packet[9] = (byte) (port & 0xFF);
                System.arraycopy(data, offset, packet, 10, length);
                DatagramPacket dp = new DatagramPacket(
                        packet, packet.length,
                        clientAddr[0].getAddress(), clientAddr[0].getPort());
                udpSock.send(dp);
            } catch (Exception ignored) {}
        });

        Thread udpThread = new Thread(() -> {
            byte[] buf = new byte[65535];
            while (alive.get() && running.get()) {
                try {
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    udpSock.receive(dp);
                    clientAddr[0] = new InetSocketAddress(dp.getAddress(), dp.getPort());
                    parseAndForwardUdp(buf, dp.getLength());
                } catch (SocketTimeoutException e) {
                    // keep waiting
                } catch (Exception e) {
                    if (alive.get()) {
                        Log.w(TAG, "udp relay: " + e.getMessage());
                    }
                    break;
                }
            }
            try { udpSock.close(); } catch (Exception ignored) {}
        }, "socks-udp-relay");
        udpThread.setDaemon(true);
        udpThread.start();

        // ค้าง TCP control ไว้จน client ปิด
        try {
            InputStream in = tcpControl.getInputStream();
            byte[] tmp = new byte[256];
            while (running.get()) {
                int n = in.read(tmp);
                if (n < 0) break;
            }
        } catch (Exception ignored) {
        } finally {
            alive.set(false);
            try { udpSock.close(); } catch (Exception ignored) {}
            try { udpThread.join(500); } catch (Exception ignored) {}
        }
    }

    /** ถอด SOCKS5 UDP header แล้วส่งเข้า udpgw */
    private void parseAndForwardUdp(byte[] buf, int length) {
        if (udpgw == null || length < 10) return;
        // RSV(2) FRAG(1) ATYP(1)
        int frag = buf[2] & 0xFF;
        if (frag != 0) return;
        int atyp = buf[3] & 0xFF;
        try {
            int idx = 4;
            InetAddress dst;
            if (atyp == ATYP_IPV4) {
                byte[] ip = new byte[4];
                System.arraycopy(buf, idx, ip, 0, 4);
                idx += 4;
                dst = InetAddress.getByAddress(ip);
            } else if (atyp == ATYP_DOMAIN) {
                int dlen = buf[idx] & 0xFF;
                idx++;
                String host = new String(buf, idx, dlen);
                idx += dlen;
                dst = InetAddress.getByName(host);
            } else {
                return;
            }
            int port = ((buf[idx] & 0xFF) << 8) | (buf[idx + 1] & 0xFF);
            idx += 2;
            int dataLen = length - idx;
            if (dataLen <= 0) return;
            udpgw.send(dst, port, buf, idx, dataLen);
        } catch (Exception e) {
            Log.w(TAG, "forward udp: " + e.getMessage());
        }
    }

    private static void sendReply(OutputStream out, int rep) throws IOException {
        out.write(new byte[]{
                (byte) VER, (byte) rep, 0x00, (byte) ATYP_IPV4,
                0, 0, 0, 0, 0, 0
        });
        out.flush();
    }

    private static void pipe(Socket client, ChannelDirectTCPIP ch) {
        try {
            copy(client.getInputStream(), ch.getOutputStream());
        } catch (Exception ignored) {
        } finally {
            try { client.shutdownOutput(); } catch (IOException ignored) {}
            try { ch.getOutputStream().close(); } catch (Exception ignored) {}
        }
    }

    private static void pipe(ChannelDirectTCPIP ch, Socket client) {
        try {
            copy(ch.getInputStream(), client.getOutputStream());
        } catch (Exception ignored) {
        } finally {
            try { ch.getInputStream().close(); } catch (Exception ignored) {}
            try { client.shutdownOutput(); } catch (IOException ignored) {}
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    public void stop() {
        running.set(false);
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (udpgw != null) {
            try { udpgw.stop(); } catch (Exception ignored) {}
            udpgw = null;
        }
        pool.shutdownNow();
        Log.i(TAG, "SOCKS5 server stopped");
    }
}
