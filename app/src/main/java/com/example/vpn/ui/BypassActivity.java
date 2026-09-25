package com.example.vpn.ui;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.vpn.ProxyVpnService;
import com.example.vpn.R;
import com.example.vpn.model.AppInfo;
import com.example.vpn.util.BypassPrefs;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * หน้า Bypass Mode — เลือกแอปที่ใช้เน็ตตรง (ไม่ผ่าน VPN)
 */
public class BypassActivity extends AppCompatActivity
        implements AppListAdapter.Listener {

    private BypassPrefs prefs;
    private AppListAdapter adapter;
    private RecyclerView recycler;
    private LinearLayout loadingView;
    private LinearLayout emptyView;
    private TextView txtCount;
    private TextInputEditText edtSearch;

    private final List<AppInfo> allApps = new ArrayList<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    private boolean hideSystem = true;
    private boolean showBypassedOnly = false;
    private String currentQuery = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bypass);

        prefs = new BypassPrefs(this);
        hideSystem = prefs.isHideSystemApps();

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(tb);

        recycler = findViewById(R.id.recyclerApps);
        loadingView = findViewById(R.id.loadingView);
        emptyView = findViewById(R.id.emptyView);
        txtCount = findViewById(R.id.txtCount);
        edtSearch = findViewById(R.id.edtSearch);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppListAdapter(this);
        recycler.setAdapter(adapter);

        if (edtSearch != null) {
            edtSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    currentQuery = s != null ? s.toString() : "";
                    applyFilter();
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }

        updateCount();
        loadApps();
    }

    private boolean canSubmit() {
        return !destroyed.get()
                && !isFinishing()
                && !isDestroyed()
                && !pool.isShutdown()
                && !pool.isTerminated();
    }

    // ============================================================
    // Load apps
    // ============================================================
    private void loadApps() {
        if (!canSubmit()) return;

        loadingView.setVisibility(View.VISIBLE);
        recycler.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);

        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    if (destroyed.get()) return;
                    if (loadingView.getVisibility() == View.VISIBLE) {
                        loadingView.setVisibility(View.GONE);
                        emptyView.setVisibility(View.VISIBLE);
                        Toast.makeText(this,
                                "โหลดช้าเกินไป — ลองเปิดอีกครั้ง",
                                Toast.LENGTH_SHORT).show();
                    }
                }, 12_000);

        try {
            pool.execute(() -> {
                if (destroyed.get()) return;

                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps;
                try {
                    apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        loadingView.setVisibility(View.GONE);
                        emptyView.setVisibility(View.VISIBLE);
                    });
                    return;
                }

                if (destroyed.get()) return;

                Set<String> bypassed = prefs.getPackages();
                List<AppInfo> result = new ArrayList<>();

                for (ApplicationInfo ai : apps) {
                    if (destroyed.get()) return;
                    if (ai.packageName.equals(getPackageName())) continue;

                    try {
                        String label = ai.loadLabel(pm).toString();
                        boolean isSystem =
                                (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        AppInfo info = new AppInfo(
                                ai.packageName,
                                label,
                                null,
                                isSystem,
                                bypassed.contains(ai.packageName)
                        );
                        result.add(info);
                    } catch (Exception ignored) {}
                }

                // เรียง: bypassed ก่อน → ชื่อ A-Z
                Collections.sort(result, (a, b) -> {
                    if (a.bypassed != b.bypassed) {
                        return a.bypassed ? -1 : 1;
                    }
                    return a.appName.compareToIgnoreCase(b.appName);
                });

                final List<AppInfo> finalResult = result;

                if (destroyed.get()) return;
                runOnUiThread(() -> {
                    if (destroyed.get() || isFinishing() || isDestroyed()) return;

                    allApps.clear();
                    allApps.addAll(finalResult);
                    applyFilter();
                    loadingView.setVisibility(View.GONE);
                    recycler.setVisibility(View.VISIBLE);
                    updateCount();
                    loadIcons();
                });
            });
        } catch (Exception e) {
            android.util.Log.w("BypassActivity",
                    "loadApps submit failed: " + e.getMessage());
        }
    }

    private void loadIcons() {
        if (!canSubmit()) return;
        final List<AppInfo> snapshot = new ArrayList<>(allApps);
        if (snapshot.isEmpty()) return;

        try {
            pool.execute(() -> {
                if (destroyed.get()) return;
                PackageManager pm = getPackageManager();

                for (int i = 0; i < snapshot.size(); i++) {
                    if (destroyed.get()) return;
                    AppInfo app = snapshot.get(i);
                    try {
                        app.icon = pm.getApplicationIcon(app.packageName);
                    } catch (Exception ignored) {}

                    final int index = i;
                    if (index % 8 == 0) {
                        runOnUiThread(() -> {
                            if (destroyed.get() || isFinishing() || isDestroyed()) return;
                            try { adapter.notifyDataSetChanged(); } catch (Exception ignored) {}
                        });
                    }
                }

                if (destroyed.get()) return;
                runOnUiThread(() -> {
                    if (destroyed.get() || isFinishing() || isDestroyed()) return;
                    try { adapter.notifyDataSetChanged(); } catch (Exception ignored) {}
                });
            });
        } catch (Exception e) {
            android.util.Log.w("BypassActivity",
                    "loadIcons failed: " + e.getMessage());
        }
    }

    // ============================================================
    // Filter / Sort display
    // ============================================================
    private void applyFilter() {
        String q = currentQuery != null ? currentQuery.trim().toLowerCase() : "";
        List<AppInfo> filtered = new ArrayList<>();

        for (AppInfo app : allApps) {
            if (hideSystem && app.isSystemApp) continue;
            if (showBypassedOnly && !app.bypassed) continue;

            if (!q.isEmpty()) {
                if (!app.appName.toLowerCase().contains(q)
                        && !app.packageName.toLowerCase().contains(q)) {
                    continue;
                }
            }
            filtered.add(app);
        }

        adapter.submit(filtered);
        if (emptyView != null) {
            emptyView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (recycler != null) {
            recycler.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    // ============================================================
    // Toggle
    // ============================================================
    @Override
    public void onToggle(AppInfo app, boolean bypassed) {
        app.bypassed = bypassed;
        prefs.togglePackage(app.packageName, bypassed);
        updateCount();
        hintReconnectIfNeeded();
    }

    private void updateCount() {
        if (txtCount != null) {
            int count = prefs.getCount();
            txtCount.setText("Bypass: " + count + " แอป"
                    + (count > 0 ? "  •  ต้อง Reconnect VPN เพื่อให้มีผล" : ""));
        }
    }

    private void hintReconnectIfNeeded() {
        if (ProxyVpnService.isServiceRunning(this)) {
            Toast.makeText(this,
                    "บันทึกแล้ว — กด Reconnect VPN เพื่อให้มีผล",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================
    // AI quick bypass
    // ============================================================
    private void applyAiBypass(boolean enable) {
        if (enable) {
            List<String> installed = prefs.getInstalledAiPackages(getPackageManager());
            if (installed.isEmpty()) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("ไม่พบแอป AI")
                        .setMessage("ไม่พบแอป AI ที่รู้จักบนเครื่องนี้\n"
                                + "เช่น ChatGPT, Claude, Gemini, AiPASS\n\n"
                                + "สามารถติ๊กแอปเองจากรายการด้านล่างได้")
                        .setPositiveButton("ตกลง", null)
                        .show();
                return;
            }

            List<String> added = prefs.addInstalledAiApps(getPackageManager());
            Set<String> bypassed = prefs.getPackages();
            for (AppInfo app : allApps) {
                if (bypassed.contains(app.packageName)) app.bypassed = true;
            }
            // เรียงใหม่
            Collections.sort(allApps, (a, b) -> {
                if (a.bypassed != b.bypassed) return a.bypassed ? -1 : 1;
                return a.appName.compareToIgnoreCase(b.appName);
            });
            applyFilter();
            updateCount();

            StringBuilder names = new StringBuilder();
            for (String pkg : installed) {
                if (names.length() > 0) names.append("\n");
                names.append("• ").append(pkg);
            }

            new MaterialAlertDialogBuilder(this)
                    .setTitle("Bypass แอป AI")
                    .setMessage(
                            (added.isEmpty()
                                    ? "แอป AI ถูก bypass อยู่แล้ว\n\n"
                                    : "เพิ่ม Bypass แล้ว +" + added.size() + " แอป\n\n")
                                    + "ที่พบบนเครื่อง:\n" + names + "\n\n"
                                    + "ถ้า VPN กำลังเชื่อมอยู่ — กด Reconnect เพื่อให้มีผล")
                    .setPositiveButton("ตกลง", null)
                    .show();

            if (!added.isEmpty()) hintReconnectIfNeeded();
        } else {
            int removed = prefs.removeAiApps();
            Set<String> bypassed = prefs.getPackages();
            for (AppInfo app : allApps) {
                app.bypassed = bypassed.contains(app.packageName);
            }
            applyFilter();
            updateCount();
            Toast.makeText(this,
                    removed > 0
                            ? "ยกเลิก Bypass แอป AI แล้ว (" + removed + " แอป)"
                            : "ไม่มีแอป AI ในรายการ bypass",
                    Toast.LENGTH_SHORT).show();
            if (removed > 0) hintReconnectIfNeeded();
        }
    }

    // ============================================================
    // Menu
    // ============================================================
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_bypass, menu);
        MenuItem hideItem = menu.findItem(R.id.action_hide_system);
        if (hideItem != null) hideItem.setChecked(hideSystem);
        MenuItem onlyItem = menu.findItem(R.id.action_show_bypassed_only);
        if (onlyItem != null) onlyItem.setChecked(showBypassedOnly);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_bypass_ai) {
            applyAiBypass(true);
            return true;
        }
        if (id == R.id.action_remove_ai_bypass) {
            applyAiBypass(false);
            return true;
        }
        if (id == R.id.action_show_bypassed_only) {
            showBypassedOnly = !item.isChecked();
            item.setChecked(showBypassedOnly);
            applyFilter();
            return true;
        }
        if (id == R.id.action_hide_system) {
            hideSystem = !item.isChecked();
            item.setChecked(hideSystem);
            prefs.setHideSystemApps(hideSystem);
            applyFilter();
            return true;
        }
        if (id == R.id.action_clear) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("ล้าง Bypass ทั้งหมด?")
                    .setMessage("ยกเลิกการ bypass ทุกแอป")
                    .setPositiveButton("ล้าง", (d, w) -> {
                        prefs.clear();
                        for (AppInfo app : allApps) app.bypassed = false;
                        applyFilter();
                        updateCount();
                        Toast.makeText(this, "ล้างแล้ว", Toast.LENGTH_SHORT).show();
                        hintReconnectIfNeeded();
                    })
                    .setNegativeButton("ยกเลิก", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        destroyed.set(true);
        super.onDestroy();
        try { pool.shutdownNow(); } catch (Exception ignored) {}
    }
}
