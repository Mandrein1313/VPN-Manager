package com.example.vpn.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * ธีมทั้งแอป: System / Light / Dark
 * ใช้ทั้ง AppCompatDelegate + setTheme แยก (กันบางเครื่องสลับมืด/สว่างผิด)
 */
public final class ThemePrefs {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    private static final String PREF = "theme_prefs";
    private static final String KEY_MODE = "theme_mode";

    private final SharedPreferences prefs;

    public ThemePrefs(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public int getMode() {
        return prefs.getInt(KEY_MODE, MODE_SYSTEM);
    }

    public void setMode(int mode) {
        if (mode < MODE_SYSTEM || mode > MODE_DARK) mode = MODE_SYSTEM;
        prefs.edit().putInt(KEY_MODE, mode).apply();
        applyMode(mode);
    }

    public void apply() {
        applyMode(getMode());
    }

    public void applySaved() {
        apply();
    }

    /**
     * เรียกใน Activity.onCreate() ก่อน setContentView()
     * บังคับสไตล์ให้ตรงโหมด — แก้เคส DayNight สลับผิดบนบาง OEM
     */
    public void applyToActivity(Activity activity) {
        int mode = getMode();
        switch (mode) {
            case MODE_LIGHT:
                activity.setTheme(com.example.vpn.R.style.Theme_MyApp_Light);
                break;
            case MODE_DARK:
                activity.setTheme(com.example.vpn.R.style.Theme_MyApp_Dark);
                break;
            case MODE_SYSTEM:
            default:
                activity.setTheme(com.example.vpn.R.style.Theme_MyApp);
                break;
        }
    }

    public static void applyMode(int mode) {
        // ถูกต้องตามมาตรฐาน AppCompat:
        // LIGHT = ไม่ใช้ night resources | DARK = ใช้ night resources
        final int nightMode;
        switch (mode) {
            case MODE_LIGHT:
                nightMode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case MODE_DARK:
                nightMode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            case MODE_SYSTEM:
            default:
                nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(nightMode);
    }

    public static String getModeName(int mode) {
        switch (mode) {
            case MODE_LIGHT:
                return "สว่าง (Light)";
            case MODE_DARK:
                return "มืด (Dark)";
            case MODE_SYSTEM:
            default:
                return "ตามระบบ (System)";
        }
    }
}
