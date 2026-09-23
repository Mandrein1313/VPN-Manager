package com.example.vpn.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.Toast;

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
import com.example.vpn.util.QrGenerator;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

public class QrScanActivity extends AppCompatActivity {

    private DecoratedBarcodeView barcodeView;
    private ImageButton btnClose;
    private ImageButton btnFlash;

    private ProfileViewModel viewModel;
    private boolean handled = false;
    private boolean flashOn = false;

    // ⭐ ขอสิทธิ์กล้อง
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startScanning();
                } else {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("ต้องการสิทธิ์กล้อง")
                            .setMessage("แอปต้องใช้กล้องเพื่อสแกน QR Code\nกรุณาอนุญาตสิทธิ์กล้องในการตั้งค่า")
                            .setPositiveButton("ปิด", (d, w) -> finish())
                            .setCancelable(false)
                            .show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_qr);

        barcodeView = findViewById(R.id.barcodeView);
        btnClose = findViewById(R.id.btnClose);
        btnFlash = findViewById(R.id.btnFlash);

        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        viewModel = new ViewModelProvider(this, new ProfileViewModelFactory(repo))
                .get(ProfileViewModel.class);

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }

        if (btnFlash != null) {
            btnFlash.setOnClickListener(v -> toggleFlash());
        }

        // ⭐ ตรวจสอบสิทธิ์กล้องก่อน
        checkCameraPermission();
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startScanning();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startScanning() {
        barcodeView.setStatusText("วาง QR ให้อยู่ในกรอบ");
        barcodeView.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (handled) return;
                if (result == null || result.getText() == null) return;

                handled = true;
                barcodeView.pause();

                String content = result.getText();
                Profile p = QrGenerator.parsePayload(content);

                if (p == null) {
                    new MaterialAlertDialogBuilder(QrScanActivity.this)
                            .setTitle("❌ QR ไม่ถูกต้อง")
                            .setMessage("QR นี้ไม่ใช่โปรไฟล์ VPN Manager\n\n"
                                    + "ข้อมูลที่พบ:\n"
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

                showConfirmDialog(p);
            }

            @Override
            public void possibleResultPoints(
                    java.util.List<com.google.zxing.ResultPoint> resultPoints) {
                // ไม่ใช้
            }
        });
    }

    private void toggleFlash() {
        try {
            if (flashOn) {
                barcodeView.setTorchOff();
                flashOn = false;
                Toast.makeText(this, "ปิดแฟลช", Toast.LENGTH_SHORT).show();
            } else {
                barcodeView.setTorchOn();
                flashOn = true;
                Toast.makeText(this, "เปิดแฟลช", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "อุปกรณ์ไม่รองรับแฟลช", Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(QrScanActivity.this,
                                "นำเข้าสำเร็จ: " + p.name,
                                Toast.LENGTH_LONG).show();
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

    @Override
    protected void onResume() {
        super.onResume();
        // เริ่มสแกนเฉพาะเมื่อมีสิทธิ์แล้ว
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
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