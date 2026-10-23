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
 * เรียก wrap() ใน attachBaseContext และ apply ใน Application
 */
public final class LanguagePrefs {

    public static final String LANG_TH = "th";
    public static final String LANG_EN = "en";

    private static final String PREF = "language_prefs";
    private static final String KEY_LANG = "app_lang";

    private final SharedPreferences prefs;

    public LanguagePrefs(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
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
        String lang = new LanguagePrefs(context).getLanguage();
        return updateResources(context, lang);
    }

    public static Context updateResources(Context context, String language) {
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
            return context.createConfigurationContext(config);
        } else {
            config.locale = locale;
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
