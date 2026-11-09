package com.example.vpn.util;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ข้อความโปรไฟล์ด้านล่างหน้า MAIN
 * - ตัวอักษรปกติ = เทา
 * - ตัวเลข / วันที่ / เวลา = ฟ้า
 * - ลิงก์ http(s) = ฟ้า + ขีดเส้นใต้ คลิกเปิดเบราว์เซอร์
 * - แท็กยังใช้ได้: {green}...{/} {#RRGGBB}...{/}
 */
public final class NoteColors {

    private static final int COLOR_GRAY = 0xFF9E9E9E;
    private static final int COLOR_BLUE = 0xFF4FC3F7;

    private static final Pattern TAG = Pattern.compile(
            "\\{(green|gold|cyan|red|white|pink|orange|gray|blue|#[0-9A-Fa-f]{6})\\}"
                    + "(.*?)"
                    + "\\{/\\}",
            Pattern.DOTALL);

    private static final Pattern URL = Pattern.compile(
            "(https?://[^\\s<>\"']+)",
            Pattern.CASE_INSENSITIVE);

    /** กลุ่มตัวเลข (รวมวันที่ เวลา พอร์ต รหัส เช่น 2026/11/09, 11:34:22, *900*131#) */
    private static final Pattern NUMBERS = Pattern.compile(
            "\\d+(?:[:./\\-]\\d+)*|#?\\d+(?:\\*\\d+)*#?");

    private NoteColors() {}

    public static CharSequence colorize(@Nullable String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty()) return "";

        SpannableStringBuilder sb;
        if (text.contains("{/") || text.contains("{#")) {
            sb = parseTags(text);
        } else {
            sb = new SpannableStringBuilder(text);
            // ทั้งก้อนเป็นเทา
            sb.setSpan(new ForegroundColorSpan(COLOR_GRAY), 0, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // ตัวเลข = ฟ้า
            applyNumberBlue(sb);
        }
        return linkify(sb);
    }

    private static void applyNumberBlue(SpannableStringBuilder sb) {
        String plain = sb.toString();
        Matcher m = NUMBERS.matcher(plain);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            if (end <= start) continue;
            // อย่าทับช่วงที่เป็นส่วนหนึ่งของ URL ตอนนี้ — linkify จะทับทีหลัง
            sb.setSpan(new ForegroundColorSpan(COLOR_BLUE), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

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
            while (end > start) {
                char c = plain.charAt(end - 1);
                if (c == '.' || c == ',' || c == ')' || c == ']' || c == '!' || c == '?' || c == '…') {
                    end--;
                } else break;
            }
            if (end <= start) continue;
            URLSpan[] old = sb.getSpans(start, end, URLSpan.class);
            for (URLSpan u : old) sb.removeSpan(u);
            // ลบสีเก่าในช่วงลิงก์ แล้วใส่ฟ้า + URL
            ForegroundColorSpan[] colors = sb.getSpans(start, end, ForegroundColorSpan.class);
            for (ForegroundColorSpan c : colors) {
                int cs = sb.getSpanStart(c);
                int ce = sb.getSpanEnd(c);
                if (cs >= start && ce <= end) sb.removeSpan(c);
            }
            sb.setSpan(new ForegroundColorSpan(COLOR_BLUE), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new URLSpan(url) {
                @Override
                public void updateDrawState(TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setUnderlineText(true);
                    ds.setColor(COLOR_BLUE);
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private static SpannableStringBuilder parseTags(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Matcher m = TAG.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                appendGrayWithNumbers(sb, text.substring(last, m.start()));
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
            appendGrayWithNumbers(sb, text.substring(last));
        }
        return sb;
    }

    private static void appendGrayWithNumbers(SpannableStringBuilder sb, String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        int base = sb.length();
        sb.append(chunk);
        sb.setSpan(new ForegroundColorSpan(COLOR_GRAY), base, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Matcher m = NUMBERS.matcher(chunk);
        while (m.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_BLUE),
                    base + m.start(), base + m.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static int resolveColor(String token) {
        if (token == null) return COLOR_GRAY;
        String t = token.toLowerCase(Locale.US);
        if (t.startsWith("#") && t.length() == 7) {
            try {
                return 0xFF000000 | Integer.parseInt(t.substring(1), 16);
            } catch (Exception e) {
                return COLOR_GRAY;
            }
        }
        switch (t) {
            case "green": return 0xFF00E676;
            case "gold": return 0xFFFFD54F;
            case "cyan":
            case "blue": return COLOR_BLUE;
            case "red": return 0xFFFF5252;
            case "white": return 0xFFFFFFFF;
            case "pink": return 0xFFFF80AB;
            case "orange": return 0xFFFFAB40;
            case "gray":
            default: return COLOR_GRAY;
        }
    }
}
