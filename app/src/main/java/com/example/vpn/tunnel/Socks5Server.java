package com.example.vpn.tunnel;

import android.util.Log;

import com.jcraft.jsch.ChannelDirectTCPIP;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SOCKS5 ผ่าน SSH tunnel
 * - bindLocalOnly=true  → 127.0.0.1 (ค่าเริ่มต้น, ใช้กับ Tun2Socks)
 * - bindLocalOnly=false → 0.0.0.0 (แชร์ให้เครื่องอื่นใน Wi‑Fi/Hotspot)
 */
public class Socks5Server {

    private static final String TAG = "Socks5Server";
    public static final int LOCAL_PORT = 1080;

    private static final int VER = 0x05;
    private static final int CMD_CONNECT = 0x01;
    private static final int ATYP_IPV4 = 0x01;
    private static final int ATYP_DOMAIN = 0x03;
    private static final int ATYP_IPV6 = 0x04;
    private static final int REP_SUCCESS = 0x00;
    private static final int REP_GENERAL_FAIL = 0x01;
    private static final int REP_CMD_NOT_SUPPORTED = 0x07;

    private final SshTunnel ssh;
    private final boolean bindLocalOnly;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private Thread acceptThread;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private String listenAddress = "127.0.0.1";

    public Socks5Server(SshTunnel ssh) {
        this(ssh, true);
    }

    public Socks5Server(SshTunnel ssh, boolean bindLocalOnly) {
        this.ssh = ssh;
        this.bindLocalOnly = bindLocalOnly;
    }

    public void start() throws IOException {
        String host = bindLocalOnly ? "127.0.0.1" : "0.0.0.0";
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName(host), LOCAL_PORT), 50);
        running.set(true);
        listenAddress = host;

        acceptThread = new Thread(this::acceptLoop, "socks5-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        Log.i(TAG, "SOCKS5 listening on " + host + ":" + LOCAL_PORT
                + (bindLocalOnly ? " (local)" : " (shared / LAN)"));
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

    public boolean isRunning() {
        return running.get();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = server.accept();
                pool.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) Log.w(TAG, "accept error", e);
            }
        }
    }

    private void handleClient(Socket client) {
        ChannelDirectTCPIP channel = null;
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(0);
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();

            int ver = in.readUnsignedByte();
            if (ver != VER) { client.close(); return; }

            int nMethods = in.readUnsignedByte();
            for (int i = 0; i < nMethods; i++) in.readUnsignedByte();

            out.write(new byte[]{(byte) VER, 0x00});
            out.flush();

            in.readUnsignedByte();
            int cmd = in.readUnsignedByte();
            in.readUnsignedByte();
            int atyp = in.readUnsignedByte();

            String destHost;
            switch (atyp) {
                case ATYP_IPV4: {
                    byte[] addr = new byte[4];
                    in.readFully(addr);
                    destHost = InetAddress.getByAddress(addr).getHostAddress();
                    break;
                }
                case ATYP_DOMAIN: {
                    int len = in.readUnsignedByte();
                    byte[] name = new byte[len];
                    in.readFully(name);
                    destHost = new String(name, "UTF-8");
                    break;
                }
                case ATYP_IPV6: {
                    byte[] addr = new byte[16];
                    in.readFully(addr);
                    destHost = InetAddress.getByAddress(addr).getHostAddress();
                    break;
                }
                default:
                    sendReply(out, REP_GENERAL_FAIL);
                    client.close();
                    return;
            }

            int destPort = in.readUnsignedShort();

            if (cmd != CMD_CONNECT) {
                sendReply(out, REP_CMD_NOT_SUPPORTED);
                client.close();
                return;
            }

            if (ssh == null || !ssh.isConnected()) {
                sendReply(out, REP_GENERAL_FAIL);
                client.close();
                return;
            }

            channel = ssh.openTcp(destHost, destPort);

            out.write(new byte[]{
                    (byte) VER, (byte) REP_SUCCESS, 0x00, (byte) ATYP_IPV4,
                    0, 0, 0, 0, 0, 0
            });
            out.flush();

            final ChannelDirectTCPIP ch = channel;
            Thread t1 = new Thread(() -> pipe(client, ch), "socks-up");
            Thread t2 = new Thread(() -> pipe(ch, client), "socks-down");
            t1.start();
            t2.start();
            t1.join();
            t2.join();

        } catch (Exception e) {
            Log.w(TAG, "handleClient: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            if (channel != null) channel.disconnect();
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
        pool.shutdownNow();
        Log.i(TAG, "SOCKS5 server stopped");
    }
}
