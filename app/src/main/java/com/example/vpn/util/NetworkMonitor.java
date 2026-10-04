package com.example.vpn.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

/**
 * ⭐ ตรวจสอบการเปลี่ยนแปลงของ network (Wi-Fi ↔ Mobile)
 * ใช้ NetworkCallback เพื่อ auto-reconnect VPN
 */
public class NetworkMonitor {

    public interface Listener {
        /** เรียกเมื่อ network กลับมาใช้งานได้ (มี connection ใหม่) */
        void onNetworkAvailable();
        /** เรียกเมื่อ network หายไป */
        void onNetworkLost();
    }

    private final Context appContext;
    private final Listener listener;
    private ConnectivityManager.NetworkCallback callback;
    private boolean registered = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ⭐ หน่วงเวลา debounce — ป้องกัน callback ซ้ำซ้อน
    private static final long DEBOUNCE_MS = 1500;
    private Runnable pendingConnect;
    private Runnable pendingLost;

    public NetworkMonitor(Context ctx, Listener listener) {
        this.appContext = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (registered) return;

        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    super.onAvailable(network);
                    scheduleAvailable();
                }

                @Override
                public void onLost(@NonNull Network network) {
                    super.onLost(network);
                    scheduleLost();
                }
            };

            cm.registerNetworkCallback(request, callback);
            registered = true;

        } catch (Exception ignored) {}
    }

    public void stop() {
        if (!registered) return;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && callback != null) {
                cm.unregisterNetworkCallback(callback);
            }
        } catch (Exception ignored) {}
        callback = null;
        registered = false;

        // ยกเลิก debounce ที่ค้างอยู่
        if (pendingConnect != null) handler.removeCallbacks(pendingConnect);
        if (pendingLost != null) handler.removeCallbacks(pendingLost);
    }

    // ============================================================
    // Debounce
    // ============================================================

    private void scheduleAvailable() {
        if (pendingConnect != null) handler.removeCallbacks(pendingConnect);
        pendingConnect = () -> {
            try { listener.onNetworkAvailable(); } catch (Exception ignored) {}
        };
        handler.postDelayed(pendingConnect, DEBOUNCE_MS);
    }

    private void scheduleLost() {
        if (pendingLost != null) handler.removeCallbacks(pendingLost);
        pendingLost = () -> {
            try { listener.onNetworkLost(); } catch (Exception ignored) {}
        };
        handler.postDelayed(pendingLost, DEBOUNCE_MS);
    }
}