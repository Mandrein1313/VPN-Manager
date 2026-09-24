package com.example.vpn.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.example.vpn.R;
import com.example.vpn.util.LogColors;
import com.example.vpn.util.VpnLogger;

import java.util.List;

public class LogFragment extends Fragment implements VpnLogger.Listener {

    private ScrollView scrollLogView;
    private TextView txtLogContent;
    private ImageButton btnLogCopy;
    private ImageButton btnLogClear;
    private ImageButton btnLogScrollBottom;

    private View root;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.page_connection_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        root = v;

        scrollLogView = v.findViewById(R.id.scrollLogView);
        txtLogContent = v.findViewById(R.id.txtLogContent);
        btnLogCopy = v.findViewById(R.id.btnLogCopy);
        btnLogClear = v.findViewById(R.id.btnLogClear);
        btnLogScrollBottom = v.findViewById(R.id.btnLogScrollBottom);

        btnLogCopy.setOnClickListener(v1 -> copyLogToClipboard());
        btnLogClear.setOnClickListener(v1 -> confirmClearLog());
        btnLogScrollBottom.setOnClickListener(v1 -> scrollLogToBottom());

        refreshLog();
    }

    @Override
    public void onResume() {
        super.onResume();
        VpnLogger.setListener(this);
        refreshLog();
    }

    @Override
    public void onPause() {
        super.onPause();
        VpnLogger.setListener(null);
    }

    @Override
    public void onLogAdded(String line) {
        if (root == null || txtLogContent == null) return;
        if (!isVisible()) return;
        root.post(() -> {
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append(LogColors.coloredLine(line));
            ssb.append("\n");
            txtLogContent.append(ssb);
            scrollLogToBottom();
        });
    }

    private void refreshLog() {
        if (txtLogContent == null) return;
        List<String> lines = VpnLogger.snapshot();
        txtLogContent.setText(LogColors.build(lines));
        scrollLogToBottom();
    }

    private void copyLogToClipboard() {
        String log = VpnLogger.dump();
        if (log == null || log.isEmpty()) {
            Toast.makeText(getContext(), "ไม่มี log", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("VPN Log", log));
            Toast.makeText(getContext(), "คัดลอก log แล้ว",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearLog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("ล้าง Log?")
                .setMessage("ลบ log ทั้งหมดใช่หรือไม่?")
                .setPositiveButton("ล้าง", (d, w) -> {
                    VpnLogger.clear();
                    if (txtLogContent != null) txtLogContent.setText("");
                    Toast.makeText(getContext(), "ล้าง log แล้ว",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void scrollLogToBottom() {
        if (scrollLogView != null) {
            scrollLogView.post(() -> scrollLogView.fullScroll(View.FOCUS_DOWN));
        }
    }
}