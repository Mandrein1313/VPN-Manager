package com.example.vpn.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ตรวจธงชาติอัตโนมัติจากชื่อโปรไฟล์ / hostname / IP
 * — ไม่ต้องพิมพ์รหัสประเทศเอง
 * ลำดับ: token ชัดเจน → ccTLD → keyword → GeoIP (cache)
 */
public final class CountryFlag {

    private static final ExecutorService GEO_EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** host/ip → ISO 2 ตัว */
    private static final Map<String, String> GEO_CACHE = new ConcurrentHashMap<>();

    private CountryFlag() {}

    /** รหัสประเทศที่รองรับ (กัน assets→as / true→tr หลอก) */
    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
            "th", "sg", "my", "id", "ph", "vn", "kh", "la", "mm", "bn",
            "jp", "kr", "cn", "tw", "hk", "mo", "in", "pk", "bd", "lk",
            "us", "ca", "mx", "br", "ar", "cl", "co", "pe",
            "gb", "uk", "de", "fr", "nl", "be", "ch", "at", "se", "no", "dk", "fi",
            "it", "es", "pt", "pl", "cz", "ro", "ua", "ru", "tr", "gr", "ie",
            "au", "nz", "za", "eg", "il", "ae", "sa", "qa", "kw",
            "as", "gu", "mp", "pr", "vi" // territories — ใช้เมื่อจับได้จริงจาก TLD เท่านั้น
    ));

    /** ccTLD → ประเทศ (ไม่รวม .com / .net / .org) */
    private static final Map<String, String> CCTLD = new HashMap<>();
    static {
        String[][] pairs = {
                {"th", "th"}, {"sg", "sg"}, {"my", "my"}, {"id", "id"}, {"ph", "ph"},
                {"vn", "vn"}, {"kh", "kh"}, {"la", "la"}, {"mm", "mm"}, {"jp", "jp"},
                {"kr", "kr"}, {"cn", "cn"}, {"tw", "tw"}, {"hk", "hk"}, {"mo", "mo"},
                {"in", "in"}, {"pk", "pk"}, {"bd", "bd"}, {"lk", "lk"},
                {"us", "us"}, {"ca", "ca"}, {"mx", "mx"}, {"br", "br"}, {"ar", "ar"},
                {"cl", "cl"}, {"co", "co"}, {"pe", "pe"},
                {"uk", "gb"}, {"gb", "gb"}, {"de", "de"}, {"fr", "fr"}, {"nl", "nl"},
                {"be", "be"}, {"ch", "ch"}, {"at", "at"}, {"se", "se"}, {"no", "no"},
                {"dk", "dk"}, {"fi", "fi"}, {"it", "it"}, {"es", "es"}, {"pt", "pt"},
                {"pl", "pl"}, {"cz", "cz"}, {"ro", "ro"}, {"ua", "ua"}, {"ru", "ru"},
                {"tr", "tr"}, {"gr", "gr"}, {"ie", "ie"},
                {"au", "au"}, {"nz", "nz"}, {"za", "za"}, {"eg", "eg"}, {"il", "il"},
                {"ae", "ae"}, {"sa", "sa"}, {"qa", "qa"}, {"kw", "kw"},
        };
        for (String[] p : pairs) CCTLD.put(p[0], p[1]);
    }

    public static String flagFor(String name, String host) {
        String code = detectCode(name, host);
        if (code == null) return "🌐";
        return codeToEmoji(code);
    }

    public static void applyTo(@Nullable ImageView imageView,
                               @Nullable TextView emojiFallback,
                               @Nullable String name,
                               @Nullable String host) {
        if (imageView == null) return;
        Context ctx = imageView.getContext();
        String code = detectCode(name, host);
        bind(imageView, emojiFallback, name, host, code);

        // ถ้ายังไม่รู้ประเทศ → ลอง GeoIP เบื้องหลัง แล้วอัปเดต UI
        if (code == null && host != null && !host.trim().isEmpty()) {
            resolveGeoAsync(host.trim(), cc -> {
                if (cc == null || imageView.getContext() == null) return;
                // กัน recycle: tag host
                Object tag = imageView.getTag();
                if (tag != null && !host.equals(String.valueOf(tag))) return;
                bind(imageView, emojiFallback, name, host, cc);
            });
            imageView.setTag(host.trim());
        } else if (host != null) {
            imageView.setTag(host.trim());
        }
    }

    public static void applyTo(@Nullable ImageView imageView,
                               @Nullable String name,
                               @Nullable String host) {
        applyTo(imageView, null, name, host);
    }

    private static void bind(ImageView imageView, @Nullable TextView emojiFallback,
                             @Nullable String name, @Nullable String host,
                             @Nullable String code) {
        Context ctx = imageView.getContext();
        int res = resolveDrawable(ctx, code);
        if (res != 0) {
            imageView.setVisibility(android.view.View.VISIBLE);
            imageView.setImageResource(res);
            if (emojiFallback != null) emojiFallback.setVisibility(android.view.View.GONE);
        } else {
            if (emojiFallback != null) {
                imageView.setVisibility(android.view.View.GONE);
                emojiFallback.setVisibility(android.view.View.VISIBLE);
                emojiFallback.setText(code != null ? codeToEmoji(code) : "🌐");
            } else {
                imageView.setVisibility(android.view.View.VISIBLE);
                int globe = resolveDrawable(ctx, null);
                if (globe != 0) imageView.setImageResource(globe);
                else imageView.setImageResource(android.R.drawable.ic_menu_mapmode);
            }
        }
    }

    private static int resolveDrawable(Context ctx, @Nullable String code) {
        if (code != null && code.length() == 2) {
            String resName = code.toLowerCase(Locale.US) + "_flag";
            int id = ctx.getResources()
                    .getIdentifier(resName, "drawable", ctx.getPackageName());
            if (id != 0) return id;
            id = ctx.getResources()
                    .getIdentifier("ic_flag_" + code.toLowerCase(Locale.US),
                            "drawable", ctx.getPackageName());
            if (id != 0) return id;
        }
        int u = ctx.getResources()
                .getIdentifier("flag_unknown", "drawable", ctx.getPackageName());
        if (u != 0) return u;
        return ctx.getResources()
                .getIdentifier("ic_flag_unknown", "drawable", ctx.getPackageName());
    }

    // ------------------------------------------------------------------
    // ตรวจจับรหัสประเทศ
    // ------------------------------------------------------------------

    public static String detectCode(@Nullable String name, @Nullable String host) {
        String n = name != null ? name.trim() : "";
        String h = host != null ? host.trim() : "";

        // 1) จาก cache GeoIP ของ host
        if (!h.isEmpty()) {
            String cached = GEO_CACHE.get(h.toLowerCase(Locale.US));
            if (cached != null) return cached;
        }

        // 2) token จากชื่อโปรไฟล์ก่อน (ผู้ใช้ตั้งชื่อ TH2, Singapore ฯลฯ)
        String fromName = tokenCountryStrict(n);
        if (fromName != null) return fromName;
        fromName = keywordCountry(n);
        if (fromName != null) return fromName;

        // 3) จาก hostname: ccTLD / subdomain
        String fromHost = detectFromHost(h);
        if (fromHost != null) return fromHost;

        // 4) keyword ทั้งชื่อ+โฮสต์
        String all = (n + " " + h).toLowerCase(Locale.US);
        String kw = keywordCountry(all);
        if (kw != null) return kw;

        return null;
    }

    /** ดึงจากโครงสร้าง hostname โดยไม่เดา 2 ตัวอักษรแรกมั่ว */
    private static String detectFromHost(String host) {
        if (host == null || host.isEmpty()) return null;
        String h = host.toLowerCase(Locale.US).trim();
        // ตัดพอร์ต
        int colon = h.indexOf(':');
        if (colon > 0 && h.lastIndexOf(':') == colon) {
            // host:port ไม่ใช่ IPv6
            try {
                Integer.parseInt(h.substring(colon + 1));
                h = h.substring(0, colon);
            } catch (Exception ignored) {}
        }
        // ตัด path
        int slash = h.indexOf('/');
        if (slash > 0) h = h.substring(0, slash);

        // IP ตัวเลข → ใช้ GeoIP อย่างเดียว (คืน null ที่นี่)
        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return null;

        String[] labels = h.split("\\.");
        if (labels.length == 0) return null;

        // ccTLD: example.co.th / example.com.sg / example.jp
        String tld = labels[labels.length - 1];
        if (labels.length >= 3) {
            String sld = labels[labels.length - 2];
            // co.th, com.sg, co.uk, com.au, com.my, co.id, com.ph, com.vn
            String dual = sld + "." + tld;
            if (dual.equals("co.th") || dual.equals("ac.th") || dual.equals("go.th")
                    || dual.equals("or.th") || dual.equals("in.th") || dual.equals("net.th"))
                return "th";
            if (dual.equals("com.sg") || dual.equals("edu.sg") || dual.equals("net.sg"))
                return "sg";
            if (dual.equals("com.my") || dual.equals("edu.my") || dual.equals("net.my"))
                return "my";
            if (dual.equals("co.id") || dual.equals("web.id") || dual.equals("or.id"))
                return "id";
            if (dual.equals("com.ph") || dual.equals("co.ph")) return "ph";
            if (dual.equals("com.vn") || dual.equals("net.vn")) return "vn";
            if (dual.equals("co.uk") || dual.equals("org.uk") || dual.equals("ac.uk"))
                return "gb";
            if (dual.equals("com.au") || dual.equals("net.au") || dual.equals("org.au"))
                return "au";
            if (dual.equals("co.jp") || dual.equals("or.jp") || dual.equals("ne.jp"))
                return "jp";
            if (dual.equals("co.kr") || dual.equals("or.kr") || dual.equals("ne.kr"))
                return "kr";
            if (dual.equals("com.tr") || dual.equals("gov.tr")) return "tr";
            if (dual.equals("com.br") || dual.equals("com.ar") || dual.equals("com.mx"))
                return CCTLD.get(tld);
        }
        // TLD เดี่ยวที่เป็นประเทศ
        if (CCTLD.containsKey(tld)) {
            // กัน .co / .tv / .me ที่ไม่ใช่ประเทศชัด
            if (tld.equals("co") || tld.equals("tv") || tld.equals("me")
                    || tld.equals("io") || tld.equals("cc") || tld.equals("to")) {
                // ไม่ใช้
            } else {
                return CCTLD.get(tld);
            }
        }

        // subdomain ตัวแรก: th1.vpn.com, sg.xxx, jp2.xxx, bkk.xxx
        if (labels.length >= 2) {
            String sub = labels[0];
            String subCode = tokenCountryStrict(sub);
            if (subCode != null) return subCode;
            // th2 / sg1 / us-east
            if (sub.length() >= 2) {
                String two = sub.substring(0, 2);
                if (isStrictCode(two)
                        && (sub.length() == 2
                        || Character.isDigit(sub.charAt(2))
                        || sub.charAt(2) == '-' || sub.charAt(2) == '_')) {
                    return two;
                }
            }
        }

        // true.th / help.x.com ไม่เกี่ยว — trueapp-static.true.th
        if (h.endsWith(".true.th") || h.contains(".true.th")) return "th";

        return null;
    }

    /**
     * เฉพาะ token ที่เป็นรหัสประเทศจริง + รูปแบบชัด
     * ไม่ใช้ 2 ตัวแรกของคำทั่วไป (assets→as)
     */
    private static String tokenCountryStrict(String s) {
        if (s == null || s.isEmpty()) return null;
        String raw = s.toLowerCase(Locale.US);
        String[] parts = raw
                .replace('-', ' ')
                .replace('_', ' ')
                .replace('.', ' ')
                .replace(':', ' ')
                .split("\\s+");
        for (String p : parts) {
            if (p.isEmpty()) continue;
            // TH / th / TH2 / th2 / sg1
            if (p.length() == 2 && isStrictCode(p)) return p;
            if (p.length() >= 3 && isStrictCode(p.substring(0, 2))) {
                char c2 = p.charAt(2);
                if (Character.isDigit(c2) || c2 == '-' || c2 == '_') {
                    return p.substring(0, 2);
                }
            }
        }
        return null;
    }

    private static boolean isStrictCode(String cc) {
        return cc != null && cc.length() == 2 && KNOWN.contains(cc.toLowerCase(Locale.US));
    }

    private static String keywordCountry(String text) {
        if (text == null || text.isEmpty()) return null;
        String all = text.toLowerCase(Locale.US);
        // ประเทศไทยก่อน (ใช้บ่อย)
        if (match(all, "thailand", "bangkok", "thai", "บ้างกอก", "ไทย", "bkk")) return "th";
        if (match(all, "singapore", "sgp", "sin ")) return "sg";
        if (match(all, "malaysia", "kuala", "kl ")) return "my";
        if (match(all, "indonesia", "jakarta")) return "id";
        if (match(all, "philippines", "manila")) return "ph";
        if (match(all, "vietnam", "hanoi", "saigon", "hochiminh")) return "vn";
        if (match(all, "japan", "tokyo", "osaka", "nrt", "hnd")) return "jp";
        if (match(all, "korea", "seoul", "icn")) return "kr";
        if (match(all, "hong kong", "hongkong", "hkg")) return "hk";
        if (match(all, "taiwan", "taipei", "tpe")) return "tw";
        if (match(all, "china", "beijing", "shanghai")) return "cn";
        if (match(all, "united states", "usa", "america", "los angeles", "new york",
                "seattle", "miami", "chicago", "dallas", "sjc", "lax", "nyc")) return "us";
        if (match(all, "canada", "toronto", "montreal", "vancouver")) return "ca";
        if (match(all, "united kingdom", "london", "england", "britain")) return "gb";
        if (match(all, "germany", "frankfurt", "berlin", "munich", "fra")) return "de";
        if (match(all, "france", "paris", "cdg")) return "fr";
        if (match(all, "netherlands", "amsterdam", "ams")) return "nl";
        if (match(all, "turkey", "istanbul", "ankara")) return "tr";
        if (match(all, "russia", "moscow", "siberia")) return "ru";
        if (match(all, "india", "mumbai", "delhi", "bangalore")) return "in";
        if (match(all, "australia", "sydney", "melbourne", "syd")) return "au";
        if (match(all, "cambodia", "phnom")) return "kh";
        if (match(all, "laos", "vientiane")) return "la";
        if (match(all, "myanmar", "yangon", "burma")) return "mm";
        if (match(all, "uae", "dubai", "abu dhabi")) return "ae";
        if (match(all, "brazil", "sao paulo", "são paulo")) return "br";
        return null;
    }

    private static boolean match(String all, String... keys) {
        for (String k : keys) {
            if (all.contains(k)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // GeoIP แบบเบา ๆ (ฟรี ip-api.com ไม่ต้อง key) + cache
    // ------------------------------------------------------------------

    public interface GeoCallback {
        void onResult(@Nullable String countryCode);
    }

    public static void resolveGeoAsync(String host, GeoCallback cb) {
        if (host == null || host.isEmpty() || cb == null) return;
        final String key = host.toLowerCase(Locale.US);
        String cached = GEO_CACHE.get(key);
        if (cached != null) {
            MAIN.post(() -> cb.onResult(cached));
            return;
        }
        GEO_EXEC.execute(() -> {
            String cc = null;
            try {
                String ip = host;
                if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                    InetAddress[] addrs = InetAddress.getAllByName(host);
                    if (addrs != null && addrs.length > 0) {
                        ip = addrs[0].getHostAddress();
                    }
                }
                // ip-api.com — ไม่ต้อง API key, HTTP
                URL url = new URL("http://ip-api.com/json/"
                        + ip + "?fields=status,countryCode");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "TunnelMate/1.0");
                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject j = new JSONObject(sb.toString());
                    if ("success".equalsIgnoreCase(j.optString("status"))) {
                        String c = j.optString("countryCode", "").trim();
                        if (c.length() == 2) {
                            cc = c.toLowerCase(Locale.US);
                            GEO_CACHE.put(key, cc);
                            if (!ip.equals(key)) GEO_CACHE.put(ip, cc);
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                VpnLogger.d("CountryFlag", "GeoIP fail " + host + ": " + e.getMessage());
            }
            final String result = cc;
            MAIN.post(() -> cb.onResult(result));
        });
    }

    public static String codeToEmoji(String countryCode) {
        if (countryCode == null || countryCode.length() != 2) return "🌐";
        String cc = countryCode.toUpperCase(Locale.US);
        int a = Character.codePointAt(cc, 0) - 'A' + 0x1F1E6;
        int b = Character.codePointAt(cc, 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }
}
