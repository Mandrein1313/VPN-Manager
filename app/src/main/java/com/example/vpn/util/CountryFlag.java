package com.example.vpn.util;

import android.content.Context;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * แปลงชื่อโปรไฟล์ / hostname → รหัสประเทศ + ไอคอนธง PNG
 * ไฟล์ drawable: th_flag.png, sg_flag.png, us_flag.png, ...
 * (ชื่อ = รหัส ISO 2 ตัว + "_flag")
 */
public final class CountryFlag {

    private CountryFlag() {}

    /** emoji สำรอง (ถ้ายังไม่มี PNG) */
    public static String flagFor(String name, String host) {
        String code = detectCode(name, host);
        if (code == null) return "🌐";
        return codeToEmoji(code);
    }

    /**
     * ใส่ธงลง ImageView จาก drawable `{cc}_flag`
     * ถ้าไม่พบไฟล์ → ใช้ ic_flag_unknown หรือซ่อนแล้วให้ TextView โชว์ emoji
     */
    public static void applyTo(@Nullable ImageView imageView,
                               @Nullable TextView emojiFallback,
                               @Nullable String name,
                               @Nullable String host) {
        if (imageView == null) return;
        Context ctx = imageView.getContext();
        String code = detectCode(name, host);
        int res = resolveDrawable(ctx, code);
        if (res != 0) {
            imageView.setVisibility(android.view.View.VISIBLE);
            imageView.setImageResource(res);
            if (emojiFallback != null) emojiFallback.setVisibility(android.view.View.GONE);
        } else {
            // ไม่มี PNG — ใช้ emoji ถ้ามี TextView
            if (emojiFallback != null) {
                imageView.setVisibility(android.view.View.GONE);
                emojiFallback.setVisibility(android.view.View.VISIBLE);
                emojiFallback.setText(flagFor(name, host));
            } else {
                imageView.setVisibility(android.view.View.VISIBLE);
                int globe = resolveDrawable(ctx, null); // try flag_unknown
                if (globe == 0) {
                    // ใช้ system icon ชั่วคราว
                    imageView.setImageResource(android.R.drawable.ic_menu_mapmode);
                } else {
                    imageView.setImageResource(globe);
                }
            }
        }
    }

    /** เวอร์ชันสั้น — มีแต่ ImageView */
    public static void applyTo(@Nullable ImageView imageView,
                               @Nullable String name,
                               @Nullable String host) {
        applyTo(imageView, null, name, host);
    }

    @DrawableRes
    public static int resolveDrawable(Context ctx, @Nullable String code) {
        if (ctx == null) return 0;
        if (code != null && code.length() == 2) {
            String resName = code.toLowerCase(Locale.US) + "_flag";
            int id = ctx.getResources()
                    .getIdentifier(resName, "drawable", ctx.getPackageName());
            if (id != 0) return id;
            // บางชุดใช้ ic_flag_th
            id = ctx.getResources()
                    .getIdentifier("ic_flag_" + code.toLowerCase(Locale.US),
                            "drawable", ctx.getPackageName());
            if (id != 0) return id;
        }
        int unknown = ctx.getResources()
                .getIdentifier("flag_unknown", "drawable", ctx.getPackageName());
        if (unknown != 0) return unknown;
        unknown = ctx.getResources()
                .getIdentifier("ic_flag_unknown", "drawable", ctx.getPackageName());
        return unknown;
    }

    public static String detectCode(String name, String host) {
        String n = name != null ? name.toLowerCase(Locale.US) : "";
        String h = host != null ? host.toLowerCase(Locale.US) : "";
        String all = n + " " + h;

        String fromToken = tokenCountry(n);
        if (fromToken != null) return fromToken;
        fromToken = tokenCountry(h);
        if (fromToken != null) return fromToken;

        if (match(all, "thailand", "bangkok", ".th.", "th1.", "th2.", "th-", "th_",
                "true-", "ais-", "dtac", "vpnjz")) return "th";
        if (match(all, "singapore", "sg.", "sg1.", "sg-", "sg_")) return "sg";
        if (match(all, "japan", "tokyo", "osaka", "jp.", "jp1.", "jp-", "jp_")) return "jp";
        if (match(all, "vietnam", "hanoi", "saigon", "vn.", "vn1.", "vn-", "vn_")) return "vn";
        if (match(all, "indonesia", "jakarta", "id.", "id1.", "id-", "id_")) return "id";
        if (match(all, "malaysia", "kuala", "my.", "my1.", "my-", "my_")) return "my";
        if (match(all, "philippines", "manila", "ph.", "ph1.", "ph-", "ph_")) return "ph";
        if (match(all, "hongkong", "hong kong", "hk.", "hk1.", "hk-", "hk_")) return "hk";
        if (match(all, "taiwan", "taipei", "tw.", "tw1.", "tw-", "tw_")) return "tw";
        if (match(all, "korea", "seoul", "kr.", "kr1.", "kr-", "kr_")) return "kr";
        if (match(all, "china", "shanghai", "beijing", "cn.", "cn1.")) return "cn";
        if (match(all, "india", "mumbai", "in.", "in1.", "in-", "in_")) return "in";
        if (match(all, "united states", "usa", "america", "new york", "los angeles",
                "us.", "us1.", "us-", "us_", "nyc", "la.")) return "us";
        if (match(all, "united kingdom", "london", "england", "uk.", "uk1.", "gb.", "gb-")) return "gb";
        if (match(all, "germany", "frankfurt", "de.", "de1.", "de-", "de_")) return "de";
        if (match(all, "france", "paris", "fr.", "fr1.", "fr-", "fr_")) return "fr";
        if (match(all, "netherlands", "amsterdam", "nl.", "nl1.", "nl-", "nl_")) return "nl";
        if (match(all, "russia", "moscow", "ru.", "ru1.", "ru-", "ru_")) return "ru";
        if (match(all, "australia", "sydney", "au.", "au1.", "au-", "au_")) return "au";
        if (match(all, "canada", "toronto", "ca.", "ca1.", "ca-", "ca_")) return "ca";
        if (match(all, "brazil", "sao paulo", "br.", "br1.", "br-", "br_")) return "br";
        if (match(all, "turkey", "istanbul", "tr.", "tr1.", "tr-", "tr_")) return "tr";
        if (match(all, "uae", "dubai", "ae.", "ae1.", "ae-")) return "ae";
        if (match(all, "spain", "madrid", "es.", "es1.", "es-")) return "es";
        if (match(all, "italy", "rome", "milan", "it.", "it1.", "it-")) return "it";
        if (match(all, "sweden", "stockholm", "se.", "se1.", "se-")) return "se";
        if (match(all, "poland", "warsaw", "pl.", "pl1.", "pl-")) return "pl";
        if (match(all, "finland", "helsinki", "fi.", "fi1.", "fi-")) return "fi";
        if (match(all, "norway", "oslo", "no.", "no1.", "no-")) return "no";
        if (match(all, "switzerland", "zurich", "ch.", "ch1.", "ch-")) return "ch";
        if (match(all, "austria", "vienna", "at.", "at1.", "at-")) return "at";
        if (match(all, "belgium", "brussels", "be.", "be1.", "be-")) return "be";
        if (match(all, "portugal", "lisbon", "pt.", "pt1.", "pt-")) return "pt";
        if (match(all, "romania", "bucharest", "ro.", "ro1.", "ro-")) return "ro";
        if (match(all, "ukraine", "kyiv", "kiev", "ua.", "ua1.", "ua-")) return "ua";
        if (match(all, "israel", "tel aviv", "il.", "il1.", "il-")) return "il";
        if (match(all, "south africa", "za.", "za1.", "za-")) return "za";
        if (match(all, "mexico", "mx.", "mx1.", "mx-")) return "mx";
        if (match(all, "argentina", "ar.", "ar1.", "ar-")) return "ar";
        if (match(all, "chile", "cl.", "cl1.", "cl-")) return "cl";
        if (match(all, "cambodia", "kh.", "kh1.", "kh-", "phnom")) return "kh";
        if (match(all, "laos", "lao", "la.", "la1.")) return "la";
        if (match(all, "myanmar", "burma", "mm.", "mm1.", "mm-")) return "mm";

        return null;
    }

    private static String tokenCountry(String s) {
        if (s == null || s.isEmpty()) return null;
        // ชื่อสั้น เช่น "TH1", "th2", "SG", "us-east"
        String[] parts = s.replace('-', ' ').replace('_', ' ').replace('.', ' ').split("\\s+");
        for (String p : parts) {
            if (p.length() >= 2) {
                String two = p.substring(0, 2).toLowerCase(Locale.US);
                if (isKnownCode(two) && (p.length() == 2 || Character.isDigit(p.charAt(2)) || p.charAt(2) == ' ')) {
                    return two;
                }
            }
        }
        // ขึ้นต้นด้วยรหัส 2 ตัว + ตัวเลข
        if (s.length() >= 2) {
            String two = s.substring(0, 2).toLowerCase(Locale.US);
            if (isKnownCode(two)) return two;
        }
        return null;
    }

    private static boolean isKnownCode(String cc) {
        if (cc == null || cc.length() != 2) return false;
        // ยอมรับ a-z 2 ตัว (จะหา drawable เอง ถ้าไม่มีก็ fallback)
        return Character.isLetter(cc.charAt(0)) && Character.isLetter(cc.charAt(1));
    }

    private static boolean match(String all, String... keys) {
        for (String k : keys) {
            if (all.contains(k)) return true;
        }
        return false;
    }

    public static String codeToEmoji(String countryCode) {
        if (countryCode == null || countryCode.length() != 2) return "🌐";
        String cc = countryCode.toUpperCase(Locale.US);
        int a = Character.codePointAt(cc, 0) - 'A' + 0x1F1E6;
        int b = Character.codePointAt(cc, 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }
}
