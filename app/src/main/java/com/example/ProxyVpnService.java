package com.example.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.service.quicksettings.TileService;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.tunnel.Socks5Server;
import com.example.vpn.tunnel.SshTunnel;
import com.example.vpn.util.BypassPrefs;
import com.example.vpn.util.ConnectivityChecker;
import com.example.vpn.util.NetworkBinder;
import com.example.vpn.util.NetworkMonitor;
import com.example.vpn.util.StatusBus;
import com.example.vpn.util.VpnLogger;
import com.example.vpn.util.VpnPrefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import hev.htproxy.TProxyService;

public class ProxyVpnService extends VpnService
        implements NetworkMonitor.Listener {

    public static final String TAG = "ProxyVpnService";
    public static final String ACTION_START = "START_VPN";
    public static final String ACTION_STOP  = "STOP_VPN";
    public static final String ACTION_RECONNECT = "RECONNECT_VPN";
    public static final String EXTRA_PROFILE_ID = "profile_id";

    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIF_ID = 1001;
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final String VPN_ROUTE   = "0.0.0.0";
    private static final int VPN_PREFIX     = 0;
    private static final int VPN_MTU = 1280;

    // ⭐ Service state (persist ข้าม process)
    private static final String STATE_PREF = "vpn_state";
    private static final String KEY_RUNNING = "running";

    private ParcelFileDescriptor tunFd;
    private Thread workerThread;
    private volatile boolean running = false;
    private volatile boolean destroying = false;
    private volatile boolean tun2socksRunning = false;
    private File configFile;

    private SshTunnel sshTunnel;
    private Socks5Server socks5Server;
    private NetworkMonitor networkMonitor;
    private ConnectivityChecker connectivityChecker;
    private VpnPrefs prefs;
    private BypassPrefs bypassPrefs;

    private Profile currentProfile;
    private volatile boolean connected = false;
    private volatile boolean reconnecting = false;

    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private static final long RECONNECT_DELAY_MS = 3000;

    // ============================================================
    // ⭐ Service state helpers
    // ============================================================

    private void setServiceRunning(boolean running) {
        try {
            getSharedPreferences(STATE_PREF, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_RUNNING, running)
                    .apply();
            VpnLogger.i(TAG, "Service running = " + running);

            // ⭐ Sync Tile
            notifyTileStateChanged();

        } catch (Exception ignored) {}
    }

    /** ⭐ ให้ UI เรียกได้ */
    public static boolean isServiceRunning(Context ctx) {
        try {
            return ctx.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY_RUNNING, false);
        } catch (Exception e) {
            return false;
        }
    }

    // ============================================================
    // ⭐ Sync Tile state
    // ============================================================
    private void notifyTileStateChanged() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                TileService.requestListeningState(
                        this,
                        new ComponentName(this, VpnTileService.class));
                VpnLogger.i(TAG, "Requested tile state refresh");
            }
        } catch (Exception e) {
            VpnLogger.w(TAG, "notifyTileStateChanged failed: " + e.getMessage());
        }
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new VpnPrefs(this);
        bypassPrefs = new BypassPrefs(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            VpnLogger.i(TAG, "STOP action received");
            prefs.setWasConnected(false);
            prefs.setKillSwitch(false);
            stopVpn(true);
            return START_NOT_STICKY;
        }

        if (ACTION_RECONNECT.equals(action)) {
            if (currentProfile != null && !reconnecting) {
                VpnLogger.i(TAG, "Manual reconnect requested");
                doReconnect();
            }
            return START_STICKY;
        }

        if (ACTION_START.equals(action)) {
            long profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L);
            if (profileId <= 0) {
                VpnLogger.e(TAG, "Invalid profile id");
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                if (running || workerThread != null || tunFd != null) {
                    setServiceRunning(true);
                    return START_STICKY;
                }
                startForeground(NOTIF_ID, buildNotification("กำลังเชื่อมต่อ..."));
            } catch (Throwable e) {
                VpnLogger.e(TAG, "startForeground failed", e);
                stopSelf();
                return START_NOT_STICKY;
            }
            prefs.setLastProfileId(profileId);
            setServiceRunning(true);
            loadProfileAndStart(profileId);
        }

        return START_STICKY;
    }

    private void loadProfileAndStart(long profileId) {
        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        repo.getById(profileId, profile -> {
            if (profile == null) {
                VpnLogger.e(TAG, "Profile not found: " + profileId);
                stopVpn(true);
                return;
            }
            currentProfile = profile;
            workerThread = new Thread(() -> startVpn(profile), "vpn-worker");
            workerThread.start();
        });
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private void startVpn(Profile profile) {
        try {
            if (profile == null || profile.host == null || profile.host.trim().isEmpty()) {
                throw new IOException("โปรไฟล์ไม่มี host");
            }
            if (profile.port < 1 || profile.port > 65535) {
                throw new IOException("พอร์ตไม่ถูกต้อง: " + profile.port);
            }

            StatusBus.post(StatusBus.State.CONNECTING_SSH, "กำลังสร้าง TUN...");
            VpnLogger.i(TAG, "Creating TUN interface...");

            Builder builder = new Builder()
                    .setSession(profile.name)
                    .addAddress(VPN_ADDRESS, 32)
                    .addRoute(VPN_ROUTE, VPN_PREFIX)
                    .addDisallowedApplication(getPackageName())
                    .setMtu(VPN_MTU)
                    .setBlocking(true);

            int bypassCount = 0;
            Set<String> bypassed = bypassPrefs.getPackages();
            for (String pkg : bypassed) {
                try {
                    builder.addDisallowedApplication(pkg);
                    bypassCount++;
                } catch (Exception e) {
                    VpnLogger.w(TAG, "Bypass: cannot add " + pkg);
                }
            }
            if (bypassCount > 0) {
                VpnLogger.i(TAG, "Bypass Mode: " + bypassCount + " apps excluded");
            }

            addDnsIfValid(builder, profile.dns1);
            addDnsIfValid(builder, profile.dns2);

            tunFd = builder.establish();
            if (tunFd == null) {
                throw new IOException("Failed to establish TUN");
            }
            running = true;
            VpnLogger.i(TAG, "TUN established: fd=" + tunFd.getFd() + " MTU=" + VPN_MTU);

            prefs.setWasConnected(true);

            try {
                setUnderlyingNetworks(null);
                VpnLogger.i(TAG, "Forced underlying networks = null");
            } catch (Throwable t) {
                VpnLogger.w(TAG, "setUnderlyingNetworks failed: " + t.getMessage());
            }

            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            VpnLogger.i(TAG, "VPN fully established — starting SSH...");

            connectSshAndSocks(profile);

        } catch (Throwable e) {
            VpnLogger.e(TAG, "startVpn error: " + e.getMessage(), e);
            try {
                StatusBus.post(StatusBus.State.ERROR, "ผิดพลาด: " + e.getMessage());
                updateNotification("ผิดพลาด: " + e.getMessage());
            } catch (Exception ignored) {}

            if (prefs.isKillSwitch() && tunFd != null) {
                VpnLogger.w(TAG, "Kill Switch: keeping TUN active");
                updateNotification("🔒 Kill Switch Active");
                return;
            }

            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            stopVpn(true);
        }
    }

    private void connectSshAndSocks(Profile profile) {
        try {
            StatusBus.post(StatusBus.State.CONNECTING_SSH,
                    "กำลังเชื่อมต่อ SSH: " + profile.host + ":" + profile.port);
            updateNotification("กำลังเชื่อมต่อ SSH...");
            VpnLogger.i(TAG, "Connecting SSH to " + profile.host + ":" + profile.port);

            sshTunnel = new SshTunnel(
                    profile.host,
                    profile.port,
                    profile.user,
                    profile.pass,
                    emptyToNull(profile.httpProxy),
                    emptyToNull(profile.payload),
                    emptyToNull(profile.sni),
                    socket -> protect(socket)
            );

            if (!sshTunnel.isConnected()) {
                throw new IOException("SSH not connected");
            }
            StatusBus.post(StatusBus.State.SSH_CONNECTED, "SSH เชื่อมต่อแล้ว");
            VpnLogger.i(TAG, "SSH connected");
            updateNotification("SSH เชื่อมต่อแล้ว กำลังเปิด SOCKS...");

            VpnLogger.i(TAG, "Starting SOCKS5 server...");
            socks5Server = new Socks5Server(sshTunnel);
            socks5Server.start();

            StatusBus.post(StatusBus.State.SOCKS_READY,
                    "SOCKS5 พร้อม: 127.0.0.1:" + Socks5Server.LOCAL_PORT);
            VpnLogger.i(TAG, "SOCKS5 ready on 127.0.0.1:" + Socks5Server.LOCAL_PORT);

            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            VpnLogger.i(TAG, "Waiting 500ms for SOCKS5 to be fully ready...");

            StatusBus.post(StatusBus.State.TUN2SOCKS_READY, "กำลังเปิด Tun2Socks...");
            updateNotification("กำลังเชื่อมต่อทราฟฟิก...");
            VpnLogger.i(TAG, "Starting Tun2Socks bridge...");

            copyConfigFromAssets();
            VpnLogger.i(TAG, "Config: " + configFile.getAbsolutePath());

            boolean started = false;
            try {
                started = TProxyService.TProxyStartService(
                        configFile.getAbsolutePath(),
                        tunFd.getFd()
                );
            } catch (Throwable t) {
                VpnLogger.e(TAG, "TProxyStartService threw: " + t.getMessage(),
                        t instanceof Exception ? (Exception) t : new Exception(t));
            }

            if (!started) {
                VpnLogger.w(TAG, "Tun2Socks not available — SSH/SOCKS5 only");
                tun2socksRunning = false;
                connected = true;
                StatusBus.post(StatusBus.State.CONNECTED,
                        "เชื่อมต่อ (SSH เท่านั้น): " + profile.name);
                updateNotification("SSH พร้อม — Tun2Socks ไม่ทำงาน");
                startNetworkMonitor();
                return;
            }

            tun2socksRunning = true;
            VpnLogger.i(TAG, "Tun2Socks started — VPN is active");

            // FIRST CONNECT FIX
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            VpnLogger.i(TAG, "[Fix] Waiting 800ms for tunnel to stabilize...");

            NetworkBinder.forceReroute(this);
            VpnLogger.i(TAG, "[Fix] Forced network reroute");

            try {
                setUnderlyingNetworks(null);
                VpnLogger.i(TAG, "[Fix] setUnderlyingNetworks(null) — Force re-evaluate");
            } catch (Throwable t) {
                VpnLogger.w(TAG, "[Fix] setUnderlyingNetworks failed: " + t.getMessage());
            }

            StatusBus.post(StatusBus.State.TUN2SOCKS_READY,
                    "กำลังตรวจสอบการเชื่อมต่อ...");
            updateNotification("กำลังตรวจสอบการเชื่อมต่อ...");

            connectivityChecker = new ConnectivityChecker(this);
            connectivityChecker.check(new ConnectivityChecker.Callback() {
                @Override
                public void onReady() {
                    connected = true;
                    VpnLogger.i(TAG, "[Fix] ✅ Connectivity verified — VPN ready!");
                    StatusBus.post(StatusBus.State.CONNECTED,
                            "เชื่อมต่อแล้ว: " + profile.name);
                    updateNotification("เชื่อมต่อแล้ว: " + profile.name);
                    startNetworkMonitor();
                }

                @Override
                public void onFailed(String reason) {
                    connected = true;
                    VpnLogger.w(TAG, "[Fix] ⚠️ Verification failed: " + reason);
                    StatusBus.post(StatusBus.State.CONNECTED,
                            "เชื่อมต่อแล้ว (อาจต้องรอสักครู่): " + profile.name);
                    updateNotification("เชื่อมต่อแล้ว: " + profile.name);
                    startNetworkMonitor();
                }
            });

        } catch (Throwable e) {
            VpnLogger.e(TAG, "connectSshAndSocks error: " + e.getMessage(), e);
            try {
                StatusBus.post(StatusBus.State.ERROR, "ผิดพลาด: " + e.getMessage());
                updateNotification("ผิดพลาด: " + e.getMessage());
            } catch (Exception ignored) {}

            if (prefs.isKillSwitch() && tunFd != null) {
                VpnLogger.w(TAG, "Kill Switch: keeping TUN active");
                updateNotification("🔒 Kill Switch Active");
                scheduleReconnect();
                return;
            }

            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            stopVpn(true);
        }
    }

    private void startNetworkMonitor() {
        if (networkMonitor == null) {
            networkMonitor = new NetworkMonitor(this, this);
        }
        networkMonitor.start();
        VpnLogger.i(TAG, "NetworkMonitor started");
    }

    private void stopNetworkMonitor() {
        if (networkMonitor != null) {
            networkMonitor.stop();
            networkMonitor = null;
        }
    }

    @Override
    public void onNetworkAvailable() {
        VpnLogger.i(TAG, "Network available — checking VPN state");
        if (!connected && currentProfile != null && prefs.isAutoReconnect()) {
            VpnLogger.i(TAG, "Auto-reconnect: network is back");
            scheduleReconnect();
        }
    }

    @Override
    public void onNetworkLost() {
        VpnLogger.w(TAG, "Network lost");
        if (connected) {
            connected = false;
            if (prefs.isKillSwitch()) {
                updateNotification("🔒 รอ network กลับมา...");
            } else {
                updateNotification("Network lost — กำลังรอ...");
            }
            StatusBus.post(StatusBus.State.ERROR, "Network lost");
        }
    }

    private void scheduleReconnect() {
        if (reconnecting) return;
        reconnecting = true;

        reconnectHandler.removeCallbacksAndMessages(null);
        reconnectHandler.postDelayed(() -> {
            reconnecting = false;
            if (currentProfile != null) {
                doReconnect();
            }
        }, RECONNECT_DELAY_MS);

        VpnLogger.i(TAG, "Reconnect scheduled in " + RECONNECT_DELAY_MS + "ms");
    }

    private void doReconnect() {
        if (currentProfile == null) return;
        VpnLogger.i(TAG, "Reconnecting...");
        updateNotification("กำลังเชื่อมต่อใหม่...");
        StatusBus.post(StatusBus.State.CONNECTING_SSH, "กำลัง reconnect...");

        if (connectivityChecker != null) {
            connectivityChecker.cancel();
            connectivityChecker = null;
        }

        if (tun2socksRunning) {
            try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
            tun2socksRunning = false;
        }
        if (socks5Server != null) {
            try { socks5Server.stop(); } catch (Throwable ignored) {}
            socks5Server = null;
        }
        if (sshTunnel != null) {
            try { sshTunnel.disconnect(); } catch (Throwable ignored) {}
            sshTunnel = null;
        }

        workerThread = new Thread(() -> connectSshAndSocks(currentProfile), "vpn-reconnect");
        workerThread.start();
    }

    // ============================================================
    // ⭐ Stop — เพิ่ม setServiceRunning(false) + สั่ง statusBus ทันที
    // ============================================================
    private void stopVpn(boolean fullClose) {
        running = false;
        connected = false;
        VpnLogger.i(TAG, "Stopping VPN (full=" + fullClose + ")...");

        if (connectivityChecker != null) {
            connectivityChecker.cancel();
            connectivityChecker = null;
        }

        stopNetworkMonitor();

        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }

        if (tun2socksRunning) {
            try {
                TProxyService.TProxyStopService();
                VpnLogger.i(TAG, "Tun2Socks stopped");
            } catch (Throwable t) {
                VpnLogger.w(TAG, "TProxyStopService error: " + t.getMessage());
            }
            tun2socksRunning = false;
        }

        if (socks5Server != null) {
            try { socks5Server.stop(); } catch (Throwable ignored) {}
            socks5Server = null;
        }

        if (sshTunnel != null) {
            try { sshTunnel.disconnect(); } catch (Throwable ignored) {}
            sshTunnel = null;
        }

        if (fullClose) {
            if (tunFd != null) {
                try { tunFd.close(); } catch (IOException ignored) {}
                tunFd = null;
            }
            if (configFile != null && configFile.exists()) {
                configFile.delete();
                configFile = null;
            }
            prefs.setWasConnected(false);

            // ⭐ set flag + post status ทันที
            setServiceRunning(false);
            StatusBus.post(StatusBus.State.STOPPED, "หยุดแล้ว");

            try { stopForeground(true); } catch (Exception ignored) {}

            // ⭐ เลื่อน stopSelf 100ms
            if (!destroying) {
                new Handler(Looper.getMainLooper()).postDelayed(this::stopSelf, 100);
            } else {
                stopSelf();
            }
        } else {
            VpnLogger.w(TAG, "Keeping TUN active (kill switch mode)");
        }
    }

    @Override
    public void onDestroy() {
        destroying = true;
        stopVpn(true);
        setServiceRunning(false);   // ⭐ mark stopped
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        prefs.setWasConnected(false);
        stopVpn(true);
        super.onRevoke();
    }

    private static void addDnsIfValid(Builder builder, String dns) {
        if (dns == null || dns.trim().isEmpty()) return;
        String value = dns.trim();
        if (!value.matches("[0-9a-fA-F:.]+") || !value.matches(".*[0-9].*")) return;
        try {
            builder.addDnsServer(value);
        } catch (IllegalArgumentException ignored) {}
    }

    private void copyConfigFromAssets() throws IOException {
        configFile = new File(getFilesDir(), "hev-config.yml");
        try (InputStream in = getAssets().open("hev-config.yml");
             FileOutputStream out = new FileOutputStream(configFile)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private Notification buildNotification(String text) {
        createChannelIfNeeded();

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, ProxyVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent reconnectIntent = new Intent(this, ProxyVpnService.class);
        reconnectIntent.setAction(ACTION_RECONNECT);
        PendingIntent reconnectPi = PendingIntent.getService(
                this, 1, reconnectIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VPN Manager")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_rotate, "Reconnect", reconnectPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "หยุด", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception e) {
            VpnLogger.w(TAG, "updateNotification error: " + e.getMessage());
        }
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "VPN Status",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("สถานะการเชื่อมต่อ VPN");
                nm.createNotificationChannel(ch);
            }
        }
    }
}
