package com.example.vpn.util;

import android.content.Context;
import android.content.SharedPreferences;

public class VpnPrefs {

    private static final String PREF_NAME = "vpn_prefs";
    private static final String KEY_AUTO_RECONNECT = "auto_reconnect";
    private static final String KEY_KILL_SWITCH = "kill_switch";
    private static final String KEY_LAST_PROFILE_ID = "last_profile_id";
    private static final String KEY_WAS_CONNECTED = "was_connected";
    private static final String KEY_AUTO_CONNECT_BOOT = "auto_connect_boot";
    private static final String KEY_BYPASS_DISABLED = "bypass_disabled";
    private static final String KEY_SHARE_WIFI = "share_wifi_proxy";

    private final SharedPreferences prefs;

    public VpnPrefs(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isAutoReconnect() {
        return prefs.getBoolean(KEY_AUTO_RECONNECT, true);
    }

    public void setAutoReconnect(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply();
    }

    public boolean isKillSwitch() {
        return prefs.getBoolean(KEY_KILL_SWITCH, false);
    }

    public void setKillSwitch(boolean value) {
        prefs.edit().putBoolean(KEY_KILL_SWITCH, value).apply();
    }

    public boolean isAutoConnectBoot() {
        return prefs.getBoolean(KEY_AUTO_CONNECT_BOOT, false);
    }

    public void setAutoConnectBoot(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT_BOOT, value).apply();
    }

    public boolean isBypassDisabled() {
        return prefs.getBoolean(KEY_BYPASS_DISABLED, false);
    }

    public void setBypassDisabled(boolean value) {
        prefs.edit().putBoolean(KEY_BYPASS_DISABLED, value).apply();
    }

    /** แชร์ SOCKS5 ไปยังเครื่องอื่นใน Wi‑Fi / Hotspot */
    public boolean isShareWifi() {
        return prefs.getBoolean(KEY_SHARE_WIFI, false);
    }

    public void setShareWifi(boolean value) {
        prefs.edit().putBoolean(KEY_SHARE_WIFI, value).apply();
    }

    public long getLastProfileId() {
        return prefs.getLong(KEY_LAST_PROFILE_ID, -1L);
    }

    public void setLastProfileId(long id) {
        prefs.edit().putLong(KEY_LAST_PROFILE_ID, id).apply();
    }

    public boolean wasConnected() {
        return prefs.getBoolean(KEY_WAS_CONNECTED, false);
    }

    public void setWasConnected(boolean value) {
        prefs.edit().putBoolean(KEY_WAS_CONNECTED, value).apply();
    }
}
