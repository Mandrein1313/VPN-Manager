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
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;

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
    private final String authUser;   // null/empty = no auth
    private final String authPass;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private Thread acceptThread;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private String listenAddress = "127.0.0.1";
    /** จำนวน client ที่กำลังใช้ proxy อยู่ */
    private static final AtomicInteger ACTIVE_CLIENTS = new AtomicInteger(0);

    private UdpgwClient udpgw;

    public Socks5Server(SshTunnel ssh) {
        this(ssh, true, 0, null, null);
    }

    public Socks5Server(SshTunnel ssh, boolean bindLocalOnly) {
        this(ssh, bindLocalOnly, 0, null, null);
    }

    public Socks5Server(SshTunnel ssh, boolean bindLocalOnly, int udpgwPort) {
        this(ssh, bindLocalOnly, udpgwPort, null, null);
    }

    public Socks5Server(SshTunnel ssh, boolean bindLocalOnly, int udpgwPort,
                        String authUser, String authPass) {
        this.ssh = ssh;
        this.bindLocalOnly = bindLocalOnly;
        this.udpgwPort = udpgwPort > 0 ? udpgwPort : 0;
        this.authUser = (authUser != null && !authUser.isEmpty()) ? authUser : null;
        this.authPass = authPass != null ? authPass : "";
    }

    public static int getActiveClients() {
        return ACTIVE_CLIENTS.get();
    }

    public boolean isAuthEnabled() {
        return authUser != null;
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
                + (authUser != null ? " auth=ON user=" + authUser : " auth=OFF")
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

            // greeting + method negotiation
            int ver = din.readUnsignedByte();
            if (ver != VER) return;
            int nMethods = din.readUnsignedByte();
            byte[] methods = new byte[nMethods];
            din.readFully(methods);

            boolean wantAuth = authUser != null;
            boolean clientHasNoAuth = false;
            boolean clientHasUserPass = false;
            for (byte m : methods) {
                if (m == 0x00) clientHasNoAuth = true;
                if (m == 0x02) clientHasUserPass = true;
            }

            if (wantAuth) {
                if (!clientHasUserPass) {
                    out.write(new byte[]{(byte) VER, (byte) 0xFF}); // no acceptable
                    out.flush();
                    return;
                }
                out.write(new byte[]{(byte) VER, 0x02}); // username/password
                out.flush();
                // RFC 1929
                int aVer = din.readUnsignedByte();
                if (aVer != 0x01) return;
                int uLen = din.readUnsignedByte();
                byte[] uBytes = new byte[uLen];
                din.readFully(uBytes);
                int pLen = din.readUnsignedByte();
                byte[] pBytes = new byte[pLen];
                din.readFully(pBytes);
                String u = new String(uBytes, StandardCharsets.UTF_8);
                String pw = new String(pBytes, StandardCharsets.UTF_8);
                if (!authUser.equals(u) || !authPass.equals(pw)) {
                    out.write(new byte[]{0x01, 0x01}); // failure
                    out.flush();
                    Log.w(TAG, "SOCKS auth failed from " + client.getInetAddress());
                    return;
                }
                out.write(new byte[]{0x01, 0x00}); // success
                out.flush();
            } else {
                if (!clientHasNoAuth && clientHasUserPass) {
                    // client only offered auth but we don't need it — still reject if no 0x00
                    out.write(new byte[]{(byte) VER, (byte) 0xFF});
                    out.flush();
                    return;
                }
                out.write(new byte[]{(byte) VER, 0x00}); // NO AUTH
                out.flush();
            }

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

                    ACTIVE_CLIENTS.incrementAndGet();
                    try {
                        final ChannelDirectTCPIP ch = channel;
                        Thread t1 = new Thread(() -> pipe(client, ch), "socks-up");
                        Thread t2 = new Thread(() -> pipe(ch, client), "socks-down");
                        t1.start();
                        t2.start();
                        t1.join();
                        t2.join();
                    } finally {
                        ACTIVE_CLIENTS.decrementAndGet();
                    }
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
     * SOCKS5 UDP ASSOCIATE → relay ผ่าน badvpn-udpgw
     * hev-socks5-tunnel จะยิง UDP มาที่พอร์ตนี้
     */
    private void handleUdpAssociate(Socket tcpControl, OutputStream out) throws Exception {
        if (udpgw == null || !udpgw.isEnabled()) {
            Log.w(TAG, "UDP ASSOCIATE rejected — udpgw disabled");
            sendReply(out, REP_CMD_NOT_SUPPORTED);
            return;
        }

        final DatagramSocket udpSock = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        udpSock.setSoTimeout(300_000);
        final int bindPort = udpSock.getLocalPort();

        // SOCKS5 reply: BND.ADDR = 127.0.0.1, BND.PORT = bindPort
        byte[] rep = new byte[]{
                (byte) VER, (byte) REP_SUCCESS, 0x00, (byte) ATYP_IPV4,
                127, 0, 0, 1,
                (byte) ((bindPort >> 8) & 0xFF), (byte) (bindPort & 0xFF)
        };
        out.write(rep);
        out.flush();
        Log.i(TAG, "UDP ASSOCIATE OK — relay 127.0.0.1:" + bindPort);

        final AtomicBoolean alive = new AtomicBoolean(true);
        final InetSocketAddress[] clientAddr = { null };
        final Object clientLock = new Object();

        // udpgw → มือถือ (หุ้ม SOCKS5 UDP header แล้วส่งกลับ client)
        udpgw.setListener((src, port, data, offset, length) -> {
            try {
                InetSocketAddress dest;
                synchronized (clientLock) {
                    dest = clientAddr[0];
                }
                if (dest == null || !alive.get()) return;
                byte[] ip = src.getAddress();
                if (ip == null || ip.length != 4) return;

                byte[] packet = new byte[10 + length];
                packet[0] = 0;
                packet[1] = 0;
                packet[2] = 0; // FRAG
                packet[3] = (byte) ATYP_IPV4;
                System.arraycopy(ip, 0, packet, 4, 4);
                packet[8] = (byte) ((port >> 8) & 0xFF);
                packet[9] = (byte) (port & 0xFF);
                System.arraycopy(data, offset, packet, 10, length);

                udpSock.send(new DatagramPacket(
                        packet, packet.length, dest.getAddress(), dest.getPort()));
            } catch (Exception e) {
                Log.w(TAG, "udp downstream: " + e.getMessage());
            }
        });

        Thread udpThread = new Thread(() -> {
            byte[] buf = new byte[65535];
            while (alive.get() && running.get()) {
                try {
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    udpSock.receive(dp);
                    synchronized (clientLock) {
                        clientAddr[0] = new InetSocketAddress(dp.getAddress(), dp.getPort());
                    }
                    parseAndForwardUdp(buf, dp.getLength());
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                    if (alive.get()) Log.w(TAG, "udp relay: " + e.getMessage());
                    break;
                }
            }
            try { udpSock.close(); } catch (Exception ignored) {}
        }, "socks-udp-relay");
        udpThread.setDaemon(true);
        udpThread.start();

        // ค้าง TCP control ไว้จนกว่า client ปิด (ตามสเปก SOCKS5)
        try {
            InputStream in = tcpControl.getInputStream();
            byte[] tmp = new byte[256];
            while (running.get() && alive.get()) {
                int n = in.read(tmp);
                if (n < 0) break;
            }
        } catch (Exception ignored) {
        } finally {
            alive.set(false);
            try { udpSock.close(); } catch (Exception ignored) {}
            try { udpThread.join(800); } catch (Exception ignored) {}
            Log.i(TAG, "UDP ASSOCIATE closed port=" + bindPort);
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
