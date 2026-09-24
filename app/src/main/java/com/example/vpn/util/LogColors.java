package com.example.vpn.util;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.List;

/**
 * ⭐ Utility แปลง log line เป็น Spannable ที่มีสี
 * ใช้ได้ทั้ง ConnectionActivity และ LogViewerActivity
 */
public class LogColors {

    public static final int COLOR_TIME      = 0xFF888888;
    public static final int COLOR_TAG       = 0xFF4A90C2;
    public static final int COLOR_MSG_INFO  = 0xFFDDDDDD;
    public static final int COLOR_MSG_DEBUG = 0xFF808080;
    public static final int COLOR_MSG_WARN  = 0xFFFFA726;
    public static final int COLOR_MSG_ERROR = 0xFFEF5350;

    public static final int COLOR_LVL_INFO  = 0xFF00E676;
    public static final int COLOR_LVL_DEBUG = 0xFF888888;
    public static final int COLOR_LVL_WARN  = 0xFFFFA726;
    public static final int COLOR_LVL_ERROR = 0xFFEF5350;

    /** ⭐ แปลง log ทั้ง list เป็น Spannable ตัวเดียว */
    public static CharSequence build(List<String> lines) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (lines == null) return sb;

        for (String line : lines) {
            sb.append(coloredLine(line));
            sb.append("\n");
        }
        return sb;
    }

    /** ⭐ ใส่สีใน 1 บรรทัด */
    public static CharSequence coloredLine(String line) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(line);

        if (line == null || line.length() < 15) {
            return ssb;
        }

        // รูปแบบ: "20:51:11.973 I/ProxyVpnService: Creating TUN interface..."
        int timestampEnd = line.indexOf(' ');
        if (timestampEnd < 0) timestampEnd = Math.min(12, line.length());

        // ---- Timestamp ----
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

    private static void highlightKeywords(SpannableStringBuilder ssb, String line, int from) {
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
}