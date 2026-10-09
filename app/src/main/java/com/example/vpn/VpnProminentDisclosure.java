package com.example.vpn;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Google Play — Prominent Disclosure สำหรับแอปที่ใช้ VpnService
 * แสดงครั้งเดียวตอนเปิดแอปครั้งแรก (หรือจนกว่าผู้ใช้กดยอมรับ)
 */
public final class VpnProminentDisclosure {

    private static final String PREF = "vpn_disclosure";
    private static final String KEY_ACCEPTED = "prominent_disclosure_accepted_v1";

    private VpnProminentDisclosure() {}

    public static boolean hasAccepted(@NonNull Context ctx) {
        return prefs(ctx).getBoolean(KEY_ACCEPTED, false);
    }

    /** เรียกจาก MainActivity.onCreate — แสดงครั้งเดียว */
    public static void showIfNeeded(@NonNull Activity activity) {
        if (activity.isFinishing() || hasAccepted(activity)) return;

        CharSequence message =
                "Tunnel Mate ใช้บริการ VPN ของระบบ Android (VpnService)\n\n"
                        + "เมื่อคุณกดเชื่อมต่อ:\n"
                        + "• ทราฟฟิกอินเทอร์เน็ตจากอุปกรณ์จะถูกส่งผ่านอุโมงค์ VPN "
                        + "ไปยังเซิร์ฟเวอร์ที่คุณตั้งค่าเอง (SSH / V2Ray ฯลฯ)\n"
                        + "• แอปไม่ได้เป็นผู้ให้บริการ VPN สาธารณะ — "
                        + "คุณนำเข้าหรือกรอกคอนฟิกเซิร์ฟเวอร์เอง\n"
                        + "• แอปไม่เก็บประวัติการเข้าเว็บหรือเนื้อหาทราฟฟิกของคุณไว้บนเซิร์ฟเวอร์ของเรา\n"
                        + "• ข้อมูลคอนฟิก (โฮสต์, พอร์ต, บัญชี) เก็บในเครื่องคุณเท่านั้น "
                        + "เว้นแต่คุณเลือกส่งออก/แชร์เอง\n"
                        + "• การเชื่อมต่ออาจใช้การแจ้งเตือนขณะ VPN ทำงาน\n\n"
                        + "โดยการกด «ยอมรับ» คุณยืนยันว่าได้อ่านและเข้าใจข้อความนี้แล้ว";

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("ข้อมูลสำคัญเกี่ยวกับ VPN")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("ยอมรับ", (d, w) -> {
                    prefs(activity).edit().putBoolean(KEY_ACCEPTED, true).apply();
                })
                .create();

        dialog.setOnShowListener(d -> {
            // ให้ข้อความเลื่อนได้ถ้าจอเล็ก
            try {
                TextView msg = dialog.findViewById(android.R.id.message);
                if (msg != null) {
                    msg.setMovementMethod(LinkMovementMethod.getInstance());
                    msg.setTextSize(14f);
                }
            } catch (Exception ignored) {}
        });

        if (!activity.isFinishing()) {
            dialog.show();
        }
    }

    /** สำหรับทดสอบ — รีเซ็ตให้แสดงใหม่ */
    public static void resetForDebug(@NonNull Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_ACCEPTED, false).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
