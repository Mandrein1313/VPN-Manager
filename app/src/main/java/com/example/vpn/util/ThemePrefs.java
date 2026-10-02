package com.example.vpn.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * ธีมทั้งแอป: System / Light / Dark
 * มืด → values-night (ดำทั้งจอ) | สว่าง → values (ขาวทั้งจอ)
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

    /** บันทึก + ใช้ทันทีทั้ง process */
    public void setMode(int mode) {
        if (mode < MODE_SYSTEM || mode > MODE_DARK) mode = MODE_SYSTEM;
        prefs.edit().putInt(KEY_MODE, mode).apply();
        applyMode(mode);
    }

    /** เรียกใน Application.onCreate() ก่อน Activity ใด ๆ */
    public void apply() {
        applyMode(getMode());
    }

    /** alias ของ apply() — ใช้ใน App.java */
    public void applySaved() {
        apply();
    }

    public static void applyMode(int mode) {
        int nightMode;
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
