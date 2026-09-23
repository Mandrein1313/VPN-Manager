package com.example.vpn.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.vpn.R;
import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.util.QrGenerator;
import com.google.android.material.button.MaterialButton;

public class QrShareActivity extends AppCompatActivity {

    public static final String EXTRA_PROFILE_ID = "profile_id";

    private ImageView imgQr;
    private TextView txtProfileName;
    private TextView txtProfileInfo;
    private TextView txtHint;
    private MaterialButton btnShare;
    private MaterialButton btnCopy;
    private ImageButton btnClose;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_share);

        imgQr = findViewById(R.id.imgQr);
        txtProfileName = findViewById(R.id.txtProfileName);
        txtProfileInfo = findViewById(R.id.txtProfileInfo);
        txtHint = findViewById(R.id.txtHint);
        btnShare = findViewById(R.id.btnShare);
        btnCopy = findViewById(R.id.btnCopy);
        btnClose = findViewById(R.id.btnClose);

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }

        long profileId = getIntent().getLongExtra(EXTRA_PROFILE_ID, -1L);
        if (profileId <= 0) {
            Toast.makeText(this, "ไม่พบโปรไฟล์", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        repo.getById(profileId, p -> {
            if (p == null) {
                Toast.makeText(this, "ไม่พบโปรไฟล์", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            renderQr(p);
        });
    }

    private void renderQr(Profile p) {
        String payload = QrGenerator.buildPayload(p);
        if (payload == null) {
            Toast.makeText(this, "สร้าง QR ไม่สำเร็จ", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Bitmap qr = QrGenerator.generate(payload, 800);
        if (qr == null) {
            Toast.makeText(this, "สร้าง QR ไม่สำเร็จ", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        imgQr.setImageBitmap(qr);
        txtProfileName.setText(p.name);
        txtProfileInfo.setText(p.host + ":" + p.port + " · " + p.protocol.displayName);
        txtHint.setText("Scan this from another device to import the profile");

        btnShare.setOnClickListener(v -> shareQr(qr, p));
        btnCopy.setOnClickListener(v -> copyPayload(payload));
    }

    private void shareQr(Bitmap qr, Profile p) {
        try {
            java.io.File cacheDir = new java.io.File(getCacheDir(), "qr");
            if (!cacheDir.exists()) cacheDir.mkdirs();

            java.io.File qrFile = new java.io.File(cacheDir,
                    "qr-" + System.currentTimeMillis() + ".png");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(qrFile);
            qr.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    qrFile);

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/png");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "VPN Profile: " + p.name);
            share.putExtra(Intent.EXTRA_TEXT,
                    "แชร์โปรไฟล์ VPN: " + p.name + "\nHost: " + p.host + ":" + p.port);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(share, "แชร์ QR"));

        } catch (Exception e) {
            Toast.makeText(this, "แชร์ไม่สำเร็จ: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void copyPayload(String payload) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "VPN Profile", payload));
            Toast.makeText(this, "คัดลอกลิงก์แล้ว", Toast.LENGTH_SHORT).show();
        }
    }
}