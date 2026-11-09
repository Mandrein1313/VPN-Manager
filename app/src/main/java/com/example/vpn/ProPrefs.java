package com.example.vpn;

import android.content.Context;
import android.content.SharedPreferences;

/** สถานะสมาชิก PRO (เติม billing จริงทีหลังได้) */
public final class ProPrefs {
    private static final String PREF = "tunnel_mate_pro";
    private static final String KEY_PRO = "is_pro";

    private final SharedPreferences sp;

    public ProPrefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public boolean isPro() {
        return sp.getBoolean(KEY_PRO, false);
    }

    public void setPro(boolean pro) {
        sp.edit().putBoolean(KEY_PRO, pro).apply();
    }
}
