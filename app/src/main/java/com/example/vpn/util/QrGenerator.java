package com.example.vpn.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.example.vpn.model.Profile;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.HashMap;
import java.util.Map;

public class QrGenerator {

    private static final int DEFAULT_SIZE = 800;

    public static Bitmap generate(String content) {
        return generate(content, DEFAULT_SIZE);
    }

    public static Bitmap generate(String content, int size) {
        if (content == null || content.isEmpty()) return null;

        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION,
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);

            BitMatrix matrix = new MultiFormatWriter().encode(
                    content, BarcodeFormat.QR_CODE, size, size, hints);

            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y)
                            ? Color.BLACK : Color.WHITE;
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;

        } catch (Exception e) {
            VpnLogger.e("QrGenerator", "generate error: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * สร้าง payload จาก Profile
     * Format: vpnmanager://profile?data=base64(json)
     */
    public static String buildPayload(Profile p) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("v", 1);
            o.put("name", p.name);
            o.put("protocol", p.protocol.id);
            o.put("host", p.host);
            o.put("port", p.port);
            o.put("user", p.user != null ? p.user : "");
            o.put("pass", p.pass != null ? p.pass : "");
            o.put("httpProxy", p.httpProxy != null ? p.httpProxy : "");
            o.put("payload", p.payload != null ? p.payload : "");
            o.put("sni", p.sni != null ? p.sni : "");
            o.put("dns1", p.dns1 != null ? p.dns1 : "8.8.8.8");
            o.put("dns2", p.dns2 != null ? p.dns2 : "8.8.4.4");

            // ⭐ V2Ray fields
            o.put("v2rayType", p.v2rayType != null ? p.v2rayType : "vless");
            o.put("v2rayUuid", p.v2rayUuid != null ? p.v2rayUuid : "");
            o.put("v2rayNetwork", p.v2rayNetwork != null ? p.v2rayNetwork : "tcp");
            o.put("v2rayPath", p.v2rayPath != null ? p.v2rayPath : "/");
            o.put("v2rayHost", p.v2rayHost != null ? p.v2rayHost : "");
            o.put("v2rayServiceName", p.v2rayServiceName != null ? p.v2rayServiceName : "");
            o.put("v2rayTls", p.v2rayTls);
            o.put("v2rayFlow", p.v2rayFlow != null ? p.v2rayFlow : "");
            o.put("v2rayMethod", p.v2rayMethod != null ? p.v2rayMethod : "aes-256-gcm");

            String json = o.toString();
            String base64 = android.util.Base64.encodeToString(
                    json.getBytes("UTF-8"),
                    android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);

            return "vpnmanager://profile?data=" + base64;

        } catch (Exception e) {
            VpnLogger.e("QrGenerator", "buildPayload error: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parse payload → Profile
     */
    public static Profile parsePayload(String content) {
        if (content == null) return null;

        try {
            String prefix = "vpnmanager://profile?data=";
            if (!content.startsWith(prefix)) return null;

            String base64 = content.substring(prefix.length());
            byte[] decoded = android.util.Base64.decode(
                    base64,
                    android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);

            String json = new String(decoded, "UTF-8");
            org.json.JSONObject o = new org.json.JSONObject(json);

            Profile p = new Profile();
            p.name = o.optString("name", "");
            p.protocol = com.example.vpn.model.Protocol.fromId(
                    o.optString("protocol", "ssh"));
            p.host = o.optString("host", "");
            p.port = o.optInt("port", 22);
            p.user = o.optString("user", "");
            p.pass = o.optString("pass", "");
            p.httpProxy = o.optString("httpProxy", "");
            p.payload = o.optString("payload", "");
            p.sni = o.optString("sni", "");
            p.dns1 = o.optString("dns1", "8.8.8.8");
            p.dns2 = o.optString("dns2", "8.8.4.4");

            // ⭐ V2Ray fields
            p.v2rayType = o.optString("v2rayType", "vless");
            p.v2rayUuid = o.optString("v2rayUuid", "");
            p.v2rayNetwork = o.optString("v2rayNetwork", "tcp");
            p.v2rayPath = o.optString("v2rayPath", "/");
            p.v2rayHost = o.optString("v2rayHost", "");
            p.v2rayServiceName = o.optString("v2rayServiceName", "");
            p.v2rayTls = o.optBoolean("v2rayTls", false);
            p.v2rayFlow = o.optString("v2rayFlow", "");
            p.v2rayMethod = o.optString("v2rayMethod", "aes-256-gcm");

            if (p.host.isEmpty()) return null;
            return p;

        } catch (Exception e) {
            VpnLogger.e("QrGenerator", "parsePayload error: " + e.getMessage(), e);
            return null;
        }
    }
}