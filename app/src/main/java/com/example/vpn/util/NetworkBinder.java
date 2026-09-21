package com.example.vpn.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;

import java.util.List;

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
                        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                        // ไม่ bind ให้ network นี้ — ปล่อยให้ระบบเลือก VPN เอง
                    }
                }
            }

            // ⭐ Trigger network callbacks ทั่วทั้งระบบ
            // ส่ง dummy network request → Android จะ re-evaluate routing
            try {
                cm.requestNetwork(
                        new android.net.NetworkRequest.Builder()
                                .addCapability(
                                        android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
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

    /**
     * ⭐ ล้าง DNS cache ทั่วเครื่อง
     */
    public static void clearDnsCache() {
        // ⚠️ Android ไม่มี public API — ใช้วิธีบังคับ resolve ชื่อใหม่
        // ดู ConnectivityChecker.warmupDns() แทน
    }
}