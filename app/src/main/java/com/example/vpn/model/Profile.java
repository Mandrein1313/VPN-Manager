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

    /** โหมดการเชื่อมต่อ SSH — ดู ConnectionMode */
    public String connectionMode = ConnectionMode.DIRECT.name();

    // V2Ray fields
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
        // ถ้ายังเป็น DIRECT แต่มี proxy → เดาใหม่ (โปรไฟล์เก่า)
        if (m == ConnectionMode.DIRECT) {
            ConnectionMode inferred = ConnectionMode.infer(httpProxy, payload, sni);
            if (inferred != ConnectionMode.DIRECT) return inferred;
        }
        return m;
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
