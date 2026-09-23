package com.example.vpn.util;

import android.content.Context;
import android.net.Uri;

import com.example.vpn.model.Profile;
import com.example.vpn.model.Protocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ⭐ Backup / Restore Manager
 * - Export ข้อมูลทั้งหมดเป็น JSON
 * - Import จากไฟล์ที่ backup
 */
public class BackupManager {

    private static final String TAG = "BackupManager";
    private static final int FORMAT_VERSION = 1;
    private static final String FILE_EXTENSION = ".vpnbackup";

    private final Context appContext;

    public BackupManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    // ============================================================
    // ⭐ Backup — Export ทั้งหมด
    // ============================================================
    public String exportAll(List<Profile> profiles) {
        try {
            JSONObject root = new JSONObject();

            // Metadata
            root.put("app", "VPN Manager");
            root.put("version", FORMAT_VERSION);
            root.put("exported_at", new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));

            // ⭐ Profiles
            JSONArray profilesArr = new JSONArray();
            for (Profile p : profiles) {
                profilesArr.put(profileToJson(p));
            }
            root.put("profiles", profilesArr);

            // ⭐ Bypass list
            BypassPrefs bypassPrefs = new BypassPrefs(appContext);
            Set<String> bypassed = bypassPrefs.getPackages();
            JSONArray bypassArr = new JSONArray();
            for (String pkg : bypassed) {
                bypassArr.put(pkg);
            }
            root.put("bypass", bypassArr);

            // ⭐ Settings
            VpnPrefs prefs = new VpnPrefs(appContext);
            JSONObject settings = new JSONObject();
            settings.put("auto_reconnect", prefs.isAutoReconnect());
            settings.put("kill_switch", prefs.isKillSwitch());
            settings.put("auto_connect_boot", prefs.isAutoConnectBoot());
            settings.put("bypass_disabled", prefs.isBypassDisabled());
            root.put("settings", settings);

            // ⭐ Theme
            ThemePrefs themePrefs = new ThemePrefs(appContext);
            JSONObject theme = new JSONObject();
            theme.put("mode", themePrefs.getMode());
            root.put("theme", theme);

            return root.toString(2);

        } catch (Exception e) {
            VpnLogger.e(TAG, "exportAll error: " + e.getMessage(), e);
            return null;
        }
    }

    // ============================================================
    // ⭐ Restore — Import จากไฟล์
    // ============================================================
    public RestoreResult importAll(Uri uri) {
        RestoreResult result = new RestoreResult();

        try {
            InputStream is = appContext.getContentResolver().openInputStream(uri);
            if (is == null) {
                result.error = "ไม่สามารถเปิดไฟล์ได้";
                return result;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            is.close();

            String json = sb.toString();
            return importFromJson(json);

        } catch (Exception e) {
            VpnLogger.e(TAG, "importAll error: " + e.getMessage(), e);
            result.error = "เกิดข้อผิดพลาด: " + e.getMessage();
            return result;
        }
    }

    public RestoreResult importFromJson(String json) {
        RestoreResult result = new RestoreResult();

        try {
            JSONObject root = new JSONObject(json);

            // ตรวจสอบ version
            int version = root.optInt("version", 0);
            if (version == 0) {
                result.error = "ไฟล์ไม่ถูกต้อง (ไม่พบ version)";
                return result;
            }
            if (version > FORMAT_VERSION) {
                result.error = "ไฟล์เวอร์ชันใหม่เกินไป (v" + version
                        + " > v" + FORMAT_VERSION + ")";
                return result;
            }

            // ⭐ Profiles
            JSONArray profilesArr = root.optJSONArray("profiles");
            if (profilesArr != null) {
                for (int i = 0; i < profilesArr.length(); i++) {
                    Profile p = jsonToProfile(profilesArr.getJSONObject(i));
                    if (p != null) result.profiles.add(p);
                }
            }

            // ⭐ Bypass list
            JSONArray bypassArr = root.optJSONArray("bypass");
            if (bypassArr != null) {
                for (int i = 0; i < bypassArr.length(); i++) {
                    result.bypassPackages.add(bypassArr.getString(i));
                }
            }

            // ⭐ Settings
            JSONObject settings = root.optJSONObject("settings");
            if (settings != null) {
                result.autoReconnect = settings.optBoolean("auto_reconnect", true);
                result.killSwitch = settings.optBoolean("kill_switch", false);
                result.autoConnectBoot = settings.optBoolean("auto_connect_boot", false);
                result.bypassDisabled = settings.optBoolean("bypass_disabled", false);
                result.hasSettings = true;
            }

            // ⭐ Theme
            JSONObject theme = root.optJSONObject("theme");
            if (theme != null) {
                result.themeMode = theme.optInt("mode", ThemePrefs.MODE_SYSTEM);
                result.hasTheme = true;
            }

            result.success = true;
            return result;

        } catch (Exception e) {
            VpnLogger.e(TAG, "importFromJson error: " + e.getMessage(), e);
            result.error = "Parse JSON ไม่สำเร็จ: " + e.getMessage();
            return result;
        }
    }

    // ============================================================
    // ⭐ Apply Settings หลัง Restore
    // ============================================================
    public void applySettings(RestoreResult result) {
        try {
            // Bypass
            if (!result.bypassPackages.isEmpty()) {
                BypassPrefs bypassPrefs = new BypassPrefs(appContext);
                bypassPrefs.setPackages(result.bypassPackages);
            }

            // Settings
            if (result.hasSettings) {
                VpnPrefs prefs = new VpnPrefs(appContext);
                prefs.setAutoReconnect(result.autoReconnect);
                prefs.setKillSwitch(result.killSwitch);
                prefs.setAutoConnectBoot(result.autoConnectBoot);
                prefs.setBypassDisabled(result.bypassDisabled);
            }

            // Theme
            if (result.hasTheme) {
                ThemePrefs themePrefs = new ThemePrefs(appContext);
                themePrefs.setMode(result.themeMode);
            }

            VpnLogger.i(TAG, "Settings applied successfully");

        } catch (Exception e) {
            VpnLogger.e(TAG, "applySettings error: " + e.getMessage(), e);
        }
    }

    // ============================================================
    // ⭐ Generate Filename
    // ============================================================
    public static String generateFileName() {
        String timestamp = new SimpleDateFormat(
                "yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return "vpn-backup-" + timestamp + FILE_EXTENSION;
    }

    // ============================================================
    // Private helpers
    // ============================================================

    private JSONObject profileToJson(Profile p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", p.name);
        o.put("protocol", p.protocol.id);
        o.put("host", p.host);
        o.put("port", p.port);
        o.put("user", p.user);
        o.put("pass", p.pass);
        o.put("httpProxy", p.httpProxy);
        o.put("payload", p.payload);
        o.put("sni", p.sni);
        o.put("dns1", p.dns1);
        o.put("dns2", p.dns2);
        o.put("isFavorite", p.isFavorite);
        return o;
    }

    private Profile jsonToProfile(JSONObject o) {
        try {
            Profile p = new Profile();
            p.name = o.optString("name", "");
            p.protocol = Protocol.fromId(o.optString("protocol", "ssh"));
            p.host = o.optString("host", "");
            p.port = o.optInt("port", 22);
            p.user = o.optString("user", "");
            p.pass = o.optString("pass", "");
            p.httpProxy = o.optString("httpProxy", "");
            p.payload = o.optString("payload", "");
            p.sni = o.optString("sni", "");
            p.dns1 = o.optString("dns1", "8.8.8.8");
            p.dns2 = o.optString("dns2", "8.8.4.4");
            p.isFavorite = o.optBoolean("isFavorite", false);

            if (p.name.isEmpty()) p.name = p.host;
            if (p.host.isEmpty()) return null;

            return p;
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    // Result class
    // ============================================================
    public static class RestoreResult {
        public boolean success = false;
        public String error = null;

        public List<Profile> profiles = new ArrayList<>();
        public List<String> bypassPackages = new ArrayList<>();

        public boolean hasSettings = false;
        public boolean autoReconnect = true;
        public boolean killSwitch = false;
        public boolean autoConnectBoot = false;
        public boolean bypassDisabled = false;

        public boolean hasTheme = false;
        public int themeMode = ThemePrefs.MODE_SYSTEM;

        public int getProfileCount() {
            return profiles.size();
        }

        public int getBypassCount() {
            return bypassPackages.size();
        }
    }
}