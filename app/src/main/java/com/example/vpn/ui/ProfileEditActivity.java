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

public class ProfileEditActivity extends AppCompatActivity {

    public static final String EXTRA_PREFILL_HOST = "prefill_host";
    public static final String EXTRA_PREFILL_PORT = "prefill_port";
    public static final String EXTRA_PREFILL_USER = "prefill_user";
    public static final String EXTRA_PREFILL_PASS = "prefill_pass";

    private ProfileViewModel viewModel;
    private Profile existing;

    private TextInputEditText edtName, edtHost, edtPort, edtUser, edtPass,
            edtHttpProxy, edtPayload, edtSni, edtDns1, edtDns2;
    private MaterialAutoCompleteTextView ddProtocol;
    private LinearLayout groupCredentials, groupSsh;
    private MaterialButton btnSave;

    // ⭐ Options switches
    private MaterialSwitch switchAutoReconnect;
    private MaterialSwitch switchKillSwitch;

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

        // ⭐ Bind options
        switchAutoReconnect = findViewById(R.id.switchAutoReconnect);
        switchKillSwitch = findViewById(R.id.switchKillSwitch);

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