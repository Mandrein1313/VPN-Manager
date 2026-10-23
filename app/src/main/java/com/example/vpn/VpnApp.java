package com.example.vpn;

import android.app.Application;
import android.content.Context;

import com.example.vpn.util.LanguagePrefs;
import com.example.vpn.util.ThemePrefs;

/**
 * ใช้ธีม + ภาษาตั้งแต่ process เริ่ม — ก่อน Activity ใด ๆ
 */
public class VpnApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LanguagePrefs.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        new ThemePrefs(this).apply();
        // locale ถูก apply แล้วใน attachBaseContext
        LanguagePrefs.updateResources(this, new LanguagePrefs(this).getLanguage());
    }
}
