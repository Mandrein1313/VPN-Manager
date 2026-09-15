package com.example.vpn.data;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import com.example.vpn.model.Profile;
import com.example.vpn.model.Protocol;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Entity(tableName = "profiles")
public class ProfileEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;
    public String protocolId;
    public String host;
    public int port;
    public String user;
    public String pass;
    public String payload;
    public String sni;
    public String dns1;
    public String dns2;

    @ColumnInfo(name = "extras_json")
    public String extrasJson;

    public boolean isFavorite;
    public long lastUsedAt;

    // ---- Mappers ----

    public static ProfileEntity fromDomain(Profile p) {
        ProfileEntity e = new ProfileEntity();
        e.id = p.id;
        e.name = p.name;
        e.protocolId = p.protocol.id;
        e.host = p.host;
        e.port = p.port;
        e.user = p.user;
        e.pass = p.pass;
        e.payload = p.payload;
        e.sni = p.sni;
        e.dns1 = p.dns1;
        e.dns2 = p.dns2;
        e.extrasJson = mapToJson(p.extras);
        e.isFavorite = p.isFavorite;
        e.lastUsedAt = p.lastUsedAt;
        return e;
    }

    public Profile toDomain() {
        Profile p = new Profile();
        p.id = id;
        p.name = name;
        p.protocol = Protocol.fromId(protocolId);
        p.host = host;
        p.port = port;
        p.user = user;
        p.pass = pass;
        p.payload = payload;
        p.sni = sni;
        p.dns1 = dns1;
        p.dns2 = dns2;
        p.extras = jsonToMap(extrasJson);
        p.isFavorite = isFavorite;
        p.lastUsedAt = lastUsedAt;
        return p;
    }

    private static String mapToJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, String> e : map.entrySet()) {
                obj.put(e.getKey(), e.getValue());
            }
        } catch (Exception ignored) {}
        return obj.toString();
    }

    private static Map<String, String> jsonToMap(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.isEmpty()) return map;
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                map.put(k, obj.optString(k));
            }
        } catch (Exception ignored) {}
        return map;
    }
}
