package com.example.vpn.model;

import android.util.Base64;

import org.json.JSONObject;

/**
 * ⭐ โมเดล config V2Ray
 * รองรับ VLESS, VMess, Trojan, Shadowsocks
 */
public class V2RayConfig {

    public String type = "vless";
    public String uuid = "";
    public String password = "";
    public String method = "aes-256-gcm";

    public String address = "";
    public int port = 443;

    public String network = "tcp";
    public String headerType = "none";
    public String host = "";
    public String path = "/";
    public String serviceName = "";

    public boolean tls = false;
    public String sni = "";
    public String alpn = "";
    public String fingerprint = "chrome";
    public boolean allowInsecure = false;

    public String flow = "";
    public String name = "";

    // ============================================================
    // Parse URL
    // ============================================================
    public static V2RayConfig parse(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            if (url.startsWith("vless://")) return parseVless(url);
            if (url.startsWith("vmess://")) return parseVmess(url);
            if (url.startsWith("trojan://")) return parseTrojan(url);
            if (url.startsWith("ss://")) return parseShadowsocks(url);
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static V2RayConfig parseVless(String url) {
        try {
            String s = url.substring(8); // "vless://" = 8 chars
            int hashIdx = s.indexOf('#');
            String name = "";
            if (hashIdx >= 0) {
                name = decode(s.substring(hashIdx + 1));
                s = s.substring(0, hashIdx);
            }
            int atIdx = s.indexOf('@');
            if (atIdx < 0) return null;

            V2RayConfig c = new V2RayConfig();
            c.type = "vless";
            c.name = name;
            c.uuid = s.substring(0, atIdx);

            String rest = s.substring(atIdx + 1);
            int qIdx = rest.indexOf('?');
            String hostPort = qIdx >= 0 ? rest.substring(0, qIdx) : rest;
            String query = qIdx >= 0 ? rest.substring(qIdx + 1) : "";

            int colon = hostPort.lastIndexOf(':');
            if (colon < 0) return null;
            c.address = hostPort.substring(0, colon);
            c.port = Integer.parseInt(hostPort.substring(colon + 1));

            parseQuery(c, query);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static V2RayConfig parseVmess(String url) {
        try {
            String b64 = url.substring(8).trim();
            while (b64.length() % 4 != 0) b64 += "=";
            byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
            String json = new String(decoded, "UTF-8");

            JSONObject o = new JSONObject(json);
            V2RayConfig c = new V2RayConfig();
            c.type = "vmess";
            c.name = o.optString("ps", "");
            c.uuid = o.optString("id", "");
            c.address = o.optString("add", "");
            c.port = o.optInt("port", 443);
            c.network = o.optString("net", "tcp");
            c.host = o.optString("host", "");
            c.path = o.optString("path", "/");
            c.tls = "tls".equalsIgnoreCase(o.optString("tls", ""));
            c.sni = o.optString("sni", "");
            c.alpn = o.optString("alpn", "");
            c.headerType = o.optString("type", "none");

            if (c.address.isEmpty()) return null;
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static V2RayConfig parseTrojan(String url) {
        try {
            String s = url.substring(9); // "trojan://" = 9 chars
            int hashIdx = s.indexOf('#');
            String name = "";
            if (hashIdx >= 0) {
                name = decode(s.substring(hashIdx + 1));
                s = s.substring(0, hashIdx);
            }
            int atIdx = s.indexOf('@');
            if (atIdx < 0) return null;

            V2RayConfig c = new V2RayConfig();
            c.type = "trojan";
            c.name = name;
            c.password = decode(s.substring(0, atIdx));

            String rest = s.substring(atIdx + 1);
            int qIdx = rest.indexOf('?');
            String hostPort = qIdx >= 0 ? rest.substring(0, qIdx) : rest;
            String query = qIdx >= 0 ? rest.substring(qIdx + 1) : "";

            int colon = hostPort.lastIndexOf(':');
            if (colon < 0) return null;
            c.address = hostPort.substring(0, colon);
            c.port = Integer.parseInt(hostPort.substring(colon + 1));

            parseQuery(c, query);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static V2RayConfig parseShadowsocks(String url) {
        try {
            String s = url.substring(5); // "ss://" = 5 chars
            int hashIdx = s.indexOf('#');
            String name = "";
            if (hashIdx >= 0) {
                name = decode(s.substring(hashIdx + 1));
                s = s.substring(0, hashIdx);
            }
            int qIdx = s.indexOf('?');
            if (qIdx >= 0) s = s.substring(0, qIdx);

            String userInfo;
            String hostPort;
            int atIdx = s.lastIndexOf('@');
            if (atIdx >= 0) {
                userInfo = s.substring(0, atIdx);
                hostPort = s.substring(atIdx + 1);
            } else {
                // Fully encoded
                while (s.length() % 4 != 0) s += "=";
                byte[] dec = Base64.decode(s, Base64.URL_SAFE);
                String full = new String(dec, "UTF-8");
                int at2 = full.lastIndexOf('@');
                if (at2 < 0) return null;
                userInfo = full.substring(0, at2);
                hostPort = full.substring(at2 + 1);
            }

            // Decode userInfo
            String methodPassword;
            try {
                while (userInfo.length() % 4 != 0) userInfo += "=";
                byte[] dec = Base64.decode(userInfo, Base64.URL_SAFE);
                methodPassword = new String(dec, "UTF-8");
            } catch (Exception e) {
                methodPassword = userInfo;
            }

            int colon1 = methodPassword.indexOf(':');
            if (colon1 < 0) return null;

            int colon2 = hostPort.lastIndexOf(':');
            if (colon2 < 0) return null;

            V2RayConfig c = new V2RayConfig();
            c.type = "ss";
            c.name = name;
            c.method = methodPassword.substring(0, colon1);
            c.password = methodPassword.substring(colon1 + 1);
            c.address = hostPort.substring(0, colon2);
            c.port = Integer.parseInt(hostPort.substring(colon2 + 1));
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static void parseQuery(V2RayConfig c, String query) {
        if (query.isEmpty()) return;
        String[] parts = query.split("&");
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq < 0) continue;
            String key = p.substring(0, eq);
            String value = decode(p.substring(eq + 1));

            switch (key) {
                case "type":
                case "network": c.network = value; break;
                case "security": c.tls = "tls".equals(value) || "xtls".equals(value); break;
                case "sni": c.sni = value; break;
                case "host": c.host = value; break;
                case "path": c.path = value; break;
                case "serviceName": c.serviceName = value; break;
                case "headerType": c.headerType = value; break;
                case "alpn": c.alpn = value; break;
                case "fp":
                case "fingerprint": c.fingerprint = value; break;
                case "flow": c.flow = value; break;
                case "allowInsecure":
                    c.allowInsecure = "1".equals(value) || "true".equals(value);
                    break;
            }
        }
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}