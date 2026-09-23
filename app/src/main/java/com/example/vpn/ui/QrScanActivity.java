package com.example.vpn.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.io.InputStream;

public class QrScanActivity extends AppCompatActivity {

    private DecoratedBarcodeView barcodeView;
    private ImageButton btnClose;
    private ImageButton btnFlash;
    private ImageButton btnGallery;   // ⭐ ปุ่มเลือกจากแกลเลอรี

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
                            .setMessage("แอปต้องใช้กล้องเพื่อสแกน QR Code\nหรือใช้ปุ่มเลือกจากแกลเลอรีแทนได้")
                            .setPositiveButton("ตกลง", null)
                            .show();
                }
            });

    // ⭐ เลือกภาพจากแกลเลอรี
    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    decodeQrFromImage(uri);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_qr);

        barcodeView = findViewById(R.id.barcodeView);
        btnClose = findViewById(R.id.btnClose);
        btnFlash = findViewById(R.id.btnFlash);
        btnGallery = findViewById(R.id.btnGallery);   // ⭐ ต้องมีใน layout

        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        viewModel = new ViewModelProvider(this, new ProfileViewModelFactory(repo))
                .get(ProfileViewModel.class);

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }

        if (btnFlash != null) {
            btnFlash.setOnClickListener(v -> toggleFlash());
        }

        // ⭐ ปุ่มเลือกจากแกลเลอรี
        if (btnGallery != null) {
            btnGallery.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        }

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
                handleScannedContent(result.getText());
            }

            @Override
            public void possibleResultPoints(
                    java.util.List<com.google.zxing.ResultPoint> resultPoints) {
            }
        });
    }

    // ============================================================
    // ⭐ สแกนจากรูปภาพ
    // ============================================================
    private void decodeQrFromImage(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) {
                Toast.makeText(this, "เปิดรูปไม่ได้", Toast.LENGTH_SHORT).show();
                return;
            }

            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();

            if (bitmap == null) {
                Toast.makeText(this, "โหลดรูปไม่ได้", Toast.LENGTH_SHORT).show();
                return;
            }

            // ลดขนาดถ้ารูปใหญ่เกินไป (ช่วยให้ decode เร็วขึ้น)
            int maxSize = 1024;
            if (bitmap.getWidth() > maxSize || bitmap.getHeight() > maxSize) {
                float scale = Math.min(
                        (float) maxSize / bitmap.getWidth(),
                        (float) maxSize / bitmap.getHeight());
                int w = Math.round(bitmap.getWidth() * scale);
                int h = Math.round(bitmap.getHeight() * scale);
                bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true);
            }

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));

            Result result = new MultiFormatReader().decode(binaryBitmap);
            String content = result.getText();

            if (content != null && !content.isEmpty()) {
                handleScannedContent(content);
            } else {
                Toast.makeText(this, "ไม่พบ QR Code ในรูป", Toast.LENGTH_SHORT).show();
            }

        } catch (com.google.zxing.NotFoundException e) {
            Toast.makeText(this, "ไม่พบ QR Code ในรูปภาพ", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "อ่าน QR จากรูปไม่สำเร็จ: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================
    // ⭐ จัดการผลลัพธ์ที่สแกนได้ (ทั้งจากกล้องและจากรูป)
    // ============================================================
    private void handleScannedContent(String content) {
        Profile p = QrGenerator.parsePayload(content);

        if (p == null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("❌ QR ไม่ถูกต้อง")
                    .setMessage("QR นี้ไม่ใช่โปรไฟล์ VPN Manager\n\n"
                            + "ข้อมูลที่พบ:\n"
                            + (content.length() > 100
                                ? content.substring(0, 100) + "..."
                                : content))
                    .setPositiveButton("ลองใหม่", (d, w) -> {
                        handled = false;
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED) {
                            barcodeView.resume();
                        }
                    })
                    .setNegativeButton("ปิด", (d, w) -> finish())
                    .setCancelable(false)
                    .show();
            return;
        }

        showConfirmDialog(p);
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
                    // ⭐ ตรวจชื่อซ้ำก่อนบันทึก
                    viewModel.getRepo().findByName(p.name, 0L, dup -> {
                        if (dup != null) {
                            new MaterialAlertDialogBuilder(QrScanActivity.this)
                                    .setTitle("ชื่อซ้ำ")
                                    .setMessage("มีโปรไฟล์ชื่อ \"" + p.name + "\" อยู่แล้ว\n\n"
                                            + "ต้องการอัปเดตของเดิม หรือสร้างชื่อใหม่?")
                                    .setPositiveButton("อัปเดตของเดิม", (d2, w2) -> {
                                        p.id = dup.id;
                                        viewModel.save(p, id -> {
                                            Toast.makeText(QrScanActivity.this,
                                                    "อัปเดตแล้ว: " + p.name, Toast.LENGTH_LONG).show();
                                            setResult(RESULT_OK);
                                            finish();
                                        });
                                    })
                                    .setNegativeButton("สร้างชื่อใหม่", (d2, w2) -> {
                                        p.id = 0;
                                        p.name = p.name + " (" + System.currentTimeMillis() % 10000 + ")";
                                        viewModel.save(p, id -> {
                                            Toast.makeText(QrScanActivity.this,
                                                    "นำเข้าสำเร็จ: " + p.name, Toast.LENGTH_LONG).show();
                                            setResult(RESULT_OK);
                                            finish();
                                        });
                                    })
                                    .setNeutralButton("ยกเลิก", null)
                                    .show();
                        } else {
                            p.id = 0;
                            viewModel.save(p, id -> {
                                Toast.makeText(QrScanActivity.this,
                                        "นำเข้าสำเร็จ: " + p.name, Toast.LENGTH_LONG).show();
                                setResult(RESULT_OK);
                                finish();
                            });
                        }
                    });
                })
                .setNegativeButton("สแกนใหม่", (d, w) -> {
                    handled = false;
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        barcodeView.resume();
                    }
                })
                .setNeutralButton("ยกเลิก", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
