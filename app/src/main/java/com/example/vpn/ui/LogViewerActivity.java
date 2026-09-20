package com.example.vpn.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.vpn.R;
import com.example.vpn.util.VpnLogger;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.List;

public class LogViewerActivity extends AppCompatActivity
        implements VpnLogger.Listener {

    // ===== สีของแต่ละระดับ =====
    private static final int COLOR_TIME      = 0xFF888888;
    private static final int COLOR_TAG       = 0xFF4A90C2;
    private static final int COLOR_MSG_INFO  = 0xFFDDDDDD;
    private static final int COLOR_MSG_DEBUG = 0xFF808080;
    private static final int COLOR_MSG_WARN  = 0xFFFFA726;
    private static final int COLOR_MSG_ERROR = 0xFFEF5350;

    private static final int COLOR_LVL_INFO  = 0xFF00E676;
    private static final int COLOR_LVL_DEBUG = 0xFF888888;
    private static final int COLOR_LVL_WARN  = 0xFFFFA726;
    private static final int COLOR_LVL_ERROR = 0xFFEF5350;

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

        // ⭐ โหลด log ที่มีอยู่ + ใส่สี
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

        // ⭐ ลงทะเบียน listener — log ใหม่จะถูกใส่สีอัตโนมัติ
        VpnLogger.setListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        VpnLogger.setListener(null);
    }

    // ============================================================
    // ⭐ เมื่อมี log ใหม่ — ใส่สีแล้ว append
    // ============================================================
    @Override
    public void onLogAdded(String line) {
        runOnUiThread(() -> {
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            ssb.append(coloredLine(line));
            ssb.append("\n");
            txtLog.append(ssb);
            scrollToBottom();
        });
    }

    // ============================================================
    // ⭐ สร้าง Spannable ที่มีสี
    // ============================================================
    private CharSequence coloredLine(String line) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(line);

        if (line == null || line.length() < 15) {
            return ssb;
        }

        // รูปแบบ: "20:51:11.973 I/ProxyVpnService: Creating TUN interface..."
        // [0..12]   = timestamp
        // [13]      = space
        // [14]      = level (I/D/W/E)
        // [15]      = '/'
        // [16..]    = tag จนเจอ ':'
        // หลัง ': '  = message

        // ---- Timestamp ----
        int timestampEnd = line.indexOf(' ');
        if (timestampEnd < 0) timestampEnd = Math.min(12, line.length());
        ssb.setSpan(new ForegroundColorSpan(COLOR_TIME),
                0, timestampEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // ---- Level ----
        int levelPos = timestampEnd + 1;
        if (levelPos >= line.length()) return ssb;

        char level = line.charAt(levelPos);
        int levelColor;
        int msgColor;
        switch (level) {
            case 'E': levelColor = COLOR_LVL_ERROR; msgColor = COLOR_MSG_ERROR; break;
            case 'W': levelColor = COLOR_LVL_WARN;  msgColor = COLOR_MSG_WARN;  break;
            case 'D': levelColor = COLOR_LVL_DEBUG; msgColor = COLOR_MSG_DEBUG; break;
            case 'I':
            default:  levelColor = COLOR_LVL_INFO;  msgColor = COLOR_MSG_INFO;  break;
        }

        ssb.setSpan(new ForegroundColorSpan(levelColor),
                levelPos, levelPos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new StyleSpan(Typeface.BOLD),
                levelPos, levelPos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // ---- Tag ----
        int tagStart = levelPos + 2;
        int tagEnd = line.indexOf(':', tagStart);
        if (tagEnd < 0) tagEnd = line.length();

        ssb.setSpan(new ForegroundColorSpan(COLOR_TAG),
                tagStart, tagEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // ---- Message ----
        int msgStart = tagEnd + 2;
        if (msgStart < line.length()) {
            ssb.setSpan(new ForegroundColorSpan(msgColor),
                    msgStart, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            highlightKeywords(ssb, line, msgStart);
        }

        return ssb;
    }

    /** ⭐ เน้นคำสำคัญ */
    private void highlightKeywords(SpannableStringBuilder ssb, String line, int from) {
        String lower = line.toLowerCase();

        String[] greenWords = {"successfully", "success", "connected", "established",
                "ready", "พร้อม", "สำเร็จ"};
        String[] redWords = {"error", "failed", "reject", "timeout", "abort",
                "ผิดพลาด", "ล้มเหลว"};
        String[] orangeWords = {"no response", "continue", "unexpected",
                "not found", "warning"};

        for (String w : greenWords) {
            int idx = lower.indexOf(w, from - 1);
            if (idx >= 0) {
                ssb.setSpan(new ForegroundColorSpan(0xFF00E676),
                        idx, idx + w.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD),
                        idx, idx + w.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        for (String w : redWords) {
            int idx = lower.indexOf(w, from - 1);
            if (idx >= 0) {
                ssb.setSpan(new ForegroundColorSpan(0xFFEF5350),
                        idx, idx + w.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD),
                        idx, idx + w.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        for (String w : orangeWords) {
            int idx = lower.indexOf(w, from - 1);
            if (idx >= 0) {
                ssb.setSpan(new ForegroundColorSpan(0xFFFFA726),
                        idx, idx + w.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    // ============================================================
    // Refresh ทั้งหมด
    // ============================================================
    private void refreshLog() {
        List<String> lines = VpnLogger.snapshot();

        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String line : lines) {
            sb.append(coloredLine(line));
            sb.append("\n");
        }

        txtLog.setText(sb);
        scrollToBottom();
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
}