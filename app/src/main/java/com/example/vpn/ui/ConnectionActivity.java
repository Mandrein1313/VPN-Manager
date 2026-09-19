package com.example.vpn.ui;

import android.content.Intent;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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
import com.google.android.material.tabs.TabLayout;

import java.util.List;
import java.util.Locale;

public class ConnectionActivity extends AppCompatActivity {

    public static final String EXTRA_PROFILE_ID = "profile_id";

    // ===== Views =====
    private ImageView btnClose;
    private TextView txtTitle;
    private TabLayout tabLayout;
    private View contentMain;
    private View contentLog;
    private ConnectButtonView btnConnect;
    private TextView txtStatus;

    private View adFreeCard;
    private TextView txtAdFreeTime;

    private LinearLayout configCard;
    private ImageView imgConfigIcon;
    private TextView txtConfigName;
    private TextView txtConfigLeft;
    private TextView txtConfigRight;
    private ImageView btnConfigArrow;

    private TextView txtDownload;
    private TextView txtUpload;
    private TextView txtSession;
    private TextView txtLogContent;

    private LinearLayout actionEdit, actionLog, actionDelete, actionAdd;

    // ===== State =====
    private ProfileViewModel viewModel;
    private Profile targetProfile;
    private long sessionStartTime = 0L;
    private long lastUploadBytes = 0L;
    private long lastDownloadBytes = 0L;
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private final Runnable statsRunnable = this::updateStats;

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
                            }
                        }
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connection);

        // ===== Bind views =====
        btnClose = findViewById(R.id.btnClose);
        txtTitle = findViewById(R.id.txtTitle);
        tabLayout = findViewById(R.id.tabLayout);
        contentMain = findViewById(R.id.contentMain);
        contentLog = findViewById(R.id.contentLog);
        btnConnect = findViewById(R.id.btnConnect);
        txtStatus = findViewById(R.id.txtStatus);

        adFreeCard = findViewById(R.id.adFreeCard);
        txtAdFreeTime = findViewById(R.id.txtAdFreeTime);

        configCard = findViewById(R.id.configCard);
        imgConfigIcon = findViewById(R.id.imgConfigIcon);
        txtConfigName = findViewById(R.id.txtConfigName);
        txtConfigLeft = findViewById(R.id.txtConfigLeft);
        txtConfigRight = findViewById(R.id.txtConfigRight);
        btnConfigArrow = findViewById(R.id.btnConfigArrow);

        txtDownload = findViewById(R.id.txtDownload);
        txtUpload = findViewById(R.id.txtUpload);
        txtSession = findViewById(R.id.txtSession);
        txtLogContent = findViewById(R.id.txtLogContent);

        actionEdit = findViewById(R.id.actionEdit);
        actionLog = findViewById(R.id.actionLog);
        actionDelete = findViewById(R.id.actionDelete);
        actionAdd = findViewById(R.id.actionAdd);

        // ===== Buttons =====
        btnClose.setOnClickListener(v -> moveTaskToBack(true));

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
            @Override public void onTabReselected(TabLayout.Tab tab) {
                if (tab.getPosition() == 1) refreshLogView();
            }
        });

        // ===== ViewModel =====
        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        viewModel = new ViewModelProvider(this, new ProfileViewModelFactory(repo))
                .get(ProfileViewModel.class);

        // ===== Profile จาก intent =====
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
                    txtStatus.setText("[ NO PROFILE ]");
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

            // Status text
            switch (status.state) {
                case CONNECTED:
                    txtStatus.setText("[ CONNECTED ]");
                    txtStatus.setTextColor(0xFF00E676);
                    break;
                case ERROR:
                    txtStatus.setText("[ ERROR ]");
                    txtStatus.setTextColor(0xFFEF5350);
                    break;
                case CONNECTING_SSH:
                case SSH_CONNECTED:
                case SOCKS_READY:
                case TUN2SOCKS_READY:
                    txtStatus.setText("[ CONNECTING... ]");
                    txtStatus.setTextColor(0xFFFFA726);
                    break;
                case STOPPED:
                case IDLE:
                default:
                    txtStatus.setText("[ NOT CONNECTED ]");
                    txtStatus.setTextColor(0xFF00E676);
                    break;
            }

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
                Toast.makeText(this, "ไม่มีโปรไฟล์ — กด 'เพิ่ม' เพื่อสร้าง",
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

        // ===== Config card =====
        configCard.setOnClickListener(v -> openEditForCurrent());
        btnConfigArrow.setOnClickListener(v -> openEditForCurrent());

        // ===== Ad-free (placeholder) =====
        adFreeCard.setOnClickListener(v ->
                Toast.makeText(this, "Ad-free time — เร็วๆ นี้",
                        Toast.LENGTH_SHORT).show());

        // ===== Bottom actions =====
        actionEdit.setOnClickListener(v -> openEditForCurrent());

        actionLog.setOnClickListener(v ->
                tabLayout.selectTab(tabLayout.getTabAt(1)));

        actionDelete.setOnClickListener(v -> confirmDeleteCurrent());

        actionAdd.setOnClickListener(v -> {
            Intent i = new Intent(this, ProfileEditActivity.class);
            startActivity(i);
        });
    }

    private void confirmDeleteCurrent() {
        if (targetProfile == null) return;
        new AlertDialog.Builder(this)
                .setTitle("ลบโปรไฟล์?")
                .setMessage("คุณต้องการลบ \"" + targetProfile.name + "\" ใช่หรือไม่?")
                .setPositiveButton("ลบ", (d, w) -> {
                    viewModel.delete(targetProfile);
                    targetProfile = null;

                    // โหลดโปรไฟล์อื่นแทน
                    viewModel.getProfiles().observe(this, list -> {
                        if (list == null || list.isEmpty()) {
                            txtStatus.setText("[ NO PROFILE ]");
                            txtConfigName.setText("Not Set");
                            txtConfigLeft.setText("---");
                            txtConfigRight.setText("---");
                            txtTitle.setText("VPN");
                            return;
                        }
                        Profile next = null;
                        for (Profile x : list) if (x.isFavorite) { next = x; break; }
                        if (next == null) next = list.get(0);
                        bindProfile(next);
                    });
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
        if (targetProfile != null) {
            viewModel.getRepo().getById(targetProfile.id, p -> {
                if (p != null) bindProfile(p);
            });
        }
    }

    private void bindProfile(Profile p) {
        targetProfile = p;
        txtTitle.setText(p.name);

        // Config card
        txtConfigName.setText(p.name);
        txtConfigLeft.setText(p.host);
        txtConfigRight.setText(String.valueOf(p.port));

        // Icon ตาม protocol
        if (p.protocol == com.example.vpn.model.Protocol.SSH) {
            imgConfigIcon.setImageResource(android.R.drawable.ic_lock_lock);
        } else {
            imgConfigIcon.setImageResource(android.R.drawable.ic_menu_upload);
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

    // ===== Stats =====
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

    // ===== Log =====
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