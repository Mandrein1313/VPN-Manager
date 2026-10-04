package com.example.vpn.util;

import java.util.Locale;

/**
 * แปลงชื่อโปรไฟล์ / hostname → emoji ธงชาติ
 */
public final class CountryFlag {

    private CountryFlag() {}

    public static String flagFor(String name, String host) {
        String code = detectCode(name, host);
        if (code == null) return "🌐";
        return codeToEmoji(code);
    }

    public static String detectCode(String name, String host) {
        String n = name != null ? name.toLowerCase(Locale.US) : "";
        String h = host != null ? host.toLowerCase(Locale.US) : "";
        String all = n + " " + h;

        // รหัส 2 ตัวชัด ๆ ในชื่อ (เช่น "th โนโปร", "US-East")
        String fromToken = tokenCountry(n);
        if (fromToken != null) return fromToken;

        // hostname prefix / keyword
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
        if (match(all, "uae", "dubai", "ae.", "ae1.")) return "ae";

        // TLD ท้ายโดเมน
        if (h.endsWith(".th")) return "th";
        if (h.endsWith(".sg")) return "sg";
        if (h.endsWith(".jp")) return "jp";
        if (h.endsWith(".vn")) return "vn";
        if (h.endsWith(".id")) return "id";
        if (h.endsWith(".my")) return "my";
        if (h.endsWith(".ph")) return "ph";
        if (h.endsWith(".hk")) return "hk";
        if (h.endsWith(".tw")) return "tw";
        if (h.endsWith(".kr")) return "kr";
        if (h.endsWith(".cn")) return "cn";
        if (h.endsWith(".in")) return "in";
        if (h.endsWith(".us")) return "us";
        if (h.endsWith(".uk") || h.endsWith(".gb")) return "gb";
        if (h.endsWith(".de")) return "de";
        if (h.endsWith(".fr")) return "fr";
        if (h.endsWith(".nl")) return "nl";
        if (h.endsWith(".ru")) return "ru";
        if (h.endsWith(".au")) return "au";
        if (h.endsWith(".ca")) return "ca";
        if (h.endsWith(".br")) return "br";

        return null;
    }

    private static String tokenCountry(String name) {
        if (name == null || name.isEmpty()) return null;
        // แยกคำแรก ๆ
        String[] parts = name.trim().split("[\\s_\\-./]+");
        for (String p : parts) {
            if (p.length() == 2) {
                String c = p.toLowerCase(Locale.US);
                if (isKnown(c)) return c;
            }
        }
        return null;
    }

    private static boolean isKnown(String c) {
        switch (c) {
            case "th": case "sg": case "jp": case "vn": case "id": case "my":
            case "ph": case "hk": case "tw": case "kr": case "cn": case "in":
            case "us": case "gb": case "uk": case "de": case "fr": case "nl":
            case "ru": case "au": case "ca": case "br": case "tr": case "ae":
                return true;
            default:
                return false;
        }
    }

    private static boolean match(String hay, String... needles) {
        for (String n : needles) {
            if (hay.contains(n)) return true;
        }
        return false;
    }

    /** ISO 3166-1 alpha-2 → regional indicator emoji */
    public static String codeToEmoji(String countryCode) {
        if (countryCode == null || countryCode.length() != 2) return "🌐";
        String cc = countryCode.toUpperCase(Locale.US);
        if (cc.equals("UK")) cc = "GB";
        int a = Character.codePointAt(cc, 0) - 'A' + 0x1F1E6;
        int b = Character.codePointAt(cc, 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }
}
