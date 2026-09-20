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
                    try { protectedOk = protector.protect(s); }
                    catch (Exception e) {
                        VpnLogger.w(TAG, "protect() threw: " + e.getMessage());
                    }
                }
                VpnLogger.i(TAG, "Socket to " + h + ":" + p + " — protected=" + protectedOk);

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

        VpnLogger.i(TAG, "Connecting SSH to " + host + ":" + port
                + (httpProxy != null && !httpProxy.isEmpty()
                    ? " via proxy " + httpProxy : ""));

        session.connect(25_000);
        VpnLogger.i(TAG, "SSH connected successfully");
    }

    // ============================================================
    // ⭐ Direct Payload (ไม่มี proxy)
    // ============================================================
    private static Socket createDirectPayload(Socket s, String host, int port,
                                               String payload) throws IOException {
        VpnLogger.i(TAG, "Direct connect + payload to " + host + ":" + port);
        s.connect(new InetSocketAddress(host, port), 20_000);
        s.setTcpNoDelay(true);
        VpnLogger.i(TAG, "TCP connected");

        // ⭐ แทนที่ placeholders ก่อนแยก [split]
        String replaced = payload
                .replace("[host]", host)
                .replace("[port]", String.valueOf(port))
                .replace("[host_port]", host + ":" + port)
                .replace("[protocol]", "HTTP/1.1")
                .replace("[ua]", "Mozilla/5.0 (Linux; Android 10)")
                .replace("[crlf]", "\r\n")
                .replace("[cr]", "\r")
                .replace("[real_host]", host);

        // ⭐ แยก [split]
        String[] parts = replaced.split("\\[split\\]");
        String part1 = parts[0];
        String part2 = parts.length > 1 ? parts[1] : "";

        VpnLogger.i(TAG, "Sending part 1 (" + part1.length() + " chars)");
        VpnLogger.d(TAG, "Part 1:\n" + part1);

        OutputStream out = s.getOutputStream();
        out.write(part1.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // ⭐ ส่ง part 2 ถ้ามี — รอ 200ms เหมือน NPV Tunnel
        if (!part2.isEmpty()) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            VpnLogger.i(TAG, "Sending part 2 (" + part2.length() + " chars)");
            VpnLogger.d(TAG, "Part 2:\n" + part2);
            out.write(part2.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        // อ่าน response
        InputStream in = s.getInputStream();
        s.setSoTimeout(10_000);

        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));

            String statusLine = reader.readLine();
            VpnLogger.i(TAG, "Server response: " + statusLine);

            if (statusLine == null) {
                throw new IOException("No response from server");
            }

            if (statusLine.startsWith("SSH-")) {
                VpnLogger.i(TAG, "SSH banner detected!");
                s.setSoTimeout(0);
                return s;
            }

            // อ่าน header จนเจอ blank
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                VpnLogger.d(TAG, "Header: " + line);
            }

            if (statusLine.contains("200") || statusLine.contains("101")) {
                VpnLogger.i(TAG, "Tunnel established");
            } else {
                VpnLogger.w(TAG, "Unexpected response: " + statusLine);
            }

            s.setSoTimeout(0);
            return s;

        } catch (java.net.SocketTimeoutException e) {
            VpnLogger.e(TAG, "No response within 10s");
            throw new IOException("Server did not respond");
        }
    }

    // ============================================================
    // ⭐ Proxy Tunnel + [split]
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

        // ⭐ แยก [split]
        String[] parts = replaced.split("\\[split\\]");
        String part1 = parts[0];
        String part2 = parts.length > 1 ? parts[1] : "";

        VpnLogger.i(TAG, "Sending part 1 (" + part1.length() + " chars)");
        VpnLogger.d(TAG, "Part 1:\n" + part1);

        OutputStream out = s.getOutputStream();
        out.write(part1.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // ⭐ ส่ง part 2 หลัง 200ms
        if (!part2.isEmpty()) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            VpnLogger.i(TAG, "Sending part 2 (" + part2.length() + " chars)");
            VpnLogger.d(TAG, "Part 2:\n" + part2);
            out.write(part2.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        // อ่าน response
        InputStream in = s.getInputStream();
        s.setSoTimeout(10_000);

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));

        String statusLine = reader.readLine();
        VpnLogger.i(TAG, "Proxy response: " + statusLine);

        if (statusLine == null) {
            throw new IOException("No response from proxy");
        }

        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            VpnLogger.d(TAG, "Header: " + line);
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