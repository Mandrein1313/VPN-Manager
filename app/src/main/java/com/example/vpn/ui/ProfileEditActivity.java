package com.example.vpn.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.vpn.R;
import com.example.vpn.MainActivity;
import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.model.Protocol;
import com.example.vpn.util.VpnPrefs;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ProfileEditActivity extends AppCompatActivity {

    public static final String EXTRA_PREFILL_HOST = "prefill_host";
    public static final String EXTRA_PREFILL_PORT = "prefill_port";
    public static final String EXTRA_PREFILL_USER = "prefill_user";
    public static final String EXTRA_PREFILL_PASS = "prefill_pass";

    private ProfileViewModel viewModel;
    private Profile existing;

    private TextInputEditText edtName, edtHost, edtPort, edtUser, edtPass,
            edtHttpProxy, edtPayload, edtSni, edtDns1, edtDns2;
    private TextInputEditText edtV2rayUuid, edtV2rayPath, edtV2rayHost,
            edtV2rayServiceName, edtV2rayFlow;
    private MaterialAutoCompleteTextView ddProtocol, ddV2rayType, ddV2rayNetwork;
    private LinearLayout groupCredentials, groupSsh, groupV2Ray;
    private MaterialSwitch switchV2rayTls;
    private MaterialButton btnSave;

    // ⭐ Options switches
    private MaterialSwitch switchAutoReconnect;
    private MaterialSwitch switchKillSwitch;
    private MaterialSwitch switchAutoConnectBoot;   // ⭐ ใหม่

    private VpnPrefs prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_edit);

        prefs = new VpnPrefs(this);

        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        viewModel = new ViewModelProvider(this, new ProfileViewModelFactory(repo))
                .get(ProfileViewModel.class);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        edtName = findViewById(R.id.edtName);
        edtHost = findViewById(R.id.edtHost);
        edtPort = findViewById(R.id.edtPort);
        edtUser = findViewById(R.id.edtUser);
        edtPass = findViewById(R.id.edtPass);
        edtHttpProxy = findViewById(R.id.edtHttpProxy);
        edtPayload = findViewById(R.id.edtPayload);
        edtSni = findViewById(R.id.edtSni);
        edtDns1 = findViewById(R.id.edtDns1);
        edtDns2 = findViewById(R.id.edtDns2);
        ddProtocol = findViewById(R.id.ddProtocol);
        groupCredentials = findViewById(R.id.groupCredentials);
        groupSsh = findViewById(R.id.groupSsh);
        btnSave = findViewById(R.id.btnSave);

        // ⭐ V2Ray UI binding
        edtV2rayUuid = findViewById(R.id.edtV2rayUuid);
        edtV2rayPath = findViewById(R.id.edtV2rayPath);
        edtV2rayHost = findViewById(R.id.edtV2rayHost);
        edtV2rayServiceName = findViewById(R.id.edtV2rayServiceName);
        edtV2rayFlow = findViewById(R.id.edtV2rayFlow);
        ddV2rayType = findViewById(R.id.ddV2rayType);
        ddV2rayNetwork = findViewById(R.id.ddV2rayNetwork);
        groupV2Ray = findViewById(R.id.groupV2Ray);
        switchV2rayTls = findViewById(R.id.switchV2rayTls);

        if (ddV2rayType != null) {
            String[] v2Types = {"vless", "vmess", "trojan", "ss"};
            ddV2rayType.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_1, v2Types));
        }

        if (ddV2rayNetwork != null) {
            String[] v2Networks = {"tcp", "ws", "grpc", "http", "h2"};
            ddV2rayNetwork.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_1, v2Networks));
        }

        // ⭐ Bind options
        switchAutoReconnect = findViewById(R.id.switchAutoReconnect);
        switchKillSwitch = findViewById(R.id.switchKillSwitch);
        switchAutoConnectBoot = findViewById(R.id.switchAutoConnectBoot);  // ⭐ ใหม่

        if (switchAutoReconnect != null) {
            switchAutoReconnect.setChecked(prefs.isAutoReconnect());
            switchAutoReconnect.setOnCheckedChangeListener((b, checked) ->
                    prefs.setAutoReconnect(checked));
        }
        if (switchKillSwitch != null) {
            switchKillSwitch.setChecked(prefs.isKillSwitch());
            switchKillSwitch.setOnCheckedChangeListener((b, checked) ->
                    prefs.setKillSwitch(checked));
        }

        // ⭐ Auto-connect on Boot toggle
        if (switchAutoConnectBoot != null) {
            switchAutoConnectBoot.setChecked(prefs.isAutoConnectBoot());
            switchAutoConnectBoot.setOnCheckedChangeListener((b, checked) ->
                    prefs.setAutoConnectBoot(checked));
        }

        String[] protoNames = new String[Protocol.values().length];
        for (int i = 0; i < Protocol.values().length; i++) {
            protoNames[i] = Protocol.values()[i].displayName;
        }
        ddProtocol.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, protoNames));
        ddProtocol.setOnItemClickListener((p, v, pos, id) -> {
            Protocol selected = Protocol.values()[pos];
            onProtocolChanged(selected);
        });

        long id = getIntent().getLongExtra(MainActivity.EXTRA_PROFILE_ID, -1L);
        Intent intent = getIntent();

        if (id > 0) {
            tb.setTitle("แก้ไขโปรไฟล์");
            viewModel.getRepo().getById(id, loaded -> {
                if (loaded == null) { finish(); return; }
                existing = loaded;
                bindProfile(loaded);
            });
        } else if (intent.hasExtra(EXTRA_PREFILL_HOST)) {
            tb.setTitle("เพิ่มโปรไฟล์ (จาก Clipboard)");
            prefillFromIntent(intent);
        } else {
            tb.setTitle("เพิ่มโปรไฟล์");
            fillDefaults();
        }

        btnSave.setOnClickListener(v -> save());
    }

    private void prefillFromIntent(Intent intent) {
        String host = intent.getStringExtra(EXTRA_PREFILL_HOST);
        int port = intent.getIntExtra(EXTRA_PREFILL_PORT, 22);
        String user = intent.getStringExtra(EXTRA_PREFILL_USER);
        String pass = intent.getStringExtra(EXTRA_PREFILL_PASS);

        ddProtocol.setText(Protocol.SSH.displayName, false);

        if (host != null && !host.isEmpty()) {
            edtName.setText(host);
            edtHost.setText(host);
        }
        edtPort.setText(String.valueOf(port));
        if (user != null) edtUser.setText(user);
        if (pass != null) edtPass.setText(pass);
        edtDns1.setText("8.8.8.8");
        edtDns2.setText("8.8.4.4");

        onProtocolChanged(Protocol.SSH);
    }

    private void fillDefaults() {
        ddProtocol.setText(Protocol.SSH.displayName, false);
        edtPort.setText(String.valueOf(Protocol.SSH.defaultPort));
        edtDns1.setText("8.8.8.8");
        edtDns2.setText("8.8.4.4");
        onProtocolChanged(Protocol.SSH);
    }

    private void bindProfile(Profile p) {
        edtName.setText(p.name);
        ddProtocol.setText(p.protocol.displayName, false);
        edtHost.setText(p.host);
        edtPort.setText(String.valueOf(p.port));
        edtUser.setText(p.user);
        edtPass.setText(p.pass);
        edtHttpProxy.setText(p.httpProxy);
        edtPayload.setText(p.payload);
        edtSni.setText(p.sni);
        edtDns1.setText(p.dns1);
        edtDns2.setText(p.dns2);

        // ⭐ Bind V2Ray
        if (edtV2rayUuid != null) edtV2rayUuid.setText(p.v2rayUuid);
        if (edtV2rayPath != null) edtV2rayPath.setText(p.v2rayPath);
        if (edtV2rayHost != null) edtV2rayHost.setText(p.v2rayHost);
        if (edtV2rayServiceName != null) edtV2rayServiceName.setText(p.v2rayServiceName);
        if (edtV2rayFlow != null) edtV2rayFlow.setText(p.v2rayFlow);
        if (ddV2rayType != null) ddV2rayType.setText(p.v2rayType, false);
        if (ddV2rayNetwork != null) ddV2rayNetwork.setText(p.v2rayNetwork, false);
        if (switchV2rayTls != null) switchV2rayTls.setChecked(p.v2rayTls);

        onProtocolChanged(p.protocol);
    }

    private void onProtocolChanged(Protocol proto) {
        if (existing == null) {
            String currentPort = text(edtPort);
            if (currentPort.isEmpty()) {
                edtPort.setText(String.valueOf(proto.defaultPort));
            }
        }
        boolean showCredentials = (proto == Protocol.SSH || proto == Protocol.TROJAN);
        groupCredentials.setVisibility(showCredentials ? View.VISIBLE : View.GONE);
        groupSsh.setVisibility(proto == Protocol.SSH ? View.VISIBLE : View.GONE);

        // ⭐ V2Ray group
        boolean showV2Ray = (proto == Protocol.V2RAY
                || proto == Protocol.SHADOWSOCKS);
        if (groupV2Ray != null) {
            groupV2Ray.setVisibility(showV2Ray ? View.VISIBLE : View.GONE);
        }
    }

    private Protocol currentProtocol() {
        String text = ddProtocol.getText().toString();
        for (Protocol p : Protocol.values()) {
            if (p.displayName.equals(text)) return p;
        }
        return Protocol.SSH;
    }

    private void save() {
    String name = text(edtName);
    String host = text(edtHost);
    String portStr = text(edtPort);

    if (TextUtils.isEmpty(name)) { edtName.setError("กรุณากรอกชื่อ"); return; }
    if (TextUtils.isEmpty(host)) { edtHost.setError("กรุณากรอก Host"); return; }
    if (TextUtils.isEmpty(portStr)) { edtPort.setError("กรุณากรอก Port"); return; }

    int port;
    try { port = Integer.parseInt(portStr); }
    catch (NumberFormatException e) { edtPort.setError("Port ไม่ถูกต้อง"); return; }

    Profile p = existing != null ? existing.copy() : new Profile();
    p.name = name;
    p.protocol = currentProtocol();
    p.host = host;
    p.port = port;
    p.user = text(edtUser);
    p.pass = text(edtPass);
    p.httpProxy = text(edtHttpProxy);
    p.payload = text(edtPayload);
    p.sni = text(edtSni);
    p.dns1 = text(edtDns1);
    p.dns2 = text(edtDns2);

    // ... ถ้ามี field V2Ray ให้อ่านเหมือนเดิม ...

    long excludeId = (existing != null) ? existing.id : 0L;

    // ⭐ ตรวจชื่อซ้ำ
    viewModel.getRepo().findByName(name, excludeId, dup -> {
        if (dup != null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("ชื่อซ้ำ")
                    .setMessage("มีโปรไฟล์ชื่อ \"" + name + "\" อยู่แล้ว\n\n"
                            + "Host: " + dup.host + ":" + dup.port + "\n\n"
                            + "ต้องการอัปเดตโปรไฟล์เดิม หรือยกเลิก?")
                    .setPositiveButton("อัปเดตของเดิม", (d, w) -> {
                        p.id = dup.id;   // เขียนทับตัวเดิม
                        doSave(p);
                    })
                    .setNegativeButton("ยกเลิก", null)
                    .show();
        } else {
            doSave(p);
        }
    });
}

private void doSave(Profile p) {
    viewModel.save(p, id -> {
        Toast.makeText(this, "บันทึกแล้ว", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    });
}

    private String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
