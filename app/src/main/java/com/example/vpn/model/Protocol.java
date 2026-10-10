package com.example.vpn.model;

import com.example.vpn.R;

/**
 * โปรโตคอลที่รองรับในแอป
 */
public enum Protocol {
    SSH("ssh", "SSH Tunnel", 22, "🔒", R.color.proto_ssh),
    V2RAY("v2ray", "V2Ray / VLESS", 443, "⚡", R.color.proto_v2ray),
    HTTP("http", "HTTP CONNECT", 80, "🌐", R.color.proto_http),
    TROJAN("trojan", "Trojan-Go", 443, "🛡", R.color.proto_trojan),
    WIREGUARD("wireguard", "WireGuard", 51820, "🐉", R.color.proto_wireguard),
    SHADOWSOCKS("ss", "Shadowsocks", 8388, "👤", R.color.proto_shadowsocks);

    public final String id;
    public final String displayName;
    public final int defaultPort;
    public final String icon;
    public final int colorRes;

    Protocol(String id, String displayName, int defaultPort, String icon, int colorRes) {
        this.id = id;
        this.displayName = displayName;
        this.defaultPort = defaultPort;
        this.icon = icon;
        this.colorRes = colorRes;
    }

    public static Protocol fromId(String id) {
        if (id == null) return SSH;
        for (Protocol p : values()) {
            if (p.id.equalsIgnoreCase(id) || p.name().equalsIgnoreCase(id)) {
                return p;
            }
        }
        // aliases
        if ("vless".equalsIgnoreCase(id) || "vmess".equalsIgnoreCase(id)) return V2RAY;
        if ("shadowsocks".equalsIgnoreCase(id)) return SHADOWSOCKS;
        return SSH;
    }

    /** โปรโตคอลที่ให้เลือกใน UI (ที่เหลือเก็บไว้ใน enum เพื่อ profile เก่า) */
    public static Protocol[] selectable() {
        return new Protocol[]{ SSH, V2RAY };
    }
}
