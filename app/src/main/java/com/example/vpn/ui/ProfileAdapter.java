package com.example.vpn.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.vpn.R;
import com.example.vpn.model.Profile;
import com.example.vpn.util.LatencyProbe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.VH> {

    public interface Listener {
        void onConnect(Profile p);
        void onEdit(Profile p);
        void onDelete(Profile p);
        void onToggleFavorite(Profile p);
        void onShareQr(Profile p);
    }

    private final List<Profile> items = new ArrayList<>();
    /** profileId → latency ms (-1 fail, null = ยังไม่วัด) */
    private final Map<Long, Integer> latencyMap = new HashMap<>();
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ProfileAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Profile> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
        pingAll();
    }

    /** วัด latency ทุกโปรไฟล์ (TCP connect) */
    public void pingAll() {
        for (Profile p : items) {
            if (p == null || p.host == null || p.host.isEmpty()) continue;
            final long id = p.id;
            // แสดง ... ระหว่างวัด
            if (!latencyMap.containsKey(id)) {
                latencyMap.put(id, null);
            }
            LatencyProbe.measure(id, p.host, p.port, (profileId, ms) ->
                    main.post(() -> {
                        latencyMap.put(profileId, ms);
                        notifyLatencyChanged(profileId);
                    }));
        }
        notifyDataSetChanged();
    }

    private void notifyLatencyChanged(long profileId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == profileId) {
                notifyItemChanged(i);
                break;
            }
        }
    }

    public Profile getItem(int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position);
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
        Integer lat = latencyMap.get(p.id);
        h.bind(p, lat, listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ============================================================
    // ViewHolder
    // ============================================================
    static class VH extends RecyclerView.ViewHolder {
        TextView name, host, protocol, ping;
        ImageButton btnQr, btnFav, btnEdit, btnDelete;

        VH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.txtName);
            host = v.findViewById(R.id.txtHost);
            protocol = v.findViewById(R.id.txtProtocol);
            ping = v.findViewById(R.id.txtPing);
            btnQr = v.findViewById(R.id.btnQr);
            btnFav = v.findViewById(R.id.btnFavorite);
            btnEdit = v.findViewById(R.id.btnEdit);
            btnDelete = v.findViewById(R.id.btnDelete);
        }

        void bind(Profile p, Integer latencyMs, Listener l) {
            name.setText(p.name != null ? p.name : "");
            host.setText(p.host + ":" + p.port);

            if (protocol != null) {
                String proto = p.protocol != null ? p.protocol.name().toLowerCase() : "ssh";
                protocol.setText(proto);
            }

            applyLatency(ping, latencyMs);

            if (btnFav != null) {
                btnFav.setImageResource(p.isFavorite
                        ? android.R.drawable.btn_star_big_on
                        : android.R.drawable.btn_star_big_off);
                btnFav.setOnClickListener(v -> l.onToggleFavorite(p));
            }

            itemView.setOnClickListener(v -> l.onConnect(p));
            itemView.setOnLongClickListener(v -> {
                l.onEdit(p);
                return true;
            });

            if (btnQr != null) {
                btnQr.setOnClickListener(v -> l.onShareQr(p));
            }
            if (btnEdit != null) {
                btnEdit.setOnClickListener(v -> l.onEdit(p));
            }
            if (btnDelete != null) {
                btnDelete.setOnClickListener(v -> l.onDelete(p));
            }
        }

        /**
         * แสดง latency แบบแอป VPN ทั่วไป
         * เขียว = ดี, ส้ม = ปานกลาง, แดง = ช้า / ล้มเหลว
         */
        static void applyLatency(TextView tv, Integer ms) {
            if (tv == null) return;
            if (ms == null) {
                tv.setText("…");
                tv.setTextColor(0xFF888888);
                return;
            }
            if (ms < 0) {
                tv.setText("—");
                tv.setTextColor(0xFFEF5350);
                return;
            }
            tv.setText(ms + "ms");
            if (ms < 80) {
                tv.setTextColor(0xFF00E676);      // ดีมาก
            } else if (ms < 150) {
                tv.setTextColor(0xFF69F0AE);      // ดี
            } else if (ms < 300) {
                tv.setTextColor(0xFFFFC107);      // ปานกลาง
            } else {
                tv.setTextColor(0xFFFF7043);      // ช้า
            }
        }
    }
}
