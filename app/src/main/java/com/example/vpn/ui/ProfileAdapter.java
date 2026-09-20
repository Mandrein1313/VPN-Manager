package com.example.vpn.ui;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.vpn.R;
import com.example.vpn.model.Profile;
import com.google.android.material.button.MaterialButton;

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

    /** ⭐ สำหรับ MainActivity อ่านรายการที่เลือก */
    public Set<Long> getSelectedIds() {
        return new HashSet<>(selectedIds);
    }

    /** ⭐ ล้างรายการที่เลือก */
    public void clearSelection() {
        selectedIds.clear();
        notifyDataSetChanged();
        if (listener != null) listener.onProfileSelected(0);
    }

    /** ⭐ toggle การเลือก */
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
        TextView icon, name, host, protocol;
        ImageButton btnFav;
        MaterialButton btnEdit, btnDelete;

        VH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.txtIcon);
            name = v.findViewById(R.id.txtName);
            host = v.findViewById(R.id.txtHost);
            protocol = v.findViewById(R.id.txtProtocol);
            btnFav = v.findViewById(R.id.btnFavorite);
            btnEdit = v.findViewById(R.id.btnEdit);
            btnDelete = v.findViewById(R.id.btnDelete);
        }

        void bind(Profile p, Listener l, boolean selected, ProfileAdapter adapter) {
            icon.setText(p.protocol.icon);
            name.setText(p.name);
            host.setText(p.host + ":" + p.port);
            protocol.setText(p.protocol.icon + "  " + p.protocol.displayName);

            int color = ContextCompat.getColor(itemView.getContext(), p.protocol.colorRes);
            protocol.setTextColor(color);
            protocol.setBackgroundTintList(ColorStateList.valueOf(
                    (color & 0x00FFFFFF) | 0x1A000000));

            btnFav.setImageResource(p.isFavorite
                    ? android.R.drawable.btn_star_big_on
                    : android.R.drawable.btn_star_big_off);

            // ⭐ แสดงสถานะ selected
            itemView.setActivated(selected);

            // ⭐ กดที่การ์ด → toggle การเลือก
            itemView.setOnClickListener(v -> adapter.toggleSelection(p.id));

            btnEdit.setOnClickListener(v -> l.onEdit(p));
            btnDelete.setOnClickListener(v -> l.onDelete(p));
            btnFav.setOnClickListener(v -> l.onToggleFavorite(p));
        }
    }
}