package com.example.vpn.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.vpn.R;
import com.example.vpn.model.AppInfo;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.VH> {

    public interface Listener {
        void onToggle(AppInfo app, boolean bypassed);
    }

    private final List<AppInfo> items = new ArrayList<>();
    private final Listener listener;

    public AppListAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<AppInfo> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AppInfo app = items.get(position);
        h.bind(app, listener);
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name, pkg;
        MaterialCheckBox check;

        VH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.appIcon);
            name = v.findViewById(R.id.appName);
            pkg = v.findViewById(R.id.appPackage);
            check = v.findViewById(R.id.appCheck);
        }

        void bind(AppInfo app, Listener l) {
            if (app.icon != null) icon.setImageDrawable(app.icon);
            name.setText(app.appName);
            pkg.setText(app.packageName);
            check.setChecked(app.bypassed);

            // ⭐ กดทั้งแถว = toggle
            itemView.setOnClickListener(v -> {
                boolean newVal = !app.bypassed;
                check.setChecked(newVal);
                l.onToggle(app, newVal);
            });

            // ⭐ กด checkbox = toggle
            check.setOnCheckedChangeListener((b, checked) -> {
                if (checked != app.bypassed) {
                    l.onToggle(app, checked);
                }
            });
        }
    }
}