package com.example.vpn.util;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remote kill / min-version สำหรับ APK แจกก่อนขึ้น Play Store
 *
 * ไฟล์ JSON บนเซิร์ฟเวอร์ (แก้ได้ทันทีโดยไม่ต้องออก APK ใหม่):
 * {
 *   "enabled": true,
 *   "minVersion": 1,
 *   "message": "ปิดชั่วคราว — รอเวอร์ชันบน Play Store"
 * }
 *
 * enabled=false          → ปิดแอป
 * versionCode < minVersion → ปิดแอป
 * เน็ตพัง / ไฟล์ยังไม่มี → ใช้ต่อได้ (fail-open)
 */
public final class RemoteGate {

    /**
     * เปลี่ยนเป็น URL ของคุณ (GitHub raw แนะนำ)
     * หลัง push ไฟล์ remote-status.json บน repo
     */
    public static final String STATUS_URL =
            "https://raw.githubusercontent.com/Mandrein1313/VPN-Manager/master/remote-status.json";

    private static final int TIMEOUT_MS = 8000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onAllowed();
    }

    private RemoteGate() {}

    /** เรียกจาก MainActivity.onCreate หลัง setContentView */
    public static void check(@NonNull Activity activity) {
        check(activity, null);
    }

    public static void check(@NonNull Activity activity, @Nullable Callback onAllowed) {
        IO.execute(() -> {
            Result result = fetch(activity);
            MAIN.post(() -> {
                if (activity.isFinishing()) return;

                if (result.allowed) {
                    if (onAllowed != null) onAllowed.onAllowed();
                    return;
                }

                String msg = (result.message != null && !result.message.isEmpty())
                        ? result.message
                        : "แอปนี้ถูกปิดการใช้งานชั่วคราว";

                try {
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle("ไม่สามารถใช้งานได้")
                            .setMessage(msg)
                            .setCancelable(false)
                            .setPositiveButton("ปิด", (d, w) -> activity.finishAffinity())
                            .show();
                } catch (Exception e) {
                    activity.finishAffinity();
                }
            });
        });
    }

    private static Result fetch(Activity activity) {
        Result r = new Result();
        r.allowed = true; // fail-open เมื่อเน็ตพัง

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(STATUS_URL).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setInstanceFollowRedirects(true);

            if (conn.getResponseCode() != 200) return r;

            String body = readAll(conn.getInputStream());
            if (body == null || body.trim().isEmpty()) return r;

            JSONObject json = new JSONObject(body.trim());
            boolean enabled = json.optBoolean("enabled", true);
            int minVersion = json.optInt("minVersion", 0);
            r.message = json.optString("message", "");

            if (!enabled) {
                r.allowed = false;
                if (r.message.isEmpty()) {
                    r.message = "ผู้พัฒนาได้ปิดการใช้งานแอปนี้แล้ว";
                }
                return r;
            }

            int versionCode = readVersionCode(activity);
            if (minVersion > 0 && versionCode > 0 && versionCode < minVersion) {
                r.allowed = false;
                if (r.message.isEmpty()) {
                    r.message = "เวอร์ชันนี้หมดอายุแล้ว กรุณาอัปเดต";
                }
            }
        } catch (Exception ignored) {
            r.allowed = true;
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    private static int readVersionCode(Activity activity) {
        try {
            PackageInfo pi = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) pi.getLongVersionCode();
            }
            //noinspection deprecation
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static final class Result {
        boolean allowed;
        String message = "";
    }
}
