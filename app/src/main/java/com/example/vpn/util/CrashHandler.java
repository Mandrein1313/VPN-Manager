package com.example.vpn.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * ตัวดักจับ UncaughtException ทั่วทั้งแอป
 * - บันทึก stack trace ลง file
 * - เก็บไว้ 20 ไฟล์ล่าสุด
 * - เก็บ device info ประกอบ
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String DIR_NAME = "crashes";
    private static final int MAX_FILES = 20;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private final Context appContext;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashHandler(Context ctx, Thread.UncaughtExceptionHandler def) {
        this.appContext = ctx.getApplicationContext();
        this.defaultHandler = def;
    }

    public static void install(Context ctx) {
        Thread.UncaughtExceptionHandler current =
                Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(ctx, current));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            saveCrash(thread, ex);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to save crash", t);
        }

        // ส่งต่อไป handler เก่า (ทำให้แอป crash ตามปกติ)
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, ex);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        }
    }

    private void saveCrash(Thread thread, Throwable ex) {
        File dir = new File(appContext.getFilesDir(), DIR_NAME);
        if (!dir.exists()) dir.mkdirs();

        cleanupOldFiles(dir);

        String timestamp = FMT.format(new Date());
        File file = new File(dir, "crash-" + timestamp + ".txt");

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        pw.println("===== CRASH REPORT =====");
        pw.println("Time: " + new Date().toString());
        pw.println("Thread: " + thread.getName());
        pw.println();
        pw.println("===== DEVICE INFO =====");
        pw.println("Android: " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("Manufacturer: " + Build.MANUFACTURER);
        pw.println("Model: " + Build.MODEL);
        pw.println("Device: " + Build.DEVICE);
        pw.println("Product: " + Build.PRODUCT);
        pw.println();
        pw.println("===== STACK TRACE =====");
        ex.printStackTrace(pw);
        pw.println();
        pw.println("===== CAUSE =====");

        Throwable cause = ex.getCause();
        int depth = 0;
        while (cause != null && depth < 5) {
            pw.println("Cause #" + (depth + 1) + ": " + cause);
            cause.printStackTrace(pw);
            cause = cause.getCause();
            depth++;
        }
        pw.flush();

        try (FileWriter fw = new FileWriter(file)) {
            fw.write(sw.toString());
        } catch (Exception e) {
            Log.e(TAG, "write crash file failed", e);
        }

        // เก็บ logcat ด้วย (สำหรับ native crash / ก่อนตาย)
        saveLogcatSnapshot(dir, timestamp);
    }

    private void saveLogcatSnapshot(File dir, String timestamp) {
        java.lang.Process process = null;
        BufferedReader reader = null;
        try {
            // ⭐ Android ต้องส่ง arguments เป็น array ไม่ใช่ string
            process = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-t", "500", "-v", "threadtime"
            });

            reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines < 500) {
                sb.append(line).append('\n');
                lines++;
            }

            File logFile = new File(dir, "logcat-" + timestamp + ".txt");
            try (FileWriter fw = new FileWriter(logFile)) {
                fw.write(sb.toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "saveLogcatSnapshot failed: " + e.getMessage());
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            if (process != null) process.destroy();
        }
    }

    private void cleanupOldFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length < MAX_FILES) return;

        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });

        for (int i = MAX_FILES; i < files.length; i++) {
            files[i].delete();
        }
    }

    // ============================================================
    // API สำหรับอ่าน crash log
    // ============================================================

    public static File getCrashDir(Context ctx) {
        return new File(ctx.getFilesDir(), DIR_NAME);
    }

    public static File[] listCrashes(Context ctx) {
        File dir = getCrashDir(ctx);
        if (!dir.exists()) return new File[0];
        File[] files = dir.listFiles((d, name) -> name.startsWith("crash-"));
        if (files == null) return new File[0];
        Arrays.sort(files, (a, b) ->
                Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    public static void clearAll(Context ctx) {
        File dir = getCrashDir(ctx);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        }
    }
}