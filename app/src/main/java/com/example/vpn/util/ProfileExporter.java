package com.example.vpn.util;

import com.example.vpn.model.Profile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ส่งออก Profile เป็น JSON
 */
public class ProfileExporter {

    private static final int FORMAT_VERSION = 1;

    /** Export หลายโปรไฟล์ */
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

            return root.toString(2);   // pretty print

        } catch (Exception e) {
            return "{}";
        }
    }

    /** Export โปรไฟล์เดียว */
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

    private static JSONObject profileToJson(Profile p) throws Exception {
        JSONObject o = new JSONObject();
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
        return o;
    }
}