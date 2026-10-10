package com.example.vpn.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * หา IP สำหรับแชร์ Proxy ไปยังเครื่องอื่น (Hotspot / Wi‑Fi)
 */
public final class NetworkShareHelper {

    private NetworkShareHelper() {}

    public static class ShareInfo {
        public final List<String> addresses;
        public final String primary;
        public final String hint;

        public ShareInfo(List<String> addresses, String primary, String hint) {
            this.addresses = addresses;
            this.primary = primary;
            this.hint = hint;
        }
    }

    public static ShareInfo collect(Context ctx) {
        List<String> found = new ArrayList<>();

        // 1) interfaces ทั่วไป
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en != null) {
                for (NetworkInterface ni : Collections.list(en)) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    String name = ni.getName() != null ? ni.getName().toLowerCase(Locale.US) : "";
                    for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (!(addr instanceof Inet4Address)) continue;
                        if (addr.isLoopbackAddress()) continue;
                        String ip = addr.getHostAddress();
                        if (ip == null || ip.startsWith("169.254.")) continue;
                        // เน้น hotspot / softap / wlan
                        if (name.contains("ap") || name.contains("wlan")
                                || name.contains("swlan") || name.contains("wifi")
                                || ip.startsWith("192.168.43.")
                                || ip.startsWith("192.168.137.")
                                || ip.startsWith("192.168.49.")
                                || ip.startsWith("172.")) {
                            if (!found.contains(ip)) found.add(ip);
                        } else if (!found.contains(ip)) {
                            found.add(ip);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2) WifiManager (เมื่อต่อ Wi‑Fi เป็น client)
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ipInt = wm.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    String ip = String.format(Locale.US, "%d.%d.%d.%d",
                            (ipInt & 0xff),
                            (ipInt >> 8 & 0xff),
                            (ipInt >> 16 & 0xff),
                            (ipInt >> 24 & 0xff));
                    if (!ip.equals("0.0.0.0") && !found.contains(ip)) {
                        found.add(0, ip);
                    }
                }
            }
        } catch (Exception ignored) {}

        // 3) ConnectivityManager
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network n = cm.getActiveNetwork();
                if (n != null) {
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null) {
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress a = la.getAddress();
                            if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                                String ip = a.getHostAddress();
                                if (ip != null && !found.contains(ip)) found.add(ip);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        String primary = found.isEmpty() ? null : found.get(0);
        // เลือก IP แนว hotspot ก่อน
        for (String ip : found) {
            if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.137.")
                    || ip.startsWith("192.168.49.")) {
                primary = ip;
                break;
            }
        }

        String hint;
        if (primary == null) {
            hint = "ยังไม่พบ IP — เปิด Hotspot หรือเชื่อม Wi‑Fi ก่อน";
        } else if (primary.startsWith("192.168.43.") || primary.startsWith("192.168.137.")
                || primary.startsWith("192.168.49.")) {
            hint = "น่าจะเป็น IP ของ Hotspot";
        } else {
            hint = "IP บนเครือข่ายปัจจุบัน (Wi‑Fi / LAN)";
        }

        return new ShareInfo(found, primary, hint);
    }
}
