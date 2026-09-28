package com.example.vpn;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import java.io.FileOutputStream;
import java.io.File;
import androidx.core.content.FileProvider;
import android.net.Uri;
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
import com.google.android.material.navigation.NavigationView;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.view.GravityCompat;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.ui.ConnectionActivity;
import com.example.vpn.ui.CrashLogActivity;
import com.example.vpn.ui.ShareWifiActivity;
import com.example.vpn.ui.BypassActivity;
import com.example.vpn.ui.BackupActivity;
import com.example.vpn.ui.LogViewerActivity;
import com.example.vpn.ui.ProfileAdapter;
import com.example.vpn.ui.ProfileEditActivity;
import com.example.vpn.ui.ProfileViewModel;
import com.example.vpn.ui.ProfileViewModelFactory;
import com.example.vpn.ui.QrScanActivity;
import com.example.vpn.ui.QrShareActivity;
import com.example.vpn.util.ConfigParser;
import com.example.vpn.util.SubscriptionFetcher;
import com.example.vpn.util.SubscriptionPrefs;
import com.example.vpn.util.CrashHandler;
import com.example.vpn.util.ProfileExporter;
import com.example.vpn.util.ProfileImporter;
import com.example.vpn.util.StyledToast;
import com.example.vpn.util.ThemePrefs;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements ProfileAdapter.Listener,
        NavigationView.OnNavigationItemSelectedListener {

    public static final String EXTRA_PROFILE_ID = "profile_id";
    public static final int REQ_EDIT = 100;

    private ProfileViewModel viewModel;
    private ProfileAdapter adapter;
    private LinearLayout emptyState;
    private RecyclerView recycler;

    private View btnAddConfig;
    private View btnImportClipboard;
    // FAB Speed Dial
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabMain;
    private View fabOptionAdd, fabOptionImport, fabOptionQr;
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabAdd, fabImport, fabQr;
    private boolean fabMenuOpen = false;

    private View navHome, navLogs, navMore;
    private DrawerLayout drawerLayout;
    private NavigationView navView;


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
                            StyledToast.success(this, "บันทึกคอนฟิกแล้ว");
                            // LiveData จะรีเฟรชรายการเอง
                        }
                    });

    // ⭐ Launcher สำหรับรับผลการสแกน QR
    private final ActivityResultLauncher<Intent> qrScanLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            StyledToast.success(this, "นำเข้าจาก QR สำเร็จ");
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
        emptyState = findViewById(R.id.emptyState);
        recycler = findViewById(R.id.recyclerProfiles);
        btnAddConfig = findViewById(R.id.btnAddConfig);
        btnImportClipboard = findViewById(R.id.btnImportClipboard);
        setupFabMenu();

        navHome = findViewById(R.id.navHome);
        navLogs = findViewById(R.id.navLogs);
        navMore = findViewById(R.id.navMore);

        if (navHome != null) {
            navHome.setOnClickListener(v -> finish());
        }
        if (navLogs != null) {
            navLogs.setOnClickListener(v ->
                    startActivity(new Intent(this, LogViewerActivity.class)));
        }
        if (navMore != null) {
            navMore.setOnClickListener(v -> showMoreMenu());
        }

        // ===== Toolbar =====
        drawerLayout = findViewById(R.id.drawerLayout);
        navView = findViewById(R.id.navView);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (drawerLayout != null) {
                ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                        this, drawerLayout, toolbar,
                        R.string.app_name, R.string.app_name);
                drawerLayout.addDrawerListener(toggle);
                toggle.syncState();
                // ใช้ไอคอนเบอร์เกอร์ (ไม่ใช้กากบาท)
                // ActionBarDrawerToggle ใส่ไอคอนเบอร์เกอร์ให้อัตโนมัติ
            }
            if (navView != null) {
                navView.setNavigationItemSelectedListener(this);
            }
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayShowTitleEnabled(false);
            }
        }

        // ===== Back button =====

        // ===== List =====
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProfileAdapter(this);
        recycler.setAdapter(adapter);

        // ===== Empty state buttons =====
        btnAddConfig.setOnClickListener(v -> showAddConfigurationMenu());

        btnImportClipboard.setOnClickListener(v -> importFromClipboard());


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

    // ⭐ แชร์โปรไฟล์ (แสดง QR / Clipboard / ไฟล์)
    @Override
    public void onShareQr(Profile p) {
        if (p == null) return;
        final String[] options = {
                "📱  แสดง QR",
                "📋  คัดลอกไป Clipboard",
                "📄  ส่งออกเป็นไฟล์ config"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle(p.name != null ? p.name : "แชร์")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0:
                            Intent i = new Intent(this, QrShareActivity.class);
                            i.putExtra(QrShareActivity.EXTRA_PROFILE_ID, p.id);
                            startActivity(i);
                            break;
                        case 1:
                            copyProfileToClipboard(p);
                            break;
                        case 2:
                            exportProfileAsFile(p);
                            break;
                    }
                })
                .show();
    }

    private void copyProfileToClipboard(Profile p) {
        String text = ProfileExporter.toClipboardText(p);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            StyledToast.warning(this, "Clipboard ไม่พร้อม");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("VPN Config", text));
        StyledToast.success(this, "คัดลอก config แล้ว");
    }

    private void exportProfileAsFile(Profile p) {
        try {
            String json = ProfileExporter.exportOne(p);
            String fileName = ProfileExporter.safeFileName(p);

            File dir = new File(getCacheDir(), "export");
            if (!dir.exists() && !dir.mkdirs()) {
                StyledToast.error(this, "สร้างโฟลเดอร์ไม่สำเร็จ");
                return;
            }

            File out = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(json.getBytes("UTF-8"));
            fos.close();

            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    out
            );

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/json");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, fileName);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "ส่งออก config"));
        } catch (Exception e) {
            StyledToast.error(this, "ส่งออกไม่สำเร็จ: " + e.getMessage());
        }
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
        if (id == R.id.action_auto_select) {
            autoSelectLowestPing();
            return true;
        }
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
                "📷 สแกน QR Code",
                "🔗 เพิ่ม Subscription",
                "🔄 อัปเดต Subscription ทั้งหมด",
                "📋 จัดการ Subscription",
                "🧹 ลบโปรไฟล์ชื่อซ้ำ",
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
                        case 3: showAddSubscriptionDialog(); break;
                        case 4: updateAllSubscriptions(); break;
                        case 5: showManageSubscriptions(); break;
                        case 6: removeDuplicateNames(); break;
                        case 7: showThemeDialog(); break;
                        case 8: startActivity(new Intent(this, CrashLogActivity.class)); break;
                        case 9: showAboutDialog(); break;
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
            StyledToast.info(this, "ไม่พบชื่อซ้ำ");
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("ลบโปรไฟล์ชื่อซ้ำ")
                .setMessage("พบ " + toDelete.size() + " รายการชื่อซ้ำ\nจะเก็บรายการแรกของแต่ละชื่อไว้\nลบที่เหลือหรือไม่?")
                .setPositiveButton("ลบ", (d, w) -> {
                    for (Profile p : toDelete) {
                        viewModel.delete(p);
                    }
                    StyledToast.delete(this, "ลบแล้ว " + toDelete.size() + " รายการ");
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

    /**
     * Auto Select: วัด ping ทุกโปรไฟล์ แล้วเลือกตัวที่ latency ต่ำสุด
     */
    private void autoSelectLowestPing() {
        if (adapter == null || adapter.getItemCount() == 0) {
            StyledToast.warning(this, "ยังไม่มีโปรไฟล์");
            return;
        }
        StyledToast.info(this, "กำลังวัด latency...");
        if (fabMenuOpen) closeFabMenu();

        adapter.pingAll(() -> {
            Profile best = adapter.getLowestLatencyProfile();
            if (best == null) {
                StyledToast.error(this, "วัด ping ไม่สำเร็จ — ลองใหม่");
                return;
            }
            Integer ms = adapter.getLatencyMs(best.id);
            String label = best.name != null ? best.name : best.host;
            StyledToast.success(this,
                    "Auto Select: " + label + (ms != null ? " (" + ms + "ms)" : ""));

            // เลือกโปรไฟล์นี้ (กลับหน้าหลัก + ตั้งเป็นตัวเชื่อมต่อ)
            Intent result = new Intent();
            result.putExtra(EXTRA_PROFILE_ID, best.id);
            setResult(RESULT_OK, result);

            // ถ้าเปิดจาก ConnectionActivity ด้วย launcher จะได้ profile กลับ
            // ถ้าอยู่หน้า CONFIGS เฉย ๆ ก็ highlight โดย set favorite ชั่วคราวไม่ได้บังคับ
            // เชื่อมต่อทันทีเมื่อผู้ใช้กดการ์ด — หรือเรียก onConnect
            onConnect(best);
        });
    }

    private void exportAllProfiles() {
        List<Profile> all = cachedProfiles;
        if (all == null || all.isEmpty()) {
            StyledToast.warning(this, "ไม่มีโปรไฟล์ให้ส่งออก");
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
                        StyledToast.success(this, "คัดลอกแล้ว");
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
            StyledToast.warning(this, "Clipboard ว่าง — คัดลอก config ก่อน");
            return;
        }

        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            StyledToast.warning(this, "Clipboard ว่างเปล่า");
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
    // ⭐ FAB Speed Dial (เหมือน Fab Options)
    // ============================================================
    private void setupFabMenu() {
        fabMain = findViewById(R.id.fabMain);
        fabOptionAdd = findViewById(R.id.fabOptionAdd);
        fabOptionImport = findViewById(R.id.fabOptionImport);
        fabOptionQr = findViewById(R.id.fabOptionQr);
        fabAdd = findViewById(R.id.fabAdd);
        fabImport = findViewById(R.id.fabImport);
        fabQr = findViewById(R.id.fabQr);

        if (fabMain == null) return;

        fabMain.setOnClickListener(v -> toggleFabMenu());

        if (fabAdd != null) {
            fabAdd.setOnClickListener(v -> {
                closeFabMenu();
                openManualAdd();
            });
        }
        if (fabOptionAdd != null) {
            fabOptionAdd.setOnClickListener(v -> {
                closeFabMenu();
                openManualAdd();
            });
        }
        if (fabImport != null) {
            fabImport.setOnClickListener(v -> {
                closeFabMenu();
                importFromClipboard();
            });
        }
        if (fabOptionImport != null) {
            fabOptionImport.setOnClickListener(v -> {
                closeFabMenu();
                importFromClipboard();
            });
        }
        if (fabQr != null) {
            fabQr.setOnClickListener(v -> {
                closeFabMenu();
                startQrScan();
            });
        }
        if (fabOptionQr != null) {
            fabOptionQr.setOnClickListener(v -> {
                closeFabMenu();
                startQrScan();
            });
        }
    }

    private void toggleFabMenu() {
        if (fabMenuOpen) closeFabMenu();
        else openFabMenu();
    }

    private void openFabMenu() {
        fabMenuOpen = true;
        if (fabMain != null) {
            fabMain.animate().rotation(45f).setDuration(200).start();
        }
        showFabOption(fabOptionQr, 0);
        showFabOption(fabOptionImport, 40);
        showFabOption(fabOptionAdd, 80);
    }

    private void closeFabMenu() {
        fabMenuOpen = false;
        if (fabMain != null) {
            fabMain.animate().rotation(0f).setDuration(200).start();
        }
        hideFabOption(fabOptionAdd);
        hideFabOption(fabOptionImport);
        hideFabOption(fabOptionQr);
    }

    private void showFabOption(View option, long delayMs) {
        if (option == null) return;
        option.setVisibility(View.VISIBLE);
        option.setAlpha(0f);
        option.setTranslationY(40f);
        option.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(180)
                .start();
    }

    private void hideFabOption(View option) {
        if (option == null) return;
        option.animate()
                .alpha(0f)
                .translationY(40f)
                .setDuration(150)
                .withEndAction(() -> option.setVisibility(View.GONE))
                .start();
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
            StyledToast.error(this, "เปิดสแกน QR ไม่ได้: " + e.getMessage());
        }
    }

    // ============================================================
    // Import config ปกติ (ไม่ใช่ JSON)
    // ============================================================

    // ============================================================
    // Subscription (เฟส V2)
    // ============================================================
    private void showAddSubscriptionDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);

        final com.google.android.material.textfield.TextInputLayout tilName =
                new com.google.android.material.textfield.TextInputLayout(this);
        tilName.setHint("ชื่อ (เช่น My Sub)");
        final com.google.android.material.textfield.TextInputEditText edtName =
                new com.google.android.material.textfield.TextInputEditText(this);
        tilName.addView(edtName);

        final com.google.android.material.textfield.TextInputLayout tilUrl =
                new com.google.android.material.textfield.TextInputLayout(this);
        tilUrl.setHint("Subscription URL");
        final com.google.android.material.textfield.TextInputEditText edtUrl =
                new com.google.android.material.textfield.TextInputEditText(this);
        edtUrl.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        tilUrl.addView(edtUrl);

        layout.addView(tilName);
        layout.addView(tilUrl);

        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (t != null) {
                    String s = t.toString().trim();
                    if (s.startsWith("http://") || s.startsWith("https://")) {
                        edtUrl.setText(s);
                    }
                }
            }
        } catch (Exception ignored) {}

        new MaterialAlertDialogBuilder(this)
                .setTitle("เพิ่ม Subscription")
                .setView(layout)
                .setPositiveButton("ดึงและบันทึก", (d, w) -> {
                    String name = edtName.getText() != null
                            ? edtName.getText().toString().trim() : "";
                    String url = edtUrl.getText() != null
                            ? edtUrl.getText().toString().trim() : "";
                    if (url.isEmpty()) {
                        StyledToast.warning(this, "กรุณาใส่ URL");
                        return;
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        StyledToast.warning(this, "URL ต้องขึ้นต้นด้วย http:// หรือ https://");
                        return;
                    }
                    SubscriptionPrefs prefs = new SubscriptionPrefs(this);
                    String subName = name.isEmpty() ? "Subscription" : name;
                    prefs.add(subName, url);
                    updateOneSubscription(url, subName, true);
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void updateAllSubscriptions() {
        SubscriptionPrefs prefs = new SubscriptionPrefs(this);
        java.util.List<SubscriptionPrefs.Item> items = prefs.getAll();
        if (items.isEmpty()) {
            StyledToast.info(this, "ยังไม่มี Subscription — เพิ่มจากเมนูก่อน");
            return;
        }
        StyledToast.info(this, "กำลังอัปเดต " + items.size() + " subscription...");
        for (SubscriptionPrefs.Item it : items) {
            updateOneSubscription(it.url, it.name, false);
        }
    }

    private void updateOneSubscription(String url, String subName, boolean showDialog) {
        new Thread(() -> {
            SubscriptionFetcher.Result result = SubscriptionFetcher.fetch(url);
            runOnUiThread(() -> {
                if (!result.isSuccess()) {
                    if (showDialog) {
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("Subscription ไม่สำเร็จ")
                                .setMessage(result.error != null ? result.error : "ไม่ทราบสาเหตุ")
                                .setPositiveButton("ตกลง", null)
                                .show();
                    } else {
                        StyledToast.error(this, "Sub ล้มเหลว: " + result.error);
                    }
                    return;
                }
                applySubscriptionProfiles(url, subName, result.profiles, showDialog);
            });
        }, "sub-fetch").start();
    }

    private void applySubscriptionProfiles(String url, String subName,
                                           java.util.List<Profile> incoming,
                                           boolean showDialog) {
        viewModel.getRepo().getAllSync(all -> {
            int deleted = 0;
            if (all != null) {
                for (Profile p : all) {
                    String su = p.extras != null ? p.extras.get("subscription_url") : null;
                    if (url.equals(su)) {
                        viewModel.delete(p);
                        deleted++;
                    }
                }
            }
            final int delCount = deleted;
            int saved = 0;
            for (Profile p : incoming) {
                if (p.extras == null) p.extras = new java.util.HashMap<>();
                p.extras.put("subscription_url", url);
                if (subName != null) p.extras.put("subscription_name", subName);
                viewModel.save(p, id -> {});
                saved++;
            }
            final int saveCount = saved;

            SubscriptionPrefs prefs = new SubscriptionPrefs(this);
            for (SubscriptionPrefs.Item it : prefs.getAll()) {
                if (url.equals(it.url)) {
                    prefs.touch(it.id);
                    break;
                }
            }

            String msg = "Subscription: " + (subName != null ? subName : "")
                    + "\nนำเข้า " + saveCount + " โหนด"
                    + (delCount > 0 ? "\nลบของเก่า " + delCount + " รายการ" : "");
            if (showDialog) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("อัปเดตสำเร็จ")
                        .setMessage(msg)
                        .setPositiveButton("ตกลง", null)
                        .show();
            } else {
                StyledToast.success(this, "นำเข้า " + saveCount + " โหนด");
            }
        });
    }

    private void showManageSubscriptions() {
        SubscriptionPrefs prefs = new SubscriptionPrefs(this);
        java.util.List<SubscriptionPrefs.Item> items = prefs.getAll();
        if (items.isEmpty()) {
            StyledToast.info(this, "ยังไม่มี Subscription");
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            SubscriptionPrefs.Item it = items.get(i);
            labels[i] = it.name + "\n" + it.url;
        }
        final java.util.List<SubscriptionPrefs.Item> list = items;
        new MaterialAlertDialogBuilder(this)
                .setTitle("จัดการ Subscription")
                .setItems(labels, (d, which) -> {
                    SubscriptionPrefs.Item it = list.get(which);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(it.name)
                            .setMessage(it.url)
                            .setPositiveButton("อัปเดต", (d2, w2) ->
                                    updateOneSubscription(it.url, it.name, true))
                            .setNegativeButton("ลบ", (d2, w2) -> {
                                prefs.remove(it.id);
                                viewModel.getRepo().getAllSync(all -> {
                                    if (all != null) {
                                        for (Profile p : all) {
                                            String su = p.extras != null
                                                    ? p.extras.get("subscription_url") : null;
                                            if (it.url.equals(su)) {
                                                viewModel.delete(p);
                                            }
                                        }
                                    }
                                    StyledToast.delete(this, "ลบ subscription แล้ว");
                                });
                            })
                            .setNeutralButton("ปิด", null)
                            .show();
                })
                .setNegativeButton("ปิด", null)
                .show();
    }

    private void importFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
            StyledToast.warning(this, "Clipboard ว่างเปล่า");
            return;
        }

        CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            StyledToast.warning(this, "Clipboard ว่างเปล่า");
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
        String proto = profile.protocol != null ? profile.protocol.displayName : "?";
        String msg = "โปรโตคอล: " + proto + "\n"
                + "ชื่อ: " + profile.name + "\n"
                + "Host: " + profile.host + "\n"
                + "Port: " + profile.port + "\n";
        if (profile.protocol == com.example.vpn.model.Protocol.V2RAY
                || profile.protocol == com.example.vpn.model.Protocol.TROJAN
                || profile.protocol == com.example.vpn.model.Protocol.SHADOWSOCKS) {
            msg += "Type: " + (profile.v2rayType != null ? profile.v2rayType : "") + "\n"
                    + "Network: " + (profile.v2rayNetwork != null ? profile.v2rayNetwork : "") + "\n"
                    + "TLS: " + (profile.v2rayTls ? "ใช่" : "ไม่") + "\n";
        } else {
            String u = profile.user == null || profile.user.isEmpty() ? "(ว่าง)" : profile.user;
            String pw = profile.pass == null || profile.pass.isEmpty() ? "(ว่าง)" : "••••••";
            msg += "User: " + u + "\n" + "Pass: " + pw + "\n";
        }
        msg += "\nต้องการบันทึกเป็นโปรไฟล์ใหม่หรือไม่?";

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
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.nav_home) {
            finish(); // กลับหน้าหลัก
        } else if (id == R.id.nav_profiles) {
            // อยู่หน้า CONFIGS อยู่แล้ว
        } else if (id == R.id.nav_backup) {
            startActivity(new Intent(this, BackupActivity.class));
        } else if (id == R.id.nav_log) {
            startActivity(new Intent(this, LogViewerActivity.class));
        } else if (id == R.id.nav_crash) {
            startActivity(new Intent(this, CrashLogActivity.class));
        } else if (id == R.id.nav_bypass) {
            startActivity(new Intent(this, BypassActivity.class));
        } else if (id == R.id.nav_share_wifi) {
            startActivity(new Intent(this, ShareWifiActivity.class));
        } else if (id == R.id.nav_import) {
            showAddConfigurationMenu();
        } else if (id == R.id.nav_theme) {
            // เปิด theme จากหน้าหลักถ้ามี — ตอนนี้แค่ปิด drawer
            StyledToast.info(this, "เปลี่ยนธีมได้จากหน้าแรก");
        } else if (id == R.id.nav_about) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("เกี่ยวกับ VPN Manager")
                    .setMessage("VPN Manager v1.0")
                    .setPositiveButton("ตกลง", null)
                    .show();
        } else if (id == R.id.nav_exit) {
            finishAffinity();
        }
        if (drawerLayout != null) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }


}
