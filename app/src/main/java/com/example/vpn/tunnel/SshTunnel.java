package com.example.vpn.tunnel;

import android.util.Log;

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

    /** Constructor แบบเดิม (SSH ตรงๆ) — ยังคงไว้เพื่อ backward compatibility */
    public SshTunnel(String host, int port, String user, String pass,
                     SocketProtector protector) throws Exception {
        this(host, port, user, pass, null, null, null, protector);
    }

    /**
     * Constructor ใหม่ — รองรับ SSH-Proxy-Payload
     */
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
                if (protector != null) protector.protect(s);

                try {
                    if (fProxy != null && !fProxy.isEmpty()) {
                        // ---- ผ่าน HTTP Proxy + Payload ----
                        return createProxyTunnel(s, fSshHost, fSshPort, fProxy, fPayload);
                    } else {
                        // ---- ต่อตรง ----
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

        Log.i(TAG, "Connecting SSH to " + host + ":" + port
                + (httpProxy != null && !httpProxy.isEmpty()
                    ? " via proxy " + httpProxy : ""));
        session.connect(25_000);
        Log.i(TAG, "SSH connected");
    }

    // ============================================================
    // สร้าง tunnel ผ่าน HTTP Proxy + Payload
    // ============================================================
    private static Socket createProxyTunnel(Socket s, String sshHost, int sshPort,
                                            String httpProxy, String payload)
            throws IOException {

        // แยก host:port ของ proxy
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

        Log.i(TAG, "Connecting to proxy " + proxyHost + ":" + proxyPort);
        s.connect(new InetSocketAddress(proxyHost, proxyPort), 20_000);
        s.setTcpNoDelay(true);

        // เตรียม payload
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

            // ถ้ามี [split] ให้ใช้แค่ครึ่งแรก
            if (req.contains("[split]")) {
                req = req.split("\\[split\\]")[0];
            }
        } else {
            // default HTTP CONNECT
            req = "CONNECT " + sshHost + ":" + sshPort + " HTTP/1.1\r\n"
                    + "Host: " + sshHost + ":" + sshPort + "\r\n"
                    + "User-Agent: Mozilla/5.0 (Linux; Android 10)\r\n"
                    + "Connection: keep-alive\r\n\r\n";
        }

        Log.i(TAG, "Sending payload (" + req.length() + " chars)");

        OutputStream out = s.getOutputStream();
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // อ่าน response
        InputStream in = s.getInputStream();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));

        String statusLine = reader.readLine();
        Log.i(TAG, "Proxy response: " + statusLine);

        if (statusLine == null) {
            throw new IOException("No response from proxy");
        }

        // อ่าน header จนเจอ blank line
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            Log.d(TAG, "Proxy header: " + line);
        }

        // ✅ HTTP CONNECT สำเร็จ (200)
        // ✅ WebSocket upgrade สำเร็จ (101)
        if (statusLine.contains("200") || statusLine.contains("101")) {
            Log.i(TAG, "Proxy tunnel established");
        } else {
            throw new IOException("Proxy rejected: " + statusLine);
        }

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