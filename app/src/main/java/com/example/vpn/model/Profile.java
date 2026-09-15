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
    public String payload = "";
    public String sni = "";
    public String dns1 = "8.8.8.8";
    public String dns2 = "8.8.4.4";
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
        p.payload = payload;
        p.sni = sni;
        p.dns1 = dns1;
        p.dns2 = dns2;
        p.extras = new HashMap<>(extras);
        p.isFavorite = isFavorite;
        p.lastUsedAt = lastUsedAt;
        return p;
    }
}