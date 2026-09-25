package com.example.vpn.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.vpn.R;
import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.util.StyledToast;
import com.example.vpn.util.BackupManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BackupActivity extends AppCompatActivity {

    private ProfileViewModel viewModel;
    private BackupManager backupManager;

    private TextView txtProfileCount;
    private TextView txtBypassCount;
    private TextView txtLastBackup;

    private MaterialButton btnBackup;
    private MaterialButton btnRestore;

    private View cardBackupInfo;
    private View cardRestoreInfo;

    private List<Profile> cachedProfiles = new ArrayList<>();

    // ⭐ Launcher: สร้างไฟล์ใหม่ (SAF)
    private final ActivityResultLauncher<String> createFileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("application/json"),
                    uri -> {
                        if (uri != null) {
                            writeBackupToUri(uri);
                        }
                    });

    // ⭐ Launcher: เปิดไฟล์ (SAF)
    private final ActivityResultLauncher<String[]> openFileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) {
                            readBackupFromUri(uri);
                        }
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup);

        backupManager = new BackupManager(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        txtProfileCount = findViewById(R.id.txtProfileCount);
        txtBypassCount = findViewById(R.id.txtBypassCount);
        txtLastBackup = findViewById(R.id.txtLastBackup);

        btnBackup = findViewById(R.id.btnBackup);
        btnRestore = findViewById(R.id.btnRestore);

        cardBackupInfo = findViewById(R.id.cardBackupInfo);
        cardRestoreInfo = findViewById(R.id.cardRestoreInfo);

        // ViewModel
        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        viewModel = new ViewModelProvider(this, new ProfileViewModelFactory(repo))
                .get(ProfileViewModel.class);

        viewModel.getProfiles().observe(this, list -> {
            cachedProfiles = (list != null) ? new ArrayList<>(list) : new ArrayList<>();
            updateCounts();
        });

        // ⭐ Backup
        btnBackup.setOnClickListener(v -> showBackupOptions());

        // ⭐ Restore
        btnRestore.setOnClickListener(v -> showRestoreOptions());
    }

    private void updateCounts() {
        if (txtProfileCount != null) {
            txtProfileCount.setText(cachedProfiles.size() + " โปรไฟล์");
        }
        if (txtBypassCount != null) {
            com.example.vpn.util.BypassPrefs bypassPrefs =
                    new com.example.vpn.util.BypassPrefs(this);
            txtBypassCount.setText(bypassPrefs.getCount() + " แอป");
        }
    }

    // ============================================================
    // ⭐ Backup
    // ============================================================
    private void showBackupOptions() {
        String[] options = {
                "💾  บันทึกเป็นไฟล์",
                "📤  แชร์เป็นข้อความ",
                "📋  คัดลอก JSON"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("สำรองข้อมูล")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: saveToFile();
                            break;
                        case 1: shareAsText();
                            break;
                        case 2: copyToClipboard();
                            break;
                    }
                })
                .show();
    }

    private void saveToFile() {
        String fileName = BackupManager.generateFileName();
        createFileLauncher.launch(fileName);
    }

    private void writeBackupToUri(Uri uri) {
        try {
            String json = backupManager.exportAll(cachedProfiles);
            if (json == null) {
                StyledToast.error(this, "สร้าง backup ไม่สำเร็จ");
                return;
            }

            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) {
                StyledToast.info(this, "ไม่สามารถเขียนไฟล์ได้");
                return;
            }

            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            StyledToast.success(this, "✅ Backup สำเร็จ: " + cachedProfiles.size() + " โปรไฟล์");

        } catch (Exception e) {
            StyledToast.error(this, "❌ ผิดพลาด: " + e.getMessage());
        }
    }

    private void shareAsText() {
        String json = backupManager.exportAll(cachedProfiles);
        if (json == null) {
            StyledToast.error(this, "สร้าง backup ไม่สำเร็จ");
            return;
        }

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "VPN Manager Backup");
        share.putExtra(Intent.EXTRA_TEXT, json);
        startActivity(Intent.createChooser(share, "แชร์ Backup"));
    }

    private void copyToClipboard() {
        String json = backupManager.exportAll(cachedProfiles);
        if (json == null) {
            StyledToast.error(this, "สร้าง backup ไม่สำเร็จ");
            return;
        }

        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(
                        CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "VPN Backup", json));
            StyledToast.success(this, "คัดลอกแล้ว — paste ที่ไหนก็ได้");
        }
    }

    // ============================================================
    // ⭐ Restore
    // ============================================================
    private void showRestoreOptions() {
        String[] options = {
                "📂  เลือกไฟล์",
                "📋  วาง JSON จาก Clipboard"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("กู้คืนข้อมูล")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: openFilePicker();
                            break;
                        case 1: pasteFromClipboard();
                            break;
                    }
                })
                .show();
    }

    private void openFilePicker() {
        openFileLauncher.launch(new String[]{
                "application/json",
                "text/plain",
                "*/*"
        });
    }

    private void readBackupFromUri(Uri uri) {
        BackupManager.RestoreResult result = backupManager.importAll(uri);
        handleRestoreResult(result);
    }

    private void pasteFromClipboard() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(
                        CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
            StyledToast.warning(this, "Clipboard ว่างเปล่า");
            return;
        }

        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            StyledToast.warning(this, "Clipboard ว่างเปล่า");
            return;
        }

        BackupManager.RestoreResult result =
                backupManager.importFromJson(text.toString());
        handleRestoreResult(result);
    }

    private void handleRestoreResult(BackupManager.RestoreResult result) {
        if (!result.success) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("❌ กู้คืนไม่สำเร็จ")
                    .setMessage(result.error)
                    .setPositiveButton("ตกลง", null)
                    .show();
            return;
        }

        int profileCount = result.getProfileCount();
        int bypassCount = result.getBypassCount();

        if (profileCount == 0 && bypassCount == 0
                && !result.hasSettings && !result.hasTheme) {
            StyledToast.warning(this, "ไฟล์ไม่มีข้อมูล");
            return;
        }

        StringBuilder msg = new StringBuilder();
        msg.append("พบข้อมูล:\n\n");
        if (profileCount > 0) {
            msg.append("• ").append(profileCount).append(" โปรไฟล์\n");
        }
        if (bypassCount > 0) {
            msg.append("• ").append(bypassCount).append(" แอป Bypass\n");
        }
        if (result.hasSettings) {
            msg.append("• การตั้งค่า VPN\n");
        }
        if (result.hasTheme) {
            msg.append("• ธีม\n");
        }
        msg.append("\nต้องการนำเข้าหรือไม่?");

        new MaterialAlertDialogBuilder(this)
                .setTitle("✅ ยืนยันการกู้คืน")
                .setMessage(msg.toString())
                .setPositiveButton("นำเข้า", (d, w) -> {
                    applyRestore(result);
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void applyRestore(BackupManager.RestoreResult result) {
        // ⭐ Apply settings (bypass, vpn prefs, theme)
        backupManager.applySettings(result);

        // ⭐ Import profiles
        int imported = 0;
        if (!result.profiles.isEmpty()) {
            for (Profile p : result.profiles) {
                p.id = 0;   // ⭐ reset ID — ป้องกัน conflict
                viewModel.save(p, id -> {
                    // นับเสร็จ — แสดง toast หลังสุดท้าย
                });
                imported++;
            }
        }

        final int finalImported = imported;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    StyledToast.success(this, "✅ นำเข้าสำเร็จ " + finalImported + " โปรไฟล์");

                    // Refresh
                    updateCounts();

                    // ⭐ ถ้ามีธีม → apply
                    if (result.hasTheme) {
                        com.example.vpn.util.ThemePrefs.applyMode(result.themeMode);
                    }
                }, 500);
    }
}