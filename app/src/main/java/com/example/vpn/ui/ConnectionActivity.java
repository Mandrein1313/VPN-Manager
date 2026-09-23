package com.example.vpn.ui;

import android.content.Intent;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.example.vpn.MainActivity;
import com.example.vpn.ProxyVpnService;
import com.example.vpn.R;
import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.util.StatusBus;
import com.example.vpn.util.ThemePrefs;
import com.example.vpn.util.VpnLogger;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.Locale;

public class ConnectionActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener,
                   MainFragment.Listener {

    public static final String EXTRA_PROFILE_ID = "profile_id";

    private DrawerLayout drawerLayout;
    private NavigationView navView;
    private MaterialToolbar toolbar;
    private TabLayout tabLayout;
    private ViewPager2 viewPager;

    private MainFragment mainFragment;
    private LogFragment logFragment;

    private ProfileViewModel viewModel;
    private Profile targetProfile;
    private long sessionStartTime = 0L;
    private long lastUploadBytes = 0L;
    private long lastDownloadBytes = 0L;

    private boolean skipNextResumeReload = false;

    private ThemePrefs themePrefs;

    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsRunnable = this::updateStats;

    // ============================================================
    // Launchers
    // ============================================================
    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && targetProfile != null) {
                            startVpnService(targetProfile);
                        } else {
                            Toast.makeText(this, "คุณไม่อนุญาตให้ใช้ VPN",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });

    private final ActivityResultLauncher<Intent> manageProfilesLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK
                                && result.getData() != null) {
                            long profileId = result.getData()
                                    .getLongExtra(EXTRA_PROFILE_ID, -1L);
                            if (profileId > 0) {
                                viewModel.getRepo().getById(profileId, p -> {
                                    if (p != null) bindProfile(p);
                                });
                                return;
                            }
                        }
                        reloadProfiles();
                    });

    private final ActivityResultLauncher<Intent> addProfileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> reloadProfiles());

    // ============================================================
    // Lifecycle
    // ============================================================
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connection);

        themePrefs = new ThemePrefs(this);

        drawerLayout = findViewById(R.id.drawerLayout);
        navView = findViewById(R.id.navView);
        toolbar = findViewById(R.id.toolbar);
        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);

        ConnectionPagerAdapter pagerAdapter = new ConnectionPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setUserInputEnabled(true);

        // ⭐ 3 Tabs: MAIN / CHART / LOG
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText("MAIN");
                    break;
                case 1:
                    tab.setText("CHART");
                    break;
                case 2:
                    tab.setText("LOG");
                    break;
            }
        }).attach();

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setTitle("VPN Manager");

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.app_name, R.string.app_name);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        navView.setNavigationItemSelectedListener(this);

        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        viewModel = new ViewModelProvider(this, new ProfileViewModelFactory(repo))
                .get(ProfileViewModel.class);

        long profileId = getIntent().getLongExtra(EXTRA_PROFILE_ID, -1L);
        if (profileId > 0) {
            viewModel.getRepo().getById(profileId, p -> {
                if (p == null) {
                    Toast.makeText(this, "ไม่พบโปรไฟล์", Toast.LENGTH_SHORT).show();
                    reloadProfiles();
                    return;
                }
                bindProfile(p);
            });
        } else {
            viewModel.getProfiles().observe(this, list -> {
                if (targetProfile != null) return;
                if (list == null || list.isEmpty()) {
                    updateStatusText("[ NO PROFILE ]", 0xFF00E676);
                    updateConfigCard("Not Set", "---", "---");
                    toolbar.setTitle("VPN Manager");
                    return;
                }
                Profile p = null;
                for (Profile x : list) if (x.isFavorite) { p = x; break; }
                if (p == null) p = list.get(0);
                bindProfile(p);
            });
        }

        // ⭐ Observe Status
        StatusBus.get().observe(this, status -> {
            if (status == null) return;

            switch (status.state) {
                case CONNECTED:
                    updateStatusText("[ CONNECTED ]", 0xFF00E676);
                    break;
                case ERROR:
                    updateStatusText("[ ERROR ]", 0xFFEF5350);
                    break;
                case CONNECTING_SSH:
                case SSH_CONNECTED:
                case SOCKS_READY:
                case TUN2SOCKS_READY:
                    updateStatusText("[ CONNECTING... ]", 0xFFFFA726);
                    break;
                case STOPPED:
                case IDLE:
                default:
                    updateStatusText("[ NOT CONNECTED ]", 0xFF00E676);
                    break;
            }

            if (mainFragment != null && mainFragment.getConnectButton() != null) {
                mainFragment.getConnectButton().setState(mapStatus(status.state));
            }

            if (status.state == StatusBus.State.CONNECTED) {
                if (sessionStartTime == 0L) {
                    sessionStartTime = System.currentTimeMillis();
                    startStatsUpdates();
                }
            } else if (status.state == StatusBus.State.STOPPED
                    || status.state == StatusBus.State.ERROR) {
                stopStatsUpdates();
                sessionStartTime = 0L;
                if (mainFragment != null) {
                    if (mainFragment.getTxtSession() != null)
                        mainFragment.getTxtSession().setText("00:00:00");
                    if (mainFragment.getTxtUpload() != null)
                        mainFragment.getTxtUpload().setText("0 B");
                    if (mainFragment.getTxtDownload() != null)
                        mainFragment.getTxtDownload().setText("0 B");
                }
            }
        });

        View actionEdit = findViewById(R.id.actionEdit);
        View actionLog = findViewById(R.id.actionLog);
        View actionDelete = findViewById(R.id.actionDelete);
        View actionAdd = findViewById(R.id.actionAdd);

        if (actionEdit != null) {
            actionEdit.setOnClickListener(v -> {
                if (targetProfile == null) {
                    Toast.makeText(this, "ยังไม่มีโปรไฟล์",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                openEditForCurrent();
            });
        }
        // ⭐ กด Log → ไปแท็บ LOG (index 2)
        if (actionLog != null) {
            actionLog.setOnClickListener(v -> viewPager.setCurrentItem(2, true));
        }
        if (actionDelete != null) {
            actionDelete.setOnClickListener(v -> {
                if (targetProfile == null) {
                    Toast.makeText(this, "ไม่มีโปรไฟล์ให้ลบ",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                confirmDeleteCurrent();
            });
        }
        if (actionAdd != null) {
            actionAdd.setOnClickListener(v -> {
                Intent i = new Intent(this, ProfileEditActivity.class);
                addProfileLauncher.launch(i);
            });
        }
    }

    @Override
    public void onAttachFragment(@NonNull androidx.fragment.app.Fragment fragment) {
        super.onAttachFragment(fragment);
        if (fragment instanceof MainFragment) {
            mainFragment = (MainFragment) fragment;
            mainFragment.setListener(this);
        } else if (fragment instanceof LogFragment) {
            logFragment = (LogFragment) fragment;
        }
    }

    // ============================================================
    // MainFragment.Listener
    // ============================================================
    @Override
    public void onMainConnectClick() {
        if (targetProfile == null) {
            Toast.makeText(this, "ยังไม่มีโปรไฟล์ — กด 'เพิ่ม' เพื่อสร้าง",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        boolean serviceRunning = ProxyVpnService.isServiceRunning(this);

        if (serviceRunning) {
            VpnLogger.i("ConnectionActivity", "Service is running — stopping");
            stopVpnService();
            return;
        }

        if (mainFragment != null && mainFragment.getConnectButton() != null) {
            ConnectButtonView.State state = mainFragment.getConnectButton().getState();
            if (state == ConnectButtonView.State.CONNECTING) {
                stopVpnService();
                return;
            }
        }

        requestConnect();
    }

    @Override
    public void onConfigCardClick() {
        openProfilePicker();
    }

    @Override
    public void onAdFreeClick() {
        Toast.makeText(this, "Ad-free time — เร็วๆ นี้",
                Toast.LENGTH_SHORT).show();
    }

    // ============================================================
    // Update UI helpers
    // ============================================================
    private void updateStatusText(String text, int color) {
        if (mainFragment == null || mainFragment.getTxtStatus() == null) return;
        mainFragment.getTxtStatus().setText(text);
        mainFragment.getTxtStatus().setTextColor(color);
    }

    private void updateConfigCard(String name, String left, String right) {
        if (mainFragment == null) return;
        if (mainFragment.getTxtConfigName() != null)
            mainFragment.getTxtConfigName().setText(name);
        if (mainFragment.getTxtConfigLeft() != null)
            mainFragment.getTxtConfigLeft().setText(left);
        if (mainFragment.getTxtConfigRight() != null)
            mainFragment.getTxtConfigRight().setText(right);
    }

    private void syncButtonState() {
        if (mainFragment == null || mainFragment.getConnectButton() == null) return;

        boolean serviceRunning = ProxyVpnService.isServiceRunning(this);
        ConnectButtonView.State currentState = mainFragment.getConnectButton().getState();

        if (!serviceRunning && currentState == ConnectButtonView.State.CONNECTED) {
            mainFragment.getConnectButton().setState(ConnectButtonView.State.IDLE);
            updateStatusText("[ NOT CONNECTED ]", 0xFF00E676);
            VpnLogger.i("ConnectionActivity", "Synced to IDLE");
        } else if (serviceRunning && currentState == ConnectButtonView.State.IDLE) {
            mainFragment.getConnectButton().setState(ConnectButtonView.State.CONNECTED);
            updateStatusText("[ CONNECTED ]", 0xFF00E676);
            VpnLogger.i("ConnectionActivity", "Synced to CONNECTED");
        }
    }

    private void reloadProfiles() {
        viewModel.getProfiles().observe(this, list -> {
            if (targetProfile != null) return;
            if (list == null || list.isEmpty()) {
                updateStatusText("[ NO PROFILE ]", 0xFF00E676);
                updateConfigCard("Not Set", "---", "---");
                toolbar.setTitle("VPN Manager");
                return;
            }
            Profile p = null;
            for (Profile x : list) if (x.isFavorite) { p = x; break; }
            if (p == null) p = list.get(0);
            bindProfile(p);
        });
    }

    // ============================================================
    // Navigation Drawer
    // ============================================================
    @Override
public boolean onNavigationItemSelected(@NonNull MenuItem item) {
    int id = item.getItemId();

    if (id == R.id.nav_home) {
        viewPager.setCurrentItem(0, true);
    } else if (id == R.id.nav_profiles) {
        openProfilePicker();
    } else if (id == R.id.nav_chart) {
        // ⭐ ไปหน้า CHART
        viewPager.setCurrentItem(1, true);
    } else if (id == R.id.nav_log) {
        // ⭐ ไปหน้า LOG
        viewPager.setCurrentItem(2, true);
    } else if (id == R.id.nav_crash) {
        startActivity(new Intent(this, CrashLogActivity.class));
    } else if (id == R.id.nav_bypass) {
        startActivity(new Intent(this, BypassActivity.class));
    } else if (id == R.id.nav_backup) {
        // ⭐ หน้า Backup/Restore
        startActivity(new Intent(this, BackupActivity.class));
    } else if (id == R.id.nav_theme) {
        showThemeDialog();
    } else if (id == R.id.nav_import) {
        Toast.makeText(this, "เปิดหน้า Profile เพื่อ Import",
                Toast.LENGTH_SHORT).show();
        openProfilePicker();
    } else if (id == R.id.nav_about) {
        showAboutDialog();
    } else if (id == R.id.nav_exit) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("ออกจากแอป?")
                .setMessage("คุณต้องการปิดแอปทั้งหมดหรือไม่?")
                .setPositiveButton("ออก", (d, w) -> {
                    stopVpnService();
                    finishAffinity();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    drawerLayout.closeDrawer(GravityCompat.START);
    return true;
}
    private void showAboutDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("เกี่ยวกับ VPN Manager")
                .setMessage("VPN Manager v1.0\n\n" +
                        "แอป VPN ที่รองรับ SSH Tunnel\n" +
                        "และหลาย protocol\n\n" +
                        "สร้างด้วย ❤️ ในประเทศไทย")
                .setPositiveButton("ตกลง", null)
                .show();
    }

    // ============================================================
    // ⭐ Theme Picker Dialog
    // ============================================================
    private void showThemeDialog() {
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

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else if (viewPager.getCurrentItem() != 0) {
            viewPager.setCurrentItem(0, true);
        } else {
            super.onBackPressed();
        }
    }

    private void openProfilePicker() {
        skipNextResumeReload = true;
        Intent i = new Intent(this, MainActivity.class);
        manageProfilesLauncher.launch(i);
    }

    private void confirmDeleteCurrent() {
        if (targetProfile == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("ลบโปรไฟล์?")
                .setMessage("คุณต้องการลบ \"" + targetProfile.name + "\" ใช่หรือไม่?")
                .setPositiveButton("ลบ", (d, w) -> {
                    viewModel.delete(targetProfile);
                    targetProfile = null;
                    reloadProfiles();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void openEditForCurrent() {
        if (targetProfile == null) {
            Toast.makeText(this, "ไม่มีโปรไฟล์", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, ProfileEditActivity.class);
        i.putExtra(MainActivity.EXTRA_PROFILE_ID, targetProfile.id);
        startActivity(i);
    }

    @Override
    protected void onResume() {
        super.onResume();

        syncButtonState();

        if (skipNextResumeReload) {
            skipNextResumeReload = false;
            return;
        }
        if (targetProfile != null) {
            viewModel.getRepo().getById(targetProfile.id, p -> {
                if (p != null) {
                    bindProfile(p);
                } else {
                    targetProfile = null;
                    reloadProfiles();
                }
            });
        } else {
            reloadProfiles();
        }
    }

    private void bindProfile(Profile p) {
        targetProfile = p;
        toolbar.setTitle(p.name);

        updateConfigCard(p.name, p.host, String.valueOf(p.port));

        if (mainFragment != null && mainFragment.getImgConfigIcon() != null) {
            if (p.protocol == com.example.vpn.model.Protocol.SSH) {
                mainFragment.getImgConfigIcon().setImageResource(
                        android.R.drawable.ic_lock_lock);
            } else {
                mainFragment.getImgConfigIcon().setImageResource(
                        android.R.drawable.ic_menu_upload);
            }
        }
    }

    private void requestConnect() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare);
            return;
        }
        startVpnService(targetProfile);
    }

    private void startVpnService(Profile p) {
        Intent svc = new Intent(this, ProxyVpnService.class);
        svc.setAction(ProxyVpnService.ACTION_START);
        svc.putExtra(ProxyVpnService.EXTRA_PROFILE_ID, p.id);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Toast.makeText(this, "ผิดพลาด: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void stopVpnService() {
        Intent svc = new Intent(this, ProxyVpnService.class);
        svc.setAction(ProxyVpnService.ACTION_STOP);
        try {
            startService(svc);
        } catch (Exception e) {
            VpnLogger.w("ConnectionActivity", "stopVpnService error: " + e.getMessage());
        }
    }

    private ConnectButtonView.State mapStatus(StatusBus.State s) {
        switch (s) {
            case CONNECTING_SSH:
            case SSH_CONNECTED:
            case SOCKS_READY:
            case TUN2SOCKS_READY:
                return ConnectButtonView.State.CONNECTING;
            case CONNECTED:
                return ConnectButtonView.State.CONNECTED;
            case ERROR:
                return ConnectButtonView.State.ERROR;
            case IDLE:
            case STOPPED:
            default:
                return ConnectButtonView.State.IDLE;
        }
    }

    // ============================================================
    // Stats
    // ============================================================
    private void startStatsUpdates() {
        statsHandler.removeCallbacks(statsRunnable);
        statsHandler.post(statsRunnable);
    }

    private void stopStatsUpdates() {
        statsHandler.removeCallbacks(statsRunnable);
    }

    private void updateStats() {
        if (sessionStartTime == 0L) return;

        long elapsed = System.currentTimeMillis() - sessionStartTime;
        long h = elapsed / 3_600_000L;
        long m = (elapsed % 3_600_000L) / 60_000L;
        long s = (elapsed % 60_000L) / 1000L;

        if (mainFragment != null) {
            if (mainFragment.getTxtSession() != null) {
                mainFragment.getTxtSession().setText(
                        String.format(Locale.US, "%02d:%02d:%02d", h, m, s));
            }
        }

        long rx = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        long tx = TrafficStats.getUidTxBytes(android.os.Process.myUid());
        if (rx < 0) rx = 0;
        if (tx < 0) tx = 0;

        if (mainFragment != null) {
            if (mainFragment.getTxtDownload() != null)
                mainFragment.getTxtDownload().setText(
                        formatBytes(rx - lastDownloadBytes));
            if (mainFragment.getTxtUpload() != null)
                mainFragment.getTxtUpload().setText(
                        formatBytes(tx - lastUploadBytes));
        }

        statsHandler.postDelayed(statsRunnable, 1000);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB",
                bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        statsHandler.removeCallbacks(statsRunnable);
        VpnLogger.setListener(null);
    }
}
