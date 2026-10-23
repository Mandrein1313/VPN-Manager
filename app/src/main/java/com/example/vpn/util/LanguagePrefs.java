package com.example.vpn.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

/**
 * ภาษาแอป: ไทย / English
 * เรียก wrap() ใน Activity.attachBaseContext เท่านั้น
 * (อย่า wrap Application.attachBaseContext — เสี่ยง NPE getApplicationContext)
 */
public final class LanguagePrefs {

    public static final String LANG_TH = "th";
    public static final String LANG_EN = "en";

    private static final String PREF = "language_prefs";
    private static final String KEY_LANG = "app_lang";

    private final SharedPreferences prefs;

    public LanguagePrefs(Context context) {
        Context store = safeAppContext(context);
        prefs = store.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** กัน NPE ตอน Application ยัง attach ไม่เสร็จ */
    private static Context safeAppContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is null");
        }
        try {
            Context app = context.getApplicationContext();
            if (app != null) return app;
        } catch (Exception ignored) {
        }
        return context;
    }

    public String getLanguage() {
        return prefs.getString(KEY_LANG, LANG_TH);
    }

    public void setLanguage(String lang) {
        if (!LANG_EN.equals(lang) && !LANG_TH.equals(lang)) {
            lang = LANG_TH;
        }
        prefs.edit().putString(KEY_LANG, lang).apply();
    }

    public boolean isEnglish() {
        return LANG_EN.equals(getLanguage());
    }

    /** ห่อ Context ให้ใช้ locale ที่เลือก — เรียกใน Activity.attachBaseContext */
    public static Context wrap(Context context) {
        if (context == null) return null;
        try {
            String lang = new LanguagePrefs(context).getLanguage();
            return updateResources(context, lang);
        } catch (Exception e) {
            // อย่าทำให้แอปเด้งตอนเปิด — ใช้ context เดิม
            return context;
        }
    }

    public static Context updateResources(Context context, String language) {
        if (context == null) return null;
        if (language == null || language.isEmpty()) language = LANG_TH;

        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
            return context.createConfigurationContext(config);
        } else {
            //noinspection deprecation
            config.locale = locale;
            //noinspection deprecation
            res.updateConfiguration(config, res.getDisplayMetrics());
            return context;
        }
    }

    /** ใช้หลังเปลี่ยนภาษา — recreate activity */
    public void applyAndRecreate(Activity activity) {
        updateResources(activity, getLanguage());
        activity.recreate();
    }

    public static String getDisplayName(String lang) {
        if (LANG_EN.equals(lang)) return "English";
        return "ไทย";
    }
}
