package com.example.vpn.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class QrShareActivity extends AppCompatActivity {

    public static final String EXTRA_PROFILE_ID = "profile_id";

    private ProfileViewModel viewModel;
    private Profile targetProfile;
    private Bitmap qrBitmap;

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
        MaterialButton btnSave = findViewById(R.id.btnSave);

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
            renderQr(p, btnShare, btnCopy, btnSave);
        });
    }

    private void renderQr(Profile p, MaterialButton btnShare,
                          MaterialButton btnCopy, MaterialButton btnSave) {
        String payload = QrPayload.encode(p);
        if (payload == null) {
            Toast.makeText(this, "สร้าง QR ไม่สำเร็จ", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        qrBitmap = generateQr(payload, 800);
        if (qrBitmap == null) {
            Toast.makeText(this, "สร้าง QR ไม่สำเร็จ", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        imgQr.setImageBitmap(qrBitmap);
        txtProfileName.setText(p.name);
        txtProfileInfo.setText(p.host + ":" + p.port + " · " + p.protocol.displayName);

        // ⭐ Long-press ที่ QR → แสดง Dialog
        imgQr.setOnLongClickListener(v -> {
            showQrActions(payload);
            return true;
        });

        // ⭐ Share
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> shareQr(qrBitmap, p));
        }

        // ⭐ Copy payload
        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> copyPayload(payload));
        }

        // ⭐ Save to gallery
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> saveToGallery(qrBitmap, p));
        }
    }

    // ============================================================
    // ⭐ Generate QR
    // ============================================================
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

    // ============================================================
    // ⭐ Long-press Actions
    // ============================================================
    private void showQrActions(String payload) {
        String[] options = {
                "📤  แชร์เป็นรูป",
                "💾  บันทึกลง Gallery",
                "📋  คัดลอกข้อมูล",
                "📝  แชร์เป็นข้อความ"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("ตัวเลือก QR")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: shareQr(qrBitmap, targetProfile);
                            break;
                        case 1: saveToGallery(qrBitmap, targetProfile);
                            break;
                        case 2: copyPayload(payload);
                            break;
                        case 3: shareAsText(payload, targetProfile);
                            break;
                    }
                })
                .show();
    }

    // ============================================================
    // ⭐ Share QR
    // ============================================================
    private void shareQr(Bitmap qr, Profile p) {
        try {
            File cacheDir = new File(getCacheDir(), "qr");
            if (!cacheDir.exists()) cacheDir.mkdirs();

            File qrFile = new File(cacheDir,
                    "qr-" + System.currentTimeMillis() + ".png");
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
                    "แชร์โปรไฟล์ VPN: " + p.name + "\n"
                            + "Host: " + p.host + ":" + p.port + "\n"
                            + "Protocol: " + p.protocol.displayName);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(share, "แชร์ QR"));

        } catch (Exception e) {
            Toast.makeText(this, "แชร์ไม่สำเร็จ: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ============================================================
    // ⭐ Save to Gallery
    // ============================================================
    private void saveToGallery(Bitmap qr, Profile p) {
        try {
            String fileName = "VPN-QR-" + p.name + "-"
                    + System.currentTimeMillis() + ".png";

            OutputStream os;
            Uri uri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ → MediaStore
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/VPN Manager");

                uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("ไม่สามารถสร้างไฟล์ได้");

                os = getContentResolver().openOutputStream(uri);
            } else {
                // Android 9 ลงไป → File
                File picturesDir = new File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_PICTURES),
                        "VPN Manager");
                if (!picturesDir.exists()) picturesDir.mkdirs();

                File qrFile = new File(picturesDir, fileName);
                os = new FileOutputStream(qrFile);

                uri = Uri.fromFile(qrFile);
            }

            if (os == null) throw new Exception("ไม่สามารถเขียนไฟล์ได้");

            qr.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.flush();
            os.close();

            Toast.makeText(this,
                    "✅ บันทึกแล้ว: Pictures/VPN Manager",
                    Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this,
                    "❌ บันทึกไม่สำเร็จ: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ============================================================
    // ⭐ Copy payload
    // ============================================================
    private void copyPayload(String payload) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("VPN Profile", payload));
            Toast.makeText(this,
                    "📋 คัดลอกลิงก์แล้ว — paste ที่ไหนก็ได้",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ============================================================
    // ⭐ Share as Text
    // ============================================================
    private void shareAsText(String payload, Profile p) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, "VPN Profile: " + p.name);
            share.putExtra(Intent.EXTRA_TEXT,
                    "VPN Profile: " + p.name + "\n\n"
                            + "Host: " + p.host + ":" + p.port + "\n"
                            + "Protocol: " + p.protocol.displayName + "\n\n"
                            + "Payload (paste ใน VPN Manager):\n"
                            + payload);
            startActivity(Intent.createChooser(share, "แชร์ข้อมูล"));
        } catch (Exception e) {
            Toast.makeText(this, "แชร์ไม่สำเร็จ",
                    Toast.LENGTH_SHORT).show();
        }
    }
}