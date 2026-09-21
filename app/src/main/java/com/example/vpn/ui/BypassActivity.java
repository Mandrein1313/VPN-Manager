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
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

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

        // Search
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
    // Load apps (background thread)
    // ============================================================
    private void loadApps() {
        loadingView.setVisibility(View.VISIBLE);
        recycler.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);

        pool.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(
                    PackageManager.GET_META_DATA);

            Set<String> bypassed = prefs.getPackages();
            List<AppInfo> result = new ArrayList<>();

            for (ApplicationInfo ai : apps) {
                // ข้ามแอปตัวเอง
                if (ai.packageName.equals(getPackageName())) continue;

                try {
                    String label = ai.loadLabel(pm).toString();
                    AppInfo info = new AppInfo(
                            ai.packageName,
                            label,
                            null,   // ไม่โหลด icon ที่นี่ (ใช้ memory เยอะ)
                            (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                            bypassed.contains(ai.packageName)
                    );
                    result.add(info);
                } catch (Exception ignored) {}
            }

            // เรียงตามชื่อ
            Collections.sort(result, (a, b) ->
                    a.appName.compareToIgnoreCase(b.appName));

            runOnUiThread(() -> {
                allApps.clear();
                allApps.addAll(result);
                adapter.submit(result);
                loadingView.setVisibility(View.GONE);
                recycler.setVisibility(View.VISIBLE);
                emptyView.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                updateCount();

                // โหลด icon แบบ lazy
                loadIcons();
            });
        });
    }

    /** ⭐ โหลด icon แบบ lazy ทีละตัว — ประหยัด memory */
    private void loadIcons() {
        pool.execute(() -> {
            PackageManager pm = getPackageManager();
            for (int i = 0; i < allApps.size(); i++) {
                AppInfo info = allApps.get(i);
                try {
                    info.icon = pm.getApplicationIcon(info.packageName);
                } catch (Exception ignored) {}

                final int index = i;
                if (index % 5 == 0) {
                    runOnUiThread(() -> {
                        if (index < adapter.getItemCount()) {
                            adapter.notifyItemChanged(index);
                        }
                    });
                }
            }
            runOnUiThread(() -> adapter.notifyDataSetChanged());
        });
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}