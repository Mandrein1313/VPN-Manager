package com.example.vpn.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.vpn.R;
import com.example.vpn.model.Profile;
import com.example.vpn.util.CountryFlag;

public class MainFragment extends Fragment {

    private ConnectButtonView btnConnect;
    private TextView txtStatus;
    private View adFreeCard;
    private TextView txtAdFreeTime;
    private View configCard;
    private ImageView imgConfigIcon;
    private android.widget.ImageView imgConfigFlag;
    private TextView txtConfigName;
    private TextView txtConfigLeft;
    private TextView txtConfigRight;
    private TextView txtConfigProtocol;
    private TextView txtConfigPing;
    private ImageView imgConfigSignal;
    private ImageView btnConfigArrow;
    private TextView txtDownload;
    private TextView txtUpload;
    private TextView txtSession;

    public interface Listener {
        void onMainConnectClick();
        void onConfigCardClick();
        void onAdFreeClick();
    }

    private Listener listener;

    public void setListener(Listener l) {
        this.listener = l;
    }

    public ConnectButtonView getConnectButton() { return btnConnect; }
    public TextView getTxtStatus() { return txtStatus; }
    public TextView getTxtConfigName() { return txtConfigName; }
    public TextView getTxtConfigLeft() { return txtConfigLeft; }
    public TextView getTxtConfigRight() { return txtConfigRight; }
    public ImageView getImgConfigIcon() { return imgConfigIcon; }
    public TextView getTxtDownload() { return txtDownload; }
    public TextView getTxtUpload() { return txtUpload; }
    public TextView getTxtSession() { return txtSession; }
    public TextView getTxtAdFreeTime() { return txtAdFreeTime; }

    public void setAdFreeLabel(String label) {
        if (txtAdFreeTime != null && label != null) {
            txtAdFreeTime.setText(label);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.page_connection_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        btnConnect = v.findViewById(R.id.btnConnect);
        txtStatus = v.findViewById(R.id.txtStatus);
        adFreeCard = v.findViewById(R.id.adFreeCard);
        txtAdFreeTime = v.findViewById(R.id.txtAdFreeTime);
        configCard = v.findViewById(R.id.configCard);
        imgConfigIcon = v.findViewById(R.id.imgConfigIcon);
        imgConfigFlag = v.findViewById(R.id.imgConfigFlag);
        txtConfigName = v.findViewById(R.id.txtConfigName);
        txtConfigLeft = v.findViewById(R.id.txtConfigLeft);
        txtConfigRight = v.findViewById(R.id.txtConfigRight);
        txtConfigProtocol = v.findViewById(R.id.txtConfigProtocol);
        txtConfigPing = v.findViewById(R.id.txtConfigPing);
        imgConfigSignal = v.findViewById(R.id.imgConfigSignal);
        btnConfigArrow = v.findViewById(R.id.btnConfigArrow);
        txtDownload = v.findViewById(R.id.txtDownload);
        txtUpload = v.findViewById(R.id.txtUpload);
        txtSession = v.findViewById(R.id.txtSession);

        if (btnConnect != null) {
            btnConnect.setListener(() -> {
                if (listener != null) listener.onMainConnectClick();
            });
        }

        if (configCard != null) {
            configCard.setOnClickListener(v1 -> {
                if (listener != null) listener.onConfigCardClick();
            });
        }
        if (btnConfigArrow != null) {
            btnConfigArrow.setOnClickListener(v1 -> {
                if (listener != null) listener.onMainConnectClick(); // ปุ่ม ▶ = เชื่อมต่อ
            });
        }
        if (adFreeCard != null) {
            adFreeCard.setOnClickListener(v1 -> {
                if (listener != null) listener.onAdFreeClick();
            });
        }
    }

    /**
     * อัปเดตการ์ด ACTIVE CONFIGURATION ตามภาพที่ออกแบบ
     * @param latencyMs null = ยังไม่วัด, &lt;0 = ล้มเหลว
     */
    public void bindActiveProfile(@Nullable Profile p, @Nullable Integer latencyMs) {
        if (p == null) {
            if (txtConfigName != null) txtConfigName.setText("Not Set");
            if (txtConfigLeft != null) txtConfigLeft.setText("---");
            if (imgConfigFlag != null) CountryFlag.applyTo(imgConfigFlag, null, null);
            if (txtConfigProtocol != null) txtConfigProtocol.setText("---");
            if (txtConfigPing != null) txtConfigPing.setText("--");
            if (imgConfigSignal != null)
                imgConfigSignal.setImageResource(R.drawable.ic_signal_0);
            return;
        }

        String name = (p.name != null && !p.name.isEmpty()) ? p.name : (p.host != null ? p.host : "---");
        String host = p.host != null ? p.host : "---";
        String proto = p.protocol != null ? p.protocol.name().toLowerCase() : "ssh";

        if (txtConfigName != null) txtConfigName.setText(name);
        if (txtConfigLeft != null) txtConfigLeft.setText(host);
        if (imgConfigFlag != null)
            CountryFlag.applyTo(imgConfigFlag, p.name, p.host);
        if (txtConfigProtocol != null) txtConfigProtocol.setText(proto);

        applyLatency(latencyMs);

        if (imgConfigIcon != null) {
            imgConfigIcon.setImageResource(android.R.drawable.ic_lock_lock);
        }
    }

    public void applyLatency(@Nullable Integer ms) {
        if (txtConfigPing != null) {
            if (ms == null) {
                txtConfigPing.setText("--");
            } else if (ms < 0) {
                txtConfigPing.setText("fail");
            } else {
                txtConfigPing.setText(ms + "ms");
            }
        }
        if (imgConfigSignal != null) {
            imgConfigSignal.setImageResource(signalIconFor(ms));
        }
    }

    private static int signalIconFor(Integer ms) {
        if (ms == null || ms < 0) return R.drawable.ic_signal_0;
        if (ms < 80) return R.drawable.ic_signal_4;
        if (ms < 150) return R.drawable.ic_signal_3;
        if (ms < 300) return R.drawable.ic_signal_2;
        return R.drawable.ic_signal_1;
    }
}
