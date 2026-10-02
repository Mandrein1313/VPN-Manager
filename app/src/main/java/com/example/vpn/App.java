package com.example.vpn;

import android.app.Application;

import com.example.vpn.util.CrashHandler;
import com.example.vpn.util.ThemePrefs;

/**
 * Application class — apply theme ก่อน Activity ใด ๆ
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // ติดตั้ง Crash Handler
        CrashHandler.install(this);

        // Apply theme ที่บันทึกไว้
        ThemePrefs prefs = new ThemePrefs(this);
        prefs.applySaved();
    }
}
