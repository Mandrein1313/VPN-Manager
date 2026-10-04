package com.example.vpn.ui;

import android.app.ActivityManager;
import android.content.Intent;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;

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
import com.example.vpn.VpnDisclosurePrefs;
import com.example.vpn.R;
import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.util.StyledToast;
import com.example.vpn.util.RemoteGate;
import com.example.vpn.util.StatusBus;
import com.example.vpn.util.ThemePrefs;
import com.example.vpn.util.VpnLogger;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.List;
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
    private boolean logMenuVisible = false;
    // ค่าการ์ด ACTIVE CONFIG — กัน mainFragment ยังไม่พร้อม
    private String pendingConfigName;
    private String pendingConfigLeft;
    private String pendingConfigRight;

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
                            StyledToast.info(this, "คุณไม่อนุญาตให้ใช้ VPN");
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

        // Remote kill — บล็อกทั้งหน้าเชื่อมต่อ
        RemoteGate.check(this);

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

        // เปิดแท็บจาก Intent (เช่น จากปุ่ม LOGS หน้า CONFIGS)
        handleOpenTabIntent(getIntent());

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                logMenuVisible = (position == 2); // LOG tab
                invalidateOptionsMenu();
            }
        });

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
                    StyledToast.info(this, "ไม่พบโปรไฟล์");
                    reloadProfiles();
                    return;
                }
                bindProfile(p);
            });
        } else {
            reloadProfiles();
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

        View actionHome = findViewById(R.id.actionHome);
        View actionLog = findViewById(R.id.actionLog);
        View actionShareWifi = findViewById(R.id.actionShareWifi);
        View actionAdd = findViewById(R.id.actionAdd);

        // ⭐ Home → กลับแท็บ MAIN (index 0)
        if (actionHome != null) {
            actionHome.setOnClickListener(v -> {
                if (viewPager != null) {
                    viewPager.setCurrentItem(0, true);
                }
                if (drawerLayout != null && drawerLayout.isDrawerOpen(
                        androidx.core.view.GravityCompat.START)) {
                    drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
                }
            });
        }
        // ⭐ กด Log → ไปแท็บ LOG (index 2)
        if (actionLog != null) {
            actionLog.setOnClickListener(v -> viewPager.setCurrentItem(2, true));
        }
        // ⭐ แชร์ VPN (Wi‑Fi) — แทนปุ่มลบ
        if (actionShareWifi != null) {
            actionShareWifi.setOnClickListener(v ->
                    startActivity(new Intent(this, ShareWifiActivity.class)));
        }
        if (actionAdd != null) {
            // ปุ่มเพิ่มโปรไฟล์ — ไม่ใช่ disclosure (disclosure อยู่ตอนกดเชื่อมต่อ)
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
            applyConfigCardUi(); // ใส่ชื่อโปรไฟล์ที่ค้างไว้ตอน fragment ยังไม่พร้อม
            refreshAdFreeCard();
            if (targetProfile != null) {
                bindProfile(targetProfile);
            }
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
            StyledToast.warning(this, "ยังไม่มีโปรไฟล์ — กด 'เพิ่ม' เพื่อสร้าง");
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
        showAdFreeDialog();
    }

    private com.example.vpn.util.AdFreePrefs adFreePrefs;

    private com.example.vpn.util.AdFreePrefs adFree() {
        if (adFreePrefs == null) {
            adFreePrefs = new com.example.vpn.util.AdFreePrefs(this);
        }
        return adFreePrefs;
    }

    private void refreshAdFreeCard() {
        if (mainFragment != null) {
            mainFragment.setAdFreeLabel(adFree().getCardLabel());
        }
    }

    private void showAdFreeDialog() {
        com.example.vpn.util.AdFreePrefs af = adFree();
        String status;
        if (af.isAdFreeActive()) {
            status = "สถานะ: กำลังปลอดโฆษณา\nเหลือ " + af.getCardLabel();
        } else {
            status = "สถานะ: ยังไม่เปิด Ad-free\nคะแนนปัจจุบัน: " + af.getPoints() + " pts";
        }
        status += "\n\n• รับโบนัสรายวัน +" + com.example.vpn.util.AdFreePrefs.DAILY_BONUS_PTS
                + " pts\n• ดูโฆษณา +" + com.example.vpn.util.AdFreePrefs.WATCH_AD_PTS
                + " pts\n• แลก " + com.example.vpn.util.AdFreePrefs.COST_30_MIN
                + " pts = " + com.example.vpn.util.AdFreePrefs.MINUTES_PER_REDEEM + " นาที";

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("⭐ Ad-free time")
                .setMessage(status)
                .setPositiveButton("แลก 30 นาที", (d, w) -> {
                    if (af.redeem30Minutes()) {
                        StyledToast.success(this, "เปิด Ad-free 30 นาทีแล้ว");
                        refreshAdFreeCard();
                    } else {
                        StyledToast.info(this, "คะแนนไม่พอ (ต้องการ "
                                + com.example.vpn.util.AdFreePrefs.COST_30_MIN + " pts)");
                    }
                })
                .setNeutralButton("รับโบนัสวัน", (d, w) -> {
                    if (af.claimDailyBonus()) {
                        StyledToast.success(this, "รับ +"
                                + com.example.vpn.util.AdFreePrefs.DAILY_BONUS_PTS + " pts");
                        refreshAdFreeCard();
                    } else {
                        StyledToast.info(this, "รับโบนัสวันนี้ไปแล้ว");
                    }
                })
                .setNegativeButton("ดูโฆษณา (+pts)", (d, w) -> {
                    af.rewardWatchAd();
                    StyledToast.success(this, "ได้รับ +"
                            + com.example.vpn.util.AdFreePrefs.WATCH_AD_PTS + " pts");
                    refreshAdFreeCard();
                })
                .show();
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
        pendingConfigName = name != null ? name : "Not Set";
        pendingConfigLeft = left != null ? left : "---";
        pendingConfigRight = right != null ? right : "---";
        applyConfigCardUi();
    }

    private void applyConfigCardUi() {
        if (mainFragment == null) return;
        if (mainFragment.getTxtConfigName() != null)
            mainFragment.getTxtConfigName().setText(
                    pendingConfigName != null ? pendingConfigName : "Not Set");
        if (mainFragment.getTxtConfigLeft() != null)
            mainFragment.getTxtConfigLeft().setText(
                    pendingConfigLeft != null ? pendingConfigLeft : "---");
        if (mainFragment.getTxtConfigRight() != null)
            mainFragment.getTxtConfigRight().setText(
                    pendingConfigRight != null ? pendingConfigRight : "---");
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
        // ใช้ getAll ครั้งเดียว — กัน observe ซ้อนจน crash / สถานะเพี้ยน
        viewModel.getRepo().getAllSync(list -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (list == null || list.isEmpty()) {
                targetProfile = null;
                updateStatusText("[ NO PROFILE ]", 0xFF00E676);
                updateConfigCard("Not Set", "---", "---");
                if (mainFragment != null) {
                    try { mainFragment.bindActiveProfile(null, null); } catch (Exception ignored) {}
                }
                toolbar.setTitle("VPN Manager");
                return;
            }
            if (targetProfile != null) {
                // คงโปรไฟล์เดิมถ้ายังอยู่ใน list
                for (Profile x : list) {
                    if (x.id == targetProfile.id) {
                        bindProfile(x);
                        return;
                    }
                }
            }
            Profile p = null;
            for (Profile x : list) if (x.isFavorite) { p = x; break; }
            if (p == null) p = list.get(0);
            bindProfile(p);
        }));
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

    } else if (id == R.id.nav_auto_select) {
            autoSelectLowestPing();
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

    } else if (id == R.id.nav_share_wifi) {
        // ⭐ หน้า Share WiFi
        startActivity(new Intent(this, ShareWifiActivity.class));

    } else if (id == R.id.nav_theme) {
        showThemeDialog();

    } else if (id == R.id.nav_import) {
        StyledToast.info(this, "เปิดหน้า Profile เพื่อ Import");
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
                    StyledToast.info(this, "ธีม: " + ThemePrefs.getModeName(modes[which]));
                    // รีสร้าง Activity ให้สีทั้งจอสลับทันที
                    recreate();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }



    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenTabIntent(intent);
    }

    private void handleOpenTabIntent(android.content.Intent intent) {
        if (intent == null || viewPager == null) return;
        int tab = intent.getIntExtra("open_tab", -1);
        if (tab >= 0 && tab <= 2) {
            viewPager.post(() -> {
                viewPager.setCurrentItem(tab, false);
                logMenuVisible = (tab == 2);
                invalidateOptionsMenu();
            });
        }
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        if (logMenuVisible) {
            getMenuInflater().inflate(R.menu.menu_log_toolbar, menu);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_log_copy) {
            if (logFragment != null) logFragment.copyLogToClipboard();
            return true;
        }
        if (id == R.id.action_log_clear) {
            if (logFragment != null) logFragment.confirmClearLog();
            return true;
        }
        if (id == R.id.action_log_scroll) {
            if (logFragment != null) logFragment.scrollLogToBottom();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
            StyledToast.warning(this, "ไม่มีโปรไฟล์");
            return;
        }
        Intent i = new Intent(this, ProfileEditActivity.class);
        i.putExtra(MainActivity.EXTRA_PROFILE_ID, targetProfile.id);
        startActivity(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Remote kill — บล็อกทุกครั้งที่กลับมาหน้านี้
        RemoteGate.check(this);
        refreshAdFreeCard();

        // ⭐ ถ้า UI บอกว่าไม่เชื่อมต่อ แต่ service flag ยัง running → บังคับหยุด (กันกุญแจค้าง)
        tryCleanupOrphanVpn();

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

    /**
     * เคลียร์เฉพาะกรณี "flag บอกว่า running แต่ service ไม่อยู่จริง"
     * (แอปถูกฆ่า / crash แล้วกุญแจค้าง)
     *
     * สำคัญ: ห้ามใช้ข้อความ UI ตัดสิน — กด X กลับจาก CONFIGS แล้ว onResume
     * จะเจอ UI ยังเป็น NOT CONNECTED ชั่วคราว แล้วไป STOP VPN ผิด → เน็ตค้าง
     */
    private void tryCleanupOrphanVpn() {
        try {
            boolean flagRunning = ProxyVpnService.isServiceRunning(this);
            if (!flagRunning) return;

            boolean serviceAlive = isProxyVpnServiceAlive();
            if (serviceAlive) {
                // VPN กำลังทำงานจริง — อย่าหยุด แค่ sync ปุ่ม
                return;
            }

            // flag = true แต่ process/service ไม่อยู่ = orphan จริง
            android.util.Log.w("ConnectionActivity",
                    "Orphan VPN flag (service not alive) — clearing");
            getSharedPreferences("vpn_state", MODE_PRIVATE)
                    .edit().putBoolean("running", false).apply();
            try {
                Intent stop = new Intent(this, ProxyVpnService.class);
                stop.setAction(ProxyVpnService.ACTION_STOP);
                startService(stop);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            android.util.Log.w("ConnectionActivity",
                    "tryCleanupOrphanVpn: " + e.getMessage());
        }
    }

    /** ตรวจจาก ActivityManager ว่า ProxyVpnService ยังรันอยู่หรือไม่ */
    private boolean isProxyVpnServiceAlive() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            for (ActivityManager.RunningServiceInfo info
                    : am.getRunningServices(Integer.MAX_VALUE)) {
                if (info == null || info.service == null) continue;
                if (ProxyVpnService.class.getName()
                        .equals(info.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }


    /**
     * Auto Select โปรไฟล์ที่ ping ต่ำสุด แล้วตั้งเป็น target
     */
    private void autoSelectLowestPing() {
        List<Profile> list = viewModel.getProfiles().getValue();
        if (list == null || list.isEmpty()) {
            // ยังไม่มี cache — โหลดครั้งเดียว
            viewModel.getProfiles().observe(this, profiles -> {
                viewModel.getProfiles().removeObservers(this);
                runAutoSelectOnList(profiles);
            });
            return;
        }
        runAutoSelectOnList(list);
    }

    private void runAutoSelectOnList(List<Profile> list) {
        if (list == null || list.isEmpty()) {
            StyledToast.warning(this, "ยังไม่มีโปรไฟล์");
            return;
        }
        StyledToast.info(this, "กำลังวัด latency...");
        final java.util.concurrent.atomic.AtomicInteger left =
                new java.util.concurrent.atomic.AtomicInteger(list.size());
        final Profile[] best = {null};
        final int[] bestMs = {Integer.MAX_VALUE};
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

        for (Profile p : list) {
            if (p.host == null || p.host.isEmpty()) {
                if (left.decrementAndGet() == 0) finishAutoSelect(best[0], bestMs[0]);
                continue;
            }
            com.example.vpn.util.LatencyProbe.measure(p.id, p.host, p.port, (id, ms) ->
                    h.post(() -> {
                        if (ms > 0 && ms < bestMs[0]) {
                            bestMs[0] = ms;
                            best[0] = p;
                        }
                        if (left.decrementAndGet() == 0) {
                            finishAutoSelect(best[0], bestMs[0]);
                        }
                    }));
        }
    }

    private void finishAutoSelect(Profile best, int ms) {
        if (best == null) {
            StyledToast.error(this, "วัด ping ไม่สำเร็จ — ลองใหม่");
            return;
        }
        bindProfile(best);
        StyledToast.success(this,
                "Auto Select: " + (best.name != null ? best.name : best.host)
                        + " (" + ms + "ms)");
    }

    private void bindProfile(Profile p) {
        if (p == null) return;
        targetProfile = p;
        toolbar.setTitle("VPN Manager");

        // มีโปรไฟล์แล้ว — อย่าค้างข้อความ NO PROFILE
        if (!ProxyVpnService.isServiceRunning(this)) {
            updateStatusText("[ NOT CONNECTED ]", 0xFF00E676);
        }

        String hostLine = (p.host != null ? p.host : "---");
        String name = (p.name != null && !p.name.isEmpty()) ? p.name : hostLine;
        updateConfigCard(name, hostLine, String.valueOf(p.port));

        if (mainFragment != null) {
            try {
                mainFragment.bindActiveProfile(p, null);
            } catch (Exception ignored) {}
            final long pid = p.id;
            com.example.vpn.util.LatencyProbe.measure(pid, p.host, p.port, (profileId, ms) -> {
                try {
                    if (isFinishing() || isDestroyed()) return;
                    if (targetProfile != null && targetProfile.id == profileId && mainFragment != null) {
                        mainFragment.applyLatency(ms);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    private void requestConnect() {
        if (RemoteGate.isBlocked(this)) {
            RemoteGate.enforceBlock(this);
            return;
        }
        if (targetProfile == null) {
            StyledToast.info(this, "กรุณาเลือกโปรไฟล์ก่อน");
            return;
        }
        // ⭐ Prominent Disclosure (Play policy) — แสดงครั้งเดียวก่อนขอสิทธิ์ VPN
        VpnDisclosurePrefs disclosurePrefs = new VpnDisclosurePrefs(this);
        if (!disclosurePrefs.isAccepted()) {
            showVpnProminentDisclosure(disclosurePrefs);
            return;
        }
        proceedVpnPermission();
    }

    /**
     * Google Play: ต้องแจ้งชัดเจนก่อนเรียก VpnService.prepare()
     * ว่าแอปจะใช้ VPN เพื่อส่งทราฟฟิกผ่านเซิร์ฟเวอร์ที่ผู้ใช้เลือก
     */
    private void showVpnProminentDisclosure(VpnDisclosurePrefs disclosurePrefs) {
        String appName = "Tunnel Mate";
        try {
            CharSequence label = getPackageManager()
                    .getApplicationLabel(getApplicationInfo());
            if (label != null && label.length() > 0) {
                appName = label.toString();
            }
        } catch (Exception ignored) {}

        String message = "แอป " + appName + " ใช้บริการ VPN ของระบบ Android\n\n"
                + "• ทราฟฟิกอินเทอร์เน็ตของอุปกรณ์จะถูกส่งผ่านเซิร์ฟเวอร์ที่คุณเลือก "
                + "(SSH / V2Ray ตามโปรไฟล์)\n"
                + "• ใช้เพื่อการเชื่อมต่อเครือข่ายที่คุณตั้งค่าเองเท่านั้น\n"
                + "• แอปจะไม่ขายข้อมูลทราฟฟิกของคุณ\n\n"
                + "กด «ยอมรับ» เพื่อดำเนินการขอสิทธิ์ VPN จากระบบ";

        new MaterialAlertDialogBuilder(this)
                .setTitle("การใช้บริการ VPN")
                .setMessage(message)
                .setCancelable(false)
                .setNegativeButton("ไม่ยอมรับ", (d, w) ->
                        StyledToast.info(this, "ต้องยอมรับก่อนจึงจะเชื่อมต่อ VPN ได้"))
                .setPositiveButton("ยอมรับ", (d, w) -> {
                    disclosurePrefs.setAccepted(true);
                    proceedVpnPermission();
                })
                .show();
    }

    private void proceedVpnPermission() {
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
            StyledToast.error(this, "ผิดพลาด: " + e.getMessage());
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
