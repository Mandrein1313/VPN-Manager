package com.example.vpn.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.vpn.ProxyVpnService;
import com.example.vpn.R;
import com.example.vpn.tunnel.Socks5Server;
import com.example.vpn.util.NetworkShareHelper;
import com.example.vpn.util.StyledToast;
import com.example.vpn.util.VpnPrefs;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.HashMap;
import java.util.Map;

/**
 * แชร์ VPN ให้เครื่องอื่นผ่าน SOCKS5 (Wi‑Fi / Hotspot)
 * - Auth username/password (RFC 1929)
 * - QR โค้ด IP:port (+ credentials)
 * - นับจำนวน client ที่เชื่อมอยู่
 */
public class ShareWifiActivity extends AppCompatActivity {

    private VpnPrefs prefs;
    private MaterialSwitch switchShare;
    private MaterialSwitch switchAuth;
    private TextView txtStatus;
    private TextView txtAddress;
    private TextView txtGuide;
    private TextView txtClients;
    private TextView txtQrHint;
    private TextInputEditText edtUser;
    private TextInputEditText edtPass;
    private MaterialButton btnCopy;
    private MaterialButton btnRefresh;
    private MaterialButton btnSaveAuth;
    private ImageView imgQr;
    private TextView txtManualHost, txtManualPort, txtManualUser, txtManualPass;
    private MaterialButton btnCopyHost, btnCopyPass, btnShareText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clientPoll = new Runnable() {
        @Override
        public void run() {
            updateClientCount();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_wifi);

        prefs = new VpnPrefs(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(tb);

        switchShare = findViewById(R.id.switchShare);
        switchAuth = findViewById(R.id.switchAuth);
        txtStatus = findViewById(R.id.txtStatus);
        txtAddress = findViewById(R.id.txtAddress);
        txtGuide = findViewById(R.id.txtGuide);
        txtClients = findViewById(R.id.txtClients);
        txtQrHint = findViewById(R.id.txtQrHint);
        edtUser = findViewById(R.id.edtUser);
        edtPass = findViewById(R.id.edtPass);
        btnCopy = findViewById(R.id.btnCopy);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnSaveAuth = findViewById(R.id.btnSaveAuth);
        imgQr = findViewById(R.id.imgQr);
        txtManualHost = findViewById(R.id.txtManualHost);
        txtManualPort = findViewById(R.id.txtManualPort);
        txtManualUser = findViewById(R.id.txtManualUser);
        txtManualPass = findViewById(R.id.txtManualPass);
        btnCopyHost = findViewById(R.id.btnCopyHost);
        btnCopyPass = findViewById(R.id.btnCopyPass);
        btnShareText = findViewById(R.id.btnShareText);

        // load auth
        if (switchAuth != null) switchAuth.setChecked(prefs.isShareAuthEnabled());
        if (edtUser != null) edtUser.setText(prefs.getShareUsername());
        if (edtPass != null) edtPass.setText(prefs.getSharePassword());
        updateAuthFieldsEnabled();

        if (switchAuth != null) {
            switchAuth.setOnCheckedChangeListener((b, checked) -> {
                prefs.setShareAuthEnabled(checked);
                updateAuthFieldsEnabled();
                refreshUi();
                if (ProxyVpnService.isServiceRunning(this) && prefs.isShareWifi()) {
                    promptReconnectForShare(true);
                }
            });
        }
        if (btnSaveAuth != null) {
            btnSaveAuth.setOnClickListener(v -> saveAuth());
        }

        switchShare.setChecked(prefs.isShareWifi());
        switchShare.setOnCheckedChangeListener((b, checked) -> {
            prefs.setShareWifi(checked);
            refreshUi();
            if (ProxyVpnService.isServiceRunning(this)) {
                promptReconnectForShare(checked);
            } else if (checked) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("เปิดแชร์ VPN")
                        .setMessage("SOCKS5 จะฟังที่ทุก interface (0.0.0.0:"
                                + Socks5Server.LOCAL_PORT + ")\n\n"
                                + "ขั้นตอนถัดไป:\n"
                                + "1) เชื่อมต่อ VPN ในแอปนี้\n"
                                + "2) เปิด Hotspot (หรือใช้ Wi‑Fi เดียวกัน)\n"
                                + "3) ตั้ง Proxy SOCKS5 ที่เครื่องลูก"
                                + (prefs.isShareAuthEnabled()
                                ? "\n4) ใส่ Username/Password ที่ตั้งไว้"
                                : ""))
                        .setPositiveButton("ตกลง", null)
                        .show();
            } else {
                StyledToast.info(this, "ปิดแชร์แล้ว");
            }
        });

        btnCopy.setOnClickListener(v -> copyAddress());
        btnRefresh.setOnClickListener(v -> refreshUi());
        if (btnCopyHost != null) {
            btnCopyHost.setOnClickListener(v -> copyPlain(currentHost()));
        }
        if (btnCopyPass != null) {
            btnCopyPass.setOnClickListener(v -> {
                if (!prefs.isShareAuthEnabled()) {
                    StyledToast.info(this, "ยังไม่ได้เปิดรหัสผ่าน SOCKS5");
                    return;
                }
                copyPlain(prefs.getSharePassword());
            });
        }
        if (btnShareText != null) {
            btnShareText.setOnClickListener(v -> shareSetupText());
        }

        txtGuide.setText(
                "วิธีใช้\n\n"
                        + "1) เปิดสวิตช์แชร์ด้านบน\n"
                        + "2) (แนะนำ) เปิดรหัสผ่าน SOCKS5 แล้วบันทึก\n"
                        + "3) เชื่อมต่อ VPN ในแอปนี้\n"
                        + "4) เปิด Hotspot บนมือถือ (หรือต่อ Wi‑Fi เดียวกัน)\n"
                        + "5) เครื่องลูกสแกน QR หรือตั้ง Proxy:\n"
                        + "    • ประเภท: SOCKS5\n"
                        + "    • โฮสต์: IP ที่แสดงด้านบน\n"
                        + "    • พอร์ต: " + Socks5Server.LOCAL_PORT + "\n"
                        + "    • User/Pass: ถ้าเปิดรหัสผ่านไว้\n\n"
                        + "Android (ลูก): ตั้งค่า Wi‑Fi → แก้ไขเครือข่าย → Proxy ขั้นสูง\n"
                        + "หรือใช้แอปที่รองรับ SOCKS5\n\n"
                        + "Windows: การตั้งค่า → เครือข่าย → Proxy\n\n"
                        + "ข้อจำกัด: แชร์แบบโปร่งใส (ไม่ต้องตั้ง proxy) บน Android\n"
                        + "โดยไม่มี root ทำไม่ได้ — โหมดนี้ใช้ Proxy ซึ่งเสถียรกว่า"
        );

        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        handler.removeCallbacks(clientPoll);
        handler.post(clientPoll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(clientPoll);
        super.onPause();
    }

    private void updateAuthFieldsEnabled() {
        boolean on = switchAuth != null && switchAuth.isChecked();
        if (edtUser != null) edtUser.setEnabled(on);
        if (edtPass != null) edtPass.setEnabled(on);
        if (btnSaveAuth != null) btnSaveAuth.setEnabled(on);
    }

    private void saveAuth() {
        String user = edtUser != null && edtUser.getText() != null
                ? edtUser.getText().toString().trim() : "";
        String pass = edtPass != null && edtPass.getText() != null
                ? edtPass.getText().toString() : "";
        if (prefs.isShareAuthEnabled() && user.isEmpty()) {
            StyledToast.warning(this, "กรุณาใส่ Username");
            return;
        }
        prefs.setShareUsername(user);
        prefs.setSharePassword(pass);
        StyledToast.success(this, "บันทึกรหัสแล้ว");
        refreshUi();
        if (ProxyVpnService.isServiceRunning(this) && prefs.isShareWifi()) {
            promptReconnectForShare(true);
        }
    }

    private void refreshUi() {
        boolean shareOn = prefs.isShareWifi();
        boolean vpnOn = ProxyVpnService.isServiceRunning(this);

        NetworkShareHelper.ShareInfo info = NetworkShareHelper.collect(this);
        String ip = info.primary != null ? info.primary : "—";
        String addr = ip + ":" + Socks5Server.LOCAL_PORT;

        if (txtAddress != null) txtAddress.setText(addr);
        updateManualCard(ip);

        StringBuilder st = new StringBuilder();
        st.append(shareOn ? "แชร์: เปิด" : "แชร์: ปิด");
        st.append(" | VPN: ").append(vpnOn ? "เชื่อมอยู่" : "ยังไม่เชื่อม");
        if (shareOn && prefs.isShareAuthEnabled()) {
            st.append(" | Auth: เปิด (").append(prefs.getShareUsername()).append(")");
        } else if (shareOn) {
            st.append(" | Auth: ปิด");
        }
        st.append("\n").append(info.hint);
        if (!info.addresses.isEmpty()) {
            st.append("\nIP ที่พบ: ").append(String.join(", ", info.addresses));
        }
        if (txtStatus != null) txtStatus.setText(st.toString());

        updateClientCount();
        renderQr(ip);
    }

    private void updateClientCount() {
        if (txtClients == null) return;
        int n = Socks5Server.getActiveClients();
        boolean vpnOn = ProxyVpnService.isServiceRunning(this);
        if (!vpnOn) {
            txtClients.setText("Client ที่เชื่อมอยู่: — (VPN ยังไม่เชื่อม)");
        } else {
            txtClients.setText("Client ที่เชื่อมอยู่: " + n);
        }
    }

    private void renderQr(String ip) {
        if (imgQr == null) return;
        if (ip == null || ip.equals("—") || ip.isEmpty()) {
            imgQr.setImageDrawable(null);
            if (txtQrHint != null) txtQrHint.setText("ยังไม่มี IP — เปิด Hotspot หรือ Wi‑Fi ก่อน");
            return;
        }
        String payload = buildProxyUri(ip);
        try {
            Bitmap bmp = encodeQr(payload, 512);
            imgQr.setImageBitmap(bmp);
            if (txtQrHint != null) {
                txtQrHint.setText(payload);
            }
        } catch (Exception e) {
            if (txtQrHint != null) txtQrHint.setText("สร้าง QR ไม่สำเร็จ");
        }
    }

    private String buildProxyUri(String ip) {
        // รูปแบบที่แอป proxy หลายตัวอ่านได้
        if (prefs.isShareAuthEnabled()) {
            String u = prefs.getShareUsername();
            String p = prefs.getSharePassword();
            return "socks5://" + u + ":" + p + "@" + ip + ":" + Socks5Server.LOCAL_PORT;
        }
        return "socks5://" + ip + ":" + Socks5Server.LOCAL_PORT;
    }

    private static Bitmap encodeQr(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix = new MultiFormatWriter()
                .encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bmp;
    }

    private void copyAddress() {
        CharSequence t = txtAddress != null ? txtAddress.getText() : null;
        if (t == null || t.toString().contains("—")) {
            StyledToast.warning(this, "ยังไม่มีที่อยู่ให้คัดลอก");
            return;
        }
        String copy = t.toString();
        if (prefs.isShareAuthEnabled()) {
            copy = buildProxyUri(copy.split(":")[0]);
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("socks5", copy));
            StyledToast.success(this, "คัดลอก " + copy + " แล้ว");
        }
    }


    private String currentHost() {
        NetworkShareHelper.ShareInfo info = NetworkShareHelper.collect(this);
        return info.primary != null ? info.primary : "";
    }

    private void updateManualCard(String ip) {
        if (txtManualHost != null) {
            txtManualHost.setText("โฮสต์: " + (ip != null && !ip.isEmpty() && !ip.equals("—") ? ip : "—"));
        }
        if (txtManualPort != null) {
            txtManualPort.setText("พอร์ต: " + Socks5Server.LOCAL_PORT);
        }
        if (txtManualUser != null) {
            if (prefs.isShareAuthEnabled()) {
                txtManualUser.setText("Username: " + prefs.getShareUsername());
            } else {
                txtManualUser.setText("Username: (ไม่ใช้)");
            }
        }
        if (txtManualPass != null) {
            if (prefs.isShareAuthEnabled()) {
                String pass = prefs.getSharePassword();
                String mask = pass.isEmpty() ? "(ว่าง)" : "********";
                txtManualPass.setText("Password: " + mask + "  (กดคัดลอกรหัสได้)");
            } else {
                txtManualPass.setText("Password: (ไม่ใช้)");
            }
        }
    }

    private void copyPlain(String text) {
        if (text == null || text.isEmpty() || text.equals("—")) {
            StyledToast.warning(this, "ยังไม่มีค่าให้คัดลอก");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("proxy", text));
            StyledToast.success(this, "คัดลอกแล้ว: " + text);
        }
    }

    /** แชร์ข้อความตั้งค่า — ใช้เมื่อเครื่องลูกสแกน QR ไม่ได้ */
    private void shareSetupText() {
        String ip = currentHost();
        if (ip.isEmpty()) {
            StyledToast.warning(this, "ยังไม่มี IP — เปิด Hotspot หรือ Wi‑Fi ก่อน");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ตั้งค่า Proxy SOCKS5\n\n");
        sb.append("ประเภท: SOCKS5\n");
        sb.append("โฮสต์: ").append(ip).append("\n");
        sb.append("พอร์ต: ").append(Socks5Server.LOCAL_PORT).append("\n");
        if (prefs.isShareAuthEnabled()) {
            sb.append("Username: ").append(prefs.getShareUsername()).append("\n");
            sb.append("Password: ").append(prefs.getSharePassword()).append("\n");
        } else {
            sb.append("Username/Password: ไม่ต้องใส่\n");
        }
        sb.append("\nหรือ URI:\n").append(buildProxyUri(ip));

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "SOCKS5 Proxy");
        send.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(send, "แชร์การตั้งค่า Proxy"));
    }

    private void promptReconnectForShare(boolean shareEnabled) {
        String title = shareEnabled ? "ต้องเชื่อมต่อใหม่" : "ควรเชื่อมต่อใหม่";
        String msg = shareEnabled
                ? ("การเปลี่ยนโหมดแชร์ / รหัสผ่าน จะมีผลหลัง Reconnect เท่านั้น\n\n"
                + "ต้องการ Reconnect ตอนนี้เลยไหม?")
                : ("ปิดแชร์แล้ว — SOCKS5 ควรกลับไปฟังเฉพาะเครื่องนี้\n\n"
                + "ต้องการ Reconnect ตอนนี้เลยไหม?");

        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Reconnect เลย", (d, w) -> requestReconnect())
                .setNegativeButton("ทีหลัง", (d, w) ->
                        StyledToast.warning(this,
                                "อย่าลืม Reconnect ไม่งั้นการตั้งค่าอาจยังไม่เปลี่ยน"))
                .show();
    }

    private void requestReconnect() {
        if (!ProxyVpnService.isServiceRunning(this)) {
            StyledToast.warning(this, "VPN ยังไม่ได้เชื่อมต่อ");
            return;
        }
        try {
            Intent i = new Intent(this, ProxyVpnService.class);
            i.setAction(ProxyVpnService.ACTION_RECONNECT);
            startService(i);
            StyledToast.success(this, "กำลัง Reconnect...");
            if (switchShare != null) {
                switchShare.postDelayed(this::refreshUi, 1500);
                switchShare.postDelayed(this::refreshUi, 4000);
            }
        } catch (Exception e) {
            StyledToast.error(this, "Reconnect ไม่สำเร็จ: " + e.getMessage());
        }
    }
}
