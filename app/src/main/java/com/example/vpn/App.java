package com.example.vpn;

import android.app.Application;

import com.example.vpn.util.CrashHandler;
import com.example.vpn.util.ThemePrefs;

/**
 * ⭐ Application class — apply theme ก่อนทุกอย่าง
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // ⭐ ติดตั้ง Crash Handler
        CrashHandler.install(this);

        // ⭐ Apply theme ที่บันทึกไว้ — ก่อน UI ทุกอย่าง
        ThemePrefs prefs = new ThemePrefs(this);
        prefs.applySaved();
    }
}