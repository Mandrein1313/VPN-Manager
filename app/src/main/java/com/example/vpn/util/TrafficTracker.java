package com.example.vpn.util;

import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ เก็บค่าความเร็ว Download/Upload ย้อนหลัง 60 วินาที
 */
public class TrafficTracker {

    private static final int MAX_POINTS = 60;   // 60 วินาที

    private final List<Float> downloadHistory = new ArrayList<>();
    private final List<Float> uploadHistory = new ArrayList<>();

    private long lastRx = -1;
    private long lastTx = -1;
    private long lastTime = 0;

    /** ⭐ เรียกทุก 1 วินาที — return [downloadKBps, uploadKBps] */
    public synchronized float[] update(long rx, long tx) {
        long now = System.currentTimeMillis();

        if (lastRx < 0 || lastTx < 0) {
            // ครั้งแรก — set ค่าเริ่มต้น
            lastRx = rx;
            lastTx = tx;
            lastTime = now;
            return new float[]{0f, 0f};
        }

        long dtMs = now - lastTime;
        if (dtMs <= 0) return new float[]{0f, 0f};

        long dRx = Math.max(0, rx - lastRx);
        long dTx = Math.max(0, tx - lastTx);

        // แปลงเป็น KB/s
        float downKBps = (dRx * 1000f) / dtMs / 1024f;
        float upKBps = (dTx * 1000f) / dtMs / 1024f;

        lastRx = rx;
        lastTx = tx;
        lastTime = now;

        // เพิ่มเข้า history
        downloadHistory.add(downKBps);
        uploadHistory.add(upKBps);

        // จำกัด 60 จุด
        while (downloadHistory.size() > MAX_POINTS) downloadHistory.remove(0);
        while (uploadHistory.size() > MAX_POINTS) uploadHistory.remove(0);

        return new float[]{downKBps, upKBps};
    }

    public synchronized List<Float> getDownloadHistory() {
        return new ArrayList<>(downloadHistory);
    }

    public synchronized List<Float> getUploadHistory() {
        return new ArrayList<>(uploadHistory);
    }

    public synchronized float getMaxDownload() {
        float max = 0;
        for (float f : downloadHistory) if (f > max) max = f;
        return max;
    }

    public synchronized float getMaxUpload() {
        float max = 0;
        for (float f : uploadHistory) if (f > max) max = f;
        return max;
    }

    public synchronized float getAvgDownload() {
        if (downloadHistory.isEmpty()) return 0;
        float sum = 0;
        for (float f : downloadHistory) sum += f;
        return sum / downloadHistory.size();
    }

    public synchronized float getAvgUpload() {
        if (uploadHistory.isEmpty()) return 0;
        float sum = 0;
        for (float f : uploadHistory) sum += f;
        return sum / uploadHistory.size();
    }

    public synchronized void reset() {
        downloadHistory.clear();
        uploadHistory.clear();
        lastRx = -1;
        lastTx = -1;
        lastTime = 0;
    }
}