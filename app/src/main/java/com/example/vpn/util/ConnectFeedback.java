package com.example.vpn.util;

import android.content.Context;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * สั่น + เสียงสั้น ๆ เมื่อเชื่อมต่อ VPN สำเร็จ / หลุด
 */
public final class ConnectFeedback {

    private ConnectFeedback() {}

    /** เชื่อมต่อสำเร็จ */
    public static void onConnected(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        vibrate(app, new long[]{0, 40, 60, 40}, new int[]{0, 180, 0, 220});
        playTone(ToneGenerator.TONE_PROP_ACK, 120);
    }

    /** ตัดการเชื่อมต่อ / error */
    public static void onDisconnected(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        vibrate(app, new long[]{0, 30}, new int[]{0, 120});
    }

    public static void onError(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        vibrate(app, new long[]{0, 50, 40, 50}, new int[]{0, 200, 0, 200});
        playTone(ToneGenerator.TONE_PROP_NACK, 150);
    }

    private static void vibrate(Context ctx, long[] timings, int[] amps) {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager)
                        ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amps, -1));
            } else {
                vibrator.vibrate(timings, -1);
            }
        } catch (Exception ignored) {}
    }

    private static void playTone(int toneType, int durationMs) {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(toneType, durationMs);
            // release หลังเล่นจบ
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(tg::release, durationMs + 50L);
        } catch (Exception ignored) {}
    }
}
