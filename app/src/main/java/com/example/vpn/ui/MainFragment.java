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

public class MainFragment extends Fragment {

    private ConnectButtonView btnConnect;
    private TextView txtStatus;
    private View adFreeCard;
    private TextView txtAdFreeTime;
    private View configCard;
    private ImageView imgConfigIcon;
    private TextView txtConfigName;
    private TextView txtConfigLeft;
    private TextView txtConfigRight;
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
        txtConfigName = v.findViewById(R.id.txtConfigName);
        txtConfigLeft = v.findViewById(R.id.txtConfigLeft);
        txtConfigRight = v.findViewById(R.id.txtConfigRight);
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
                if (listener != null) listener.onConfigCardClick();
            });
        }
        if (adFreeCard != null) {
            adFreeCard.setOnClickListener(v1 -> {
                if (listener != null) listener.onAdFreeClick();
            });
        }
    }
}