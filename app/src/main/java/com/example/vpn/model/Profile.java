package com.example.vpn.model;

import java.util.HashMap;
import java.util.Map;

public class Profile {
    public long id = 0L;
    public String name = "";
    public Protocol protocol = Protocol.SSH;
    public String host = "";
    public int port = 22;
    public String user = "";
    public String pass = "";
    public String httpProxy = "";
    public String payload = "";
    public String sni = "";
    public String dns1 = "8.8.8.8";
    public String dns2 = "8.8.4.4";

    /** badvpn-udpgw port บน SSH server (0 = ปิด) */
    public int udpgwPort = 7300;

    /** พอร์ต HTTP Proxy แยก (0 = ใช้จาก httpProxy host:port) */
    public int proxyPort = 0;

    /** พอร์ต SSL/TLS แยก (0 = ใช้พอร์ต SSH หลัก) */
    public int sslPort = 0;

    /** password | private_key */
    public String authMethod = "password";

    /** เนื้อหา private key (PEM) */
    public String privateKey = "";

    /** passphrase ของ key (ถ้ามี) */
    public String keyPassphrase = "";

    /** โหมดการเชื่อมต่อ SSH */
    public String connectionMode = ConnectionMode.DIRECT.name();

    // V2Ray
    public String v2rayType = "vless";
    public String v2rayUuid = "";
    public String v2rayNetwork = "tcp";
    public String v2rayPath = "/";
    public String v2rayHost = "";
    public String v2rayServiceName = "";
    public boolean v2rayTls = false;
    public String v2rayFlow = "";
    public String v2rayMethod = "aes-256-gcm";

    public Map<String, String> extras = new HashMap<>();
    public boolean isFavorite = false;
    public long lastUsedAt = 0L;

    public Profile() {}

    public ConnectionMode getConnectionMode() {
        if (connectionMode == null || connectionMode.isEmpty()) {
            return ConnectionMode.infer(httpProxy, payload, sni);
        }
        ConnectionMode m = ConnectionMode.fromId(connectionMode);
        if (m == ConnectionMode.DIRECT) {
            ConnectionMode inferred = ConnectionMode.infer(httpProxy, payload, sni);
            if (inferred != ConnectionMode.DIRECT) return inferred;
        }
        return m;
    }

    public boolean usePrivateKey() {
        return "private_key".equalsIgnoreCase(authMethod)
                && privateKey != null && privateKey.trim().length() > 40;
    }

    /** host ของ proxy จากช่อง httpProxy (ตัด :port ออก) */
    public String proxyHostOnly() {
        if (httpProxy == null || httpProxy.isEmpty()) return "";
        String s = httpProxy.trim();
        int c = s.lastIndexOf(':');
        if (c > 0 && c < s.length() - 1) {
            String after = s.substring(c + 1);
            if (after.matches("\\d+")) return s.substring(0, c);
        }
        return s;
    }

    /** พอร์ต proxy จริง */
    public int effectiveProxyPort() {
        if (proxyPort > 0 && proxyPort <= 65535) return proxyPort;
        if (httpProxy == null || httpProxy.isEmpty()) return 80;
        int c = httpProxy.lastIndexOf(':');
        if (c > 0) {
            try {
                return Integer.parseInt(httpProxy.substring(c + 1).trim());
            } catch (Exception ignored) {}
        }
        return 80;
    }

    public int effectiveSslPort() {
        if (sslPort > 0 && sslPort <= 65535) return sslPort;
        return port > 0 ? port : 443;
    }

    public Profile copy() {
        Profile p = new Profile();
        p.id = id;
        p.name = name;
        p.protocol = protocol;
        p.host = host;
        p.port = port;
        p.user = user;
        p.pass = pass;
        p.httpProxy = httpProxy;
        p.payload = payload;
        p.sni = sni;
        p.dns1 = dns1;
        p.dns2 = dns2;
        p.udpgwPort = udpgwPort;
        p.proxyPort = proxyPort;
        p.sslPort = sslPort;
        p.authMethod = authMethod;
        p.privateKey = privateKey;
        p.keyPassphrase = keyPassphrase;
        p.connectionMode = connectionMode;
        p.v2rayType = v2rayType;
        p.v2rayUuid = v2rayUuid;
        p.v2rayNetwork = v2rayNetwork;
        p.v2rayPath = v2rayPath;
        p.v2rayHost = v2rayHost;
        p.v2rayServiceName = v2rayServiceName;
        p.v2rayTls = v2rayTls;
        p.v2rayFlow = v2rayFlow;
        p.v2rayMethod = v2rayMethod;
        p.extras = new HashMap<>(extras);
        p.isFavorite = isFavorite;
        p.lastUsedAt = lastUsedAt;
        return p;
    }
}
