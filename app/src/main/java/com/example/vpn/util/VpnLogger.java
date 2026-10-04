package com.example.vpn.util;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Logger เก็บ log ล่าสุด 500 บรรทัดไว้ใน memory
 * ใช้แสดงใน Log Viewer UI
 */
public class VpnLogger {

    public interface Listener {
        void onLogAdded(String line);
    }

    private static final int MAX_LINES = 500;
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static final List<String> BUFFER = new ArrayList<>();
    private static Listener listener;

    public static synchronized void setListener(Listener l) {
        listener = l;
    }

    public static synchronized void i(String tag, String msg) {
        Log.i(tag, msg);
        append("I", tag, msg, null);
    }

    public static synchronized void d(String tag, String msg) {
        Log.d(tag, msg);
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

        if (listener != null) {
            listener.onLogAdded(line);
        }
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
    }
}