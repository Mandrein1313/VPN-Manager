package com.example.vpn;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;

import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.util.VpnLogger;
import com.example.vpn.util.VpnPrefs;

/**
 * ⭐ Quick Settings Tile — ปุ่ม VPN ใน notification shade
 * - กด = connect / disconnect
 * - ยาวกด = เปิดแอป
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class VpnTileService extends TileService {

    private static final String TAG = "VpnTileService";

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        VpnLogger.i(TAG, "Tile added");
        updateTileState();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        VpnLogger.i(TAG, "Tile listening — refreshing state");
        updateTileState();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
    }

    @Override
    public void onTileRemoved() {
        super.onTileRemoved();
        VpnLogger.i(TAG, "Tile removed");
    }

    // ============================================================
    // ⭐ กดปุ่ม Tile
    // ============================================================
    @Override
    public void onClick() {
        super.onClick();

        boolean serviceRunning = ProxyVpnService.isServiceRunning(this);
        VpnLogger.i(TAG, "Tile clicked — service running = " + serviceRunning);

        if (serviceRunning) {
            // ⭐ กำลังทำงาน → หยุด
            stopVpn();
        } else {
            // ⭐ ไม่ทำงาน → เริ่ม (เปิดแอปถ้าต้องการ permission)
            startVpn();
        }
    }

    // ============================================================
    // Start VPN
    // ============================================================
    private void startVpn() {
        VpnPrefs prefs = new VpnPrefs(this);
        long profileId = prefs.getLastProfileId();

        // ⭐ ถ้าไม่มี profile ล่าสุด → หา favorite หรือ profile แรก
        if (profileId <= 0) {
            loadFirstAvailableProfile();
            return;
        }

        // ⭐ เช็ค VPN permission ก่อน
        Intent prepare = android.net.VpnService.prepare(this);
        if (prepare != null) {
            // ยังไม่ได้รับอนุญาต → เปิดแอปให้ผู้ใช้ขอ permission
            VpnLogger.i(TAG, "VPN permission not granted — opening app");
            openApp();
            return;
        }

        startVpnService(profileId);
    }

    private void loadFirstAvailableProfile() {
        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        repo.observeAll().observeForever(profiles -> {
            if (profiles == null || profiles.isEmpty()) {
                VpnLogger.w(TAG, "No profile available — opening app");
                openApp();
                return;
            }

            // หา favorite ก่อน
            long targetId = -1;
            for (com.example.vpn.model.Profile p : profiles) {
                if (p.isFavorite) {
                    targetId = p.id;
                    break;
                }
            }
            // ถ้าไม่มี favorite → ใช้ตัวแรก
            if (targetId <= 0) targetId = profiles.get(0).id;

            Intent prepare = android.net.VpnService.prepare(this);
            if (prepare != null) {
                VpnLogger.i(TAG, "VPN permission not granted — opening app");
                openApp();
                return;
            }

            startVpnService(targetId);
        });
    }

    private void startVpnService(long profileId) {
        Intent svc = new Intent(this, ProxyVpnService.class);
        svc.setAction(ProxyVpnService.ACTION_START);
        svc.putExtra(ProxyVpnService.EXTRA_PROFILE_ID, profileId);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }

            // อัปเดต Tile เป็น connecting
            updateTileConnecting();
            VpnLogger.i(TAG, "VPN service started from tile");

        } catch (Exception e) {
            VpnLogger.e(TAG, "startVpnService failed: " + e.getMessage(), e);
        }
    }

    // ============================================================
    // Stop VPN
    // ============================================================
    private void stopVpn() {
        Intent svc = new Intent(this, ProxyVpnService.class);
        svc.setAction(ProxyVpnService.ACTION_STOP);
        try {
            startService(svc);
            updateTileState();
            VpnLogger.i(TAG, "VPN stop requested from tile");
        } catch (Exception e) {
            VpnLogger.e(TAG, "stopVpn failed: " + e.getMessage(), e);
        }
    }

    // ============================================================
    // เปิดแอป
    // ============================================================
    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("from_tile", true);
        try {
            startActivityAndCollapse(intent);
        } catch (Exception e) {
            VpnLogger.e(TAG, "openApp failed: " + e.getMessage(), e);
        }
    }

    // ============================================================
    // อัปเดตสถานะ Tile
    // ============================================================
    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean running = ProxyVpnService.isServiceRunning(this);

        if (running) {
            // ⭐ เชื่อมต่อแล้ว
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("VPN เปิด");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle("เชื่อมต่ออยู่");
            }
        } else {
            // ⭐ ปิดอยู่
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("VPN ปิด");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle("กดเพื่อเชื่อมต่อ");
            }
        }

        // ⭐ Icon
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_vpn));

        // ⭐ อัปเดต UI
        tile.updateTile();
    }

    private void updateTileConnecting() {
        Tile tile = getQsTile();
        if (tile == null) return;

        tile.setState(Tile.STATE_ACTIVE);
        tile.setLabel("VPN กำลังเชื่อมต่อ...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle("กรุณารอสักครู่");
        }
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_vpn));
        tile.updateTile();
    }
}