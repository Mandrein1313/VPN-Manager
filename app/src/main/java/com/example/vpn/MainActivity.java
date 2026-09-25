package com.example.vpn;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.ui.ConnectionActivity;
import com.example.vpn.ui.CrashLogActivity;
import com.example.vpn.ui.LogViewerActivity;
import com.example.vpn.ui.ProfileAdapter;
import com.example.vpn.ui.ProfileEditActivity;
import com.example.vpn.ui.ProfileViewModel;
import com.example.vpn.ui.ProfileViewModelFactory;
import com.example.vpn.ui.QrScanActivity;
import com.example.vpn.ui.QrShareActivity;
import com.example.vpn.util.ConfigParser;
import com.example.vpn.util.CrashHandler;
import com.example.vpn.util.ProfileExporter;
import com.example.vpn.util.ProfileImporter;
import com.example.vpn.util.ThemePrefs;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements ProfileAdapter.Listener {

    public static final String EXTRA_PROFILE_ID = "profile_id";
    public static final int REQ_EDIT = 100;

    private ProfileViewModel viewModel;
    private ProfileAdapter adapter;
    private LinearLayout emptyState;
    private RecyclerView recycler;

    private View btnAddConfig;
    private View btnImportClipboard;
    private View btnBack;

    private View navHome, navLogs, navMore;

    private List<Profile> cachedProfiles = new ArrayList<>();

    private final ActivityResultLauncher<String> notifPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> { /* ไม่เป็นไร */ });

    private final ActivityResultLauncher<Intent> addProfileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            Toast.makeText(this, "บันทึกคอนฟิกแล้ว", Toast.LENGTH_SHORT).show();
                            // LiveData จะรีเฟรชรายการเอง
                        }
                    });

    // ⭐ Launcher สำหรับรับผลการสแกน QR
    private final ActivityResultLauncher<Intent> qrScanLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            Toast.makeText(this, "นำเข้าจาก QR สำเร็จ",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        CrashHandler.install(this);
        super.onCreate(savedInstanceState);

        // ⭐ ถ้าเปิดจาก Quick Settings Tile → เปิด ConnectionActivity ทันที
        if (getIntent() != null && getIntent().getBooleanExtra("from_tile", false)) {
            Intent connIntent = new Intent(this, ConnectionActivity.class);
            connIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(connIntent);
            finish();
            return;
        }

        // ⭐ เปิดจาก Notification "Stats"
        if (getIntent() != null && getIntent().getBooleanExtra("show_stats", false)) {
            Intent connIntent = new Intent(this, ConnectionActivity.class);
            connIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(connIntent);
            finish();
            return;
        }

        setContentView(R.layout.activity_profile_list);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }

        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        viewModel = new ViewModelProvider(this, new ProfileViewModelFactory(repo))
                .get(ProfileViewModel.class);

        // ===== Bind views =====
        btnBack = findViewById(R.id.btnBack);
        emptyState = findViewById(R.id.emptyState);
        recycler = findViewById(R.id.recyclerProfiles);
        btnAddConfig = findViewById(R.id.btnAddConfig);
        btnImportClipboard = findViewById(R.id.btnImportClipboard);
        navHome = findViewById(R.id.navHome);
        navLogs = findViewById(R.id.navLogs);
        navMore = findViewById(R.id.navMore);

        // ⭐ ซ่อน fabConfirm ถ้ามี
        View fabConfirm = findViewById(R.id.fabConfirm);
        if (fabConfirm != null) fabConfirm.setVisibility(View.GONE);

        // ===== Toolbar =====
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayShowTitleEnabled(false);
            }
        }

        // ===== Back button =====
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        // ===== List =====
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProfileAdapter(this);
        recycler.setAdapter(adapter);

        // ===== Empty state buttons =====
        btnAddConfig.setOnClickListener(v -> showAddConfigurationMenu());

        btnImportClipboard.setOnClickListener(v -> importFromClipboard());

        // ===== Bottom nav =====
        navHome.setOnClickListener(v -> finish());

        navLogs.setOnClickListener(v ->
                startActivity(new Intent(this, LogViewerActivity.class)));

        navMore.setOnClickListener(v -> showMoreMenu());

        // ===== Observe profiles =====
        viewModel.getProfiles().observe(this, list -> {
            cachedProfiles = (list != null) ? new ArrayList<>(list) : new ArrayList<>();
            adapter.submit(list);
            boolean empty = list == null || list.isEmpty();
            emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        });
    }

    // ============================================================
    // ⭐ Adapter callbacks
    // ============================================================

    @Override
    public void onConnect(Profile p) {
        viewModel.markUsed(p);

        Intent result = new Intent();
        result.putExtra(ConnectionActivity.EXTRA_PROFILE_ID, p.id);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public void onEdit(Profile p) {
        Intent i = new Intent(this, ProfileEditActivity.class);
        i.putExtra(EXTRA_PROFILE_ID, p.id);
        startActivityForResult(i, REQ_EDIT);
    }

    @Override
    public void onDelete(Profile p) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("ลบโปรไฟล์?")
                .setMessage("คุณต้องการลบ \"" + p.name + "\" ใช่หรือไม่?")
                .setPositiveButton("ลบ", (d, w) -> viewModel.delete(p))
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    @Override
    public void onToggleFavorite(Profile p) {
        viewModel.toggleFavorite(p);
    }

    // ⭐ แชร์/สแกน QR เมื่อกดค้างที่รายการโปรไฟล์
    @Override
    public void onShareQr(Profile p) {
        String[] options = {
                "📱  แสดง QR",
                "📷  สแกน QR"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle(p.name)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        Intent i = new Intent(this, QrShareActivity.class);
                        i.putExtra(QrShareActivity.EXTRA_PROFILE_ID, p.id);
                        startActivity(i);
                    } else {
                        startQrScan();
                    }
                })
                .show();
    }

    // ============================================================
    // Toolbar Menu
    // ============================================================
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_profile_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_export_all) {
            exportAllProfiles();
            return true;
        }
        if (id == R.id.action_import) {
            importFromClipboardDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ============================================================
    // Bottom Nav "More"
    // ============================================================
    private void showMoreMenu() {
        String[] options = {
                "📤 ส่งออกทั้งหมด",
                "📥 นำเข้าจาก Clipboard",
                "📷 สแกน QR Code",     // ⭐ ตัวเลือกสแกน QR
                "🧹 ลบโปรไฟล์ชื่อซ้ำ",  // ⭐ ตัวเลือกลบโปรไฟล์ชื่อซ้ำ
                "🎨 เปลี่ยนธีม",
                "🐛 Crash Log",
                "ℹ️ เกี่ยวกับ"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle("เมนูเพิ่มเติม")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: exportAllProfiles(); break;
                        case 1: importFromClipboardDialog(); break;
                        case 2: startQrScan(); break;
                        case 3: removeDuplicateNames(); break; // ⭐ เรียกใช้งานฟังก์ชันลบชื่อซ้ำ
                        case 4: showThemeDialog(); break;
                        case 5: startActivity(new Intent(this, CrashLogActivity.class)); break;
                        case 6: showAboutDialog(); break;
                    }
                })
                .show();
    }

     private void removeDuplicateNames() {
        if (cachedProfiles == null || cachedProfiles.isEmpty()) {
            Toast.makeText(this, "ไม่มีโปรไฟล์", Toast.LENGTH_SHORT).show();
            return;
        }

        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.List<Profile> toDelete = new java.util.ArrayList<>();

        for (Profile p : cachedProfiles) {
            String key = p.name == null ? "" : p.name.trim().toLowerCase();
            if (seen.contains(key)) {
                toDelete.add(p);
            } else {
                seen.add(key);
            }
        }

        if (toDelete.isEmpty()) {
            Toast.makeText(this, "ไม่พบชื่อซ้ำ", Toast.LENGTH_SHORT).show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("ลบโปรไฟล์ชื่อซ้ำ")
                .setMessage("พบ " + toDelete.size() + " รายการชื่อซ้ำ\nจะเก็บรายการแรกของแต่ละชื่อไว้\nลบที่เหลือหรือไม่?")
                .setPositiveButton("ลบ", (d, w) -> {
                    for (Profile p : toDelete) {
                        viewModel.delete(p);
                    }
                    Toast.makeText(this, "ลบแล้ว " + toDelete.size() + " รายการ",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void startQrScan() {
        Intent i = new Intent(this, QrScanActivity.class);
        qrScanLauncher.launch(i);
    }

    private void showAboutDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("เกี่ยวกับ VPN Manager")
                .setMessage("VPN Manager v1.0\n\n" +
                        "แอป VPN ที่รองรับ SSH Tunnel\n" +
                        "สร้างด้วย ❤️ ในประเทศไทย")
                .setPositiveButton("ตกลง", null)
                .show();
    }

    // ============================================================
    // ⭐ Theme Picker
    // ============================================================
    private void showThemeDialog() {
        ThemePrefs themePrefs = new ThemePrefs(this);

        final int[] modes = {
                ThemePrefs.MODE_SYSTEM,
                ThemePrefs.MODE_LIGHT,
                ThemePrefs.MODE_DARK
        };
        final String[] names = {
                "🌗  ตามระบบ (System)",
                "☀️  สว่าง (Light)",
                "🌙  มืด (Dark)"
        };

        int current = themePrefs.getMode();
        int checkedItem = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == current) {
                checkedItem = i;
                break;
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("เลือกธีม")
                .setSingleChoiceItems(names, checkedItem, (d, which) -> {
                    themePrefs.setMode(modes[which]);
                    d.dismiss();
                    Toast.makeText(this,
                            "ธีม: " + ThemePrefs.getModeName(modes[which]),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    // ============================================================
    // 📤 Export
    // ============================================================
    private void exportAllProfiles() {
        List<Profile> all = cachedProfiles;
        if (all == null || all.isEmpty()) {
            Toast.makeText(this, "ไม่มีโปรไฟล์ให้ส่งออก", Toast.LENGTH_SHORT).show();
            return;
        }

        String json = ProfileExporter.export(all);

        new MaterialAlertDialogBuilder(this)
                .setTitle("ส่งออกโปรไฟล์")
                .setMessage("พบ " + all.size() + " โปรไฟล์\n\nคัดลอก JSON หรือแชร์?")
                .setPositiveButton("คัดลอก", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("VPN Config", json));
                        Toast.makeText(this, "คัดลอกแล้ว", Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("แชร์", (d, w) -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, json);
                    startActivity(Intent.createChooser(share, "แชร์ config"));
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    // ============================================================
    // 📥 Import
    // ============================================================
    private void importFromClipboardDialog() {
        ClipboardManager cm = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
            Toast.makeText(this,
                    "Clipboard ว่าง — คัดลอก ssh:// หรือ user:pass@host:port ก่อน",
                    Toast.LENGTH_LONG).show();
            return;
        }

        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            Toast.makeText(this, "Clipboard ว่างเปล่า", Toast.LENGTH_SHORT).show();
            return;
        }

        ProfileImporter.Result result = ProfileImporter.importFromJson(text.toString());

        if (!result.isSuccess()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("นำเข้าไม่สำเร็จ")
                    .setMessage(result.error + "\n\nข้อมูล:\n"
                            + text.subSequence(0, Math.min(200, text.length())))
                    .setPositiveButton("ตกลง", null)
                    .show();
            return;
        }

        int count = result.profiles.size();
        StringBuilder preview = new StringBuilder();
        preview.append("พบ ").append(count).append(" โปรไฟล์:\n\n");
        for (int i = 0; i < Math.min(count, 5); i++) {
            Profile p = result.profiles.get(i);
            preview.append("• ").append(p.name)
                    .append(" — ").append(p.host).append(":").append(p.port).append('\n');
        }
        if (count > 5) preview.append("... และอีก ").append(count - 5);

        new MaterialAlertDialogBuilder(this)
                .setTitle("ยืนยันการนำเข้า")
                .setMessage(preview.toString())
                .setPositiveButton("นำเข้าทั้งหมด", (d, w) -> {
                    final int total = count;
                    final int[] imported = {0};
                    for (Profile p : result.profiles) {
                        viewModel.save(p, id -> {
                            imported[0]++;
                            if (imported[0] == total) {
                                runOnUiThread(() -> Toast.makeText(this,
                                        "นำเข้าสำเร็จ " + imported[0] + " โปรไฟล์",
                                        Toast.LENGTH_LONG).show());
                            }
                        });
                    }
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    // ============================================================
    // ⭐ เมนูเพิ่มคอนฟิก (Add Configuration)
    // ============================================================
    private void showAddConfigurationMenu() {
        final String[] items = {
                "✏️  กรอกเอง (Manual)",
                "📋  นำเข้าจาก Clipboard",
                "📷  สแกน QR Code"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Add Configuration")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            openManualAdd();
                            break;
                        case 1:
                            importFromClipboard();
                            break;
                        case 2:
                            openQrScan();
                            break;
                    }
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void openManualAdd() {
        Intent i = new Intent(this, ProfileEditActivity.class);
        addProfileLauncher.launch(i);
    }

    private void openQrScan() {
        try {
            Intent i = new Intent(this, QrScanActivity.class);
            qrScanLauncher.launch(i);
        } catch (Exception e) {
            Toast.makeText(this, "เปิดสแกน QR ไม่ได้: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================
    // Import config ปกติ (ไม่ใช่ JSON)
    // ============================================================
    private void importFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
            Toast.makeText(this, "Clipboard ว่างเปล่า", Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            Toast.makeText(this, "Clipboard ว่างเปล่า", Toast.LENGTH_SHORT).show();
            return;
        }

        ConfigParser.Result result = ConfigParser.parse(text.toString());

        if (!result.isSuccess()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Import ไม่สำเร็จ")
                    .setMessage(result.error + "\n\nข้อมูล:\n" + text)
                    .setPositiveButton("ตกลง", null)
                    .show();
            return;
        }

        final Profile profile = result.profile;
        String msg = "ชื่อ: " + profile.name + "\n"
                + "Host: " + profile.host + "\n"
                + "Port: " + profile.port + "\n"
                + "User: " + (profile.user.isEmpty() ? "(ว่าง)" : profile.user) + "\n"
                + "Pass: " + (profile.pass.isEmpty() ? "(ว่าง)" : "••••••") + "\n\n"
                + "ต้องการบันทึกเป็นโปรไฟล์ใหม่หรือไม่?";

        new MaterialAlertDialogBuilder(this)
                .setTitle("ยืนยันการ Import")
                .setMessage(msg)
                .setPositiveButton("บันทึก", (d, w) -> {
                    viewModel.getRepo().findByName(profile.name, 0L, dup -> {
                        if (dup != null) {
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("ชื่อซ้ำ")
                                    .setMessage("มีโปรไฟล์ชื่อ \"" + profile.name + "\" อยู่แล้ว\nต้องการอัปเดตของเดิมไหม?")
                                    .setPositiveButton("อัปเดตของเดิม", (d2, w2) -> {
                                        profile.id = dup.id;
                                        viewModel.save(profile, id ->
                                                Toast.makeText(this, "อัปเดตแล้ว: " + profile.name,
                                                        Toast.LENGTH_SHORT).show());
                                    })
                                    .setNegativeButton("สร้างชื่อใหม่", (d2, w2) -> {
                                        profile.id = 0;
                                        profile.name = profile.name + " (" + (System.currentTimeMillis() % 10000) + ")";
                                        viewModel.save(profile, id ->
                                                Toast.makeText(this, "Import สำเร็จ: " + profile.name,
                                                        Toast.LENGTH_SHORT).show());
                                    })
                                    .setNeutralButton("ยกเลิก", null)
                                    .show();
                        } else {
                            viewModel.save(profile, id ->
                                    Toast.makeText(this, "Import สำเร็จ: " + profile.name,
                                            Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton("แก้ไขก่อน", (d, w) -> {
                    Intent i = new Intent(this, ProfileEditActivity.class);
                    i.putExtra(ProfileEditActivity.EXTRA_PREFILL_HOST, profile.host);
                    i.putExtra(ProfileEditActivity.EXTRA_PREFILL_PORT, profile.port);
                    i.putExtra(ProfileEditActivity.EXTRA_PREFILL_USER, profile.user);
                    i.putExtra(ProfileEditActivity.EXTRA_PREFILL_PASS, profile.pass);
                    startActivityForResult(i, REQ_EDIT);
                })
                .setNeutralButton("ยกเลิก", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
