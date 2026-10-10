package com.example.vpn;

import android.app.Application;

import com.example.vpn.util.ThemePrefs;

/**
 * ใช้ธีมตั้งแต่ process เริ่ม — ก่อน Activity ใด ๆ
 */
public class VpnApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        new ThemePrefs(this).apply();
    }
}
