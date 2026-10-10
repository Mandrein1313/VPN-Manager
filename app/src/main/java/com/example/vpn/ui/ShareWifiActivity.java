package com.example.vpn.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.vpn.ProxyVpnService;
import com.example.vpn.R;
import com.example.vpn.tunnel.Socks5Server;
import com.example.vpn.util.StyledToast;
import com.example.vpn.util.NetworkShareHelper;
import com.example.vpn.util.VpnPrefs;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * แชร์ VPN ให้เครื่องอื่นผ่าน SOCKS5 (Wi‑Fi / Hotspot)
 *
 * หมายเหตุ: Android ไม่ให้ Hotspot วิ่งผ่าน VpnService แบบโปร่งใสโดยไม่ root
 * วิธีที่ใช้ได้จริง = เปิด SOCKS5 บน LAN แล้วให้เครื่องลูกตั้ง Proxy
 */
public class ShareWifiActivity extends AppCompatActivity {

    private VpnPrefs prefs;
    private MaterialSwitch switchShare;
    private TextView txtStatus;
    private TextView txtAddress;
    private TextView txtGuide;
    private MaterialButton btnCopy;
    private MaterialButton btnRefresh;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_wifi);

        prefs = new VpnPrefs(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(tb);

        switchShare = findViewById(R.id.switchShare);
        txtStatus = findViewById(R.id.txtStatus);
        txtAddress = findViewById(R.id.txtAddress);
        txtGuide = findViewById(R.id.txtGuide);
        btnCopy = findViewById(R.id.btnCopy);
        btnRefresh = findViewById(R.id.btnRefresh);

        switchShare.setChecked(prefs.isShareWifi());
        switchShare.setOnCheckedChangeListener((b, checked) -> {
            prefs.setShareWifi(checked);
            if (checked) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("เปิดแชร์ VPN")
                        .setMessage("SOCKS5 จะฟังที่ทุก interface (0.0.0.0:"
                                + Socks5Server.LOCAL_PORT + ")\n\n"
                                + "ถ้า VPN กำลังเชื่อมอยู่ — ต้อง Reconnect ครั้งหนึ่ง\n"
                                + "จากนั้นเปิด Hotspot แล้วตั้ง Proxy ที่เครื่องลูก")
                        .setPositiveButton("ตกลง", null)
                        .show();
            }
            refreshUi();
            hintReconnect();
        });

        btnCopy.setOnClickListener(v -> copyAddress());
        btnRefresh.setOnClickListener(v -> refreshUi());

        txtGuide.setText(
                "วิธีใช้\n\n"
                        + "1) เปิดสวิตช์แชร์ด้านบน\n"
                        + "2) เชื่อมต่อ VPN ในแอปนี้\n"
                        + "3) เปิด Hotspot บนมือถือ (หรือต่อ Wi‑Fi เดียวกัน)\n"
                        + "4) เครื่องลูกเชื่อม Wi‑Fi/Hotspot นี้\n"
                        + "5) ตั้งค่า Proxy บนเครื่องลูก:\n"
                        + "    • ประเภท: SOCKS5\n"
                        + "    • โฮสต์: IP ที่แสดงด้านบน\n"
                        + "    • พอร์ต: " + Socks5Server.LOCAL_PORT + "\n\n"
                        + "Android (ลูก): ตั้งค่า Wi‑Fi → แก้ไขเครือข่าย → Proxy ขั้นสูง\n"
                        + "หรือใช้แอปที่รองรับ SOCKS5\n\n"
                        + "Windows: การตั้งค่า → เครือข่าย → Proxy\n"
                        + "หรือในเบราว์เซอร์ (บางตัวรองรับ SOCKS)\n\n"
                        + "ข้อจำกัด: แชร์แบบโปร่งใส (ไม่ต้องตั้ง proxy) บน Android\n"
                        + "โดยไม่มี root ทำไม่ได้ — โหมดนี้ใช้ Proxy ซึ่งเสถียรกว่า"
        );

        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void refreshUi() {
        boolean shareOn = prefs.isShareWifi();
        boolean vpnOn = ProxyVpnService.isServiceRunning(this);

        NetworkShareHelper.ShareInfo info = NetworkShareHelper.collect(this);
        String ip = info.primary != null ? info.primary : "—";
        String addr = ip + ":" + Socks5Server.LOCAL_PORT;

        txtAddress.setText(addr);

        StringBuilder st = new StringBuilder();
        st.append(shareOn ? "แชร์: เปิด" : "แชร์: ปิด");
        st.append("  |  ");
        st.append(vpnOn ? "VPN: เชื่อมอยู่" : "VPN: ยังไม่เชื่อม");
        st.append("\n").append(info.hint);
        if (!info.addresses.isEmpty()) {
            st.append("\nIP ที่พบ: ").append(String.join(", ", info.addresses));
        }
        txtStatus.setText(st.toString());
    }

    private void copyAddress() {
        CharSequence t = txtAddress.getText();
        if (t == null || t.toString().contains("—")) {
            StyledToast.success(this, "ยังไม่มีที่อยู่ให้คัดลอก");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("socks5", t));
            StyledToast.success(this, "คัดลอก " + t + " แล้ว");
        }
    }

    private void hintReconnect() {
        if (ProxyVpnService.isServiceRunning(this)) {
            StyledToast.info(this, "กด Reconnect VPN เพื่อให้โหมดแชร์มีผล");
        }
    }
}
