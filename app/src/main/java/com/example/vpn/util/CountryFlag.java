package com.example.vpn.util;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ธงชาติอัตโนมัติจาก IP ของเซิร์ฟเวอร์ VPN
 * ผู้ใช้ไม่ต้องพิมพ์รหัสประเทศ — แอป resolve host → IP → ประเทศ แล้วแสดงธง
 */
public final class CountryFlag {

    private static final String PREF = "country_flag_geo";
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, String> MEM = new ConcurrentHashMap<>();
    private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();

    private static SharedPreferences prefs;

    private CountryFlag() {}

    public static void init(Context ctx) {
        if (prefs == null && ctx != null) {
            prefs = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }
    }

    public interface OnCountry {
        void onCountry(@Nullable String iso2);
    }

    /** emoji สำรอง */
    public static String flagFor(String name, String host) {
        String code = peekCode(host);
        if (code == null) code = detectOffline(name, host);
        return code != null ? codeToEmoji(code) : "🌐";
    }

    /**
     * ใส่ธงลง ImageView — ถ้ายังไม่รู้ประเทศจะขอ GeoIP แล้วอัปเดตเอง
     */
    public static void applyTo(@Nullable ImageView imageView,
                               @Nullable String name,
                               @Nullable String host) {
        applyTo(imageView, null, name, host, null);
    }

    public static void applyTo(@Nullable ImageView imageView,
                               @Nullable TextView emojiFallback,
                               @Nullable String name,
                               @Nullable String host) {
        applyTo(imageView, emojiFallback, name, host, null);
    }

    /**
     * @param onResolved ถูกเรียกเมื่อได้รหัสประเทศจาก GeoIP (ใช้ notify รายการ)
     */
    public static void applyTo(@Nullable ImageView imageView,
                               @Nullable TextView emojiFallback,
                               @Nullable String name,
                               @Nullable String host,
                               @Nullable OnCountry onResolved) {
        if (imageView == null) return;
        Context ctx = imageView.getContext();
        init(ctx);

        final String h = host != null ? host.trim() : "";
        imageView.setTag(h); // host ปัจจุบัน — ห้ามใช้ android.R.id.* เป็น key

        String code = peekCode(h);
        if (code == null) code = detectOffline(name, h);
        bind(imageView, emojiFallback, code);

        if (h.isEmpty()) {
            if (onResolved != null) onResolved.onCountry(code);
            return;
        }

        // มี cache แล้ว
        if (peekCode(h) != null) {
            if (onResolved != null) onResolved.onCountry(peekCode(h));
            return;
        }

        // ขอ GeoIP เสมอเมื่อยังไม่รู้ (หลักของผู้ใช้: ตรวจจาก IP)
        final String hostKey = h;
        resolveGeo(ctx, hostKey, cc -> {
            if (cc == null) return;
            // กัน view ถูก bind โปรไฟล์อื่น
            Object tag = imageView.getTag();
            if (tag != null && !hostKey.equals(String.valueOf(tag))) return;
            bind(imageView, emojiFallback, cc);
            if (onResolved != null) onResolved.onCountry(cc);
        });
    }

    /** อ่านรหัสที่รู้แล้ว (memory + disk) โดยไม่ยิงเน็ต */
    public static String peekCode(@Nullable String host) {
        if (host == null || host.isEmpty()) return null;
        String key = normalizeHost(host);
        String c = MEM.get(key);
        if (c != null) return c;
        if (prefs != null) {
            c = prefs.getString("h:" + key, null);
            if (c != null && c.length() == 2) {
                MEM.put(key, c);
                return c;
            }
        }
        return null;
    }

    /** บังคับ resolve ล่วงหน้า (เช่น ตอนโหลดรายการ) */
    public static void prefetch(Context ctx, @Nullable String host) {
        if (ctx == null || host == null || host.trim().isEmpty()) return;
        init(ctx);
        String h = host.trim();
        if (peekCode(h) != null) return;
        resolveGeo(ctx, h, cc -> {});
    }

    // ------------------------------------------------------------------

    private static void bind(ImageView imageView, @Nullable TextView emojiFallback,
                             @Nullable String code) {
        Context ctx = imageView.getContext();
        int res = resolveDrawable(ctx, code);
        if (res != 0 && code != null) {
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
            String cc = code.toLowerCase(Locale.US);
            int id = ctx.getResources()
                    .getIdentifier(cc + "_flag", "drawable", ctx.getPackageName());
            if (id != 0) return id;
            id = ctx.getResources()
                    .getIdentifier("ic_flag_" + cc, "drawable", ctx.getPackageName());
            if (id != 0) return id;
        }
        int u = ctx.getResources()
                .getIdentifier("flag_unknown", "drawable", ctx.getPackageName());
        if (u != 0) return u;
        return ctx.getResources()
                .getIdentifier("ic_flag_unknown", "drawable", ctx.getPackageName());
    }

    private static String normalizeHost(String host) {
        String h = host.trim().toLowerCase(Locale.US);
        int slash = h.indexOf('/');
        if (slash > 0) h = h.substring(0, slash);
        // host:port
        if (h.matches(".*:\\d+$")) {
            int c = h.lastIndexOf(':');
            h = h.substring(0, c);
        }
        return h;
    }

    private static void save(String hostKey, String iso2) {
        if (iso2 == null || iso2.length() != 2) return;
        String cc = iso2.toLowerCase(Locale.US);
        MEM.put(hostKey, cc);
        if (prefs != null) {
            prefs.edit().putString("h:" + hostKey, cc).apply();
        }
    }

    private static void resolveGeo(Context ctx, String host, OnCountry cb) {
        final String key = normalizeHost(host);
        String cached = peekCode(key);
        if (cached != null) {
            MAIN.post(() -> cb.onCountry(cached));
            return;
        }
        if (!INFLIGHT.add(key)) return; // มี request ค้างอยู่แล้ว

        EXEC.execute(() -> {
            String cc = null;
            try {
                String ip = key;
                if (!key.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                    InetAddress[] addrs = InetAddress.getAllByName(key);
                    if (addrs == null || addrs.length == 0) {
                        VpnLogger.w("CountryFlag", "resolve fail: " + key);
                    } else {
                        ip = addrs[0].getHostAddress();
                        VpnLogger.i("CountryFlag", key + " → IP " + ip);
                    }
                }

                // 1) ipapi.co HTTPS
                cc = fetchCountry("https://ipapi.co/" + ip + "/country/", ip);
                // 2) country.is HTTPS
                if (cc == null) {
                    cc = fetchCountryJson("https://api.country.is/" + ip, "country");
                }
                // 3) ip-api.com HTTP (cleartext)
                if (cc == null) {
                    cc = fetchCountryJson(
                            "http://ip-api.com/json/" + ip + "?fields=status,countryCode",
                            "countryCode");
                }

                if (cc != null) {
                    save(key, cc);
                    if (!ip.equals(key)) save(ip, cc);
                    VpnLogger.i("CountryFlag", key + " → country " + cc);
                } else {
                    VpnLogger.w("CountryFlag", "GeoIP empty for " + key);
                }
            } catch (Exception e) {
                VpnLogger.w("CountryFlag", "GeoIP error " + key + ": " + e.getMessage());
            } finally {
                INFLIGHT.remove(key);
            }
            final String result = cc;
            MAIN.post(() -> cb.onCountry(result));
        });
    }

    private static String fetchCountry(String urlStr, String ip) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "TunnelMate/1.0");
            conn.setRequestProperty("Accept", "text/plain");
            if (conn.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line.trim());
            br.close();
            String body = sb.toString().trim();
            if (body.length() == 2 && Character.isLetter(body.charAt(0))) {
                return body.toLowerCase(Locale.US);
            }
        } catch (Exception e) {
            VpnLogger.d("CountryFlag", "fetch " + urlStr + " : " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static String fetchCountryJson(String urlStr, String field) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "TunnelMate/1.0");
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject j = new JSONObject(sb.toString());
            if (j.has("status") && !"success".equalsIgnoreCase(j.optString("status"))) {
                // ip-api only
                if (urlStr.contains("ip-api.com")) return null;
            }
            String c = j.optString(field, "").trim();
            if (c.length() == 2) return c.toLowerCase(Locale.US);
        } catch (Exception e) {
            VpnLogger.d("CountryFlag", "fetchJson " + urlStr + " : " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    // offline เบา ๆ — ไม่เดา 2 ตัวอักษรจากคำทั่วไป
    private static String detectOffline(@Nullable String name, @Nullable String host) {
        String n = name != null ? name.toLowerCase(Locale.US) : "";
        String h = host != null ? host.toLowerCase(Locale.US) : "";
        // ชื่อชัด TH2 / Singapore
        for (String part : n.replace('-', ' ').replace('_', ' ').split("\\s+")) {
            if (part.length() == 2 && isIso(part)) return part;
            if (part.length() >= 3 && isIso(part.substring(0, 2))
                    && Character.isDigit(part.charAt(2))) {
                return part.substring(0, 2);
            }
        }
        if (h.endsWith(".th") || h.contains(".co.th")) return "th";
        if (h.endsWith(".sg") || h.contains(".com.sg")) return "sg";
        if (h.endsWith(".jp") || h.contains(".co.jp")) return "jp";
        if (h.contains("thailand") || h.contains("bangkok") || n.contains("thailand"))
            return "th";
        if (h.contains("singapore") || n.contains("singapore")) return "sg";
        if (h.contains("tokyo") || n.contains("japan")) return "jp";
        if (h.contains("frankfurt") || n.contains("germany")) return "de";
        if (h.contains("london") || n.contains("united kingdom")) return "gb";
        return null;
    }

    private static final Set<String> ISO = new HashSet<>(Arrays.asList(
            "th", "sg", "my", "id", "ph", "vn", "kh", "la", "mm",
            "jp", "kr", "cn", "tw", "hk", "mo", "in",
            "us", "ca", "mx", "br", "ar", "cl",
            "gb", "uk", "de", "fr", "nl", "be", "ch", "at", "se", "no", "dk", "fi",
            "it", "es", "pt", "pl", "cz", "ro", "ua", "ru", "tr", "gr", "ie",
            "au", "nz", "za", "eg", "il", "ae", "sa"
    ));

    private static boolean isIso(String cc) {
        return cc != null && ISO.contains(cc.toLowerCase(Locale.US));
    }

    public static String codeToEmoji(String countryCode) {
        if (countryCode == null || countryCode.length() != 2) return "🌐";
        String cc = countryCode.toUpperCase(Locale.US);
        int a = Character.codePointAt(cc, 0) - 'A' + 0x1F1E6;
        int b = Character.codePointAt(cc, 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }
}
