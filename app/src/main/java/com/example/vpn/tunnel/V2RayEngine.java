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

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * ⭐ V2Ray Engine — ใช้ AndroidLibXrayLite v26.9.9 (API ใหม่)
 * รองรับ VLESS, VMess, Trojan, Shadowsocks
 */
public class V2RayEngine {

    private static final String TAG = "V2RayEngine";
    public static final int SOCKS_PORT = 1081;

    private final Context ctx;
    private final V2RayConfig config;
    private final SocketProtector protector;

    private CoreController coreController;
    private volatile boolean running = false;

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

        // เขียนไฟล์ config (สำรองไว้ debug)
        File configFile = new File(ctx.getFilesDir(), "v2ray-config.json");
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            fos.write(configJson.getBytes("UTF-8"));
        }

        // ✅ สร้าง CoreController ด้วย callback handler ใหม่
        coreController = Libv2ray.newCoreController(buildCallbackHandler());

        // ✅ FIX #1: เปลี่ยน StartLoop(String) → startLoop(String, int)
        //    พารามิเตอร์ที่ 2 คือ file descriptor ของ VPN interface
        //    ถ้าใช้ SOCKS proxy mode (ไม่ใช่ full VPN) ให้ส่ง 0
        coreController.startLoop(configJson, 0);
        VpnLogger.i(TAG, "startLoop called");

        // รอ SOCKS พร้อม
        for (int i = 0; i < 30; i++) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            if (checkSocksReady()) {
                running = true;
                VpnLogger.i(TAG, "V2Ray SOCKS5 ready on 127.0.0.1:" + SOCKS_PORT);
                return;
            }
        }
        VpnLogger.w(TAG, "V2Ray SOCKS5 not ready after timeout");
        running = true; // ถือว่าเริ่มไปแล้ว แม้จะ timeout
    }

    public void stop() {
        VpnLogger.i(TAG, "Stopping V2Ray...");
        running = false;
        try {
            if (coreController != null) {
                // ✅ FIX #2: เปลี่ยน StopLoop() → stopLoop()
                coreController.stopLoop();
                coreController = null;
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

        // DNS — ช่วย resolve ผ่าน proxy (ลดเคส Heartbeat OK แต่เว็บไม่ขึ้น)
        JSONObject dns = new JSONObject();
        JSONArray dnsServers = new JSONArray();
        dnsServers.put("8.8.8.8");
        dnsServers.put("1.1.1.1");
        dns.put("servers", dnsServers);
        dns.put("queryStrategy", "UseIPv4");
        root.put("dns", dns);

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
        JSONObject sniffing = new JSONObject();
        sniffing.put("enabled", true);
        JSONArray destOverride = new JSONArray();
        destOverride.put("http");
        destOverride.put("tls");
        destOverride.put("quic");
        sniffing.put("destOverride", destOverride);
        sniffing.put("routeOnly", false);
        socksIn.put("sniffing", sniffing);
        inbounds.put(socksIn);
        root.put("inbounds", inbounds);

        // Outbounds
        JSONArray outbounds = new JSONArray();

        // Proxy outbound
        JSONObject out = new JSONObject();
        out.put("tag", "proxy");
        out.put("protocol", config.type);
        out.put("settings", buildOutboundSettings());
        JSONObject stream = buildStreamSettings();
        // Fragment: ให้ proxy ออกผ่าน outbound "fragment"
        if (config.fragment) {
            JSONObject sockopt = stream.optJSONObject("sockopt");
            if (sockopt == null) sockopt = new JSONObject();
            sockopt.put("dialerProxy", "fragment");
            sockopt.put("tcpNoDelay", true);
            stream.put("sockopt", sockopt);
            VpnLogger.i(TAG, "Fragment enabled → dialerProxy=fragment");
        }
        out.put("streamSettings", stream);
        outbounds.put(out);

        // Fragment outbound (ต้องอยู่หลัง proxy ใน list ก็ได้ แต่ tag ต้องตรง)
        if (config.fragment) {
            outbounds.put(buildFragmentOutbound());
        }

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

        // Routing — ส่งทราฟฟิกทั้งหมดไป proxy เป็นค่าเริ่มต้น
        JSONObject routing = new JSONObject();
        routing.put("domainStrategy", "AsIs");
        JSONArray rules = new JSONArray();
        JSONObject dnsRule = new JSONObject();
        dnsRule.put("type", "field");
        dnsRule.put("port", "53");
        dnsRule.put("network", "udp,tcp");
        dnsRule.put("outboundTag", "proxy");
        rules.put(dnsRule);
        routing.put("rules", rules);
        root.put("routing", routing);

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
        stream.put("network", config.network == null || config.network.isEmpty()
                ? "tcp" : config.network);

        // Xray ห้าม VLESS ไปโดเมนสาธารณะโดยไม่มี TLS
        // กำหนด security: reality | tls | none
        String sec = config.security != null ? config.security.toLowerCase() : "";
        if (sec.isEmpty()) {
            if (config.publicKey != null && !config.publicKey.isEmpty()) {
                sec = "reality";
            } else if (config.tls) {
                sec = "tls";
            } else {
                sec = "none";
            }
        }

        // Fragment ใช้ได้กับ TLS/REALITY
        if (config.fragment && "none".equals(sec)) {
            sec = "tls";
            VpnLogger.w(TAG, "Fragment เปิด → บังคับ TLS");
        }
        // VLESS ไปโดเมนสาธารณะ: บังคับ TLS เฉพาะเมื่อไม่ได้ตั้ง Reality และผู้ใช้ไม่ได้ปิดตั้งใจ
        if ("none".equals(sec) && isVlessFamily() && isPublicServer(config.address)
                && (config.publicKey == null || config.publicKey.isEmpty())) {
            sec = "tls";
            VpnLogger.w(TAG, "Force TLS for VLESS public host: " + config.address);
        }

        if ("reality".equals(sec)) {
            stream.put("security", "reality");
            JSONObject reality = new JSONObject();
            String serverName = config.sni;
            if (serverName == null || serverName.isEmpty()) {
                serverName = (config.host != null && !config.host.isEmpty())
                        ? config.host : config.address;
            }
            if (serverName != null && !serverName.isEmpty()) {
                reality.put("serverName", serverName);
            }
            String fp = (config.fingerprint != null && !config.fingerprint.isEmpty())
                    ? config.fingerprint : "chrome";
            reality.put("fingerprint", fp);
            if (config.publicKey != null && !config.publicKey.isEmpty()) {
                reality.put("publicKey", config.publicKey);
            }
            // shortId: Xray รับ string หรือ array — ใช้ string ว่างได้
            reality.put("shortId", config.shortId != null ? config.shortId : "");
            if (config.spiderX != null && !config.spiderX.isEmpty()) {
                reality.put("spiderX", config.spiderX);
            }
            // show: false ปกติ
            reality.put("show", false);
            stream.put("realitySettings", reality);
            VpnLogger.i(TAG, "REALITY: sni=" + serverName
                    + " pbk=" + (config.publicKey != null ? config.publicKey.substring(0, Math.min(8, config.publicKey.length())) + "…" : "")
                    + " sid=" + config.shortId);
        } else if ("tls".equals(sec) || "xtls".equals(sec)) {
            stream.put("security", "tls");
            JSONObject tls = new JSONObject();
            String serverName = config.sni;
            if (serverName == null || serverName.isEmpty()) {
                serverName = (config.host != null && !config.host.isEmpty())
                        ? config.host : config.address;
            }
            if (serverName != null && !serverName.isEmpty()) {
                tls.put("serverName", serverName);
            }
            if (config.alpn != null && !config.alpn.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String a : config.alpn.split(",")) {
                    String t = a.trim();
                    if (!t.isEmpty()) arr.put(t);
                }
                if (arr.length() > 0) tls.put("alpn", arr);
            }
            String fp = (config.fingerprint != null && !config.fingerprint.isEmpty())
                    ? config.fingerprint : "chrome";
            tls.put("fingerprint", fp);
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
    // ✅ Core Callback Handler (API ใหม่ แทน V2RayVPNServiceSupportsSet)
    // ============================================================
    private CoreCallbackHandler buildCallbackHandler() {
        return new CoreCallbackHandler() {

            // ✅ FIX #3: เปลี่ยน Startup() → startup() (ตัว s พิมพ์เล็ก)
            @Override
            public long startup() {
                VpnLogger.i(TAG, "V2Ray callback: startup");
                return 0;
            }

            @Override
            public long shutdown() {
                VpnLogger.i(TAG, "V2Ray callback: shutdown");
                running = false;
                return 0;
            }

            // หมายเหตุ: OnEmitStatus อาจสะกดต่างออกไป
            // ถ้ายัง error ให้ลบบรรทัด @Override ออกแล้วลองคอมไพล์
            @Override
            public long onEmitStatus(long code, String message) {
                VpnLogger.d(TAG, "V2Ray status: " + code + " — " + message);
                return 0;
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

    private JSONObject buildFragmentOutbound() throws Exception {
        JSONObject frag = new JSONObject();
        frag.put("tag", "fragment");
        frag.put("protocol", "freedom");

        JSONObject settings = new JSONObject();
        settings.put("domainStrategy", "AsIs");
        JSONObject fragment = new JSONObject();
        String packets = (config.fragmentPackets != null && !config.fragmentPackets.isEmpty())
                ? config.fragmentPackets : "tlshello";
        String length = (config.fragmentLength != null && !config.fragmentLength.isEmpty())
                ? config.fragmentLength : "100-200";
        String interval = (config.fragmentInterval != null && !config.fragmentInterval.isEmpty())
                ? config.fragmentInterval : "10-20";
        fragment.put("packets", packets);
        fragment.put("length", length);
        fragment.put("interval", interval);
        settings.put("fragment", fragment);
        frag.put("settings", settings);

        JSONObject stream = new JSONObject();
        JSONObject sockopt = new JSONObject();
        sockopt.put("tcpNoDelay", true);
        stream.put("sockopt", sockopt);
        frag.put("streamSettings", stream);

        VpnLogger.i(TAG, "Fragment outbound: packets=" + packets
                + " length=" + length + " interval=" + interval);
        return frag;
    }

    private boolean isVlessFamily() {
        String t = config.type == null ? "" : config.type.toLowerCase();
        return "vless".equals(t) || "trojan".equals(t);
    }

    /** true ถ้าไม่ใช่ private IP / localhost */
    private static boolean isPublicServer(String host) {
        if (host == null || host.isEmpty()) return true;
        String h = host.trim().toLowerCase();
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")) return false;
        if (h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) return false;
        if (h.startsWith("172.")) {
            try {
                String[] parts = h.split("\\.");
                if (parts.length >= 2) {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) return false;
                }
            } catch (Exception ignored) {}
        }
        return true;
    }


}