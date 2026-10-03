package com.example.vpn.util;

import com.example.vpn.model.Profile;
import com.example.vpn.model.Protocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * นำเข้าโปรไฟล์จาก JSON / ข้อความ config
 */
public final class ProfileImporter {

    public static final class Result {
        public List<Profile> profiles;
        public String error;

        public boolean isSuccess() {
            return profiles != null && !profiles.isEmpty() && error == null;
        }
    }

    private ProfileImporter() {}

    public static Result importFromJson(String text) {
        Result r = new Result();
        r.profiles = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            r.error = "ไม่มีข้อมูล";
            return r;
        }

        try {
            String trimmed = text.trim();

            // ตัด BOM
            if (trimmed.startsWith("\uFEFF")) {
                trimmed = trimmed.substring(1).trim();
            }

            if (trimmed.startsWith("{")) {
                JSONObject root = new JSONObject(trimmed);
                JSONArray arr = root.optJSONArray("profiles");
                if (arr == null) {
                    // บางไฟล์เป็น object โปรไฟล์เดียว
                    Profile single = parseProfile(root);
                    if (single != null) {
                        r.profiles.add(single);
                        return r;
                    }
                    r.error = "ไม่พบ field 'profiles' ใน JSON";
                    return r;
                }
                for (int i = 0; i < arr.length(); i++) {
                    Profile p = parseProfile(arr.getJSONObject(i));
                    if (p != null) r.profiles.add(p);
                }
            } else if (trimmed.startsWith("[")) {
                JSONArray arr = new JSONArray(trimmed);
                for (int i = 0; i < arr.length(); i++) {
                    Profile p = parseProfile(arr.getJSONObject(i));
                    if (p != null) r.profiles.add(p);
                }
            } else {
                // หลายบรรทัด config
                String[] lines = trimmed.split("\\r?\\n");
                boolean any = false;
                for (String line : lines) {
                    String L = line.trim();
                    if (L.isEmpty() || L.startsWith("#")) continue;
                    ConfigParser.Result parsed = ConfigParser.parse(L);
                    if (parsed.isSuccess() && parsed.profile != null) {
                        r.profiles.add(parsed.profile);
                        any = true;
                    }
                }
                if (!any) {
                    ConfigParser.Result parsed = ConfigParser.parse(trimmed);
                    if (parsed.isSuccess() && parsed.profile != null) {
                        r.profiles.add(parsed.profile);
                    } else {
                        r.error = parsed.error != null ? parsed.error : "รูปแบบไม่ถูกต้อง";
                        return r;
                    }
                }
            }

            if (r.profiles.isEmpty()) {
                r.error = "ไม่พบโปรไฟล์ในไฟล์";
            }
            return r;

        } catch (Exception e) {
            r.error = "Parse ไม่สำเร็จ: " + e.getMessage();
            return r;
        }
    }

    private static Profile parseProfile(JSONObject o) {
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

            // VLESS / Reality fields ถ้ามีใน model
            try {
                java.lang.reflect.Field f;
                for (String key : new String[]{
                        "uuid", "path", "network", "security",
                        "realityPublicKey", "realityShortId",
                        "realityFingerprint", "realityServerName", "realitySpiderX"
                }) {
                    if (o.has(key)) {
                        try {
                            f = Profile.class.getField(key);
                            if (f.getType() == String.class) {
                                f.set(p, o.optString(key, ""));
                            }
                        } catch (NoSuchFieldException ignored) {}
                    }
                }
            } catch (Exception ignored) {}

            if (p.name == null || p.name.isEmpty()) p.name = p.host;
            if (p.host == null || p.host.isEmpty()) return null;

            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
