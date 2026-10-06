package com.example.vpn.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * เก็บรายชื่อ package ที่ไม่ต้องผ่าน VPN (Bypass)
 */
public class BypassPrefs {

    private static final String PREF_NAME = "bypass_prefs";
    private static final String KEY_PACKAGES = "bypass_packages";
    private static final String KEY_HIDE_SYSTEM = "hide_system_apps";

    /**
     * รายการแอป AI / ที่มักโดนบล็อกผ่าน VPN
     */
    public static final List<String> AI_PACKAGES = Collections.unmodifiableList(Arrays.asList(
            // ไทย / ท้องถิ่น
            "th.co.humanintelligence.aipass",
            // OpenAI
            "com.openai.chatgpt",
            // Anthropic
            "com.anthropic.claude",
            // Google
            "com.google.android.apps.gemini",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.bard",
            // Microsoft
            "com.microsoft.copilot",
            "com.microsoft.bing",
            "com.microsoft.emmx",
            // อื่น ๆ
            "ai.perplexity.app.android",
            "ai.character.app",
            "com.poe.android",
            "ai.x.grok",
            "com.x.android",
            "com.deepseek.chat",
            "com.inflection.pi",
            "com.you.app",
            "notion.id",
            "com.chatbot.android",
            "com.aichat.bot",
            "com.midjourney.app"
    ));

    private final SharedPreferences prefs;

    public BypassPrefs(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public Set<String> getPackages() {
        Set<String> empty = new HashSet<>();
        Set<String> result = prefs.getStringSet(KEY_PACKAGES, empty);
        return result != null ? new HashSet<>(result) : empty;
    }

    public void setPackages(Set<String> packages) {
        prefs.edit().putStringSet(KEY_PACKAGES, new HashSet<>(packages)).apply();
    }

    public boolean isBypassed(String pkg) {
        return getPackages().contains(pkg);
    }

    public void addPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> set = getPackages();
        if (set.add(pkg)) setPackages(set);
    }

    public void removePackage(String pkg) {
        Set<String> set = getPackages();
        if (set.remove(pkg)) setPackages(set);
    }

    public void togglePackage(String pkg, boolean bypass) {
        if (bypass) addPackage(pkg);
        else removePackage(pkg);
    }

    public void clear() {
        prefs.edit().remove(KEY_PACKAGES).apply();
    }

    public int getCount() {
        return getPackages().size();
    }

    public boolean isHideSystemApps() {
        return prefs.getBoolean(KEY_HIDE_SYSTEM, true); // ค่าเริ่มต้น: ซ่อนแอประบบ
    }

    public void setHideSystemApps(boolean hide) {
        prefs.edit().putBoolean(KEY_HIDE_SYSTEM, hide).apply();
    }

    /** เพิ่มแอป AI ที่ติดตั้งอยู่ */
    public List<String> addInstalledAiApps(PackageManager pm) {
        Set<String> set = getPackages();
        List<String> added = new ArrayList<>();
        for (String pkg : AI_PACKAGES) {
            if (isPackageInstalled(pm, pkg) && set.add(pkg)) {
                added.add(pkg);
            }
        }
        if (!added.isEmpty()) setPackages(set);
        return added;
    }

    /** ลบเฉพาะแอปในลิสต์ AI */
    public int removeAiApps() {
        Set<String> set = getPackages();
        int before = set.size();
        set.removeAll(new HashSet<>(AI_PACKAGES));
        int removed = before - set.size();
        if (removed > 0) setPackages(set);
        return removed;
    }

    public List<String> getInstalledAiPackages(PackageManager pm) {
        List<String> found = new ArrayList<>();
        for (String pkg : AI_PACKAGES) {
            if (isPackageInstalled(pm, pkg)) found.add(pkg);
        }
        return found;
    }

    private static boolean isPackageInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
