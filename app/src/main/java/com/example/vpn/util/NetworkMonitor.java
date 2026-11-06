package com.example.vpn.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;

/**
 * เฝ้า network ขึ้น/ลง → แจ้ง Listener ให้ service ตัดสินใจ reconnect
 */
public class NetworkMonitor {

    public interface Listener {
        void onNetworkAvailable();
        void onNetworkLost();
    }

    private final ConnectivityManager cm;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback callback;
    private boolean started = false;
    private boolean hadNetwork = false;

    public NetworkMonitor(Context ctx, Listener listener) {
        this.cm = (ConnectivityManager)
                ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    public void start() {
        if (started || cm == null || listener == null) return;
        started = true;

        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                boolean first = !hadNetwork;
                hadNetwork = true;
                main.post(() -> {
                    if (listener != null) listener.onNetworkAvailable();
                });
                VpnLogger.i("NetworkMonitor", "onAvailable first=" + first);
            }

            @Override
            public void onLost(Network network) {
                // เช็คว่ายังมี network อื่นไหม
                Network active = cm.getActiveNetwork();
                boolean still = false;
                if (active != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                    still = caps != null
                            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                }
                if (!still) {
                    hadNetwork = false;
                    main.post(() -> {
                        if (listener != null) listener.onNetworkLost();
                    });
                    VpnLogger.w("NetworkMonitor", "onLost — no internet network");
                }
            }
        };

        try {
            cm.registerNetworkCallback(req, callback);
            // สถานะเริ่มต้น
            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                hadNetwork = caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
        } catch (Exception e) {
            VpnLogger.e("NetworkMonitor", "register failed: " + e.getMessage());
            started = false;
        }
    }

    public void stop() {
        if (!started || cm == null || callback == null) return;
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (Exception ignored) {}
        callback = null;
        started = false;
    }
}
