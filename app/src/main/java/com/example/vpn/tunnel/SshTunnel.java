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

                boolean protectedOk = false;
                if (protector != null) {
                    try {
                        protectedOk = protector.protect(s);
                    } catch (Exception e) {
                        VpnLogger.w(TAG, "protect() threw: " + e.getMessage());
                    }
                }

                VpnLogger.i(TAG, "Socket to " + h + ":" + p
                        + " — protected=" + protectedOk);

                try {
                    if (fProxy != null && !fProxy.isEmpty()) {
                        VpnLogger.i(TAG, "Using HTTP Proxy: " + fProxy);
                        return createProxyTunnel(s, fSshHost, fSshPort, fProxy, fPayload);
                    } else if (fPayload != null && !fPayload.isEmpty()) {
                        VpnLogger.i(TAG, "Using direct payload (no proxy)");
                        return createDirectPayload(s, h, p, fPayload);
                    } else {
                        VpnLogger.i(TAG, "Direct connect to " + h + ":" + p);
                        s.connect(new InetSocketAddress(h, p), 20_000);
                        VpnLogger.i(TAG, "TCP connected to " + h + ":" + p);
                        return s;
                    }
                } catch (IOException e) {
                    VpnLogger.e(TAG, "connect failed: " + e.getMessage());
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

        VpnLogger.i(TAG, "Connecting SSH to " + host + ":" + port
                + (httpProxy != null && !httpProxy.isEmpty()
                    ? " via proxy " + httpProxy : ""));

        session.connect(25_000);

        VpnLogger.i(TAG, "SSH connected successfully");
    }

    // ============================================================
    // ⭐ Direct Payload (ไม่ใช้ proxy)
    // ============================================================
    private static Socket createDirectPayload(Socket s, String host, int port,
                                               String payload) throws IOException {
        VpnLogger.i(TAG, "Direct connect + payload to " + host + ":" + port);

        s.connect(new InetSocketAddress(host, port), 20_000);
        s.setTcpNoDelay(true);
        VpnLogger.i(TAG, "TCP connected to " + host + ":" + port);

        String req = payload
                .replace("[host]", host)
                .replace("[port]", String.valueOf(port))
                .replace("[host_port]", host + ":" + port)
                .replace("[protocol]", "HTTP/1.1")
                .replace("[ua]", "Mozilla/5.0 (Linux; Android 10)")
                .replace("[crlf]", "\r\n")
                .replace("[cr]", "\r")
                .replace("[real_host]", host);

        if (req.contains("[split]")) {
            req = req.split("\\[split\\]")[0];
        }

        VpnLogger.i(TAG, "Sending direct payload (" + req.length() + " chars)");
        VpnLogger.d(TAG, "Payload content:\n" + req);

        OutputStream out = s.getOutputStream();
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        InputStream in = s.getInputStream();

        // ⭐ ตั้ง timeout 5 วินาที — ถ้าไม่มี response → error
        s.setSoTimeout(5_000);

        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));

            String statusLine = reader.readLine();
            VpnLogger.i(TAG, "Server response: " + statusLine);

            if (statusLine == null) {
                throw new IOException("No response from server");
            }

            if (statusLine.startsWith("SSH-")) {
                VpnLogger.i(TAG, "SSH banner detected — payload not needed!");
                // คืน socket — เอา timeout ออก ปล่อยให้ JSch อ่าน SSH
                s.setSoTimeout(0);
                return s;
            }

            // อ่าน header จนเจอ blank
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                VpnLogger.d(TAG, "Header: " + line);
            }

            if (statusLine.contains("101") || statusLine.contains("200")) {
                VpnLogger.i(TAG, "WebSocket/HTTP tunnel established");
            } else {
                VpnLogger.w(TAG, "Unexpected response: " + statusLine);
            }

            // ⭐ เอา timeout ออก — ปล่อยให้ JSch อ่าน SSH ต่อ
            s.setSoTimeout(0);
            return s;

        } catch (java.net.SocketTimeoutException e) {
            VpnLogger.e(TAG, "No response within 5s — server did not reply");
            throw new IOException("Server did not respond to payload");
        }
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
            try {
                proxyPort = Integer.parseInt(httpProxy.substring(colon + 1));
            } catch (NumberFormatException e) {
                proxyPort = 80;
            }
        } else {
            proxyHost = httpProxy;
        }

        VpnLogger.i(TAG, "Connecting to proxy " + proxyHost + ":" + proxyPort);
        s.connect(new InetSocketAddress(proxyHost, proxyPort), 20_000);
        s.setTcpNoDelay(true);
        VpnLogger.i(TAG, "Proxy TCP connected");

        String req;
        if (payload != null && !payload.isEmpty()) {
            req = payload
                    .replace("[host]", sshHost)
                    .replace("[port]", String.valueOf(sshPort))
                    .replace("[host_port]", sshHost + ":" + sshPort)
                    .replace("[protocol]", "HTTP/1.1")
                    .replace("[ua]", "Mozilla/5.0 (Linux; Android 10)")
                    .replace("[crlf]", "\r\n")
                    .replace("[cr]", "\r")
                    .replace("[real_host]", sshHost);
            if (req.contains("[split]")) {
                req = req.split("\\[split\\]")[0];
            }
        } else {
            req = "CONNECT " + sshHost + ":" + sshPort + " HTTP/1.1\r\n"
                    + "Host: " + sshHost + ":" + sshPort + "\r\n"
                    + "User-Agent: Mozilla/5.0 (Linux; Android 10)\r\n"
                    + "Connection: keep-alive\r\n\r\n";
        }

        VpnLogger.i(TAG, "Sending payload (" + req.length() + " chars)");

        OutputStream out = s.getOutputStream();
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        s.setSoTimeout(5_000);

        InputStream in = s.getInputStream();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));

        String statusLine = reader.readLine();
        VpnLogger.i(TAG, "Proxy response: " + statusLine);

        if (statusLine == null) {
            throw new IOException("No response from proxy");
        }

        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            VpnLogger.d(TAG, "Proxy header: " + line);
        }

        if (statusLine.contains("200") || statusLine.contains("101")) {
            VpnLogger.i(TAG, "Proxy tunnel established");
        } else {
            throw new IOException("Proxy rejected: " + statusLine);
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