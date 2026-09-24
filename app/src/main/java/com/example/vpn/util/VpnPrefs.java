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
    private static final String KEY_SHARE_EXTERNAL = "share_external";
    private static final String KEY_BYPASS_DISABLED = "bypass_disabled";  // ⭐ ใหม่

    private final SharedPreferences prefs;

    public VpnPrefs(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ===== Auto-reconnect =====
    public boolean isAutoReconnect() {
        return prefs.getBoolean(KEY_AUTO_RECONNECT, true);
    }

    public void setAutoReconnect(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply();
    }

    // ===== Kill Switch =====
    public boolean isKillSwitch() {
        return prefs.getBoolean(KEY_KILL_SWITCH, false);
    }

    public void setKillSwitch(boolean value) {
        prefs.edit().putBoolean(KEY_KILL_SWITCH, value).apply();
    }

    // ===== Auto-connect on Boot =====
    public boolean isAutoConnectBoot() {
        return prefs.getBoolean(KEY_AUTO_CONNECT_BOOT, false);
    }

    public void setAutoConnectBoot(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT_BOOT, value).apply();
    }

    // ===== SOCKS5 Sharing =====
    public boolean isShareExternal() {
        return prefs.getBoolean(KEY_SHARE_EXTERNAL, false);
    }

    public void setShareExternal(boolean value) {
        prefs.edit().putBoolean(KEY_SHARE_EXTERNAL, value).apply();
    }

    // ⭐ ===== Bypass Disabled (toggle จาก notification) =====
    public boolean isBypassDisabled() {
        return prefs.getBoolean(KEY_BYPASS_DISABLED, false);
    }

    public void setBypassDisabled(boolean value) {
        prefs.edit().putBoolean(KEY_BYPASS_DISABLED, value).apply();
    }

    // ===== Profile =====
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
