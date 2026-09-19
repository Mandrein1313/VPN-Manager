package com.example.vpn.ui;

import android.content.Intent;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.vpn.MainActivity;
import com.example.vpn.ProxyVpnService;
import com.example.vpn.R;
import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.util.StatusBus;
import com.example.vpn.util.VpnLogger;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;

import java.util.List;
import java.util.Locale;

public class ConnectionActivity extends AppCompatActivity {

    public static final String EXTRA_PROFILE_ID = "profile_id";

    // ===== Views =====
    private MaterialToolbar toolbar;
    private TabLayout tabLayout;
    private View contentMain;
    private View contentLog;
    private ConnectButtonView btnConnect;
    private TextView txtStatus;
    private TextView txtServerInfo;
    private TextView txtProfileLeft;
    private TextView txtProfileRight;
    private TextView txtUpload;
    private TextView txtDownload;
    private TextView txtSession;
    private TextView txtLogContent;

    private LinearLayout actionProfiles, actionLog, actionEdit, actionAdd;

    // ===== State =====
    private ProfileViewModel viewModel;
    private Profile targetProfile;
    private long sessionStartTime = 0L;
    private long lastUploadBytes = 0L;
    private long lastDownloadBytes = 0L;
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsRunnable = this::updateStats;

    // ⭐ Launcher: VPN permission
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

    // ⭐ Launcher: จัดการโปรไฟล์ (รอผลลัพธ์)
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
                            }
                        }
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connection);

        // ===== Bind views =====
        toolbar = findViewById(R.id.toolbar);
        tabLayout = findViewById(R.id.tabLayout);
        contentMain = findViewById(R.id.contentMain);
        contentLog = findViewById(R.id.contentLog);
        btnConnect = findViewById(R.id.btnConnect);
        txtStatus = findViewById(R.id.txtStatus);
        txtServerInfo = findViewById(R.id.txtServerInfo);
        txtProfileLeft = findViewById(R.id.txtProfileLeft);
        txtProfileRight = findViewById(R.id.txtProfileRight);
        txtUpload = findViewById(R.id.txtUpload);
        txtDownload = findViewById(R.id.txtDownload);
        txtSession = findViewById(R.id.txtSession);
        txtLogContent = findViewById(R.id.txtLogContent);

        actionProfiles = findViewById(R.id.actionProfiles);
        actionLog = findViewById(R.id.actionLog);
        actionEdit = findViewById(R.id.actionEdit);
        actionAdd = findViewById(R.id.actionAdd);

        // ===== Toolbar =====
        // ⭐ ปุ่ม X = ย่อแอปลง (ไม่ปิดทิ้ง)
        toolbar.setNavigationOnClickListener(v -> moveTaskToBack(true));

        // ===== Tabs =====
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) {
                    contentMain.setVisibility(View.VISIBLE);
                    contentLog.setVisibility(View.GONE);
                } else {
                    contentMain.setVisibility(View.GONE);
                    contentLog.setVisibility(View.VISIBLE);
                    refreshLogView();
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // ===== ViewModel =====
        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        viewModel = new ViewModelProvider(this, new ProfileViewModelFactory(repo))
                .get(ProfileViewModel.class);

        // ===== อ่าน profile จาก intent =====
        long profileId = getIntent().getLongExtra(EXTRA_PROFILE_ID, -1L);
        if (profileId > 0) {
            viewModel.getRepo().getById(profileId, p -> {
                if (p == null) {
                    Toast.makeText(this, "ไม่พบโปรไฟล์", Toast.LENGTH_SHORT).show();
                    return;
                }
                bindProfile(p);
            });
        } else {
            viewModel.getProfiles().observe(this, list -> {
                if (targetProfile != null) return;
                if (list == null || list.isEmpty()) {
                    txtStatus.setText("ยังไม่มีโปรไฟล์ — กด 'จัดการ' เพื่อเพิ่ม");
                    return;
                }
                Profile p = null;
                for (Profile x : list) if (x.isFavorite) { p = x; break; }
                if (p == null) p = list.get(0);
                bindProfile(p);
            });
        }

        // ===== Observe Status =====
        StatusBus.get().observe(this, status -> {
            if (status == null) return;
            txtStatus.setText(status.message);
            btnConnect.setState(mapStatus(status.state));

            if (status.state == StatusBus.State.CONNECTED) {
                if (sessionStartTime == 0L) {
                    sessionStartTime = System.currentTimeMillis();
                    startStatsUpdates();
                }
            } else if (status.state == StatusBus.State.STOPPED
                    || status.state == StatusBus.State.ERROR) {
                stopStatsUpdates();
                sessionStartTime = 0L;
                txtSession.setText("00:00:00");
                txtUpload.setText("0 B");
                txtDownload.setText("0 B");
            }
        });

        // ===== Connect button =====
        btnConnect.setListener(() -> {
            if (targetProfile == null) {
                Toast.makeText(this, "ไม่มีโปรไฟล์ — กด 'จัดการ' เพื่อเพิ่ม",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            switch (btnConnect.getState()) {
                case CONNECTED:
                case CONNECTING:
                    stopVpnService();
                    break;
                case IDLE:
                case ERROR:
                default:
                    requestConnect();
                    break;
            }
        });

        // ===== Bottom actions =====
        // ⭐ ใช้ launcher แทน startActivity
        actionProfiles.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            manageProfilesLauncher.launch(i);
        });

        actionLog.setOnClickListener(v -> {
            tabLayout.selectTab(tabLayout.getTabAt(1));
        });

        actionEdit.setOnClickListener(v -> {
            if (targetProfile == null) {
                Toast.makeText(this, "ไม่มีโปรไฟล์", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, ProfileEditActivity.class);
            i.putExtra(MainActivity.EXTRA_PROFILE_ID, targetProfile.id);
            startActivity(i);
        });

        actionAdd.setOnClickListener(v -> {
            Intent i = new Intent(this, ProfileEditActivity.class);
            startActivity(i);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ⭐ โหลดโปรไฟล์ใหม่เมื่อกลับมาจากการแก้ไข (ไม่ใช่จาก launcher)
        if (targetProfile != null) {
            viewModel.getRepo().getById(targetProfile.id, p -> {
                if (p != null && !p.name.equals(targetProfile.name)) {
                    bindProfile(p);
                } else if (p != null) {
                    // อัปเดต server info (กรณีแก้ host/port)
                    bindProfile(p);
                }
            });
        }
    }

    private void bindProfile(Profile p) {
        targetProfile = p;
        toolbar.setTitle(p.name);

        txtServerInfo.setText("Server: " + p.port + " · " + p.protocol.displayName);
        txtProfileLeft.setText(p.name);
        txtProfileRight.setText(p.user.isEmpty() ? "General" : p.user);
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
        startService(svc);
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
        txtSession.setText(String.format(Locale.US, "%02d:%02d:%02d", h, m, s));

        long rx = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        long tx = TrafficStats.getUidTxBytes(android.os.Process.myUid());
        if (rx < 0) rx = 0;
        if (tx < 0) tx = 0;

        txtDownload.setText(formatBytes(rx - lastDownloadBytes));
        txtUpload.setText(formatBytes(tx - lastUploadBytes));

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

    // ============================================================
    // Log
    // ============================================================
    private void refreshLogView() {
        List<String> lines = VpnLogger.snapshot();
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append('\n');
        txtLogContent.setText(sb.toString());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        statsHandler.removeCallbacks(statsRunnable);
    }
}