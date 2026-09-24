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

    // ⭐ V2Ray fields
    public String v2rayType = "vless";     // vless, vmess, trojan, ss
    public String v2rayUuid = "";
    public String v2rayNetwork = "tcp";    // tcp, ws, grpc, http
    public String v2rayPath = "/";
    public String v2rayHost = "";
    public String v2rayServiceName = "";
    public boolean v2rayTls = false;
    public String v2rayFlow = "";
    public String v2rayMethod = "aes-256-gcm";  // สำหรับ SS

    public Map<String, String> extras = new HashMap<>();
    public boolean isFavorite = false;
    public long lastUsedAt = 0L;

    public Profile() {}

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