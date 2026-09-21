package com.example.vpn.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * ⭐ เก็บรายชื่อ package ที่ไม่ต้องผ่าน VPN
 */
public class BypassPrefs {

    private static final String PREF_NAME = "bypass_prefs";
    private static final String KEY_PACKAGES = "bypass_packages";

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
        Set<String> set = getPackages();
        set.add(pkg);
        setPackages(set);
    }

    public void removePackage(String pkg) {
        Set<String> set = getPackages();
        set.remove(pkg);
        setPackages(set);
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
}