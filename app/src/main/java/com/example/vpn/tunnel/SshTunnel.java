package com.example.vpn.tunnel;

import android.util.Log;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Properties;

public class SshTunnel {

    private static final String TAG = "SshTunnel";

    private final Session session;

    /** ต้องเรียก vpnService.protect(socket) ก่อน เพื่อไม่ให้เกิด routing loop */
    public interface SocketProtector {
        boolean protect(Socket socket);
    }

    public SshTunnel(String host, int port, String user, String pass,
                     SocketProtector protector) throws Exception {

        JSch jsch = new JSch();

        session = jsch.getSession(user, host, port);
        session.setPassword(pass);

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "password,keyboard-interactive");
        session.setConfig(config);

        // ต้อง protect socket ก่อนต่อ ไม่งั้นจะเกิด routing loop (ต่อตัวเองไม่ได้)
        session.setSocketFactory(new com.jcraft.jsch.SocketFactory() {
            @Override
            public Socket createSocket(String h, int p) throws IOException {
                Socket s = new Socket(h, p);
                if (protector != null) protector.protect(s);
                return s;
            }

            @Override
            public InputStream getInputStream(Socket socket) throws IOException {
                return socket.getInputStream();
            }

            @Override
            public OutputStream getOutputStream(Socket socket) throws IOException {
                return socket.getOutputStream();
            }
        });

        session.setServerAliveInterval(30_000);
        session.setServerAliveCountMax(3);

        Log.i(TAG, "Connecting SSH to " + host + ":" + port + " as " + user);
        session.connect(20_000);
        Log.i(TAG, "SSH connected");
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    /**
     * เปิด channel TCP ไปยัง host:port ปลายทางผ่าน SSH
     * เรียกใช้โดย SOCKS5 server เมื่อมี connection เข้ามา
     */
    public ChannelDirectTCPIP openTcp(String destHost, int destPort) throws Exception {
        ChannelDirectTCPIP channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
        channel.setHost(destHost);
        channel.setPort(destPort);
        channel.setOrgIPAddress("127.0.0.1");
        channel.setOrgPort(0);
        channel.connect(15_000);
        return channel;
    }

    public void disconnect() {
        try {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        } catch (Exception ignored) {}
    }
}