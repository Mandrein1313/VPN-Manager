package com.example.vpn.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.example.vpn.ProxyVpnService;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remote kill — บล็อกทุกหน้า + บล็อกเริ่ม VPN
 *
 * JSON บน GitHub:
 * {
 *   "enabled": true,
 *   "minVersion": 1,
 *   "message": "..."
 * }
 *
 * enabled=false → บล็อกทั้งแอป (จำในเครื่อง)
 * enabled=true  → เคลียร์บล็อก ใช้ได้ปกติ
 * เน็ตพัง → ใช้ค่า cache ล่าสุด
 */
public final class RemoteGate {

    public static final String STATUS_URL =
            "https://raw.githubusercontent.com/Mandrein1313/VPN-Manager/master/remote-status.json";

    private static final String PREF = "remote_gate";
    private static final String KEY_BLOCKED = "blocked";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_MIN_VERSION = "min_version";

    private static final int TIMEOUT_MS = 8000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean dialogShowing = new AtomicBoolean(false);

    /** กัน fetch ถี่เกินตอน onResume */
    private static volatile long lastFetchAt = 0L;
    private static final long FETCH_COOLDOWN_MS = 15_000L;

    public interface Callback {
        void onAllowed();
    }

    private RemoteGate() {}

    public static boolean isBlocked(@NonNull Context ctx) {
        SharedPreferences sp = prefs(ctx);
        if (sp.getBoolean(KEY_BLOCKED, false)) return true;
        int min = sp.getInt(KEY_MIN_VERSION, 0);
        if (min > 0) {
            int code = readVersionCode(ctx);
            if (code > 0 && code < min) return true;
        }
        return false;
    }

    public static String cachedMessage(@NonNull Context ctx) {
        String m = prefs(ctx).getString(KEY_MESSAGE, null);
        if (m == null || m.isEmpty()) {
            return "เวอร์ชันนี้ถูกปิดแล้ว กรุณาโหลดจาก Play Store";
        }
        return m;
    }

    /** บังคับหยุด VPN + dialog */
    public static boolean enforceBlock(@NonNull Context ctx) {
        if (!isBlocked(ctx)) return false;
        stopVpnQuietly(ctx);
        if (ctx instanceof Activity) {
            Activity a = (Activity) ctx;
            MAIN.post(() -> showBlockDialog(a, cachedMessage(ctx)));
        }
        return true;
    }

    /**
     * เรียกจาก onCreate / onResume ของทุกหน้าหลัก
     * — ถ้า cache บล็อก: โชว์ dialog + หยุด VPN ทันที
     * — แล้วยังดึงเซิร์ฟใหม่เสมอ (เพื่อเคลียร์เมื่อ enabled:true)
     */
    public static void check(@NonNull Activity activity) {
        check(activity, null);
    }

    public static void check(@NonNull Activity activity, @Nullable Callback onAllowed) {
        // cache บล็อก → UI ทันที
        if (isBlocked(activity)) {
            stopVpnQuietly(activity);
            showBlockDialog(activity, cachedMessage(activity));
        }

        long now = System.currentTimeMillis();
        if (now - lastFetchAt < FETCH_COOLDOWN_MS && !isBlocked(activity)) {
            if (onAllowed != null) onAllowed.onAllowed();
            return;
        }
        lastFetchAt = now;

        IO.execute(() -> {
            Result result = fetch(activity);
            MAIN.post(() -> {
                if (activity.isFinishing()) return;

                if (!result.allowed) {
                    saveBlocked(activity, true, result.message, result.minVersion);
                    stopVpnQuietly(activity);
                    showBlockDialog(activity, result.message);
                    return;
                }

                // เซิร์ฟเปิดใช้ → เคลียร์ cache (สำคัญ: เคยบล็อกไว้ต้องปลด)
                saveBlocked(activity, false, result.message, result.minVersion);
                dialogShowing.set(false);
                if (onAllowed != null) onAllowed.onAllowed();
            });
        });
    }

    /** ก่อน start VPN */
    public static boolean allowVpnStart(@NonNull Context ctx) {
        if (isBlocked(ctx)) return false;
        try {
            Result r = fetch(ctx);
            if (!r.allowed) {
                saveBlocked(ctx, true, r.message, r.minVersion);
                return false;
            }
            saveBlocked(ctx, false, r.message, r.minVersion);
            return true;
        } catch (Exception e) {
            return !isBlocked(ctx);
        }
    }

    private static void showBlockDialog(Activity activity, String message) {
        if (activity.isFinishing()) return;
        if (!dialogShowing.compareAndSet(false, true)) return;

        String msg = (message != null && !message.isEmpty())
                ? message
                : cachedMessage(activity);

        try {
            AlertDialog d = new MaterialAlertDialogBuilder(activity)
                    .setTitle("ไม่สามารถใช้งานได้")
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton("ปิด", (dialog, w) -> {
                        dialogShowing.set(false);
                        try {
                            activity.finishAffinity();
                        } catch (Exception e) {
                            activity.finish();
                        }
                    })
                    .create();
            d.setOnDismissListener(di -> dialogShowing.set(false));
            d.show();
        } catch (Exception e) {
            dialogShowing.set(false);
            try {
                activity.finishAffinity();
            } catch (Exception ignored) {}
        }
    }

    private static void stopVpnQuietly(Context ctx) {
        try {
            Intent svc = new Intent(ctx, ProxyVpnService.class);
            svc.setAction(ProxyVpnService.ACTION_STOP);
            ctx.startService(svc);
        } catch (Exception ignored) {}
    }

    private static Result fetch(Context ctx) {
        Result r = new Result();
        r.allowed = true;
        r.minVersion = 0;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(STATUS_URL).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);

            if (conn.getResponseCode() != 200) {
                r.allowed = !prefs(ctx).getBoolean(KEY_BLOCKED, false);
                r.message = cachedMessage(ctx);
                return r;
            }

            String body = readAll(conn.getInputStream());
            if (body == null || body.trim().isEmpty()) {
                r.allowed = !prefs(ctx).getBoolean(KEY_BLOCKED, false);
                return r;
            }

            JSONObject json = new JSONObject(body.trim());
            boolean enabled = json.optBoolean("enabled", true);
            int minVersion = json.optInt("minVersion", 0);
            r.message = json.optString("message", "");
            r.minVersion = minVersion;

            if (!enabled) {
                r.allowed = false;
                if (r.message.isEmpty()) {
                    r.message = "ผู้พัฒนาได้ปิดการใช้งานแอปนี้แล้ว";
                }
                return r;
            }

            int versionCode = readVersionCode(ctx);
            if (minVersion > 0 && versionCode > 0 && versionCode < minVersion) {
                r.allowed = false;
                if (r.message.isEmpty()) {
                    r.message = "เวอร์ชันนี้หมดอายุแล้ว กรุณาอัปเดต";
                }
                return r;
            }

            r.allowed = true;
            return r;
        } catch (Exception e) {
            r.allowed = !prefs(ctx).getBoolean(KEY_BLOCKED, false);
            return r;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void saveBlocked(Context ctx, boolean blocked, String message, int minVersion) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        ed.putBoolean(KEY_BLOCKED, blocked);
        if (message != null) ed.putString(KEY_MESSAGE, message);
        ed.putInt(KEY_MIN_VERSION, blocked ? Math.max(0, minVersion) : 0);
        ed.apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static int readVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
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

    /** ล้าง cache บล็อก (สำหรับ debug) */
    public static void clearBlockForDebug(@NonNull Context ctx) {
        saveBlocked(ctx, false, "", 0);
        dialogShowing.set(false);
        lastFetchAt = 0L;
    }

    private static final class Result {
        boolean allowed;
        String message = "";
        int minVersion;
    }
}
