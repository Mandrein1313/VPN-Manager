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
import com.example.vpn.model.V2RayConfig;
import com.example.vpn.tunnel.Socks5Server;
import com.example.vpn.tunnel.SshTunnel;
import com.example.vpn.tunnel.V2RayEngine;
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
    // ⭐ Notification actions
    public static final String ACTION_TOGGLE_BYPASS = "TOGGLE_BYPASS";
    public static final String ACTION_SHOW_STATS = "SHOW_STATS";
    public static final String ACTION_RESTART_VPN = "RESTART_VPN";
    public static final String EXTRA_PROFILE_ID = "profile_id";

    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIF_ID = 1001;
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final String VPN_ROUTE   = "0.0.0.0";
    private static final int VPN_PREFIX     = 0;
    private static final int VPN_MTU = 1280;

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
    private V2RayEngine v2rayEngine;
    private NetworkMonitor networkMonitor;
    private ConnectivityChecker connectivityChecker;
    private VpnPrefs prefs;
    private BypassPrefs bypassPrefs;

    private Profile currentProfile;
    private volatile boolean connected = false;
    private volatile boolean reconnecting = false;

    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private static final long RECONNECT_DELAY_MS = 3000;

    // ⭐ Heartbeat — ส่ง traffic เล็ก ๆ ผ่าน tunnel ทุก 20 วินาที กัน idle หลุด
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000; // ตรวจถี่ขึ้น
    private static final long HEARTBEAT_RETRY_MS = 5_000;     // หลัง fail ลองใหม่เร็ว
    private volatile int heartbeatFailCount = 0;
    private static final int HEARTBEAT_FAIL_MAX = 2; // เน็ตค้างจริง → reconnect เร็วขึ้น (~20-30 วิ)

    // ============================================================
    // ⭐ Service state
    // ============================================================

    private void setServiceRunning(boolean running) {
        try {
            getSharedPreferences(STATE_PREF, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_RUNNING, running)
                    .apply();
            VpnLogger.i(TAG, "Service running = " + running);
            notifyTileStateChanged();
        } catch (Exception ignored) {}
    }

    public static boolean isServiceRunning(Context ctx) {
        try {
            return ctx.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY_RUNNING, false);
        } catch (Exception e) {
            return false;
        }
    }

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

        // ===== STOP =====
        if (ACTION_STOP.equals(action)) {
            VpnLogger.i(TAG, "STOP action received");
            prefs.setWasConnected(false);
            prefs.setKillSwitch(false);
            stopVpn(true);
            return START_NOT_STICKY;
        }

        // ===== RECONNECT =====
        if (ACTION_RECONNECT.equals(action)) {
            if (currentProfile != null && !reconnecting) {
                VpnLogger.i(TAG, "Manual reconnect requested");
                doReconnect();
            }
            return START_STICKY;
        }

        // ===== RESTART VPN =====
        if (ACTION_RESTART_VPN.equals(action)) {
            VpnLogger.i(TAG, "Restart VPN requested");
            handleRestartVpn();
            return START_STICKY;
        }

        // ===== TOGGLE BYPASS =====
        if (ACTION_TOGGLE_BYPASS.equals(action)) {
            VpnLogger.i(TAG, "Toggle bypass requested");
            handleToggleBypass();
            return START_STICKY;
        }

        // ===== SHOW STATS =====
        if (ACTION_SHOW_STATS.equals(action)) {
            VpnLogger.i(TAG, "Show stats requested");
            handleShowStats();
            return START_STICKY;
        }

        // ===== START =====
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

    // ============================================================
    // ⭐ Notification Action Handlers
    // ============================================================

    /**
     * ⭐ Toggle Bypass — เปิด/ปิดการใช้งาน bypass list ชั่วคราว
     */
    private void handleToggleBypass() {
        try {
            boolean currentDisabled = prefs.isBypassDisabled();
            boolean newDisabled = !currentDisabled;
            prefs.setBypassDisabled(newDisabled);

            VpnLogger.i(TAG, "Bypass disabled = " + newDisabled);

            // แจ้งสถานะ
            if (newDisabled) {
                updateNotification("Bypass ปิด — กำลัง restart...");
            } else {
                updateNotification("Bypass เปิด — กำลัง restart...");
            }

            // Restart VPN เพื่อ apply การเปลี่ยนแปลง
            if (connected && currentProfile != null) {
                reconnectHandler.postDelayed(this::doReconnect, 500);
            } else {
                // แค่อัปเดต notification
                updateNotification(connected
                        ? "เชื่อมต่อแล้ว: " + currentProfile.name
                        : "VPN ปิด");
            }
        } catch (Exception e) {
            VpnLogger.e(TAG, "handleToggleBypass error: " + e.getMessage(), e);
        }
    }

    /**
     * ⭐ Show Stats — เปิดแอปไปที่หน้า Connection
     */
    private void handleShowStats() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("show_stats", true);
            startActivity(intent);
        } catch (Exception e) {
            VpnLogger.e(TAG, "handleShowStats error: " + e.getMessage(), e);
        }
    }

    /**
     * ⭐ Restart VPN — หยุดชั่วคราว + เริ่มใหม่
     */
    private void handleRestartVpn() {
        if (currentProfile == null) return;
        VpnLogger.i(TAG, "Restarting VPN...");
        reconnecting = true;
        stopHeartbeat();

        // หยุด components แต่ไม่ปิด TUN
        if (connectivityChecker != null) {
            connectivityChecker.cancel();
            connectivityChecker = null;
        }
        if (tun2socksRunning) {
            try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
            tun2socksRunning = false;
        }
        if (v2rayEngine != null) {
            try { v2rayEngine.stop(); } catch (Throwable ignored) {}
            v2rayEngine = null;
        }
        if (socks5Server != null) {
            try { socks5Server.stop(); } catch (Throwable ignored) {}
            socks5Server = null;
        }
        if (sshTunnel != null) {
            try { sshTunnel.disconnect(); } catch (Throwable ignored) {}
            sshTunnel = null;
        }

        // เริ่มใหม่หลัง 500ms
        reconnectHandler.postDelayed(() -> {
            reconnecting = false;
            if (currentProfile != null) {
                workerThread = new Thread(
                        () -> connectSshAndSocks(currentProfile), "vpn-restart");
                workerThread.start();
            }
        }, 500);
    }

    // ============================================================
    // Load & Start
    // ============================================================

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

            // ⭐ Apply bypass list — ถ้าไม่ถูก disable
            int bypassCount = 0;
            if (!prefs.isBypassDisabled()) {
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
            } else {
                VpnLogger.i(TAG, "Bypass Mode: DISABLED (user toggled)");
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
            VpnLogger.i(TAG, "VPN fully established — starting connection...");

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
            // ⭐ แยกตาม protocol
            boolean isV2Ray = profile.protocol == com.example.vpn.model.Protocol.V2RAY
                    || profile.protocol == com.example.vpn.model.Protocol.TROJAN
                    || profile.protocol == com.example.vpn.model.Protocol.SHADOWSOCKS;

            if (isV2Ray) {
                connectV2Ray(profile);
                return;
            }

            // ---- SSH Tunnel ----
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
                startHeartbeat();
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
                    startHeartbeat();
                }

                @Override
                public void onFailed(String reason) {
                    connected = true;
                    VpnLogger.w(TAG, "[Fix] ⚠️ Verification failed: " + reason);
                    StatusBus.post(StatusBus.State.CONNECTED,
                            "เชื่อมต่อแล้ว (อาจต้องรอสักครู่): " + profile.name);
                    updateNotification("เชื่อมต่อแล้ว: " + profile.name);
                    startNetworkMonitor();
                    startHeartbeat();
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

    // ============================================================
    // ⭐ V2Ray Connection
    // ============================================================
    private void connectV2Ray(Profile profile) throws Exception {
        VpnLogger.i(TAG, "Connecting V2Ray: " + profile.v2rayType);
        StatusBus.post(StatusBus.State.CONNECTING_SSH, "กำลังเชื่อมต่อ V2Ray...");
        updateNotification("กำลังเชื่อมต่อ V2Ray...");

        // สร้าง config
        V2RayConfig cfg = new V2RayConfig();
        cfg.type = profile.v2rayType;
        cfg.address = profile.host;
        cfg.port = profile.port;
        cfg.uuid = profile.v2rayUuid;
        cfg.password = profile.pass;
        cfg.method = profile.v2rayMethod;
        cfg.network = profile.v2rayNetwork;
        cfg.path = profile.v2rayPath;
        cfg.host = profile.v2rayHost;
        cfg.serviceName = profile.v2rayServiceName;
        cfg.tls = profile.v2rayTls;
        cfg.sni = profile.sni;
        cfg.flow = profile.v2rayFlow;
        cfg.fingerprint = "chrome";
        cfg.allowInsecure = true;

        // ⭐ สร้าง engine
        v2rayEngine = new V2RayEngine(this, cfg, fd -> protect(fd));
        v2rayEngine.start();

        StatusBus.post(StatusBus.State.SOCKS_READY,
                "V2Ray พร้อม: 127.0.0.1:" + V2RayEngine.SOCKS_PORT);
        VpnLogger.i(TAG, "V2Ray started on port " + V2RayEngine.SOCKS_PORT);

        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        // ---- Tun2Socks (ชี้ไปที่ V2Ray SOCKS port) ----
        StatusBus.post(StatusBus.State.TUN2SOCKS_READY, "กำลังเปิด Tun2Socks...");
        updateNotification("กำลังเชื่อมต่อทราฟฟิก...");

        copyConfigFromAssets();
        // ⭐ แก้ config ให้ชี้ port 1081
        rewriteConfigPort(V2RayEngine.SOCKS_PORT);

        boolean started = false;
        try {
            started = TProxyService.TProxyStartService(
                    configFile.getAbsolutePath(),
                    tunFd.getFd()
            );
        } catch (Throwable t) {
            VpnLogger.e(TAG, "TProxyStartService threw: " + t.getMessage());
        }

        if (!started) {
            throw new IOException("Tun2Socks failed");
        }

        tun2socksRunning = true;
        connected = true;
        VpnLogger.i(TAG, "V2Ray + Tun2Socks ready");

        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

        StatusBus.post(StatusBus.State.CONNECTED,
                "เชื่อมต่อแล้ว: " + profile.name);
        updateNotification("เชื่อมต่อแล้ว: " + profile.name);
        startNetworkMonitor();
        startHeartbeat();
    }

    /** ⭐ แก้ port ใน config yml */
    private void rewriteConfigPort(int port) {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(configFile)));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("port:")) {
                    sb.append("  port: ").append(port).append("\n");
                } else {
                    sb.append(line).append("\n");
                }
            }
            br.close();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            VpnLogger.w(TAG, "rewriteConfigPort error: " + e.getMessage());
        }
    }

    // ============================================================
    // ⭐ Heartbeat — กัน SSH/NAT ตัดตอน idle (เน็ตค้างจริง → reconnect เร็ว)
    // ============================================================

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatFailCount = 0;

        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running || !connected || destroying) {
                    return;
                }

                new Thread(() -> {
                    int result = doHeartbeatPing(); // 1=ok, 0=soft fail, -1=hard fail
                    if (!running || !connected) return;

                    if (result > 0) {
                        heartbeatFailCount = 0;
                        VpnLogger.d(TAG, "Heartbeat OK");
                        scheduleNextHeartbeat(HEARTBEAT_INTERVAL_MS);
                        return;
                    }

                    // hard fail = session ตาย → reconnect ทันที
                    if (result < 0) {
                        VpnLogger.w(TAG, "Heartbeat HARD FAIL — SSH session dead → reconnect now");
                        heartbeatFailCount = 0;
                        reconnectHandler.post(() -> {
                            if (!running || destroying) return;
                            connected = false;
                            updateNotification("SSH หลุด — กำลัง reconnect...");
                            scheduleReconnect();
                        });
                        return;
                    }

                    // soft fail = channel เปิดไม่ได้ชั่วคราว
                    heartbeatFailCount++;
                    VpnLogger.w(TAG, "Heartbeat FAIL (" + heartbeatFailCount
                            + "/" + HEARTBEAT_FAIL_MAX + ")");

                    if (heartbeatFailCount >= HEARTBEAT_FAIL_MAX) {
                        heartbeatFailCount = 0;
                        if (prefs != null && prefs.isAutoReconnect()
                                && currentProfile != null && !reconnecting) {
                            VpnLogger.w(TAG, "Heartbeat dead → schedule reconnect");
                            reconnectHandler.post(() -> {
                                if (!running || destroying) return;
                                connected = false;
                                updateNotification("Heartbeat หลุด — กำลัง reconnect...");
                                scheduleReconnect();
                            });
                            return;
                        }
                    }

                    // ลองใหม่เร็วกว่าปกติ
                    scheduleNextHeartbeat(HEARTBEAT_RETRY_MS);
                }, "vpn-heartbeat").start();
            }
        };

        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
        VpnLogger.i(TAG, "Heartbeat started (every " + HEARTBEAT_INTERVAL_MS
                + "ms, failMax=" + HEARTBEAT_FAIL_MAX + ")");
    }

    private void scheduleNextHeartbeat(long delayMs) {
        if (!running || !connected || heartbeatRunnable == null) return;
        heartbeatHandler.postDelayed(heartbeatRunnable, delayMs);
    }

    private void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
        heartbeatHandler.removeCallbacksAndMessages(null);
        heartbeatFailCount = 0;
        VpnLogger.i(TAG, "Heartbeat stopped");
    }

    /**
     * @return 1 = OK, 0 = soft fail (channel), -1 = hard fail (session dead)
     */
    private int doHeartbeatPing() {
        SshTunnel tunnel = sshTunnel;
        if (tunnel != null) {
            if (!tunnel.isConnected()) {
                VpnLogger.w(TAG, "Heartbeat: SSH session not connected");
                return -1; // hard fail
            }
            com.jcraft.jsch.ChannelDirectTCPIP channel = null;
            try {
                channel = tunnel.openTcp("1.1.1.1", 53);
                java.io.OutputStream out = channel.getOutputStream();
                if (out != null) {
                    out.write(new byte[]{
                            0x00, 0x00,
                            0x01, 0x00,
                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                    });
                    out.flush();
                }
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                return 1;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                VpnLogger.w(TAG, "Heartbeat SSH ping error: " + e.getMessage());
                // session ตายจริง → hard fail
                if (!tunnel.isConnected()
                        || msg.contains("session is down")
                        || msg.contains("not connected")
                        || msg.contains("connection is closed")) {
                    return -1;
                }
                // channel is not opened ฯลฯ แต่ session ยัง flag ว่า connected
                // จากประสบการณ์ผู้ใช้: ตอนนี้เน็ตมักใช้ไม่ได้แล้ว → นับ soft fail
                // (fail 2 ครั้งติด + retry 5 วิ จะ reconnect เร็ว)
                return 0;
            } finally {
                if (channel != null) {
                    try { channel.disconnect(); } catch (Exception ignored) {}
                }
            }
        }

        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("1.1.1.1", 53), 3_000);
            s.setTcpNoDelay(true);
            return s.isConnected() ? 1 : 0;
        } catch (Exception e) {
            VpnLogger.w(TAG, "Heartbeat TCP ping error: " + e.getMessage());
            return 0;
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
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
        stopHeartbeat();
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
        stopHeartbeat();
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

        // ⭐ ปิด V2Ray
        if (v2rayEngine != null) {
            try { v2rayEngine.stop(); } catch (Throwable ignored) {}
            v2rayEngine = null;
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

    private void stopVpn(boolean fullClose) {
        running = false;
        connected = false;
        VpnLogger.i(TAG, "Stopping VPN (full=" + fullClose + ")...");

        stopHeartbeat();

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

        // ⭐ ปิด V2Ray
        if (v2rayEngine != null) {
            try { v2rayEngine.stop(); } catch (Throwable ignored) {}
            v2rayEngine = null;
        }

        if (socks5Server != null) {
            try { socks5Server.stop(); } catch (Throwable ignored) {}
            socks5Server = null;
        }

        if (sshTunnel != null) {
            try { sshTunnel.disconnect(); } catch (Throwable ignored) {}
            sshTunnel = null;
        }

        // ⭐ ปิด TUN ก่อนเสมอเมื่อ fullClose — ลำดับนี้สำคัญมาก กันกุญแจค้าง
        if (fullClose) {
            ParcelFileDescriptor fd = tunFd;
            tunFd = null;
            if (fd != null) {
                try {
                    fd.close();
                    VpnLogger.i(TAG, "TUN closed — VPN key should disappear");
                } catch (IOException e) {
                    VpnLogger.w(TAG, "TUN close error: " + e.getMessage());
                }
            }

            if (configFile != null && configFile.exists()) {
                try { configFile.delete(); } catch (Exception ignored) {}
                configFile = null;
            }
            try { prefs.setWasConnected(false); } catch (Exception ignored) {}

            setServiceRunning(false);
            try { StatusBus.post(StatusBus.State.STOPPED, "หยุดแล้ว"); } catch (Exception ignored) {}

            // ลบ notification + foreground ทันที
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
            } catch (Exception ignored) {}
            try {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.cancel(NOTIF_ID);
            } catch (Exception ignored) {}

            if (!destroying) {
                new Handler(Looper.getMainLooper()).postDelayed(this::stopSelf, 100);
            } else {
                try { stopSelf(); } catch (Exception ignored) {}
            }
        } else {
            VpnLogger.w(TAG, "Keeping TUN active (kill switch mode)");
        }
    }

    @Override
    public void onDestroy() {
        destroying = true;
        stopVpn(true);
        setServiceRunning(false);
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

    // ============================================================
    // ⭐ Notification — 4 ปุ่ม
    // ============================================================
    private Notification buildNotification(String text) {
        createChannelIfNeeded();

        // ===== Open App =====
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // ===== Stop =====
        Intent stopIntent = new Intent(this, ProxyVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // ===== Reconnect =====
        Intent reconnectIntent = new Intent(this, ProxyVpnService.class);
        reconnectIntent.setAction(ACTION_RECONNECT);
        PendingIntent reconnectPi = PendingIntent.getService(
                this, 2, reconnectIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // ⭐ Toggle Bypass
        Intent bypassIntent = new Intent(this, ProxyVpnService.class);
        bypassIntent.setAction(ACTION_TOGGLE_BYPASS);
        PendingIntent bypassPi = PendingIntent.getService(
                this, 3, bypassIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // ⭐ Show Stats
        Intent statsIntent = new Intent(this, ProxyVpnService.class);
        statsIntent.setAction(ACTION_SHOW_STATS);
        PendingIntent statsPi = PendingIntent.getService(
                this, 4, statsIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // ⭐ ตรวจสอบสถานะ Bypass
        boolean bypassDisabled = prefs.isBypassDisabled();
        String bypassLabel = bypassDisabled ? "Bypass: ปิด" : "Bypass: เปิด";
        int bypassIcon = bypassDisabled
                ? android.R.drawable.checkbox_off_background
                : android.R.drawable.checkbox_on_background;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VPN Manager")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentIntent(pi)
                // ⭐ 4 ปุ่ม
                .addAction(bypassIcon, bypassLabel, bypassPi)
                .addAction(android.R.drawable.ic_menu_info_details,
                        "Stats", statsPi)
                .addAction(android.R.drawable.ic_menu_rotate,
                        "Reconnect", reconnectPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "หยุด", stopPi)
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
