package com.example.vpn.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.vpn.R;
import com.example.vpn.util.LogColors;
import com.example.vpn.util.VpnLogger;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.List;

public class LogViewerActivity extends AppCompatActivity
        implements VpnLogger.Listener {

    private TextView txtLog;
    private ScrollView scroll;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        txtLog = findViewById(R.id.txtLog);
        scroll = findViewById(R.id.scrollLog);

        MaterialButton btnCopy = findViewById(R.id.btnCopy);
        MaterialButton btnClear = findViewById(R.id.btnClear);
        MaterialButton btnScrollBottom = findViewById(R.id.btnScrollBottom);

        refreshLog();

        btnCopy.setOnClickListener(v -> {
            String log = VpnLogger.dump();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("VPN Log", log));
                Toast.makeText(this, "คัดลอก log แล้ว", Toast.LENGTH_SHORT).show();
            }
        });

        btnClear.setOnClickListener(v -> {
            VpnLogger.clear();
            refreshLog();
        });

        btnScrollBottom.setOnClickListener(v -> scrollToBottom());

        VpnLogger.setListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        VpnLogger.setListener(null);
    }

    @Override
    public void onLogAdded(String line) {
        runOnUiThread(() -> {
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append(LogColors.coloredLine(line));
            ssb.append("\n");
            txtLog.append(ssb);
            scrollToBottom();
        });
    }

    private void refreshLog() {
        List<String> lines = VpnLogger.snapshot();
        txtLog.setText(LogColors.build(lines));
        scrollToBottom();
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
}