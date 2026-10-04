package com.example.vpn.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.os.Build;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.vpn.R;

public class ConnectButtonView extends FrameLayout {

    public enum State {
        IDLE, CONNECTING, CONNECTED, ERROR
    }

    public interface Listener {
        void onConnectClick();
    }

    private View ringOuter;
    private View ringMiddle;
    private View circleInner;
    private ImageView iconPower;
    private TextView txtButtonLabel;

    private State currentState = State.IDLE;
    private Listener listener;
    private ObjectAnimator pulseAnimator;

    public ConnectButtonView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public ConnectButtonView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ConnectButtonView(@NonNull Context context,
                             @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context ctx) {
        // กันธีมระบบใส่พื้นหลังสี่เหลี่ยมให้ FrameLayout นี้
        setBackground(null);
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(false);
        setFocusable(false);
        setForeground(null);

        LayoutInflater.from(ctx).inflate(R.layout.view_connect_button, this, true);

        ringOuter = findViewById(R.id.ringOuter);
        ringMiddle = findViewById(R.id.ringMiddle);
        circleInner = findViewById(R.id.circleInner);
        iconPower = findViewById(R.id.iconPower);
        txtButtonLabel = findViewById(R.id.txtButtonLabel);

        clipToOval(circleInner);

        circleInner.setOnClickListener(v -> {
            if (listener != null) listener.onConnectClick();
        });

        applyState(State.IDLE);
    }

    /** ตัดวิวให้เป็นวงกลม — ripple/highlight จะไม่เป็นสี่เหลี่ยม */
    private void clipToOval(View view) {
        if (view == null) return;
        view.setForeground(null);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        view.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.setForeground(null);
        }
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setState(State state) {
        if (this.currentState == state) return;
        this.currentState = state;
        applyState(state);
    }

    public State getState() {
        return currentState;
    }

    private void applyState(State state) {
        stopPulse();

        int circleRes;
        int iconColor;
        String label;

        switch (state) {
            case CONNECTING:
                circleRes = R.drawable.bg_connect_circle_connecting;
                iconColor = 0xFFFFFFFF;
                label = "กำลังเชื่อมต่อ";
                startPulse();
                break;

            case CONNECTED:
                circleRes = R.drawable.bg_connect_circle_connected;
                iconColor = 0xFFFFFFFF;
                label = "เชื่อมต่อแล้ว\nกดเพื่อยกเลิก";
                break;

            case ERROR:
                circleRes = R.drawable.bg_connect_circle_error;
                iconColor = 0xFFFFFFFF;
                label = "ผิดพลาด\nกดเพื่อลองใหม่";
                break;

            case IDLE:
            default:
                circleRes = R.drawable.bg_connect_circle_idle;
                iconColor = 0xFFFFFFFF;
                label = "เชื่อมต่อ";
                break;
        }

        circleInner.setBackgroundResource(circleRes);
        clipToOval(circleInner);
        iconPower.setColorFilter(iconColor);
        txtButtonLabel.setText(label);
    }

    private void startPulse() {
        pulseAnimator = ObjectAnimator.ofFloat(circleInner, View.ALPHA, 1f, 0.5f, 1f);
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.start();

        ringMiddle.animate()
                .scaleX(1.05f).scaleY(1.05f)
                .setDuration(800)
                .withEndAction(() -> ringMiddle.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(800)
                        .start());
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        circleInner.setAlpha(1f);
        ringMiddle.setScaleX(1f);
        ringMiddle.setScaleY(1f);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPulse();
    }
}
