package com.example.vpn.util;

import android.net.Uri;

import com.example.vpn.model.Profile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ส่งออก Profile เป็น JSON / ssh URI / ข้อความ config
 */
public class ProfileExporter {

    private static final int FORMAT_VERSION = 1;

    public static String export(List<Profile> profiles) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", FORMAT_VERSION);
            root.put("app", "VPN Manager");
            root.put("exported_at", new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));

            JSONArray arr = new JSONArray();
            for (Profile p : profiles) {
                arr.put(profileToJson(p));
            }
            root.put("profiles", arr);
            return root.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    public static String exportOne(Profile p) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", FORMAT_VERSION);
            root.put("app", "VPN Manager");
            root.put("exported_at", new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));
            JSONArray arr = new JSONArray();
            arr.put(profileToJson(p));
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

    /** ข้อความอ่านง่าย + JSON สำรอง */
    public static String toClipboardText(Profile p) {
        if (p == null) return "";
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
        sb.append(exportOne(p));
        return sb.toString();
    }

    public static String safeFileName(Profile p) {
        String n = p != null && p.name != null ? p.name : "config";
        n = n.replaceAll("[^a-zA-Z0-9ก-๙_\\-]+", "_");
        if (n.isEmpty()) n = "config";
        return n + ".json";
    }

    private static JSONObject profileToJson(Profile p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", p.name);
        o.put("protocol", p.protocol != null ? p.protocol.id : "ssh");
        o.put("host", p.host);
        o.put("port", p.port);
        o.put("user", p.user);
        o.put("pass", p.pass);
        o.put("httpProxy", p.httpProxy != null ? p.httpProxy : "");
        o.put("payload", p.payload != null ? p.payload : "");
        o.put("sni", p.sni != null ? p.sni : "");
        o.put("dns1", p.dns1 != null ? p.dns1 : "8.8.8.8");
        o.put("dns2", p.dns2 != null ? p.dns2 : "8.8.4.4");
        return o;
    }
}
