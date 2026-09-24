package com.example.vpn.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * ⭐ จัดการธีม Dark/Light
 */
public class ThemePrefs {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    private static final String PREF_NAME = "theme_prefs";
    private static final String KEY_MODE = "theme_mode";

    private final SharedPreferences prefs;

    public ThemePrefs(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public int getMode() {
        return prefs.getInt(KEY_MODE, MODE_SYSTEM);
    }

    public void setMode(int mode) {
        prefs.edit().putInt(KEY_MODE, mode).apply();
        applyMode(mode);
    }

    /** ⭐ apply mode ทันที — ใช้ได้ทุกที่ */
    public static void applyMode(int mode) {
        switch (mode) {
            case MODE_LIGHT:
                AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case MODE_DARK:
                AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case MODE_SYSTEM:
            default:
                AppCompatDelegate.setDefaultNightMode(
                        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    /** ⭐ โหลด + apply ตอนเปิดแอป */
    public void applySaved() {
        applyMode(getMode());
    }

    public static String getModeName(int mode) {
        switch (mode) {
            case MODE_LIGHT: return "สว่าง";
            case MODE_DARK: return "มืด";
            default: return "ตามระบบ";
        }
    }
}