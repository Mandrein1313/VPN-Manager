package com.example.vpn.model;

import androidx.annotation.ColorRes;
import com.example.vpn.R;

public enum Protocol {
    SSH("ssh", "SSH Tunnel", 22, "🔐", R.color.proto_ssh),
    V2RAY("v2ray", "V2Ray / VLESS", 443, "⚡", R.color.proto_v2ray),
    HTTP("http", "HTTP CONNECT", 80, "🌐", R.color.proto_http),
    TROJAN("trojan", "Trojan-Go", 443, "🛡️", R.color.proto_trojan),
    WIREGUARD("wireguard", "WireGuard", 51820, "🐉", R.color.proto_wg),
    SHADOWSOCKS("ss", "Shadowsocks", 8388, "👤", R.color.proto_ss);

    public final String id;
    public final String displayName;
    public final int defaultPort;
    public final String icon;
    @ColorRes public final int colorRes;

    Protocol(String id, String displayName, int defaultPort,
             String icon, @ColorRes int colorRes) {
        this.id = id;
        this.displayName = displayName;
        this.defaultPort = defaultPort;
        this.icon = icon;
        this.colorRes = colorRes;
    }

    public static Protocol fromId(String id) {
        for (Protocol p : values()) {
            if (p.id.equals(id)) return p;
        }
        return SSH;
    }
}
