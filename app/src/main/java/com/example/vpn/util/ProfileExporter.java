package com.example.vpn.util;

import android.net.Uri;
import android.util.Base64;

import com.example.vpn.model.Profile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ส่งออก Profile
 *
 * โหมดแชร์:
 *  - BASIC     : เฉพาะ ssh:// (ไม่โชว์ proxy/payload)
 *  - FULL      : ข้อความ + JSON ครบ (เห็นทุกอย่าง)
 *  - PROTECTED : แพ็กเกจเข้ารหัส tmconf:1:... เปิดได้เฉพาะ Tunnel Mate
 */
public class ProfileExporter {

    private static final int FORMAT_VERSION = 1;

    /** เฉพาะ ssh credentials — ไม่มี proxy/payload */
    public static final int SHARE_BASIC = 0;
    /** ครบทุกฟิลด์ (อ่านได้ด้วยตา) */
    public static final int SHARE_FULL = 1;
    /** เข้ารหัส — แอปเดียวกันถอดได้ */
    public static final int SHARE_PROTECTED = 2;

    public static final String PROTECTED_PREFIX = "tmconf:1:";

    // กุญแจแอป (obfuscation — กันมองด้วยตา ไม่ใช่ความปลอดภัยระดับสูง)
    private static final byte[] APP_KEY = new byte[] {
            0x54, 0x6d, 0x4d, 0x61, 0x74, 0x65, 0x56, 0x32,
            0x50, 0x72, 0x6f, 0x74, 0x65, 0x63, 0x74, 0x21
    }; // "TmMateV2Protect!" 16 bytes AES

    public static String export(List<Profile> profiles) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", FORMAT_VERSION);
            root.put("app", "Tunnel Mate");
            root.put("exported_at", new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));

            JSONArray arr = new JSONArray();
            for (Profile p : profiles) {
                arr.put(profileToJson(p, true));
            }
            root.put("profiles", arr);
            return root.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    public static String exportOne(Profile p) {
        return exportOne(p, true);
    }

    public static String exportOne(Profile p, boolean includeSensitive) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", FORMAT_VERSION);
            root.put("app", "Tunnel Mate");
            root.put("exported_at", new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));
            JSONArray arr = new JSONArray();
            arr.put(profileToJson(p, includeSensitive));
            root.put("profiles", arr);
            return root.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** ssh://user:pass@host:port */
    public static String toSshUri(Profile p) {
        if (p == null) return "";
        String user = p.user != null ? Uri.encode(p.user) : "";
        String pass = p.pass != null ? Uri.encode(p.pass) : "";
        String host = p.host != null ? p.host : "";
        int port = p.port > 0 ? p.port : 22;
        if (!user.isEmpty() && !pass.isEmpty()) {
            return "ssh://" + user + ":" + pass + "@" + host + ":" + port;
        }
        if (!user.isEmpty()) {
            return "ssh://" + user + "@" + host + ":" + port;
        }
        return "ssh://" + host + ":" + port;
    }

    /** ค่าเริ่มต้น = PROTECTED (ไม่โชว์ proxy/payload แบบ plain text) */
    public static String toClipboardText(Profile p) {
        return toClipboardText(p, SHARE_PROTECTED);
    }

    public static String toClipboardText(Profile p, int mode) {
        if (p == null) return "";
        switch (mode) {
            case SHARE_BASIC:
                return toBasicText(p);
            case SHARE_FULL:
                return toFullText(p);
            case SHARE_PROTECTED:
            default:
                return toProtectedText(p);
        }
    }

    /** เฉพาะชื่อ + ssh URI — ไม่มี proxy/payload/json */
    public static String toBasicText(Profile p) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(p.name != null ? p.name : "config").append('\n');
        sb.append(toSshUri(p)).append('\n');
        return sb.toString();
    }

    /** แบบเดิม — อ่าน proxy/payload ได้ */
    public static String toFullText(Profile p) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(p.name != null ? p.name : "config").append('\n');
        sb.append(toSshUri(p)).append('\n');
        if (p.httpProxy != null && !p.httpProxy.isEmpty()) {
            sb.append("# proxy=").append(p.httpProxy).append('\n');
        }
        if (p.payload != null && !p.payload.isEmpty()) {
            sb.append("# payload=").append(p.payload).append('\n');
        }
        if (p.sni != null && !p.sni.isEmpty()) {
            sb.append("# sni=").append(p.sni).append('\n');
        }
        sb.append('\n');
        sb.append(exportOne(p, true));
        return sb.toString();
    }

    /**
     * แพ็กเกจเข้ารหัส — มีครบทุกฟิลด์แต่คนอ่านไม่ออก
     * รูปแบบ: tmconf:1:&lt;base64url&gt;
     */
    public static String toProtectedText(Profile p) {
        try {
            String json = exportOne(p, true);
            byte[] plain = json.getBytes(StandardCharsets.UTF_8);
            byte[] iv = new byte[12];
            // IV จาก hash ง่าย ๆ ของเนื้อหา + เวลา (12 ไบต์)
            long t = System.currentTimeMillis();
            for (int i = 0; i < 12; i++) {
                iv[i] = (byte) ((t >>> (i * 5)) ^ (plain.length + i * 31));
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(APP_KEY, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] enc = cipher.doFinal(plain);
            // iv + ciphertext
            byte[] pack = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, pack, 0, iv.length);
            System.arraycopy(enc, 0, pack, iv.length, enc.length);
            String b64 = Base64.encodeToString(pack, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            StringBuilder sb = new StringBuilder();
            sb.append("# Tunnel Mate protected config\n");
            sb.append("# ").append(p.name != null ? p.name : "config").append('\n');
            sb.append(PROTECTED_PREFIX).append(b64).append('\n');
            return sb.toString();
        } catch (Exception e) {
            // fallback แบบ basic ถ้าเข้ารหัสไม่ได้
            return toBasicText(p);
        }
    }

    /** ถอดแพ็กเกจ tmconf:1:... → JSON string หรือ null */
    public static String decryptProtected(String token) {
        if (token == null) return null;
        String t = token.trim();
        // รองรับทั้งบรรทัดเดียวและข้อความที่มี comment
        for (String line : t.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith(PROTECTED_PREFIX)) {
                t = line;
                break;
            }
        }
        if (!t.startsWith(PROTECTED_PREFIX)) return null;
        try {
            String b64 = t.substring(PROTECTED_PREFIX.length()).trim();
            byte[] pack = Base64.decode(b64, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            if (pack.length < 13) return null;
            byte[] iv = new byte[12];
            System.arraycopy(pack, 0, iv, 0, 12);
            byte[] enc = new byte[pack.length - 12];
            System.arraycopy(pack, 12, enc, 0, enc.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(APP_KEY, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(enc);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean looksProtected(String text) {
        if (text == null) return false;
        return text.contains(PROTECTED_PREFIX);
    }

    public static String safeFileName(Profile p) {
        String n = p != null && p.name != null ? p.name : "config";
        n = n.replaceAll("[^a-zA-Z0-9ก-๙_\\-]+", "_");
        if (n.isEmpty()) n = "config";
        return n + ".json";
    }

    private static JSONObject profileToJson(Profile p, boolean includeSensitive) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", p.name);
        o.put("protocol", p.protocol != null ? p.protocol.id : "ssh");
        o.put("host", p.host);
        o.put("port", p.port);
        o.put("user", p.user);
        o.put("pass", p.pass);
        if (includeSensitive) {
            o.put("httpProxy", p.httpProxy != null ? p.httpProxy : "");
            o.put("payload", p.payload != null ? p.payload : "");
            o.put("sni", p.sni != null ? p.sni : "");
            o.put("connectionMode", p.connectionMode != null ? p.connectionMode : "DIRECT");
            o.put("udpgwPort", p.udpgwPort);
            o.put("proxyPort", p.proxyPort);
            o.put("sslPort", p.sslPort);
            o.put("authMethod", p.authMethod != null ? p.authMethod : "password");
            o.put("privateKey", p.privateKey != null ? p.privateKey : "");
            o.put("keyPassphrase", p.keyPassphrase != null ? p.keyPassphrase : "");
        }
        o.put("dns1", p.dns1 != null ? p.dns1 : "8.8.8.8");
        o.put("dns2", p.dns2 != null ? p.dns2 : "8.8.4.4");
        return o;
    }
}
