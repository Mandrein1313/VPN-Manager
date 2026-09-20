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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.VH> {

    public interface Listener {
        void onProfileSelected(int totalSelected);
        void onEdit(Profile p);
        void onDelete(Profile p);
        void onToggleFavorite(Profile p);
    }

    private final List<Profile> items = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();
    private final Listener listener;

    public ProfileAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Profile> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    public Set<Long> getSelectedIds() {
        return new HashSet<>(selectedIds);
    }

    public void clearSelection() {
        selectedIds.clear();
        notifyDataSetChanged();
        if (listener != null) listener.onProfileSelected(0);
    }

    private void toggleSelection(long id) {
        int position = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) {
                position = i;
                break;
            }
        }
        if (position < 0) return;

        if (selectedIds.contains(id)) {
            selectedIds.remove(id);
        } else {
            selectedIds.add(id);
        }
        notifyItemChanged(position);
        if (listener != null) listener.onProfileSelected(selectedIds.size());
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
        h.bind(p, listener, selectedIds.contains(p.id), this);
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView name, host, protocol, ping;
        ImageButton btnFav, btnEdit, btnDelete;

        VH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.txtName);
            host = v.findViewById(R.id.txtHost);
            protocol = v.findViewById(R.id.txtProtocol);
            ping = v.findViewById(R.id.txtPing);
            btnFav = v.findViewById(R.id.btnFavorite);
            btnEdit = v.findViewById(R.id.btnEdit);
            btnDelete = v.findViewById(R.id.btnDelete);
        }

        void bind(Profile p, Listener l, boolean selected, ProfileAdapter adapter) {
            // ชื่อ
            name.setText(p.name);

            // Host:Port
            host.setText(p.host + ":" + p.port);

            // Protocol (ssh / v2ray / trojan ...)
            protocol.setText(p.protocol.id.toLowerCase());

            // Ping (placeholder — ยังไม่มีระบบ ping จริง)
            ping.setText("Ping —");

            // Star (Favorite)
            btnFav.setImageResource(p.isFavorite
                    ? android.R.drawable.btn_star_big_on
                    : android.R.drawable.btn_star_big_off);

            // ⭐ แสดงสถานะ selected (ขอบ + พื้นหลังเปลี่ยน)
            itemView.setActivated(selected);

            // ⭐ กดที่การ์ดทั้งใบ → เชื่อมต่อ
            itemView.setOnClickListener(v -> l.onProfileSelected(
                    adapter.toggleAndGetCount(p.id)));

            btnFav.setOnClickListener(v -> l.onToggleFavorite(p));
            btnEdit.setOnClickListener(v -> l.onEdit(p));
            btnDelete.setOnClickListener(v -> l.onDelete(p));
        }
    }

    /** ⭐ ใช้ toggle + return จำนวนที่เลือก */
    private int toggleAndGetCount(long id) {
        toggleSelection(id);
        return selectedIds.size();
    }
}