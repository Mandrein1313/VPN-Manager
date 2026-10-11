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
import com.example.vpn.util.CountryFlag;
import com.example.vpn.util.LatencyProbe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
    private long selectedProfileId = -1L;

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
        // คงแถบเขียวหลังรายการรีเฟรช
        if (selectedProfileId > 0) {
            long keep = selectedProfileId;
            selectedProfileId = -1L;
            setSelectedProfileId(keep);
        }
        pingAll();
    }

    public void pingAll() {
        pingAll(null);
    }

    public void pingAll(Runnable onComplete) {
        final List<Profile> targets = new ArrayList<>();
        for (Profile p : items) {
            if (p == null || p.host == null || p.host.isEmpty()) continue;
            targets.add(p);
            latencyMap.put(p.id, null);
        }
        notifyDataSetChanged();

        if (targets.isEmpty()) {
            if (onComplete != null) main.post(onComplete);
            return;
        }

        final AtomicInteger left = new AtomicInteger(targets.size());
        for (Profile p : targets) {
            final long id = p.id;
            LatencyProbe.measure(id, p.host, p.port, (profileId, ms) ->
                    main.post(() -> {
                        latencyMap.put(profileId, ms);
                        notifyLatencyChanged(profileId);
                        if (left.decrementAndGet() == 0 && onComplete != null) {
                            onComplete.run();
                        }
                    }));
        }
    }

    public Profile getLowestLatencyProfile() {
        Profile best = null;
        int bestMs = Integer.MAX_VALUE;
        for (Profile p : items) {
            if (p == null) continue;
            Integer ms = latencyMap.get(p.id);
            if (ms != null && ms > 0 && ms < bestMs) {
                bestMs = ms;
                best = p;
            }
        }
        return best;
    }

    public Integer getLatencyMs(long profileId) {
        return latencyMap.get(profileId);
    }

    private void notifyLatencyChanged(long profileId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == profileId) {
                notifyItemChanged(i);
                break;
            }
        }
    }


    /** โปรไฟล์ที่ถูกเลือก — แสดงแถบเขียวด้านซ้าย */
    public void setSelectedProfileId(long id) {
        if (selectedProfileId == id) return;
        selectedProfileId = id;
        notifyDataSetChanged();
    }

    public long getSelectedProfileId() {
        return selectedProfileId;
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
        h.bind(p, lat);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static int signalIconFor(Integer ms) {
        if (ms == null) return R.drawable.ic_signal_0;
        if (ms < 0) return R.drawable.ic_signal_0;
        if (ms < 80) return R.drawable.ic_signal_4;
        if (ms < 150) return R.drawable.ic_signal_3;
        if (ms < 300) return R.drawable.ic_signal_2;
        return R.drawable.ic_signal_1;
    }

    static void applyLatency(TextView tv, Integer ms) {
        if (tv == null) return;
        if (ms == null) {
            tv.setText("…");
            tv.setTextColor(0xFF888888);
            tv.setBackgroundResource(R.drawable.bg_latency_chip);
            return;
        }
        if (ms < 0) {
            tv.setText("Timeout");
            tv.setTextColor(0xFFFF8A80);
            tv.setBackgroundResource(R.drawable.bg_latency_chip_fail);
            return;
        }
        tv.setText(ms + "ms");
        tv.setBackgroundResource(R.drawable.bg_latency_chip);
        if (ms < 80) {
            tv.setTextColor(0xFF00E676);
        } else if (ms < 150) {
            tv.setTextColor(0xFF69F0AE);
        } else if (ms < 300) {
            tv.setTextColor(0xFFFFC107);
        } else {
            tv.setTextColor(0xFFFF7043);
        }
    }

    // non-static เพื่อเข้าถึง latencyMap / main / notify ของ adapter ได้
    class VH extends RecyclerView.ViewHolder {
        android.widget.ImageView flag;
        TextView name, host, protocol, ping;
        View viewSelectedBar;
        ImageButton btnQr, btnSignal, btnEdit, btnDelete;

        VH(@NonNull View v) {
            super(v);
            flag = v.findViewById(R.id.imgFlag);
            name = v.findViewById(R.id.txtName);
            host = v.findViewById(R.id.txtHost);
            protocol = v.findViewById(R.id.txtProtocol);
            ping = v.findViewById(R.id.txtPing);
            viewSelectedBar = v.findViewById(R.id.viewSelectedBar);
            btnQr = v.findViewById(R.id.btnQr);
            btnSignal = v.findViewById(R.id.btnSignal);
            btnEdit = v.findViewById(R.id.btnEdit);
            btnDelete = v.findViewById(R.id.btnDelete);
        }

        void bind(Profile p, Integer latencyMs) {
            if (flag != null) {
                CountryFlag.applyTo(flag, p.name, p.host);
            }
            name.setText(p.name != null ? p.name : "");
            host.setText(p.host + ":" + p.port);

            applyLatency(ping, latencyMs);

            boolean sel = (selectedProfileId > 0 && p.id == selectedProfileId);

            // แถบโปรโตคอล + สีการ์ดเมื่อเลือก (แบบแอปอ้างอิง)
            if (viewSelectedBar != null) {
                viewSelectedBar.setBackgroundColor(sel ? 0xFF00BCD4 : 0xFF2A2A2A);
            }
            if (protocol != null) {
                String proto = p.protocol != null ? p.protocol.name() : "SSH";
                // แสดงสั้น ๆ แนวตั้ง: SSH / VLESS / VMESS / TROJAN
                if (proto.length() > 6) proto = proto.substring(0, 5);
                protocol.setText(proto.toUpperCase());
                protocol.setTextColor(sel ? 0xFF000000 : 0xFFB0BEC5);
            }
            try {
                com.google.android.material.card.MaterialCardView card =
                        itemView instanceof com.google.android.material.card.MaterialCardView
                                ? (com.google.android.material.card.MaterialCardView) itemView
                                : itemView.findViewById(com.example.vpn.R.id.cardProfile);
                if (card != null) {
                    if (sel) {
                        card.setCardBackgroundColor(0xFF1A3A3A); // teal tint
                        card.setStrokeColor(0xFF00BCD4);
                        card.setStrokeWidth(2);
                    } else {
                        card.setCardBackgroundColor(0xFF1E1E1E);
                        card.setStrokeColor(0xFF2A2A2A);
                        card.setStrokeWidth(1);
                    }
                }
            } catch (Exception ignored) {}

            if (btnSignal != null) {
                btnSignal.setImageResource(signalIconFor(latencyMs));
                btnSignal.setOnClickListener(v -> {
                    latencyMap.put(p.id, null);
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(pos);
                    }
                    LatencyProbe.measure(p.id, p.host, p.port, (profileId, ms) ->
                            main.post(() -> {
                                latencyMap.put(profileId, ms);
                                notifyLatencyChanged(profileId);
                            }));
                });
            }

            itemView.setOnClickListener(v -> {
                setSelectedProfileId(p.id);
                listener.onConnect(p);
            });
            itemView.setOnLongClickListener(v -> {
                listener.onEdit(p);
                return true;
            });

            if (btnQr != null) {
                btnQr.setOnClickListener(v -> listener.onShareQr(p));
            }
            if (btnEdit != null) {
                btnEdit.setOnClickListener(v -> listener.onEdit(p));
            }
            if (btnDelete != null) {
                btnDelete.setOnClickListener(v -> listener.onDelete(p));
            }
        }
    }
}
