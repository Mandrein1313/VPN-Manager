package com.example.vpn.util;

import android.util.Base64;

import com.example.vpn.model.Profile;
import com.example.vpn.model.V2RayConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ดึง subscription (แบบ v2rayNG) จาก URL
 * รองรับ body เป็น Base64 หรือข้อความเปล่า ทีละบรรทัดเป็นลิงก์
 */
public class SubscriptionFetcher {

    public static class Result {
        public final List<Profile> profiles = new ArrayList<>();
        public String error;
        public int rawLines;

        public boolean isSuccess() {
            return error == null && !profiles.isEmpty();
        }
    }

    public static Result fetch(String urlStr) {
        Result r = new Result();
        if (urlStr == null || urlStr.trim().isEmpty()) {
            r.error = "URL ว่าง";
            return r;
        }
        String u = urlStr.trim();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(u);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(30_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "v2rayNG/1.8 (Android; VPN-Manager)");
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                r.error = "HTTP " + code;
                return r;
            }
            InputStream in = conn.getInputStream();
            String body = readAll(in).trim();
            if (body.isEmpty()) {
                r.error = "เนื้อหาว่าง";
                return r;
            }

            String content = decodeMaybeBase64(body);
            String[] lines = content.split("\\r?\\n");
            r.rawLines = lines.length;

            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue;

                Profile p = parseLine(t);
                if (p != null && p.host != null && !p.host.isEmpty()) {
                    p.extras.put("subscription_url", u);
                    if (p.name == null || p.name.isEmpty()) {
                        p.name = p.host;
                    }
                    r.profiles.add(p);
                }
            }

            if (r.profiles.isEmpty()) {
                r.error = "ไม่พบลิงก์ที่รองรับ (vless/vmess/trojan/ss) ใน subscription";
            }
        } catch (Exception e) {
            r.error = e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    private static Profile parseLine(String line) {
        String lower = line.toLowerCase();
        if (lower.startsWith("vless://")
                || lower.startsWith("vmess://")
                || lower.startsWith("trojan://")
                || lower.startsWith("ss://")) {
            V2RayConfig cfg = V2RayConfig.parse(line);
            if (cfg != null) return cfg.toProfile();
            return null;
        }
        // เผื่อเป็น config SSH บรรทัดเดียว
        ConfigParser.Result cr = ConfigParser.parse(line);
        if (cr.isSuccess()) return cr.profile;
        return null;
    }

    private static String decodeMaybeBase64(String body) {
        // ถ้ามีลิงก์ชัดเจนอยู่แล้ว ไม่ต้อง decode
        String sample = body.length() > 200 ? body.substring(0, 200) : body;
        String sl = sample.toLowerCase();
        if (sl.contains("vless://") || sl.contains("vmess://")
                || sl.contains("trojan://") || sl.contains("ss://")) {
            return body;
        }
        try {
            String cleaned = body.replaceAll("\\s", "");
            while (cleaned.length() % 4 != 0) cleaned += "=";
            byte[] dec = Base64.decode(cleaned, Base64.DEFAULT);
            String out = new String(dec, StandardCharsets.UTF_8);
            if (out.contains("://") || out.contains("\n")) {
                return out;
            }
        } catch (Exception ignored) {}
        try {
            String cleaned = body.replaceAll("\\s", "");
            while (cleaned.length() % 4 != 0) cleaned += "=";
            byte[] dec = Base64.decode(cleaned, Base64.URL_SAFE);
            return new String(dec, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return body;
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
