package com.example.vpn.tunnel;

import android.content.Context;

import com.example.vpn.model.V2RayConfig;
import com.example.vpn.util.VpnLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import libv2ray.Libv2ray;
import libv2ray.V2RayPoint;
import libv2ray.V2RayVPNServiceSupportsSet;

/**
 * ⭐ V2Ray Engine — ใช้ AndroidLibXrayLite
 * รองรับ VLESS, VMess, Trojan, Shadowsocks
 */
public class V2RayEngine {

    private static final String TAG = "V2RayEngine";
    public static final int SOCKS_PORT = 1081;

    private final Context ctx;
    private final V2RayConfig config;
    private final SocketProtector protector;

    private V2RayPoint v2rayPoint;
    private volatile boolean running = false;
    private Thread engineThread;

    public interface SocketProtector {
        boolean protect(int fd);
    }

    public V2RayEngine(Context ctx, V2RayConfig config, SocketProtector protector) {
        this.ctx = ctx.getApplicationContext();
        this.config = config;
        this.protector = protector;
    }

    public boolean isRunning() {
        return running;
    }

    // ============================================================
    // Start
    // ============================================================
    public void start() throws Exception {
        if (running) return;

        VpnLogger.i(TAG, "Starting V2Ray...");

        String configJson = buildConfigJson();
        VpnLogger.d(TAG, "Config:\n" + configJson);

        File configFile = new File(ctx.getFilesDir(), "v2ray-config.json");
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            fos.write(configJson.getBytes("UTF-8"));
        }

        v2rayPoint = Libv2ray.newV2RayPoint(
                buildSupportSet(),
                configFile.getAbsolutePath()
        );
        v2rayPoint.setDomainName(config.address);
        v2rayPoint.setEnableLocalDNS(false);

        engineThread = new Thread(() -> {
            try {
                VpnLogger.i(TAG, "V2Ray runLoop starting...");
                if (!v2rayPoint.runLoop(true)) {
                    VpnLogger.e(TAG, "V2Ray runLoop returned false");
                    running = false;
                    return;
                }
                running = true;
                VpnLogger.i(TAG, "V2Ray running on 127.0.0.1:" + SOCKS_PORT);
            } catch (Exception e) {
                VpnLogger.e(TAG, "V2Ray runLoop error: " + e.getMessage(), e);
            }
        }, "v2ray-engine");
        engineThread.start();

        // รอ SOCKS พร้อม
        for (int i = 0; i < 30; i++) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            if (checkSocksReady()) {
                VpnLogger.i(TAG, "V2Ray SOCKS5 ready");
                return;
            }
        }
        VpnLogger.w(TAG, "V2Ray SOCKS5 not ready after timeout");
    }

    public void stop() {
        VpnLogger.i(TAG, "Stopping V2Ray...");
        running = false;
        try {
            if (v2rayPoint != null) {
                v2rayPoint.stopLoop();
                v2rayPoint = null;
            }
        } catch (Exception e) {
            VpnLogger.w(TAG, "stop error: " + e.getMessage());
        }
    }

    // ============================================================
    // Config JSON
    // ============================================================
    private String buildConfigJson() throws Exception {
        JSONObject root = new JSONObject();

        // Log
        JSONObject log = new JSONObject();
        log.put("loglevel", "warning");
        root.put("log", log);

        // Inbounds — SOCKS5
        JSONArray inbounds = new JSONArray();
        JSONObject socksIn = new JSONObject();
        socksIn.put("tag", "socks-in");
        socksIn.put("port", SOCKS_PORT);
        socksIn.put("listen", "127.0.0.1");
        socksIn.put("protocol", "socks");
        JSONObject socksSettings = new JSONObject();
        socksSettings.put("auth", "noauth");
        socksSettings.put("udp", true);
        socksIn.put("settings", socksSettings);
        inbounds.put(socksIn);
        root.put("inbounds", inbounds);

        // Outbounds
        JSONArray outbounds = new JSONArray();

        // Proxy outbound
        JSONObject out = new JSONObject();
        out.put("tag", "proxy");
        out.put("protocol", config.type);
        out.put("settings", buildOutboundSettings());
        out.put("streamSettings", buildStreamSettings());
        outbounds.put(out);

        // Direct
        JSONObject direct = new JSONObject();
        direct.put("tag", "direct");
        direct.put("protocol", "freedom");
        direct.put("settings", new JSONObject());
        outbounds.put(direct);

        // Block
        JSONObject block = new JSONObject();
        block.put("tag", "block");
        block.put("protocol", "blackhole");
        block.put("settings", new JSONObject());
        outbounds.put(block);

        root.put("outbounds", outbounds);

        return root.toString(2);
    }

    private JSONObject buildOutboundSettings() throws Exception {
        JSONObject settings = new JSONObject();

        switch (config.type) {
            case "vless": {
                JSONArray vnext = new JSONArray();
                JSONObject server = new JSONObject();
                server.put("address", config.address);
                server.put("port", config.port);
                JSONArray users = new JSONArray();
                JSONObject user = new JSONObject();
                user.put("id", config.uuid);
                user.put("encryption", "none");
                if (!config.flow.isEmpty()) user.put("flow", config.flow);
                users.put(user);
                server.put("users", users);
                vnext.put(server);
                settings.put("vnext", vnext);
                break;
            }
            case "vmess": {
                JSONArray vnext = new JSONArray();
                JSONObject server = new JSONObject();
                server.put("address", config.address);
                server.put("port", config.port);
                JSONArray users = new JSONArray();
                JSONObject user = new JSONObject();
                user.put("id", config.uuid);
                user.put("alterId", 0);
                user.put("security", "auto");
                users.put(user);
                server.put("users", users);
                vnext.put(server);
                settings.put("vnext", vnext);
                break;
            }
            case "trojan": {
                JSONArray servers = new JSONArray();
                JSONObject s = new JSONObject();
                s.put("address", config.address);
                s.put("port", config.port);
                s.put("password", config.password);
                servers.put(s);
                settings.put("servers", servers);
                break;
            }
            case "ss": {
                JSONArray servers = new JSONArray();
                JSONObject s = new JSONObject();
                s.put("address", config.address);
                s.put("port", config.port);
                s.put("password", config.password);
                s.put("method", config.method);
                servers.put(s);
                settings.put("servers", servers);
                break;
            }
        }

        return settings;
    }

    private JSONObject buildStreamSettings() throws Exception {
        JSONObject stream = new JSONObject();
        stream.put("network", config.network);

        // Security
        if (config.tls) {
            stream.put("security", "tls");
            JSONObject tls = new JSONObject();
            if (!config.sni.isEmpty()) tls.put("serverName", config.sni);
            if (!config.alpn.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String a : config.alpn.split(",")) arr.put(a.trim());
                tls.put("alpn", arr);
            }
            if (!config.fingerprint.isEmpty()) {
                tls.put("fingerprint", config.fingerprint);
            }
            tls.put("allowInsecure", config.allowInsecure);
            stream.put("tlsSettings", tls);
        } else {
            stream.put("security", "none");
        }

        // Network settings
        switch (config.network) {
            case "ws": {
                JSONObject ws = new JSONObject();
                if (!config.host.isEmpty()) {
                    ws.put("headers", new JSONObject().put("Host", config.host));
                }
                ws.put("path", config.path.isEmpty() ? "/" : config.path);
                stream.put("wsSettings", ws);
                break;
            }
            case "tcp": {
                if (!"none".equals(config.headerType)) {
                    JSONObject tcp = new JSONObject();
                    JSONObject header = new JSONObject();
                    header.put("type", config.headerType);
                    if ("http".equals(config.headerType)) {
                        JSONObject req = new JSONObject();
                        JSONArray headers = new JSONArray();
                        headers.put(new JSONObject()
                                .put("Host", new JSONArray().put(config.host)));
                        req.put("headers", headers);
                        header.put("request", req);
                    }
                    tcp.put("header", header);
                    stream.put("tcpSettings", tcp);
                }
                break;
            }
            case "grpc": {
                JSONObject grpc = new JSONObject();
                grpc.put("serviceName", config.serviceName);
                stream.put("grpcSettings", grpc);
                break;
            }
            case "http":
            case "h2": {
                JSONObject http = new JSONObject();
                if (!config.host.isEmpty()) {
                    JSONArray hosts = new JSONArray();
                    hosts.put(config.host);
                    http.put("host", hosts);
                }
                http.put("path", config.path.isEmpty() ? "/" : config.path);
                stream.put("httpSettings", http);
                break;
            }
        }

        return stream;
    }

    // ============================================================
    // V2Ray Support Set (JNI callback)
    // ============================================================
    private V2RayVPNServiceSupportsSet buildSupportSet() {
        return new V2RayVPNServiceSupportsSet() {
            @Override
            public long shutdown() {
                running = false;
                return 0;
            }

            @Override
            public long prepare() {
                return 0;
            }

            @Override
            public boolean protect(long fd) {
                try {
                    if (protector != null) {
                        return protector.protect((int) fd);
                    }
                } catch (Exception e) {
                    VpnLogger.w(TAG, "protect error: " + e.getMessage());
                }
                return true;
            }

            @Override
            public long onEmitStatus(long code, String message) {
                VpnLogger.d(TAG, "V2Ray status: " + code + " — " + message);
                return 0;
            }

            @Override
            public boolean setup(String conf) {
                return true;
            }
        };
    }

    // ============================================================
    // Check SOCKS
    // ============================================================
    private boolean checkSocksReady() {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", SOCKS_PORT), 500);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
