package com.example.vpn.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * ระบบ Ad-free time
 * - สะสมคะแนน (pts)
 * - แลกเป็นนาทีปลอดโฆษณา
 * - รับโบนัสรายวัน
 */
public final class AdFreePrefs {

    private static final String PREF = "ad_free_prefs";
    private static final String KEY_POINTS = "points";
    private static final String KEY_UNTIL = "ad_free_until_ms"; // epoch ms ที่หมดอายุ
    private static final String KEY_LAST_DAILY = "last_daily_claim_day"; // yyyyMMdd

    /** คะแนนที่ได้จากโบนัสรายวัน */
    public static final int DAILY_BONUS_PTS = 10;
    /** คะแนนที่ได้เมื่อ "ดูโฆษณา" (จำลอง / ต่อเมื่อมี AdMob จริง) */
    public static final int WATCH_AD_PTS = 15;
    /** คะแนนที่ใช้แลก 30 นาที */
    public static final int COST_30_MIN = 20;
    public static final int MINUTES_PER_REDEEM = 30;

    private final SharedPreferences prefs;

    public AdFreePrefs(@NonNull Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public int getPoints() {
        return prefs.getInt(KEY_POINTS, 0);
    }

    public void addPoints(int delta) {
        if (delta == 0) return;
        int next = Math.max(0, getPoints() + delta);
        prefs.edit().putInt(KEY_POINTS, next).apply();
    }

    /** หมดอายุ ad-free ตอนไหน (0 = ไม่มี) */
    public long getAdFreeUntilMs() {
        return prefs.getLong(KEY_UNTIL, 0L);
    }

    public boolean isAdFreeActive() {
        return System.currentTimeMillis() < getAdFreeUntilMs();
    }

    /** มิลลิวินาทีที่เหลือ (0 ถ้าหมด) */
    public long getRemainingMs() {
        long left = getAdFreeUntilMs() - System.currentTimeMillis();
        return Math.max(0L, left);
    }

    /** ข้อความสั้น ๆ บนการ์ด เช่น "12 นาที" / "0 pts" */
    public String getCardLabel() {
        if (isAdFreeActive()) {
            long min = (getRemainingMs() + 59_999L) / 60_000L; // ปัดขึ้น
            if (min >= 60) {
                long h = min / 60;
                long m = min % 60;
                return h + " ชม. " + m + " น.";
            }
            return min + " นาที";
        }
        return getPoints() + " pts";
    }

    /**
     * แลกคะแนนเป็นนาที ad-free
     * @return true ถ้าแลกสำเร็จ
     */
    public boolean redeem30Minutes() {
        if (getPoints() < COST_30_MIN) return false;
        addPoints(-COST_30_MIN);
        long now = System.currentTimeMillis();
        long base = Math.max(now, getAdFreeUntilMs());
        long until = base + MINUTES_PER_REDEEM * 60_000L;
        prefs.edit().putLong(KEY_UNTIL, until).apply();
        return true;
    }

    /** รับโบนัสวันละครั้ง */
    public boolean claimDailyBonus() {
        int today = todayKey();
        if (prefs.getInt(KEY_LAST_DAILY, 0) == today) return false;
        prefs.edit().putInt(KEY_LAST_DAILY, today).apply();
        addPoints(DAILY_BONUS_PTS);
        return true;
    }

    public boolean canClaimDaily() {
        return prefs.getInt(KEY_LAST_DAILY, 0) != todayKey();
    }

    /** จำลองดูโฆษณาแล้วได้คะแนน (ต่อเมื่อยังไม่มี AdMob) */
    public void rewardWatchAd() {
        addPoints(WATCH_AD_PTS);
    }

    private static int todayKey() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) * 10000
                + (c.get(java.util.Calendar.MONTH) + 1) * 100
                + c.get(java.util.Calendar.DAY_OF_MONTH);
    }
}
