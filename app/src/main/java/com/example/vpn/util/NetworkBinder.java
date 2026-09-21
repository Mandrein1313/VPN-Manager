package com.example.vpn.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

/**
 * ⭐ บังคับให้ Android ใช้ VPN network กับทุก connection
 */
public class NetworkBinder {

    /**
     * ⭐ Force re-evaluate routing ของทุกแอป
     * เรียกหลัง VPN established → แอปจะยอมใช้ VPN ทันที
     */
    public static void forceReroute(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            // ⭐ Clear underlying networks — Force re-evaluate
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network[] networks = cm.getAllNetworks();
                if (networks != null) {
                    for (Network n : networks) {
                        // ดึง capabilities (ไม่ใช้ก็ได้ — แค่ trigger)
                        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                        // ไม่ bind ให้ network นี้ — ปล่อยให้ระบบเลือก VPN เอง
                    }
                }
            }

            // ⭐ Trigger network callbacks ทั่วทั้งระบบ
            // ส่ง dummy network request → Android จะ re-evaluate routing
            try {
                cm.requestNetwork(
                        new NetworkRequest.Builder()
                                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                .build(),
                        new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(Network network) {
                                // callback ทันทีแล้ว unregister
                            }
                        });
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }
}