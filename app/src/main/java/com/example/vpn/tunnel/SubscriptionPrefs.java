package com.example.vpn.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * เก็บรายการ Subscription URL (ไม่ใช่ Room — เบาและพอสำหรับเฟส V2)
 */
public class SubscriptionPrefs {

    private static final String PREF = "vpn_subscriptions";
    private static final String KEY = "items";

    public static class Item {
        public String id;
        public String name;
        public String url;
        public long lastUpdated;

        public Item() {}

        public Item(String id, String name, String url) {
            this.id = id;
            this.name = name;
            this.url = url;
        }
    }

    private final SharedPreferences prefs;

    public SubscriptionPrefs(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<Item> getAll() {
        List<Item> out = new ArrayList<>();
        try {
            String raw = prefs.getString(KEY, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.id = o.optString("id", String.valueOf(i));
                it.name = o.optString("name", "Subscription");
                it.url = o.optString("url", "");
                it.lastUpdated = o.optLong("lastUpdated", 0L);
                if (!it.url.isEmpty()) out.add(it);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public void saveAll(List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id != null ? it.id : "");
                o.put("name", it.name != null ? it.name : "");
                o.put("url", it.url != null ? it.url : "");
                o.put("lastUpdated", it.lastUpdated);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    public void add(String name, String url) {
        List<Item> all = getAll();
        // อัปเดตถ้า URL ซ้ำ
        for (Item it : all) {
            if (url.equals(it.url)) {
                it.name = name != null && !name.isEmpty() ? name : it.name;
                saveAll(all);
                return;
            }
        }
        Item it = new Item(
                String.valueOf(System.currentTimeMillis()),
                (name == null || name.isEmpty()) ? "Subscription" : name,
                url.trim());
        all.add(it);
        saveAll(all);
    }

    public void remove(String id) {
        List<Item> all = getAll();
        List<Item> next = new ArrayList<>();
        for (Item it : all) {
            if (it.id == null || !it.id.equals(id)) next.add(it);
        }
        saveAll(next);
    }

    public void touch(String id) {
        List<Item> all = getAll();
        for (Item it : all) {
            if (id != null && id.equals(it.id)) {
                it.lastUpdated = System.currentTimeMillis();
            }
        }
        saveAll(all);
    }
}
