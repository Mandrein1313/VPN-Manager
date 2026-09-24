package com.example.vpn.tunnel;

import com.example.vpn.util.VpnLogger;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class SshTunnel {

    private static final String TAG = "SshTunnel";

    /** ⭐ User-Agent แบบ Chrome จริง — ใช้กับ Cloudflare */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Mobile Safari/537.36";

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
    // ⭐ Proxy Tunnel — ส่ง 2 parts + อ่าน response ครบ
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
                .replace("[ua]", USER_AGENT)
                .replace("[crlf]", "\r\n")
                .replace("[cr]", "\r")
                .replace("[real_host]", sshHost);

        // ⭐ แยก [split]
        String part1;
        String part2 = "";

        int splitIdx = replaced.indexOf("[split]");
        if (splitIdx >= 0) {
            part1 = replaced.substring(0, splitIdx);
            part2 = replaced.substring(splitIdx + "[split]".length());
            if (part2.startsWith("\r")) part2 = part2.substring(1);
            VpnLogger.i(TAG, "Split payload → part1=" + part1.length()
                    + " chars, part2=" + part2.length() + " chars");
        } else {
            part1 = replaced;
        }

        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();

        // ---- ส่ง Part 1 ----
        VpnLogger.i(TAG, "Sending part 1 (" + part1.length() + " chars)");
        out.write(part1.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // ⭐ รอ 800ms ให้ server ตอบครบ
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

        // ---- อ่าน response Part 1 ทั้งก้อน (header + body) ----
        s.setSoTimeout(3_000);
        try {
            String resp1 = readFullHttpResponse(in);
            VpnLogger.i(TAG, "Part 1 response: " + firstLine(resp1));
        } catch (java.net.SocketTimeoutException e) {
            VpnLogger.i(TAG, "No response to part 1 — continue");
        }

        // ---- ส่ง Part 2 ----
        if (!part2.isEmpty()) {
            // ⭐ รออีก 300ms ให้ชัวร์
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            VpnLogger.i(TAG, "Sending part 2 (" + part2.length() + " chars)");
            out.write(part2.getBytes(StandardCharsets.UTF_8));
            out.flush();

            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            s.setSoTimeout(3_000);
            try {
                String resp2 = readFullHttpResponse(in);
                String line2 = firstLine(resp2);
                VpnLogger.i(TAG, "Part 2 response: " + line2);

                if (line2.contains("101")) {
                    VpnLogger.i(TAG, "WebSocket upgrade 101 — tunnel ready");
                } else if (line2.contains("200")) {
                    VpnLogger.i(TAG, "HTTP 200 — tunnel ready");
                } else {
                    VpnLogger.w(TAG, "Part 2 unexpected response");
                }
            } catch (java.net.SocketTimeoutException e) {
                VpnLogger.i(TAG, "No response to part 2 — try SSH anyway");
            }
        }

        s.setSoTimeout(0);
        VpnLogger.i(TAG, "Handing socket to JSch for SSH handshake");
        return s;
    }

    // ============================================================
    // ⭐ อ่าน HTTP response ทั้งก้อน (header + chunked/content-length body)
    // ============================================================
    private static String readFullHttpResponse(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int consecutiveLf = 0;
        int b;
        int totalRead = 0;
        final int MAX_HEADER = 8192;

        // ---- อ่าน header จนเจอ \r\n\r\n ----
        while ((b = in.read()) != -1) {
            buf.write(b);
            totalRead++;

            if (b == '\n') {
                consecutiveLf++;
                if (consecutiveLf >= 2) break;
            } else if (b == '\r') {
                // pass
            } else {
                consecutiveLf = 0;
            }

            if (totalRead > MAX_HEADER) {
                VpnLogger.w(TAG, "Header > 8KB — stop");
                break;
            }
        }

        String header = buf.toString("UTF-8");
        String lowerHeader = header.toLowerCase();

        // ---- อ่าน body ถ้ามี ----
        if (lowerHeader.contains("transfer-encoding: chunked")) {
            VpnLogger.d(TAG, "Reading chunked body...");
            try {
                readChunkedBody(in);
            } catch (Exception e) {
                VpnLogger.w(TAG, "Chunked drain error: " + e.getMessage());
            }
        } else if (lowerHeader.contains("content-length:")) {
            int idx = lowerHeader.indexOf("content-length:");
            int end = header.indexOf('\r', idx);
            if (end < 0) end = header.indexOf('\n', idx);
            if (end > idx) {
                try {
                    int len = Integer.parseInt(
                            header.substring(idx + 15, end).trim());
                    byte[] body = new byte[len];
                    int read = 0;
                    while (read < len) {
                        int n = in.read(body, read, len - read);
                        if (n < 0) break;
                        read += n;
                    }
                    VpnLogger.d(TAG, "Read body: " + read + " bytes");
                } catch (Exception e) {
                    VpnLogger.w(TAG, "Body read error: " + e.getMessage());
                }
            }
        }

        return header;
    }

    // ============================================================
    // ⭐ อ่าน chunked body จนเจอ "0\r\n\r\n"
    // ============================================================
    private static void readChunkedBody(InputStream in) throws IOException {
        int maxChunks = 100;
        int totalBytes = 0;

        while (maxChunks-- > 0) {
            // อ่าน chunk size (hex)
            StringBuilder sizeStr = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\r') continue;
                if (b == '\n') break;
                sizeStr.append((char) b);
            }

            String s = sizeStr.toString().trim();
            int semi = s.indexOf(';');
            if (semi > 0) s = s.substring(0, semi);

            int chunkSize;
            try {
                chunkSize = Integer.parseInt(s, 16);
            } catch (NumberFormatException e) {
                break;
            }

            if (chunkSize == 0) {
                // trailing CRLF
                in.read();
                in.read();
                break;
            }

            // อ่าน chunk data
            int totalRead = 0;
            while (totalRead < chunkSize) {
                long skipped = in.skip(chunkSize - totalRead);
                if (skipped <= 0) break;
                totalRead += skipped;
            }
            totalBytes += totalRead;

            // CRLF หลัง chunk
            in.read();
            in.read();
        }

        VpnLogger.d(TAG, "Chunked body drained: " + totalBytes + " bytes");
    }

    // ⭐ อ่าน header เท่านั้น (สำหรับกรณีที่ไม่อยากอ่าน body)
    private static String readHttpHeaderByteByByte(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int consecutiveLf = 0;
        int b;
        int totalRead = 0;
        final int MAX_HEADER = 8192;

        while ((b = in.read()) != -1) {
            buf.write(b);
            totalRead++;

            if (b == '\n') {
                consecutiveLf++;
                if (consecutiveLf >= 2) break;
            } else if (b == '\r') {
                // pass
            } else {
                consecutiveLf = 0;
            }

            if (totalRead > MAX_HEADER) break;
        }

        return buf.toString("UTF-8");
    }

    // ⭐ เอาแค่บรรทัดแรก
    private static String firstLine(String httpResponse) {
        if (httpResponse == null || httpResponse.isEmpty()) return "";
        int idx = httpResponse.indexOf("\r\n");
        if (idx < 0) idx = httpResponse.indexOf("\n");
        if (idx < 0) return httpResponse;
        return httpResponse.substring(0, idx);
    }

    // ============================================================
    // Direct Payload
    // ============================================================
    private static Socket createDirectPayload(Socket s, String host, int port,
                                               String payload) throws IOException {
        VpnLogger.i(TAG, "Direct connect + payload to " + host + ":" + port);
        s.connect(new InetSocketAddress(host, port), 20_000);
        s.setTcpNoDelay(true);

        String replaced = payload
                .replace("[host]", host)
                .replace("[port]", String.valueOf(port))
                .replace("[host_port]", host + ":" + port)
                .replace("[protocol]", "HTTP/1.1")
                .replace("[ua]", USER_AGENT)
                .replace("[crlf]", "\r\n")
                .replace("[cr]", "\r")
                .replace("[real_host]", host);

        String part1 = replaced;
        String part2 = "";
        int splitIdx = replaced.indexOf("[split]");
        if (splitIdx >= 0) {
            part1 = replaced.substring(0, splitIdx);
            part2 = replaced.substring(splitIdx + "[split]".length());
            if (part2.startsWith("\r")) part2 = part2.substring(1);
        }

        VpnLogger.i(TAG, "Sending part 1 (" + part1.length() + " chars)");
        OutputStream out = s.getOutputStream();
        out.write(part1.getBytes(StandardCharsets.UTF_8));
        out.flush();

        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

        if (!part2.isEmpty()) {
            VpnLogger.i(TAG, "Sending part 2 (" + part2.length() + " chars)");
            out.write(part2.getBytes(StandardCharsets.UTF_8));
            out.flush();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }

        s.setSoTimeout(3_000);
        InputStream in = s.getInputStream();
        try {
            String resp = readFullHttpResponse(in);
            VpnLogger.i(TAG, "Server response: " + firstLine(resp));
        } catch (java.net.SocketTimeoutException e) {
            VpnLogger.i(TAG, "No response — try SSH anyway");
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