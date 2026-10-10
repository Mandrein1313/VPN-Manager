package com.example.vpn.model;

import android.graphics.drawable.Drawable;

public class AppInfo {
    public String packageName;
    public String appName;
    public Drawable icon;
    public boolean isSystemApp;
    public boolean bypassed;

    public AppInfo(String packageName, String appName, Drawable icon,
                   boolean isSystemApp, boolean bypassed) {
        this.packageName = packageName;
        this.appName = appName;
        this.icon = icon;
        this.isSystemApp = isSystemApp;
        this.bypassed = bypassed;
    }
}