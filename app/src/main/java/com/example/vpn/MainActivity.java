package com.example.vpn;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.ui.CrashLogActivity;
import com.example.vpn.ui.LogViewerActivity;
import com.example.vpn.ui.ProfileAdapter;
import com.example.vpn.ui.ProfileEditActivity;
import com.example.vpn.ui.ProfileViewModel;
import com.example.vpn.ui.ProfileViewModelFactory;
import com.example.vpn.util.ConfigParser;
import com.example.vpn.util.CrashHandler;
import com.example.vpn.util.StatusBus;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

public class MainActivity extends AppCompatActivity implements ProfileAdapter.Listener {

    public static final String EXTRA_PROFILE_ID = "profile_id";
    public static final int REQ_EDIT = 100;

    private ProfileViewModel viewModel;
    private ProfileAdapter adapter;
    private LinearLayout emptyState;
    private RecyclerView recycler;
    private ExtendedFloatingActionButton fabImport;

    // ⭐ Status Bar
    private View statusDot;
    private TextView txtStatus;
    private MaterialButton btnLog;

    private Profile pendingProfile;

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && pendingProfile != null) {
                            startVpnService(pendingProfile);
                        } else {
                            Toast.makeText(this,
                                    "คุณไม่อนุญาตให้ใช้ VPN",
                                    Toast.LENGTH_SHORT).show();
                        }
                        pendingProfile = null;
                    });

    private final ActivityResultLauncher<String> notifPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> { /* ไม่เป็นไรถ้าไม่อนุญาต */ });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // ⭐ ติดตั้ง CrashHandler ก่อนทุกอย่าง
        CrashHandler.install(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_list);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }

        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        viewModel = new ViewModelProvider(this, new ProfileViewModelFactory(repo))
                .get(ProfileViewModel.class);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("โปรไฟล์ทั้งหมด");
        }

        emptyState = findViewById(R.id.emptyState);
        recycler = findViewById(R.id.recyclerProfiles);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProfileAdapter(this);
        recycler.setAdapter(adapter);

        ExtendedFloatingActionButton fab = findViewById(R.id.fabAdd);
        fab.setOnClickListener(v -> openEdit(null));

        fabImport = findViewById(R.id.fabImport);
        fabImport.setOnClickListener(v -> importFromClipboard());

        // ⭐ Status Bar
        statusDot = findViewById(R.id.statusDot);
        txtStatus = findViewById(R.id.txtStatus);
        btnLog = findViewById(R.id.btnLog);

        if (btnLog != null) {
            // กดปกติ → เปิด Log Viewer
            btnLog.setOnClickListener(v ->
                    startActivity(new Intent(this, LogViewerActivity.class)));

            // กดค้าง → เปิด Crash Log
            btnLog.setOnLongClickListener(v -> {
                startActivity(new Intent(this, CrashLogActivity.class));
                return true;
            });
        }

        StatusBus.get().observe(this, status -> {
            if (status == null) return;
            if (txtStatus != null) txtStatus.setText(status.message);
            updateStatusDot(status.state);
        });

        viewModel.getProfiles().observe(this, list -> {
            adapter.submit(list);
            boolean empty = list == null || list.isEmpty();
            emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        });
    }

    // ⭐ อัปเดตสี dot ตามสถานะ
    private void updateStatusDot(StatusBus.State state) {
        if (statusDot == null) return;
        int color;
        switch (state) {
            case CONNECTED:
                color = 0xFF4CAF50;  // green
                break;
            case ERROR:
                color = 0xFFE53935;  // red
                break;
            case CONNECTING_SSH:
            case SSH_CONNECTED:
            case SOCKS_READY:
            case TUN2SOCKS_READY:
                color = 0xFFFFA726;  // orange
                break;
            case IDLE:
            case STOPPED:
            default:
                color = 0xFF888888;  // gray
                break;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        statusDot.setBackground(bg);
    }

    private void openEdit(@Nullable Profile profile) {
        Intent i = new Intent(this, ProfileEditActivity.class);
        if (profile != null) i.putExtra(EXTRA_PROFILE_ID, profile.id);
        startActivityForResult(i, REQ_EDIT);
    }

    // ================== VPN Connection ==================

    @Override
    public void onConnect(Profile p) {
        viewModel.markUsed(p);

        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            pendingProfile = p;
            vpnPermissionLauncher.launch(prepare);
            return;
        }
        startVpnService(p);
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
            Toast.makeText(this,
                    "กำลังเชื่อมต่อ: " + p.name,
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this,
                    "เกิดข้อผิดพลาด: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ============================================================
    // Import from clipboard
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
            new AlertDialog.Builder(this)
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

        new AlertDialog.Builder(this)
                .setTitle("ยืนยันการ Import")
                .setMessage(msg)
                .setPositiveButton("บันทึก", (d, w) ->
                        viewModel.save(profile, id -> Toast.makeText(this,
                                "Import สำเร็จ: " + profile.name,
                                Toast.LENGTH_SHORT).show()))
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

    // ================== Adapter Callbacks ==================

    @Override
    public void onEdit(Profile p) {
        openEdit(p);
    }

    @Override
    public void onDelete(Profile p) {
        new AlertDialog.Builder(this)
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
}