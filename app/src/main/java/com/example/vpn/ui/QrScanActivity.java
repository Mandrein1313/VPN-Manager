package com.example.vpn.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.example.vpn.R;
import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.util.StyledToast;
import com.example.vpn.util.QrPayload;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.io.InputStream;
import java.util.List;

public class QrScanActivity extends AppCompatActivity {

    private DecoratedBarcodeView barcodeView;
    private ImageButton btnClose;
    private ImageButton btnFlash;
    private MaterialButton btnGallery;

    private ProfileViewModel viewModel;
    private boolean handled = false;
    private boolean flashOn = false;

    // ⭐ Launcher: ขอ Camera Permission
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            startCamera();
                        } else {
                            showPermissionDeniedDialog();
                        }
                    });

    // ⭐ Launcher: เลือกรูป
    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri != null) {
                            decodeFromImage(uri);
                        } else {
                            barcodeView.resume();
                        }
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_qr);

        barcodeView = findViewById(R.id.barcodeView);
        btnClose = findViewById(R.id.btnClose);
        btnFlash = findViewById(R.id.btnFlash);
        btnGallery = findViewById(R.id.btnGallery);

        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        viewModel = new ViewModelProvider(this, new ProfileViewModelFactory(repo))
                .get(ProfileViewModel.class);

        // ⭐ ปุ่มปิด
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }

        // ⭐ ปุ่มแฟลช
        if (btnFlash != null) {
            btnFlash.setOnClickListener(v -> toggleFlash());
        }

        // ⭐ ปุ่มเลือกรูป
        if (btnGallery != null) {
            btnGallery.setOnClickListener(v -> {
                barcodeView.pause();
                pickImageLauncher.launch("image/*");
            });
        }

        // ⭐ ตรวจสอบ Permission ก่อน
        if (hasCameraPermission()) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    // ============================================================
    // ⭐ ตรวจสอบ Camera Permission
    // ============================================================
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // ============================================================
    // ⭐ เริ่มกล้อง
    // ============================================================
    private void startCamera() {
        barcodeView.setStatusText("วาง QR ให้อยู่ในกรอบ");
        barcodeView.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (handled) return;
                if (result == null || result.getText() == null) return;

                handled = true;
                barcodeView.pause();
                handleScannedContent(result.getText());
            }

            @Override
            public void possibleResultPoints(List<com.google.zxing.ResultPoint> points) {}
        });
        barcodeView.resume();
    }

    // ============================================================
    // ⭐ แสดง Dialog ถ้าไม่อนุญาต
    // ============================================================
    private void showPermissionDeniedDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("🔒 ต้องการสิทธิ์ใช้กล้อง")
                .setMessage("แอปต้องใช้กล้องเพื่อสแกน QR Code\n\n"
                        + "คุณสามารถเปิดได้ที่:\n"
                        + "Settings → Apps → VPN Manager → Permissions → Camera")
                .setPositiveButton("เปิด Settings", (d, w) -> {
                    try {
                        Intent intent = new Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception ignored) {}
                    finish();
                })
                .setNegativeButton("ปิด", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    // ============================================================
    // ⭐ Toggle Flash
    // ============================================================
    private void toggleFlash() {
        try {
            if (flashOn) {
                barcodeView.setTorchOff();
                flashOn = false;
                StyledToast.info(this, "ปิดแฟลช");
            } else {
                barcodeView.setTorchOn();
                flashOn = true;
                StyledToast.info(this, "เปิดแฟลช");
            }
        } catch (Exception e) {
            StyledToast.info(this, "อุปกรณ์ไม่รองรับแฟลช");
        }
    }

    // ============================================================
    // ⭐ Decode จากรูป
    // ============================================================
    private void decodeFromImage(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) {
                StyledToast.info(this, "ไม่สามารถเปิดรูปได้");
                barcodeView.resume();
                return;
            }

            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();

            if (bitmap == null) {
                StyledToast.error(this, "อ่านรูปไม่ได้");
                barcodeView.resume();
                return;
            }

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(source));

            try {
                com.google.zxing.Result result = new MultiFormatReader().decode(binary);
                if (result != null && result.getText() != null) {
                    handleScannedContent(result.getText());
                    return;
                }
            } catch (Exception ignored) {}

            new MaterialAlertDialogBuilder(this)
                    .setTitle("❌ ไม่พบ QR Code")
                    .setMessage("ไม่พบ QR Code ในรูปภาพ\n\n"
                            + "ลองใหม่อีกครั้ง หรือใช้รูปที่คมชัดกว่านี้")
                    .setPositiveButton("ลองใหม่", (d, w) -> barcodeView.resume())
                    .setNegativeButton("ปิด", (d, w) -> finish())
                    .show();

        } catch (Exception e) {
            StyledToast.error(this, "ผิดพลาด: " + e.getMessage());
            barcodeView.resume();
        }
    }

    // ============================================================
    // ⭐ Handle content
    // ============================================================
    private void handleScannedContent(String content) {
        QrPayload.DecodeResult result = QrPayload.decode(content);

        if (!result.success) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("❌ QR ไม่ถูกต้อง")
                    .setMessage((result.error != null ? result.error : "รูปแบบไม่ถูกต้อง")
                            + "\n\nข้อมูล:\n"
                            + (content.length() > 100
                                ? content.substring(0, 100) + "..."
                                : content))
                    .setPositiveButton("ลองใหม่", (d, w) -> {
                        handled = false;
                        barcodeView.resume();
                    })
                    .setNegativeButton("ปิด", (d, w) -> finish())
                    .setCancelable(false)
                    .show();
            return;
        }

        if (result.profiles.size() == 1) {
            showConfirmDialog(result.profiles.get(0));
        } else {
            showMultiDialog(result.profiles);
        }
    }

    private void showConfirmDialog(Profile p) {
        String msg = "ชื่อ: " + p.name + "\n"
                + "Host: " + p.host + "\n"
                + "Port: " + p.port + "\n"
                + "User: " + (p.user.isEmpty() ? "(ว่าง)" : p.user) + "\n"
                + "Protocol: " + p.protocol.displayName + "\n\n"
                + "ต้องการนำเข้าโปรไฟล์นี้หรือไม่?";

        new MaterialAlertDialogBuilder(this)
                .setTitle("✅ พบโปรไฟล์")
                .setMessage(msg)
                .setPositiveButton("นำเข้า", (d, w) -> {
                    p.id = 0;
                    viewModel.save(p, id -> {
                        StyledToast.success(QrScanActivity.this, "นำเข้าสำเร็จ: " + p.name);
                        setResult(RESULT_OK);
                        finish();
                    });
                })
                .setNegativeButton("สแกนใหม่", (d, w) -> {
                    handled = false;
                    barcodeView.resume();
                })
                .setNeutralButton("ยกเลิก", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void showMultiDialog(List<Profile> profiles) {
        String[] items = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            Profile p = profiles.get(i);
            items[i] = p.name + " — " + p.host + ":" + p.port;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("✅ พบ " + profiles.size() + " โปรไฟล์")
                .setItems(items, (d, which) -> {
                    Profile p = profiles.get(which);
                    p.id = 0;
                    viewModel.save(p, id -> {
                        StyledToast.success(QrScanActivity.this, "นำเข้าสำเร็จ: " + p.name);
                        setResult(RESULT_OK);
                        finish();
                    });
                })
                .setNegativeButton("สแกนใหม่", (d, w) -> {
                    handled = false;
                    barcodeView.resume();
                })
                .setCancelable(false)
                .show();
    }

    // ============================================================
    // Lifecycle
    // ============================================================
    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission()) {
            barcodeView.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        barcodeView.pause();
        if (flashOn) {
            try {
                barcodeView.setTorchOff();
                flashOn = false;
            } catch (Exception ignored) {}
        }
    }
}