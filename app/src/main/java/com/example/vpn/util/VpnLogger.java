package com.example.vpn.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Logger ใน memory — จำกัดบรรทัด + รวมอัปเดต UI กันหน้า LOG ค้าง
 */
public class VpnLogger {

    public interface Listener {
        void onLogAdded(String line);
    }

    /** เก็บใน memory ไม่เกินนี้ (เดิม 500 ทำให้ UI ฝืด) */
    private static final int MAX_LINES = 150;
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static final List<String> BUFFER = new ArrayList<>();
    private static Listener listener;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long UI_BATCH_MS = 400;
    private static boolean uiFlushScheduled = false;
    private static String lastBatchedLine = null;

    public static synchronized void setListener(Listener l) {
        listener = l;
    }

    public static synchronized void i(String tag, String msg) {
        Log.i(tag, msg);
        append("I", tag, msg, null);
    }

    public static synchronized void d(String tag, String msg) {
        Log.d(tag, msg);
        // Heartbeat OK ถี่มาก — ใส่ logcat อย่างเดียว ไม่ยัด UI
        if (msg != null && msg.startsWith("Heartbeat OK")) {
            return;
        }
        append("D", tag, msg, null);
    }

    public static synchronized void w(String tag, String msg) {
        Log.w(tag, msg);
        append("W", tag, msg, null);
    }

    public static synchronized void e(String tag, String msg) {
        Log.e(tag, msg);
        append("E", tag, msg, null);
    }

    public static synchronized void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        append("E", tag, msg, t);
    }

    private static void append(String level, String tag, String msg, Throwable t) {
        // กรอง heartbeat สำเร็จออกจาก buffer UI
        if (msg != null && msg.contains("Heartbeat OK")) {
            return;
        }

        String line = TIME_FMT.format(new Date())
                + " " + level + "/" + tag + ": " + msg;
        if (t != null) {
            line += " — " + t.getClass().getSimpleName()
                    + ": " + t.getMessage();
        }

        BUFFER.add(line);
        while (BUFFER.size() > MAX_LINES) {
            BUFFER.remove(0);
        }

        lastBatchedLine = line;
        scheduleUiNotify();
    }

    private static void scheduleUiNotify() {
        if (uiFlushScheduled) return;
        uiFlushScheduled = true;
        MAIN.postDelayed(() -> {
            synchronized (VpnLogger.class) {
                uiFlushScheduled = false;
                Listener l = listener;
                String line = lastBatchedLine;
                if (l != null && line != null) {
                    try {
                        l.onLogAdded(line);
                    } catch (Exception ignored) {}
                }
            }
        }, UI_BATCH_MS);
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(BUFFER);
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : BUFFER) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        BUFFER.clear();
        lastBatchedLine = null;
    }
}
