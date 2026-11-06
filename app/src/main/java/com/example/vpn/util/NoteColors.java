package com.example.vpn.util;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ข้อความโปรไฟล์หลายสี
 * - แท็ก: {green}ข้อความ{/} {gold}...{/} {cyan} {red} {white} {pink} {orange}
 * - หรือ {#RRGGBB}ข้อความ{/}
 * - ไม่มีแท็ก = ไล่สีทีละคำอัตโนมัติ
 */
public final class NoteColors {

    private static final int[] RAINBOW = {
            0xFF69F0AE, // เขียวอ่อน
            0xFF80D8FF, // ฟ้า
            0xFFFFF176, // เหลือง
            0xFFFF8A80, // แดงอ่อน
            0xFFFF80AB, // ชมพู
            0xFFB388FF, // ม่วง
            0xFFFFD54F, // ทอง
            0xFFE0E0E0  // ขาวเทา
    };

    private static final Pattern TAG = Pattern.compile(
            "\\{(green|gold|cyan|red|white|pink|orange|#[0-9A-Fa-f]{6})\\}"
                    + "(.*?)"
                    + "\\{/\\}",
            Pattern.DOTALL);

    private NoteColors() {}

    public static CharSequence colorize(@Nullable String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty()) return "";

        if (text.contains("{/") || text.contains("{#")) {
            return parseTags(text);
        }
        // แท็กสั้น ๆ โดยไม่มีปิด — ยังไล่สีคำ
        return rainbowWords(text);
    }

    private static CharSequence parseTags(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Matcher m = TAG.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                appendRainbow(sb, text.substring(last, m.start()));
            }
            int color = resolveColor(m.group(1));
            String body = m.group(2) != null ? m.group(2) : "";
            int start = sb.length();
            sb.append(body);
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = m.end();
        }
        if (last < text.length()) {
            appendRainbow(sb, text.substring(last));
        }
        return sb;
    }

    private static void appendRainbow(SpannableStringBuilder sb, String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        CharSequence c = rainbowWords(chunk);
        sb.append(c);
    }

    private static CharSequence rainbowWords(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        // แยกตามช่องว่าง แต่คงช่องว่างเดิม
        int i = 0;
        int colorIdx = 0;
        while (i < text.length()) {
            // whitespace
            int j = i;
            while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
            if (j > i) {
                sb.append(text.substring(i, j));
                i = j;
            }
            if (i >= text.length()) break;
            j = i;
            while (j < text.length() && !Character.isWhitespace(text.charAt(j))) j++;
            int start = sb.length();
            sb.append(text.substring(i, j));
            int color = RAINBOW[colorIdx % RAINBOW.length];
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            colorIdx++;
            i = j;
        }
        return sb;
    }

    private static int resolveColor(String token) {
        if (token == null) return 0xFFE0E0E0;
        String t = token.toLowerCase(Locale.US);
        if (t.startsWith("#") && t.length() == 7) {
            try {
                return 0xFF000000 | Integer.parseInt(t.substring(1), 16);
            } catch (Exception e) {
                return 0xFFE0E0E0;
            }
        }
        switch (t) {
            case "green": return 0xFF00E676;
            case "gold": return 0xFFFFD54F;
            case "cyan": return 0xFF00E5FF;
            case "red": return 0xFFFF5252;
            case "white": return 0xFFFFFFFF;
            case "pink": return 0xFFFF80AB;
            case "orange": return 0xFFFFAB40;
            default: return 0xFFE0E0E0;
        }
    }
}
