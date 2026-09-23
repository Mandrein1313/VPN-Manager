package com.example.vpn.util;

import android.util.Base64;

import com.example.vpn.model.Profile;
import com.example.vpn.model.Protocol;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * ⭐ จัดการ payload ของ QR Code
 */
public class QrPayload {

    private static final String PREFIX_PROFILE = "vpnmanager://profile?data=";
    private static final String PREFIX_MULTI = "vpnmanager://multi?data=";

    // ============================================================
    // ⭐ Encode
    // ============================================================
    public static String encode(Profile p) {
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("name", p.name);
            o.put("protocol", p.protocol.id);
            o.put("host", p.host);
            o.put("port", p.port);
            o.put("user", p.user);
            o.put("pass", p.pass);
            o.put("httpProxy", p.httpProxy);
            o.put("payload", p.payload);
            o.put("sni", p.sni);
            o.put("dns1", p.dns1);
            o.put("dns2", p.dns2);

            String json = o.toString();
            String base64 = Base64.encodeToString(
                    json.getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP | Base64.URL_SAFE);

            return PREFIX_PROFILE + base64;

        } catch (Exception e) {
            VpnLogger.e("QrPayload", "encode error: " + e.getMessage(), e);
            return null;
        }
    }

    // ============================================================
    // ⭐ Decode
    // ============================================================
    public static DecodeResult decode(String content) {
        DecodeResult result = new DecodeResult();

        if (content == null || content.trim().isEmpty()) {
            result.error = "ข้อมูลว่างเปล่า";
            return result;
        }

        String text = content.trim();

        if (text.startsWith(PREFIX_PROFILE)) {
            return decodeVpnManager(text.substring(PREFIX_PROFILE.length()));
        }

        if (text.startsWith(PREFIX_MULTI)) {
            return decodeVpnManagerMulti(text.substring(PREFIX_MULTI.length()));
        }

        if (text.startsWith("ssh://")) {
            ConfigParser.Result r = ConfigParser.parse(text);
            if (r.isSuccess() && r.profile != null) {
                result.profiles.add(r.profile);
                result.success = true;
                return result;
            }
            result.error = r.error;
            return result;
        }

        if (text.contains("@") && text.contains(":")) {
            ConfigParser.Result r = ConfigParser.parse(text);
            if (r.isSuccess() && r.profile != null) {
                result.profiles.add(r.profile);
                result.success = true;
                return result;
            }
        }

        if (text.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(text);
                Profile p = jsonToProfile(o);
                if (p != null) {
                    result.profiles.add(p);
                    result.success = true;
                    return result;
                }
            } catch (Exception ignored) {}
        }

        result.error = "รูปแบบไม่ถูกต้อง";
        return result;
    }

    private static DecodeResult decodeVpnManager(String base64) {
        DecodeResult result = new DecodeResult();
        try {
            byte[] decoded = Base64.decode(base64,
                    Base64.NO_WRAP | Base64.URL_SAFE);
            String json = new String(decoded, StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(json);
            Profile p = jsonToProfile(o);
            if (p == null) {
                result.error = "ข้อมูลโปรไฟล์ไม่ครบถ้วน";
                return result;
            }
            result.profiles.add(p);
            result.success = true;
            return result;
        } catch (Exception e) {
            result.error = "Decode ไม่สำเร็จ: " + e.getMessage();
            return result;
        }
    }

    private static DecodeResult decodeVpnManagerMulti(String base64) {
        DecodeResult result = new DecodeResult();
        try {
            byte[] decoded = Base64.decode(base64,
                    Base64.NO_WRAP | Base64.URL_SAFE);
            String json = new String(decoded, StandardCharsets.UTF_8);
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                Profile p = jsonToProfile(arr.getJSONObject(i));
                if (p != null) result.profiles.add(p);
            }
            if (result.profiles.isEmpty()) {
                result.error = "ไม่พบโปรไฟล์ในข้อมูล";
                return result;
            }
            result.success = true;
            return result;
        } catch (Exception e) {
            result.error = "Decode ไม่สำเร็จ: " + e.getMessage();
            return result;
        }
    }

    private static Profile jsonToProfile(JSONObject o) {
        try {
            Profile p = new Profile();
            p.name = o.optString("name", "");
            p.protocol = Protocol.fromId(o.optString("protocol", "ssh"));
            p.host = o.optString("host", "");
            p.port = o.optInt("port", 22);
            p.user = o.optString("user", "");
            p.pass = o.optString("pass", "");
            p.httpProxy = o.optString("httpProxy", "");
            p.payload = o.optString("payload", "");
            p.sni = o.optString("sni", "");
            p.dns1 = o.optString("dns1", "8.8.8.8");
            p.dns2 = o.optString("dns2", "8.8.4.4");

            if (p.name.isEmpty()) p.name = p.host;
            if (p.host.isEmpty()) return null;

            return p;
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    // Result
    // ============================================================
    public static class DecodeResult {
        public boolean success = false;
        public String error = null;
        public java.util.List<Profile> profiles = new java.util.ArrayList<>();

        public Profile getFirst() {
            return profiles.isEmpty() ? null : profiles.get(0);
        }
    }
}