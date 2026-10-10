package com.example.vpn.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.example.vpn.R;

/**
 * Toast แบบการ์ด มีไอคอน + หัวข้อสี ตามประเภท
 * SUCCESS / ERROR / WARNING / INFO / DELETE
 */
public final class StyledToast {

    public enum Type {
        SUCCESS,
        ERROR,
        WARNING,
        INFO,
        DELETE
    }

    private StyledToast() {}

    public static void success(Context ctx, String message) {
        show(ctx, Type.SUCCESS, "SUCCESS", message);
    }

    public static void error(Context ctx, String message) {
        show(ctx, Type.ERROR, "ERROR", message);
    }

    public static void warning(Context ctx, String message) {
        show(ctx, Type.WARNING, "WARNING", message);
    }

    public static void info(Context ctx, String message) {
        show(ctx, Type.INFO, "INFO", message);
    }

    public static void delete(Context ctx, String message) {
        show(ctx, Type.DELETE, "DELETE", message);
    }

    public static void show(Context ctx, Type type, String title, String message) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();

        try {
            View view = LayoutInflater.from(app).inflate(R.layout.toast_styled, null);

            TextView tvTitle = view.findViewById(R.id.toastTitle);
            TextView tvMsg = view.findViewById(R.id.toastMessage);
            ImageView icon = view.findViewById(R.id.toastIcon);
            View iconBg = view.findViewById(R.id.toastIconBg);

            Style style = styleOf(type);

            if (tvTitle != null) {
                tvTitle.setText(title != null ? title : style.defaultTitle);
                tvTitle.setTextColor(style.titleColor);
            }
            if (tvMsg != null) {
                tvMsg.setText(message != null ? message : "");
            }
            if (icon != null) {
                icon.setImageResource(style.iconRes);
                icon.setColorFilter(style.iconTint, PorterDuff.Mode.SRC_IN);
            }
            if (iconBg != null) {
                Drawable bg = ContextCompat.getDrawable(app, R.drawable.bg_toast_icon_circle);
                if (bg != null) {
                    bg = bg.mutate();
                    bg.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
                    iconBg.setBackground(bg);
                }
            }

            Toast toast = new Toast(app);
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setView(view);
            toast.setGravity(Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 0, dp(app, 72));
            toast.show();
        } catch (Exception e) {
            // fallback
            Toast.makeText(app, message != null ? message : String.valueOf(title),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** ใช้เมื่ออยู่ใน Activity และอยากให้ safe กับ lifecycle */
    public static void showOnUi(@NonNull Activity activity, Type type, String title, String message) {
        if (activity.isFinishing()) return;
        activity.runOnUiThread(() -> show(activity, type, title, message));
    }

    private static int dp(Context ctx, int value) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }

    private static Style styleOf(Type type) {
        switch (type) {
            case SUCCESS:
                return new Style("SUCCESS", 0xFF00C853,
                        android.R.drawable.ic_dialog_info, 0xFF00C853);
            case ERROR:
                return new Style("ERROR", 0xFFE53935,
                        android.R.drawable.ic_dialog_alert, 0xFFE53935);
            case WARNING:
                return new Style("WARNING", 0xFFFFA000,
                        android.R.drawable.ic_dialog_alert, 0xFFFFA000);
            case DELETE:
                return new Style("DELETE", 0xFF1E88E5,
                        android.R.drawable.ic_menu_delete, 0xFF1E88E5);
            case INFO:
            default:
                return new Style("INFO", 0xFF1E88E5,
                        android.R.drawable.ic_dialog_info, 0xFF1E88E5);
        }
    }

    private static final class Style {
        final String defaultTitle;
        final int titleColor;
        @DrawableRes final int iconRes;
        final int iconTint;

        Style(String defaultTitle, int titleColor, int iconRes, int iconTint) {
            this.defaultTitle = defaultTitle;
            this.titleColor = titleColor;
            this.iconRes = iconRes;
            this.iconTint = iconTint;
        }
    }
}
