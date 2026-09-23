package com.example.vpn.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import com.example.vpn.R;
import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.util.QrPayload;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

public class QrShareActivity extends AppCompatActivity {

    public static final String EXTRA_PROFILE_ID = "profile_id";

    private ProfileViewModel viewModel;
    private Profile targetProfile;

    private ImageView imgQr;
    private TextView txtProfileName;
    private TextView txtProfileInfo;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_share);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        imgQr = findViewById(R.id.imgQr);
        txtProfileName = findViewById(R.id.txtProfileName);
        txtProfileInfo = findViewById(R.id.txtProfileInfo);

        MaterialButton btnShare = findViewById(R.id.btnShare);
        MaterialButton btnCopy = findViewById(R.id.btnCopy);

        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        viewModel = new ViewModelProvider(this, new ProfileViewModelFactory(repo))
                .get(ProfileViewModel.class);

        long profileId = getIntent().getLongExtra(EXTRA_PROFILE_ID, -1L);
        if (profileId <= 0) {
            Toast.makeText(this, "ไม่พบโปรไฟล์", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        viewModel.getRepo().getById(profileId, p -> {
            if (p == null) {
                Toast.makeText(this, "ไม่พบโปรไฟล์", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            targetProfile = p;
            renderQr(p, btnShare, btnCopy);
        });
    }

    private void renderQr(Profile p, MaterialButton btnShare, MaterialButton btnCopy) {
        String payload = QrPayload.encode(p);
        if (payload == null) {
            Toast.makeText(this, "สร้าง QR ไม่สำเร็จ", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Bitmap qr = generateQr(payload, 800);
        if (qr == null) {
            Toast.makeText(this, "สร้าง QR ไม่สำเร็จ", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        imgQr.setImageBitmap(qr);
        txtProfileName.setText(p.name);
        txtProfileInfo.setText(p.host + ":" + p.port + " · " + p.protocol.displayName);

        btnShare.setOnClickListener(v -> shareQr(qr, p));
        btnCopy.setOnClickListener(v -> copyPayload(payload));
    }

    private Bitmap generateQr(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION,
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);

            BitMatrix matrix = new MultiFormatWriter().encode(
                    content, BarcodeFormat.QR_CODE, size, size, hints);

            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y)
                            ? Color.BLACK : Color.WHITE;
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;

        } catch (Exception e) {
            return null;
        }
    }

    private void shareQr(Bitmap qr, Profile p) {
        try {
            File cacheDir = new File(getCacheDir(), "qr");
            if (!cacheDir.exists()) cacheDir.mkdirs();

            File qrFile = new File(cacheDir, "qr-" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(qrFile);
            qr.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    qrFile);

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/png");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "VPN Profile: " + p.name);
            share.putExtra(Intent.EXTRA_TEXT,
                    "แชร์โปรไฟล์ VPN: " + p.name + "\n" +
                    "Host: " + p.host + ":" + p.port);
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
