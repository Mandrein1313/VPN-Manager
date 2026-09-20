package com.example.vpn.tunnel;

import com.example.vpn.util.VpnLogger;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class SshTunnel {

    private static final String TAG = "SshTunnel";

    private final Session session;

    public interface SocketProtector {
        boolean protect(Socket socket);
    }

    public SshTunnel(String host, int port, String user, String pass,
                     SocketProtector protector) throws Exception {
        this(host, port, user, pass, null, null, null, protector);
    }

    public SshTunnel(String host, int port, String user, String pass,
                     String httpProxy, String payload, String sni,
                     SocketProtector protector) throws Exception {

        JSch jsch = new JSch();
        session = jsch.getSession(user, host, port);
        session.setPassword(pass);

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "password,keyboard-interactive");
        session.setConfig(config);

        final String fSshHost = host;
        final int fSshPort = port;
        final String fProxy = httpProxy;
        final String fPayload = payload;

        session.setSocketFactory(new com.jcraft.jsch.SocketFactory() {
            @Override
            public Socket createSocket(String h, int p) throws IOException {
                Socket s = new Socket();

                if (protector != null) {
                    try { protector.protect(s); }
                    catch (Exception ignored) {}
                }

                try {
                    if (fProxy != null && !fProxy.isEmpty()) {
                        return createProxyTunnel(s, fSshHost, fSshPort, fProxy, fPayload);
                    } else if (fPayload != null && !fPayload.isEmpty()) {
                        return createDirectPayload(s, h, p, fPayload);
                    } else {
                        s.connect(new InetSocketAddress(h, p), 20_000);
                        return s;
                    }
                } catch (IOException e) {
                    try { s.close(); } catch (IOException ignored) {}
                    throw e;
                }
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

        VpnLogger.i(TAG, "Connecting SSH to " + host + ":" + port);
        session.connect(25_000);
        VpnLogger.i(TAG, "SSH connected successfully");
    }

    // ============================================================
    // Proxy Tunnel
    // ============================================================
    private static Socket createProxyTunnel(Socket s, String sshHost, int sshPort,
                                            String httpProxy, String payload)
            throws IOException {

        String proxyHost;
        int proxyPort = 80;
        int colon = httpProxy.lastIndexOf(':');
        if (colon > 0) {
            proxyHost = httpProxy.substring(0, colon);
            try { proxyPort = Integer.parseInt(httpProxy.substring(colon + 1)); }
            catch (NumberFormatException e) { proxyPort = 80; }
        } else {
            proxyHost = httpProxy;
        }

        VpnLogger.i(TAG, "Connecting to proxy " + proxyHost + ":" + proxyPort);
        s.connect(new InetSocketAddress(proxyHost, proxyPort), 20_000);
        s.setTcpNoDelay(true);
        VpnLogger.i(TAG, "Proxy TCP connected");

        // ⭐ แทนที่ placeholders
        String replaced = payload
                .replace("[host]", sshHost)
                .replace("[port]", String.valueOf(sshPort))
                .replace("[host_port]", sshHost + ":" + sshPort)
                .replace("[protocol]", "HTTP/1.1")
                .replace("[ua]", "Mozilla/5.0 (Linux; Android 10)")
                .replace("[crlf]", "\r\n")
                .replace("[cr]", "\r")
                .replace("[real_host]", sshHost);

        // ⭐ ส่งทั้งหมดเป็นชิ้นเดียว ไม่แยก ไม่ delay
        VpnLogger.i(TAG, "Sending combined payload (" + replaced.length() + " chars)");

        OutputStream out = s.getOutputStream();
        out.write(replaced.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // ⭐ รอ response — แต่ไม่ throw ถ้าไม่ใช่ 200/101
        s.setSoTimeout(8_000);
        InputStream in = s.getInputStream();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));

        try {
            String statusLine = reader.readLine();
            VpnLogger.i(TAG, "Proxy first response: " + statusLine);

            if (statusLine == null) {
                throw new IOException("No response from proxy");
            }

            // อ่าน header จนเจอ blank
            String line;
            int headerCount = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty() && headerCount < 30) {
                VpnLogger.d(TAG, "Header: " + line);
                headerCount++;
            }

            // ⭐ Log สถานะ — แต่ไม่ throw
            if (statusLine.contains("101")) {
                VpnLogger.i(TAG, "WebSocket upgrade 101 — tunnel ready");
            } else if (statusLine.contains("200")) {
                VpnLogger.i(TAG, "HTTP 200 — tunnel ready");
            } else if (statusLine.contains("301") || statusLine.contains("302")) {
                VpnLogger.w(TAG, "Redirect — proxy may be upgrading. Trying SSH anyway...");
                // ⭐ ลองอ่าน response ที่ 2 (ถ้ามี)
                try {
                    String statusLine2 = reader.readLine();
                    if (statusLine2 != null && !statusLine2.isEmpty()) {
                        VpnLogger.i(TAG, "Second response: " + statusLine2);
                        String l2;
                        int c2 = 0;
                        while ((l2 = reader.readLine()) != null && !l2.isEmpty() && c2 < 30) {
                            VpnLogger.d(TAG, "H2: " + l2);
                            c2++;
                        }
                    }
                } catch (Exception ignored) {}
            } else {
                VpnLogger.w(TAG, "Unexpected status — continue anyway: " + statusLine);
            }
        } catch (java.net.SocketTimeoutException e) {
            VpnLogger.w(TAG, "No proxy response within 8s — try SSH anyway");
        }

        // ⭐ ล้าง timeout — ปล่อยให้ JSch อ่าน SSH banner
        s.setSoTimeout(0);
        VpnLogger.i(TAG, "Handing socket to JSch — attempting SSH handshake");
        return s;
    }

    // ============================================================
    // Direct Payload
    // ============================================================
    private static Socket createDirectPayload(Socket s, String host, int port,
                                               String payload) throws IOException {
        VpnLogger.i(TAG, "Direct connect + payload to " + host + ":" + port);
        s.connect(new InetSocketAddress(host, port), 20_000);
        s.setTcpNoDelay(true);

        String req = payload
                .replace("[host]", host)
                .replace("[port]", String.valueOf(port))
                .replace("[host_port]", host + ":" + port)
                .replace("[protocol]", "HTTP/1.1")
                .replace("[ua]", "Mozilla/5.0 (Linux; Android 10)")
                .replace("[crlf]", "\r\n")
                .replace("[cr]", "\r")
                .replace("[real_host]", host);

        VpnLogger.i(TAG, "Sending payload (" + req.length() + " chars)");

        OutputStream out = s.getOutputStream();
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        s.setSoTimeout(8_000);
        InputStream in = s.getInputStream();

        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));

            String statusLine = reader.readLine();
            VpnLogger.i(TAG, "Server response: " + statusLine);

            if (statusLine != null && statusLine.startsWith("SSH-")) {
                VpnLogger.i(TAG, "SSH banner detected!");
                s.setSoTimeout(0);
                return s;
            }

            if (statusLine != null) {
                String line;
                int c = 0;
                while ((line = reader.readLine()) != null && !line.isEmpty() && c < 30) {
                    VpnLogger.d(TAG, "Header: " + line);
                    c++;
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            VpnLogger.w(TAG, "No response — try SSH anyway");
        }

        s.setSoTimeout(0);
        return s;
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    public ChannelDirectTCPIP openTcp(String destHost, int destPort) throws Exception {
        ChannelDirectTCPIP channel =
                (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
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