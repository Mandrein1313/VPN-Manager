package com.example.vpn;

import android.app.Application;
import android.content.Context;

import com.example.vpn.util.CrashHandler;
import com.example.vpn.util.LanguagePrefs;
import com.example.vpn.util.ThemePrefs;

/**
 * Application — ธีม + ภาษา
 * สำคัญ: อย่า LanguagePrefs.wrap() ใน attachBaseContext ของ Application
 * (getApplicationContext ยังไม่พร้อม → NPE ตอนเปิดแอป)
 */
public class VpnApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        // ใช้ base ตรง ๆ — locale จะถูก apply ใน Activity.attachBaseContext
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            CrashHandler.install(this);
        } catch (Exception ignored) {
        }
        try {
            new ThemePrefs(this).apply();
        } catch (Exception ignored) {
        }
        try {
            // ตั้ง default locale ของ process (Activity ยัง wrap อีกชั้น)
            LanguagePrefs.updateResources(this, new LanguagePrefs(this).getLanguage());
        } catch (Exception ignored) {
        }
    }
}
