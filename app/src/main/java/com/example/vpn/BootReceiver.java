package com.example.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

import com.example.vpn.util.VpnLogger;
import com.example.vpn.util.VpnPrefs;

/**
 * ⭐ Auto-connect on Boot
 * รับ BOOT_COMPLETED → เปิด VPN อัตโนมัติ
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        String action = intent.getAction();
        VpnLogger.i(TAG, "BootReceiver received: " + action);

        // ⭐ ตรวจสอบ action
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        try {
            VpnPrefs prefs = new VpnPrefs(context);

            // ⭐ เช็คว่าผู้ใช้เปิด auto-connect ไว้ไหม
            if (!prefs.isAutoConnectBoot()) {
                VpnLogger.i(TAG, "Auto-connect on boot is OFF");
                return;
            }

            // ⭐ เช็ค Profile ล่าสุด
            long profileId = prefs.getLastProfileId();
            if (profileId <= 0) {
                VpnLogger.w(TAG, "No last profile — skip auto-connect");
                return;
            }

            // ⭐ เช็ค VPN permission
            Intent prepare = VpnService.prepare(context);
            if (prepare != null) {
                VpnLogger.w(TAG, "VPN permission not granted — skip auto-connect");
                return;
            }

            // ⭐ Delay สักหน่อย — ให้ระบบ boot เสร็จก่อน
            Thread delayThread = new Thread(() -> {
                try {
                    // รอ 10 วินาที ให้ระบบพร้อม
                    Thread.sleep(10_000);
                } catch (InterruptedException ignored) {
                    return;
                }

                // ⭐ เช็คอีกครั้ง — maybe user turned off
                VpnPrefs p2 = new VpnPrefs(context);
                if (!p2.isAutoConnectBoot()) {
                    VpnLogger.i(TAG, "Auto-connect turned off — cancel");
                    return;
                }

                // ⭐ เริ่ม VPN
                startVpnService(context, profileId);
            }, "BootVpnStarter");
            delayThread.setDaemon(true);
            delayThread.start();

        } catch (Throwable e) {
            VpnLogger.e(TAG, "BootReceiver error: " + e.getMessage(), e);
        }
    }

    private void startVpnService(Context context, long profileId) {
        try {
            Intent svc = new Intent(context, ProxyVpnService.class);
            svc.setAction(ProxyVpnService.ACTION_START);
            svc.putExtra(ProxyVpnService.EXTRA_PROFILE_ID, profileId);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }

            VpnLogger.i(TAG, "✅ VPN auto-started after boot (profile=" + profileId + ")");

        } catch (Exception e) {
            VpnLogger.e(TAG, "startVpnService failed: " + e.getMessage(), e);
        }
    }
}