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

/**
 * ⭐ สร้าง QR Code จาก Profile
 */
public class QrGenerator {

    private static final int DEFAULT_SIZE = 800;

    /**
     * ⭐ สร้าง QR Code จาก string
     */
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
     * ⭐ สร้าง QR payload จาก Profile
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
            o.put("user", p.user);
            o.put("pass", p.pass);
            o.put("httpProxy", p.httpProxy);
            o.put("payload", p.payload);
            o.put("sni", p.sni);
            o.put("dns1", p.dns1);
            o.put("dns2", p.dns2);

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
     * ⭐ Parse QR payload → Profile
     */
    public static Profile parsePayload(String content) {
        if (content == null) return null;

        try {
            // ตัด prefix
            String prefix = "vpnmanager://profile?data=";
            if (!content.startsWith(prefix)) return null;

            String base64 = content.substring(prefix.length());

            // Decode base64
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

            if (p.host.isEmpty()) return null;
            return p;

        } catch (Exception e) {
            VpnLogger.e("QrGenerator", "parsePayload error: " + e.getMessage(), e);
            return null;
        }
    }
}