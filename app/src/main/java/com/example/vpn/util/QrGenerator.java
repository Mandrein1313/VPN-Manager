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

    // ============================================================
    // Backward compatibility — เรียก QrPayload
    // ============================================================
    public static String buildPayload(Profile p) {
        return QrPayload.encode(p);
    }

    public static Profile parsePayload(String content) {
        QrPayload.DecodeResult r = QrPayload.decode(content);
        return r.success ? r.getFirst() : null;
    }
}