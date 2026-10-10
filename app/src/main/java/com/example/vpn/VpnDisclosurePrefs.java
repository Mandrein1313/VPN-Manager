package com.example.vpn;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * จำว่าผู้ใช้ยอมรับ Prominent Disclosure (VPN) แล้วหรือยัง — แสดงครั้งเดียว
 */
public final class VpnDisclosurePrefs {

    private static final String PREF = "vpn_disclosure_prefs";
    private static final String KEY_ACCEPTED = "disclosure_accepted";

    private final SharedPreferences prefs;

    public VpnDisclosurePrefs(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public boolean isAccepted() {
        return prefs.getBoolean(KEY_ACCEPTED, false);
    }

    public void setAccepted(boolean accepted) {
        prefs.edit().putBoolean(KEY_ACCEPTED, accepted).apply();
    }
}
