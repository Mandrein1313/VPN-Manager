package com.example.vpn.model;

/**
 * โหมดการเชื่อมต่อ SSH (เฟส 1)
 */
public enum ConnectionMode {
    /** ต่อ Host:Port ตรง ๆ ไม่ใช้ proxy/payload */
    DIRECT("Direct SSH"),

    /** ผ่าน HTTP Proxy + Payload (injector) — โหมดที่ใช้บ่อยในไทย */
    HTTP_PROXY_PAYLOAD("SSH + HTTP Proxy (Payload)"),

    /** หุ้มด้วย TLS/SSL ใช้ SNI (Stunnel-like) */
    SSL_TLS("SSH over SSL/TLS"),

    /** WebSocket upgrade (Cloudflare / CDN) */
    WEBSOCKET("SSH over WebSocket");

    public final String label;

    ConnectionMode(String label) {
        this.label = label;
    }

    public static ConnectionMode fromId(String id) {
        if (id == null || id.isEmpty()) return DIRECT;
        try {
            return valueOf(id.trim().toUpperCase());
        } catch (Exception e) {
            // aliases
            String s = id.trim().toLowerCase();
            if (s.contains("proxy") || s.contains("payload")) return HTTP_PROXY_PAYLOAD;
            if (s.contains("ssl") || s.contains("tls") || s.contains("stunnel")) return SSL_TLS;
            if (s.contains("ws") || s.contains("websocket")) return WEBSOCKET;
            return DIRECT;
        }
    }

    /** เดาโหมดจากค่าเดิมในโปรไฟล์ (ยังไม่มี connectionMode) */
    public static ConnectionMode infer(String httpProxy, String payload, String sni) {
        boolean hasProxy = httpProxy != null && !httpProxy.trim().isEmpty();
        boolean hasPayload = payload != null && !payload.trim().isEmpty();
        boolean hasSni = sni != null && !sni.trim().isEmpty();
        String pl = payload != null ? payload.toLowerCase() : "";

        if (hasProxy) return HTTP_PROXY_PAYLOAD;
        if (hasSni) return SSL_TLS;
        if (pl.contains("websocket") || pl.contains("upgrade")) return WEBSOCKET;
        if (hasPayload) return HTTP_PROXY_PAYLOAD; // payload อย่างเดียว → ใช้ direct+payload ผ่านโหมด proxy path แบบตรง
        return DIRECT;
    }

    public static String[] labels() {
        ConnectionMode[] v = values();
        String[] out = new String[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i].label;
        return out;
    }

    public static ConnectionMode fromLabel(String label) {
        if (label == null) return DIRECT;
        for (ConnectionMode m : values()) {
            if (m.label.equals(label)) return m;
        }
        return fromId(label);
    }
}
