package com.example.vpn.util;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ข้อความโปรไฟล์หลายสี + ลิงก์คลิกได้
 * - แท็ก: {green}ข้อความ{/} {gold}...{/} {cyan} {red} {white} {pink} {orange}
 * - หรือ {#RRGGBB}ข้อความ{/}
 * - ไม่มีแท็ก = ไล่สีทีละคำอัตโนมัติ
 * - URL http/https คลิกเปิดเบราว์เซอร์ได้
 */
public final class NoteColors {

    private static final int[] RAINBOW = {
            0xFF69F0AE,
            0xFF80D8FF,
            0xFFFFF176,
            0xFFFF8A80,
            0xFFFF80AB,
            0xFFB388FF,
            0xFFFFD54F,
            0xFFE0E0E0
    };

    private static final Pattern TAG = Pattern.compile(
            "\\{(green|gold|cyan|red|white|pink|orange|#[0-9A-Fa-f]{6})\\}"
                    + "(.*?)"
                    + "\\{/\\}",
            Pattern.DOTALL);

    /** URL + รหัสโปรโมชันแบบตัวอักษร/ตัวเลขติดกันยาวพอสมควร */
    private static final Pattern URL = Pattern.compile(
            "(https?://[^\\s<>\"']+)",
            Pattern.CASE_INSENSITIVE);

    private NoteColors() {}

    public static CharSequence colorize(@Nullable String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty()) return "";

        CharSequence colored;
        if (text.contains("{/") || text.contains("{#")) {
            colored = parseTags(text);
        } else {
            colored = rainbowWords(text);
        }
        return linkify(colored);
    }

    /** ใส่ URLSpan ให้ลิงก์ — ใช้กับ LinkMovementMethod */
    private static CharSequence linkify(CharSequence src) {
        SpannableStringBuilder sb = src instanceof SpannableStringBuilder
                ? (SpannableStringBuilder) src
                : new SpannableStringBuilder(src);
        String plain = sb.toString();
        Matcher m = URL.matcher(plain);
        while (m.find()) {
            final String url = m.group(1);
            if (url == null) continue;
            int start = m.start(1);
            int end = m.end(1);
            // ตัดเครื่องหมายท้ายลิงก์ที่ติดมา
            while (end > start) {
                char c = plain.charAt(end - 1);
                if (c == '.' || c == ',' || c == ')' || c == ']' || c == '!' || c == '?' || c == '…') {
                    end--;
                } else break;
            }
            if (end <= start) continue;
            // ลบ URLSpan เก่าทับช่วงนี้ (ถ้ามี)
            URLSpan[] old = sb.getSpans(start, end, URLSpan.class);
            for (URLSpan u : old) sb.removeSpan(u);
            sb.setSpan(new URLSpan(url) {
                @Override
                public void updateDrawState(TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setUnderlineText(true);
                    ds.setColor(0xFF4FC3F7); // ฟ้าอ่อน เห็นว่าเป็นลิงก์
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
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
        sb.append(rainbowWords(chunk));
    }

    private static CharSequence rainbowWords(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int i = 0;
        int colorIdx = 0;
        while (i < text.length()) {
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
            String word = text.substring(i, j);
            sb.append(word);
            // URL จะถูกทับสีตอน linkify เป็นฟ้า — คำอื่นไล่สีตามปกติ
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
