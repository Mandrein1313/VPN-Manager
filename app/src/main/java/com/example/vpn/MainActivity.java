package com.example.vpn;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
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
import com.example.vpn.ui.ProfileAdapter;
import com.example.vpn.ui.ProfileEditActivity;
import com.example.vpn.ui.ProfileViewModel;
import com.example.vpn.ui.ProfileViewModelFactory;
import com.example.vpn.util.ConfigParser;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

public class MainActivity extends AppCompatActivity implements ProfileAdapter.Listener {

    public static final String EXTRA_PROFILE_ID = "profile_id";
    public static final int REQ_EDIT = 100;

    private ProfileViewModel viewModel;
    private ProfileAdapter adapter;
    private LinearLayout emptyState;
    private RecyclerView recycler;
    private ExtendedFloatingActionButton fabImport;

    /** เก็บโปรไฟล์ที่รอ request VPN permission อยู่ */
    private Profile pendingProfile;

    /** Launcher สำหรับขอ VPN permission (Activity Result API) */
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

    /** Launcher สำหรับขอ POST_NOTIFICATIONS (Android 13+) */
    private final ActivityResultLauncher<String> notifPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        // ไม่เป็นไรถ้าไม่อนุญาต — VPN ยังทำงานได้
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_list);

        // ขอ POST_NOTIFICATIONS บน Android 13+
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

        viewModel.getProfiles().observe(this, list -> {
            adapter.submit(list);
            boolean empty = list == null || list.isEmpty();
            emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        });
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

        // 1. เช็คว่าผู้ใช้เคยอนุญาต VPN แล้วหรือยัง
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            // ยังไม่อนุญาต → เก็บไว้ก่อน แล้วเปิด dialog ขออนุญาต
            pendingProfile = p;
            vpnPermissionLauncher.launch(prepare);
            return;
        }

        // 2. อนุญาตแล้ว → start service
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

        CharSequence text = cm.getPrimaryClip()
                .getItemAt(0)
                .coerceToText(this);

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

        String msg = "Host: " + profile.host + "\n"
                + "Port: " + profile.port + "\n"
                + "User: " + (profile.user.isEmpty() ? "(ว่าง)" : profile.user) + "\n"
                + "Pass: " + (profile.pass.isEmpty() ? "(ว่าง)" : "••••••") + "\n\n"
                + "ต้องการบันทึกเป็นโปรไฟล์ใหม่หรือไม่?";

        new AlertDialog.Builder(this)
                .setTitle("ยืนยันการ Import")
                .setMessage(msg)
                .setPositiveButton("บันทึก", (d, w) -> {
                    viewModel.save(profile, id -> {
                        Toast.makeText(this,
                                "Import สำเร็จ: " + profile.name,
                                Toast.LENGTH_SHORT).show();
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
