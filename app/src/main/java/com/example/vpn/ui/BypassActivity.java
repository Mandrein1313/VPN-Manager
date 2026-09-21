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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.vpn.R;
import com.example.vpn.model.AppInfo;
import com.example.vpn.util.BypassPrefs;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** ⭐ flag — ป้องกัน submit งานหลัง onDestroy */
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bypass);

        prefs = new BypassPrefs(this);

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
                    applyFilter(s != null ? s.toString() : "");
                }
                @Override public void afterTextChanged(android.text.Editable s) {}
            });
        }

        updateCount();
        loadApps();
    }

    // ============================================================
    // ⭐ ตรวจสอบก่อน submit งาน
    // ============================================================
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

        try {
            pool.execute(() -> {
                if (destroyed.get()) return;

                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(
                        PackageManager.GET_META_DATA);

                if (destroyed.get()) return;

                Set<String> bypassed = prefs.getPackages();
                List<AppInfo> result = new ArrayList<>();

                for (ApplicationInfo ai : apps) {
                    if (destroyed.get()) return;

                    // ข้ามแอปตัวเอง
                    if (ai.packageName.equals(getPackageName())) continue;

                    try {
                        String label = ai.loadLabel(pm).toString();
                        AppInfo info = new AppInfo(
                                ai.packageName,
                                label,
                                null,
                                (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                                bypassed.contains(ai.packageName)
                        );
                        result.add(info);
                    } catch (Exception ignored) {}
                }

                // เรียงตามชื่อ
                Collections.sort(result, (a, b) ->
                        a.appName.compareToIgnoreCase(b.appName));

                final List<AppInfo> finalResult = result;

                // ⭐ ตรวจสอบก่อน post เข้า main thread
                if (destroyed.get()) return;
                runOnUiThread(() -> {
                    if (destroyed.get() || isFinishing() || isDestroyed()) return;

                    allApps.clear();
                    allApps.addAll(finalResult);
                    adapter.submit(finalResult);
                    loadingView.setVisibility(View.GONE);
                    recycler.setVisibility(View.VISIBLE);
                    emptyView.setVisibility(
                            finalResult.isEmpty() ? View.VISIBLE : View.GONE);
                    updateCount();

                    // ⭐ โหลด icon — พร้อมตรวจสอบทุกจุด
                    loadIcons();
                });
            });
        } catch (Exception e) {
            // pool ถูกปิดก่อน submit — เพิกเฉย
            android.util.Log.w("BypassActivity",
                    "loadApps submit failed: " + e.getMessage());
        }
    }

    // ============================================================
    // Load icons (lazy) — ⭐ ตรวจสอบทุกจุดก่อน submit
    // ============================================================
    private void loadIcons() {
        // ⭐ ตรวจสอบก่อน submit
        if (!canSubmit()) return;

        // snapshot รายการ — ป้องกัน concurrent modification
        final List<AppInfo> snapshot = new ArrayList<>(allApps);
        if (snapshot.isEmpty()) return;

        try {
            pool.execute(() -> {
                if (destroyed.get()) return;

                PackageManager pm = getPackageManager();
                for (int i = 0; i < snapshot.size(); i++) {
                    if (destroyed.get()) return;

                    AppInfo info = snapshot.get(i);
                    try {
                        info.icon = pm.getApplicationIcon(info.packageName);
                    } catch (Exception ignored) {}

                    // ⭐ update UI เป็นช่วงๆ (ทุก 5 ตัว)
                    final int index = i;
                    if (index % 5 == 0) {
                        if (destroyed.get()) return;
                        runOnUiThread(() -> {
                            if (destroyed.get()
                                    || isFinishing() || isDestroyed()) return;
                            if (index < adapter.getItemCount()) {
                                try {
                                    adapter.notifyItemChanged(index);
                                } catch (Exception ignored) {}
                            }
                        });
                    }
                }

                // ⭐ update ครั้งสุดท้าย
                if (destroyed.get()) return;
                runOnUiThread(() -> {
                    if (destroyed.get()
                            || isFinishing() || isDestroyed()) return;
                    try {
                        adapter.notifyDataSetChanged();
                    } catch (Exception ignored) {}
                });
            });
        } catch (Exception e) {
            // pool ถูกปิด — เพิกเฉย
            android.util.Log.w("BypassActivity",
                    "loadIcons submit failed: " + e.getMessage());
        }
    }

    // ============================================================
    // Search filter
    // ============================================================
    private void applyFilter(String query) {
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) {
            adapter.submit(allApps);
            return;
        }

        List<AppInfo> filtered = new ArrayList<>();
        for (AppInfo app : allApps) {
            if (app.appName.toLowerCase().contains(q)
                    || app.packageName.toLowerCase().contains(q)) {
                filtered.add(app);
            }
        }
        adapter.submit(filtered);
    }

    // ============================================================
    // Toggle
    // ============================================================
    @Override
    public void onToggle(AppInfo app, boolean bypassed) {
        app.bypassed = bypassed;
        prefs.togglePackage(app.packageName, bypassed);
        updateCount();
    }

    private void updateCount() {
        if (txtCount != null) {
            int count = prefs.getCount();
            txtCount.setText("Bypass: " + count + " แอป");
        }
    }

    // ============================================================
    // Menu
    // ============================================================
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_bypass, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_clear) {
            new AlertDialog.Builder(this)
                    .setTitle("ล้าง Bypass ทั้งหมด?")
                    .setMessage("ยกเลิกการ bypass ทุกแอป")
                    .setPositiveButton("ล้าง", (d, w) -> {
                        prefs.clear();
                        for (AppInfo app : allApps) app.bypassed = false;
                        adapter.notifyDataSetChanged();
                        updateCount();
                        Toast.makeText(this, "ล้างแล้ว", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("ยกเลิก", null)
                    .show();
            return true;
        }
        if (id == R.id.action_hide_system) {
            item.setChecked(!item.isChecked());
            List<AppInfo> filtered = new ArrayList<>();
            for (AppInfo app : allApps) {
                if (item.isChecked() && app.isSystemApp) continue;
                filtered.add(app);
            }
            adapter.submit(filtered);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ============================================================
    // ⭐ onDestroy — set flag ก่อน + shutdown แบบสุภาพ
    // ============================================================
    @Override
    protected void onDestroy() {
        destroyed.set(true);
        super.onDestroy();

        try {
            // ⭐ shutdownNow แทน — interrupt งานที่ค้าง
            pool.shutdownNow();
        } catch (Exception ignored) {}
    }
}