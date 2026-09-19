package com.example.vpn;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import com.example.vpn.ui.ConnectionActivity;
import com.example.vpn.ui.CrashLogActivity;
import com.example.vpn.ui.LogViewerActivity;
import com.example.vpn.ui.ProfileAdapter;
import com.example.vpn.ui.ProfileEditActivity;
import com.example.vpn.ui.ProfileViewModel;
import com.example.vpn.ui.ProfileViewModelFactory;
import com.example.vpn.util.ConfigParser;
import com.example.vpn.util.CrashHandler;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

public class MainActivity extends AppCompatActivity implements ProfileAdapter.Listener {

    public static final String EXTRA_PROFILE_ID = "profile_id";
    public static final int REQ_EDIT = 100;

    private ProfileViewModel viewModel;
    private ProfileAdapter adapter;
    private LinearLayout emptyState;
    private RecyclerView recycler;

    private View btnAddConfig;
    private View btnImportClipboard;
    private View btnBack;

    private View navHome, navLogs, navMore;

    private final ActivityResultLauncher<String> notifPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> { /* ไม่เป็นไร */ });

    private final ActivityResultLauncher<Intent> addProfileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> { /* list observe เอง */ });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        CrashHandler.install(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_list);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }

        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        viewModel = new ViewModelProvider(this, new ProfileViewModelFactory(repo))
                .get(ProfileViewModel.class);

        // ===== Bind views =====
        btnBack = findViewById(R.id.btnBack);
        emptyState = findViewById(R.id.emptyState);
        recycler = findViewById(R.id.recyclerProfiles);
        btnAddConfig = findViewById(R.id.btnAddConfig);
        btnImportClipboard = findViewById(R.id.btnImportClipboard);
        navHome = findViewById(R.id.navHome);
        navLogs = findViewById(R.id.navLogs);
        navMore = findViewById(R.id.navMore);

        // ===== Back button =====
        btnBack.setOnClickListener(v -> finish());

        // ===== List =====
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProfileAdapter(this);
        recycler.setAdapter(adapter);

        // ===== Empty state buttons =====
        btnAddConfig.setOnClickListener(v -> {
            Intent i = new Intent(this, ProfileEditActivity.class);
            addProfileLauncher.launch(i);
        });

        btnImportClipboard.setOnClickListener(v -> importFromClipboard());

        // ===== Bottom nav =====
        navHome.setOnClickListener(v -> finish());   // กลับไป ConnectionActivity

        navLogs.setOnClickListener(v ->
                startActivity(new Intent(this, LogViewerActivity.class)));

        navMore.setOnClickListener(v -> {
            String[] options = {"Crash Log", "เกี่ยวกับ"};
            new AlertDialog.Builder(this)
                    .setItems(options, (d, which) -> {
                        if (which == 0) {
                            startActivity(new Intent(this, CrashLogActivity.class));
                        } else {
                            new AlertDialog.Builder(this)
                                    .setTitle("เกี่ยวกับ VPN Manager")
                                    .setMessage("VPN Manager v1.0\n\n" +
                                            "แอป VPN ที่รองรับ SSH Tunnel\n" +
                                            "สร้างด้วย ❤️ ในประเทศไทย")
                                    .setPositiveButton("ตกลง", null)
                                    .show();
                        }
                    })
                    .show();
        });

        // ===== Observe profiles =====
        viewModel.getProfiles().observe(this, list -> {
            adapter.submit(list);
            boolean empty = list == null || list.isEmpty();
            emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        });
    }

    // ============================================================
    // Adapter callbacks
    // ============================================================

    @Override
    public void onConnect(Profile p) {
        // ⭐ เลือกโปรไฟล์ → ส่งกลับ ConnectionActivity ทันที
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

    // ============================================================
    // onBackPressed — กลับไป ConnectionActivity
    // ============================================================
    @Override
    public void onBackPressed() {
        // ถ้าไม่ได้เลือกโปรไฟล์ — แค่ finish กลับ
        super.onBackPressed();
    }
}
