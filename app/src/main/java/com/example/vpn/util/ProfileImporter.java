package com.example.vpn.util;

import com.example.vpn.model.Profile;
import com.example.vpn.model.Protocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * นำเข้า Profile จาก JSON
 */
public class ProfileImporter {

    public static class Result {
        public List<Profile> profiles;
        public String error;

        public boolean isSuccess() {
            return profiles != null && !profiles.isEmpty() && error == null;
        }
    }

    public static Result importFromJson(String text) {
        Result r = new Result();
        r.profiles = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            r.error = "ไม่มีข้อมูล";
            return r;
        }

        try {
            String trimmed = text.trim();

            // ⭐ ลอง JSON ก่อน
            if (trimmed.startsWith("{")) {
                JSONObject root = new JSONObject(trimmed);
                JSONArray arr = root.optJSONArray("profiles");
                if (arr == null) {
                    r.error = "ไม่พบ field 'profiles' ใน JSON";
                    return r;
                }
                for (int i = 0; i < arr.length(); i++) {
                    Profile p = parseProfile(arr.getJSONObject(i));
                    if (p != null) r.profiles.add(p);
                }
            } else {
                // ⭐ ถ้าไม่ใช่ JSON — ลอง parse เป็น config ปกติ
                ConfigParser.Result parsed = ConfigParser.parse(trimmed);
                if (parsed.isSuccess() && parsed.profile != null) {
                    r.profiles.add(parsed.profile);
                } else {
                    r.error = parsed.error != null ? parsed.error : "รูปแบบไม่ถูกต้อง";
                    return r;
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

            if (p.name.isEmpty()) p.name = p.host;
            if (p.host.isEmpty()) return null;

            return p;
        } catch (Exception e) {
            return null;
        }
    }
}