package com.example.vpn.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.vpn.R;
import com.example.vpn.model.Profile;

import java.util.ArrayList;
import java.util.List;

public class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.VH> {

    public interface Listener {
        void onConnect(Profile p);
        void onEdit(Profile p);
        void onDelete(Profile p);
        void onToggleFavorite(Profile p);
        void onShareQr(Profile p);
    }

    private final List<Profile> items = new ArrayList<>();
    private final Listener listener;

    public ProfileAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Profile> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_profile, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Profile p = items.get(position);
        h.bind(p, listener);
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView name, host, protocol, ping;
        ImageButton btnQr, btnFav, btnEdit, btnDelete;

        VH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.txtName);
            host = v.findViewById(R.id.txtHost);
            protocol = v.findViewById(R.id.txtProtocol);
            ping = v.findViewById(R.id.txtPing);
            btnQr = v.findViewById(R.id.btnQr);       // ⭐ ใหม่
            btnFav = v.findViewById(R.id.btnFavorite);
            btnEdit = v.findViewById(R.id.btnEdit);
            btnDelete = v.findViewById(R.id.btnDelete);
        }

        void bind(Profile p, Listener l) {
            name.setText(p.name);
            host.setText(p.host + ":" + p.port);
            protocol.setText(p.protocol.id.toLowerCase());
            ping.setText("Ping —");

            btnFav.setImageResource(p.isFavorite
                    ? android.R.drawable.btn_star_big_on
                    : android.R.drawable.btn_star_big_off);

            // กดการ์ด → เชื่อมต่อ
            itemView.setOnClickListener(v -> l.onConnect(p));

            // กดค้าง → เมนู QR (สำรอง)
            itemView.setOnLongClickListener(v -> {
                l.onShareQr(p);
                return true;
            });

            // ⭐ ปุ่ม QR บนการ์ด
            btnQr.setOnClickListener(v -> l.onShareQr(p));

            btnFav.setOnClickListener(v -> l.onToggleFavorite(p));
            btnEdit.setOnClickListener(v -> l.onEdit(p));
            btnDelete.setOnClickListener(v -> l.onDelete(p));
        }
    }
}