package com.example.vpn;

import com.example.vpn.util.RemoteGate;

import java.util.regex.Pattern;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.net.IpPrefix;
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
import com.example.vpn.util.ConnectFeedback;
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
    private static final int VPN_MTU = 1500; // ตรงกับ v2rayNG / V2BOX

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
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;
    private static final long HEARTBEAT_INTERVAL_V2RAY_MS = 60_000; // V2Ray: ห่างขึ้น ลด reconnect หลอก
    private static final long HEARTBEAT_RETRY_MS = 10_000;
    private static final long HEARTBEAT_RETRY_V2RAY_MS = 20_000;
    private volatile int heartbeatFailCount = 0;
    private static final int HEARTBEAT_FAIL_MAX = 4;
    private static final int HEARTBEAT_FAIL_MAX_V2RAY = 8; // V2Ray ทนมากกว่า — อย่าตัดเพราะ ping 53 หลอก

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
        // ใช้เฉพาะ flag ที่ set ตอนเชื่อมสำเร็จ — ไม่ใช้ ActivityManager
        // (service process อาจมีตอนกำลัง start ทำให้ UI เขียวผิด)
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
            if (RemoteGate.isBlocked(this)) {
                VpnLogger.w(TAG, "Reconnect blocked by RemoteGate");
                stopVpn(true);
                return START_NOT_STICKY;
            }
            if (currentProfile != null && !reconnecting) {
                VpnLogger.i(TAG, "Manual reconnect requested");
                doReconnect();
            }
            return START_STICKY;
        }

        // ===== RESTART VPN =====
        if (ACTION_RESTART_VPN.equals(action)) {
            if (RemoteGate.isBlocked(this)) {
                VpnLogger.w(TAG, "Restart blocked by RemoteGate");
                stopVpn(true);
                return START_NOT_STICKY;
            }
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
            // Android 8+ / 15: ต้อง startForeground() ภายใน ~5-10 วินาที
            // หลัง startForegroundService() — เรียกก่อนเช็คอะไรทั้งนั้น
            try {
                startForeground(NOTIF_ID, buildNotification("กำลังเชื่อมต่อ..."));
            } catch (Throwable e) {
                VpnLogger.e(TAG, "startForeground failed", e);
                stopSelf();
                return START_NOT_STICKY;
            }

            // Remote kill (cache) — ห้ามเริ่มถ้าเคยถูกสั่งปิด
            if (RemoteGate.isBlocked(this)) {
                VpnLogger.w(TAG, "VPN start blocked by RemoteGate (cache)");
                prefs.setWasConnected(false);
                try {
                    StatusBus.post(StatusBus.State.ERROR, RemoteGate.cachedMessage(this));
                } catch (Exception ignored) {}
                setServiceRunning(false);
                stopForegroundSafe();
                stopSelf();
                return START_NOT_STICKY;
            }

            long profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L);
            if (profileId <= 0) {
                VpnLogger.e(TAG, "Invalid profile id");
                setServiceRunning(false);
                stopForegroundSafe();
                stopSelf();
                return START_NOT_STICKY;
            }

            if (running || workerThread != null || tunFd != null) {
                setServiceRunning(true);
                updateNotification("เชื่อมต่ออยู่แล้ว");
                return START_STICKY;
            }

            prefs.setLastProfileId(profileId);
            // อย่า set running=true ตอนเริ่ม — รอจน CONNECTED จริง
            loadProfileAndStart(profileId);
        }

        return START_STICKY;
    }

    private void stopForegroundSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {}
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
            // เช็กเซิร์ฟอีกครั้งบน worker thread (ไม่ ANR)
            if (!RemoteGate.allowVpnStart(this)) {
                VpnLogger.w(TAG, "VPN start blocked by RemoteGate (server)");
                StatusBus.post(StatusBus.State.ERROR, RemoteGate.cachedMessage(this));
                setServiceRunning(false);
                stopSelf();
                return;
            }
            if (profile == null || profile.host == null || profile.host.trim().isEmpty()) {
                throw new IOException("โปรไฟล์ไม่มี host");
            }
            if (profile.port < 1 || profile.port > 65535) {
                throw new IOException("พอร์ตไม่ถูกต้อง: " + profile.port);
            }

            StatusBus.post(StatusBus.State.CONNECTING_SSH, "กำลังสร้าง TUN...");
            VpnLogger.i(TAG, "Creating TUN interface...");

            // ชื่อ session สั้น ใส — บางเครื่อง OEM แสดงไอคอนกุญแจจาก session
            String sessionName = profile.name != null ? profile.name.trim() : "Tunnel Mate";
            if (sessionName.isEmpty()) sessionName = "Tunnel Mate";
            // ตัด emoji/อักขระแปลก ๆ ที่ทำให้ notification/สถานะแปลก
            sessionName = sessionName.replaceAll("[^\\p{L}\\p{N}\\p{P}\\p{Z}]", "").trim();
            if (sessionName.isEmpty()) sessionName = "Tunnel Mate";
            if (sessionName.length() > 32) sessionName = sessionName.substring(0, 32);

            Builder builder = new Builder()
                    .setSession(sessionName)
                    .addAddress(VPN_ADDRESS, 30)
                    .addRoute(VPN_ROUTE, VPN_PREFIX)
                    .addDisallowedApplication(getPackageName())
                    .setMtu(VPN_MTU)
                    .setBlocking(true);

            // Android 10+: บอกระบบว่าเป็น VPN ไม่ใช่ metered ผิด ๆ
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { builder.setMetered(false); } catch (Throwable ignored) {}
            }
            // Android 5+: ให้ family IPv4 ชัด
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    builder.allowFamily(android.system.OsConstants.AF_INET);
                } catch (Throwable ignored) {}
            }

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

            // ⭐ DNS: V2Ray ใช้ 10.0.0.2 (dokodemo ใน core) — SSH ใช้ DNS ปกติ
            boolean v2Mode = profile.protocol == com.example.vpn.model.Protocol.V2RAY
                    || profile.protocol == com.example.vpn.model.Protocol.TROJAN
                    || profile.protocol == com.example.vpn.model.Protocol.SHADOWSOCKS;
            String d1;
            String d2;
            if (v2Mode) {
                // ส่ง DNS เข้า TUN IP → V2Ray dokodemo:53 → ออกผ่าน proxy
                d1 = VPN_ADDRESS; // 10.0.0.2
                d2 = null;
                addDnsIfValid(builder, d1);
                VpnLogger.i(TAG, "V2Ray DNS mode: " + d1 + " (via core dokodemo)");
            } else {
                d1 = (profile.dns1 != null && !profile.dns1.trim().isEmpty())
                        ? profile.dns1.trim() : "8.8.8.8";
                d2 = (profile.dns2 != null && !profile.dns2.trim().isEmpty())
                        ? profile.dns2.trim() : "1.1.1.1";
                addDnsIfValid(builder, d1);
                addDnsIfValid(builder, d2);
                addHostRoute(builder, d1);
                addHostRoute(builder, d2);
            }
            // IPv6: อย่า addRoute("::") ถ้าไม่มี IPv6 address บน TUN
            // ไม่งั้น Chrome/YouTube ใช้ v6 แล้วค้าง (blackhole)
            // ปิด IPv6 ผ่าน VPN ชั่วคราว — ใช้ IPv4 อย่างเดียว (เสถียรกว่ากับ hev+V2Ray)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    // ไม่ route IPv6 เข้า tunnel
                    VpnLogger.i(TAG, "IPv6 not routed into VPN (IPv4-only tunnel)");
                }
            } catch (Exception e) {
                VpnLogger.w(TAG, "IPv6 setup: " + e.getMessage());
            }
            VpnLogger.i(TAG, "DNS leak guard: " + d1 + " / " + d2);

            excludeServerFromTunnel(builder, profile);

            tunFd = builder.establish();
            if (tunFd == null) {
                throw new IOException("Failed to establish TUN — อาจมี VPN อื่นทำงานอยู่ หรือระบบปฏิเสธ");
            }
            running = true;
            // ยังไม่ setServiceRunning(true) — รอจน CONNECTED จริง กันปุ่มเขียวก่อนต่อสำเร็จ
            VpnLogger.i(TAG, "TUN established: fd=" + tunFd.getFd() + " MTU=" + VPN_MTU
                    + " (system VPN key should appear)");
            // แจ้งระบบทันทีว่า VPN ทำงาน — ช่วยเรื่องไอคอนกุญแจบาง OEM
            updateNotification("VPN interface พร้อม — กำลังเชื่อม SSH...");

            prefs.setWasConnected(true);

            try {
                setUnderlyingNetworks(null);
                VpnLogger.i(TAG, "Forced underlying networks = null");
            } catch (Throwable t) {
                VpnLogger.w(TAG, "setUnderlyingNetworks failed: " + t.getMessage());
            }

            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            VpnLogger.i(TAG, "VPN fully established — starting connection...");

            connectSshAndSocks(profile);

        } catch (Throwable e) {
            VpnLogger.e(TAG, "startVpn error: " + e.getMessage(), e);
            try {
                StatusBus.post(StatusBus.State.ERROR, "ผิดพลาด: " + e.getMessage());
                ConnectFeedback.onError(ProxyVpnService.this);
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
            VpnLogger.i(TAG, "Connecting SSH to " + profile.host + ":" + profile.port
                    + " udpgw=" + profile.udpgwPort);

            sshTunnel = new SshTunnel(
                    profile.host,
                    profile.port,
                    profile.user,
                    profile.pass,
                    emptyToNull(profile.httpProxy),
                    profile.proxyPort,
                    emptyToNull(profile.payload),
                    emptyToNull(profile.sni),
                    profile.sslPort,
                    profile.getConnectionMode(),
                    profile.usePrivateKey() ? profile.privateKey : null,
                    emptyToNull(profile.keyPassphrase),
                    socket -> protect(socket)
            );

            if (!sshTunnel.isConnected()) {
                throw new IOException("SSH not connected");
            }
            StatusBus.post(StatusBus.State.SSH_CONNECTED, "SSH เชื่อมต่อแล้ว");
            VpnLogger.i(TAG, "SSH connected");
            updateNotification("SSH เชื่อมต่อแล้ว กำลังเปิด SOCKS...");

            VpnLogger.i(TAG, "Starting SOCKS5 server...");
            boolean shareWifi = prefs != null && prefs.isShareWifi();
            // shareWifi=true → bind 0.0.0.0 ให้เครื่องอื่นใน Hotspot/LAN ใช้ได้
            String shareUser = null;
            String sharePass = null;
            if (shareWifi && prefs != null && prefs.isShareAuthEnabled()) {
                shareUser = prefs.getShareUsername();
                sharePass = prefs.getSharePassword();
            }
            socks5Server = new Socks5Server(sshTunnel, !shareWifi, profile.udpgwPort,
                    shareUser, sharePass);
            socks5Server.start();
            if (profile.udpgwPort > 0) {
                VpnLogger.i(TAG, "UDP enabled via udpgw port " + profile.udpgwPort
                        + " (DNS/QUIC/เกม ต้องมี badvpn-udpgw บนเซิร์ฟเวอร์)");
            } else {
                VpnLogger.w(TAG, "UDP disabled (udpgwPort=0) — มีแค่ TCP ผ่าน SOCKS");
            }

            String socksBind = shareWifi ? "0.0.0.0 (แชร์ LAN/Hotspot)" : "127.0.0.1";
            StatusBus.post(StatusBus.State.SOCKS_READY,
                    "SOCKS5 พร้อม: " + socksBind + ":" + Socks5Server.LOCAL_PORT);
            VpnLogger.i(TAG, "SOCKS5 ready on " + socksBind + ":" + Socks5Server.LOCAL_PORT);
            if (shareWifi) {
                updateNotification("VPN แชร์ Proxy :" + Socks5Server.LOCAL_PORT);
            }

            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            VpnLogger.i(TAG, "Waiting 500ms for SOCKS5 to be fully ready...");

            StatusBus.post(StatusBus.State.TUN2SOCKS_READY, "กำลังเปิด Tun2Socks...");
            updateNotification("กำลังเชื่อมต่อทราฟฟิก...");
            VpnLogger.i(TAG, "Starting Tun2Socks bridge...");

            copyConfigFromAssets();
            rewriteConfigPort(Socks5Server.LOCAL_PORT);
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
                setServiceRunning(true);
                StatusBus.post(StatusBus.State.CONNECTED,
                        "เชื่อมต่อ (SSH เท่านั้น): " + profile.name);
                ConnectFeedback.onConnected(ProxyVpnService.this);
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
                    setServiceRunning(true);
                    VpnLogger.i(TAG, "[Fix] ✅ Connectivity verified — VPN ready!");
                    StatusBus.post(StatusBus.State.CONNECTED,
                            "เชื่อมต่อแล้ว: " + profile.name);
                ConnectFeedback.onConnected(ProxyVpnService.this);
                    updateNotification("เชื่อมต่อแล้ว: " + profile.name);
                    startNetworkMonitor();
                    startHeartbeat();
                }

                @Override
                public void onFailed(String reason) {
                    connected = true;
                    setServiceRunning(true);
                    VpnLogger.w(TAG, "[Fix] ⚠️ Verification failed: " + reason);
                    StatusBus.post(StatusBus.State.CONNECTED,
                            "เชื่อมต่อแล้ว (อาจต้องรอสักครู่): " + profile.name);
                ConnectFeedback.onConnected(ProxyVpnService.this);
                    updateNotification("เชื่อมต่อแล้ว: " + profile.name);
                    startNetworkMonitor();
                    startHeartbeat();
                }
            });

        } catch (Throwable e) {
            VpnLogger.e(TAG, "connectSshAndSocks error: " + e.getMessage(), e);
            try {
                StatusBus.post(StatusBus.State.ERROR, "ผิดพลาด: " + e.getMessage());
                ConnectFeedback.onError(ProxyVpnService.this);
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
        cfg.host = V2RayEngine.sanitizeWsHost(profile.v2rayHost);
        cfg.serviceName = profile.v2rayServiceName;
        cfg.tls = profile.v2rayTls;
        cfg.sni = V2RayEngine.sanitizeWsHost(profile.sni);
        cfg.flow = profile.v2rayFlow;
        cfg.fingerprint = "chrome";
        // Fragment กับโหนด WS:80 ฟรีมักพัง — เปิดเฉพาะตอน user เปิด TLS/REALITY จริง
        boolean wantFrag = profile.v2rayFragment;
        if (wantFrag && !profile.v2rayTls
                && (profile.v2rayPublicKey == null || profile.v2rayPublicKey.isEmpty())) {
            VpnLogger.w(TAG, "Fragment ignored (no TLS/REALITY) — โหนดฟรีมักใช้ security=none");
            wantFrag = false;
        }
        cfg.fragment = wantFrag;
        cfg.allowInsecure = false;
        // REALITY
        String sec = profile.v2raySecurity;
        if (sec == null || sec.isEmpty()) {
            if (profile.v2rayPublicKey != null && !profile.v2rayPublicKey.isEmpty()) sec = "reality";
            else if (profile.v2rayTls) sec = "tls";
            else sec = "none";
        }
        cfg.security = sec;
        cfg.publicKey = profile.v2rayPublicKey != null ? profile.v2rayPublicKey : "";
        cfg.shortId = profile.v2rayShortId != null ? profile.v2rayShortId : "";
        cfg.spiderX = profile.v2raySpiderX != null ? profile.v2raySpiderX : "";

        // ⭐ สร้าง engine
        v2rayEngine = new V2RayEngine(this, cfg, fd -> protect(fd));
        v2rayEngine.start();

        StatusBus.post(StatusBus.State.SOCKS_READY,
                "V2Ray พร้อม: 127.0.0.1:" + V2RayEngine.SOCKS_PORT);
        VpnLogger.i(TAG, "V2Ray started on port " + V2RayEngine.SOCKS_PORT);

        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        // ---- Tun2Socks — ใช้ชุดเดียวกับ SSH ที่พิสูจน์แล้วว่าออกเน็ตได้ ----
        StatusBus.post(StatusBus.State.TUN2SOCKS_READY, "กำลังเปิด Tun2Socks...");
        updateNotification("กำลังเชื่อมต่อทราฟฟิก...");
        VpnLogger.i(TAG, "Starting Tun2Socks bridge (V2Ray, same path as SSH)...");

        // หยุด hev เก่าก่อน (กัน start ซ้ำแล้ว fail)
        try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        copyConfigFromAssets();
        rewriteConfigPort(V2RayEngine.SOCKS_PORT);
        VpnLogger.i(TAG, "Config: " + configFile.getAbsolutePath());

        boolean started = false;
        for (int attempt = 1; attempt <= 3 && !started; attempt++) {
            try {
                started = TProxyService.TProxyStartService(
                        configFile.getAbsolutePath(),
                        tunFd.getFd()
                );
                VpnLogger.i(TAG, "TProxyStart attempt " + attempt + " → " + started);
            } catch (Throwable t) {
                VpnLogger.e(TAG, "TProxyStartService threw (try " + attempt + "): "
                        + t.getMessage());
                started = false;
            }
            if (!started) {
                try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
        }

        if (!started) {
            // อย่า throw ทั้งก้อน — แจ้งชัด (SSH path ก็ soft-fail ได้)
            VpnLogger.e(TAG, "Tun2Socks failed after retries — V2Ray SOCKS ยังอยู่ที่ 1080");
            throw new IOException("Tun2Socks failed — ลองปิด VPN แล้วเปิดใหม่ หรือ Reboot");
        }

        tun2socksRunning = true;
        VpnLogger.i(TAG, "V2Ray + Tun2Socks ready (hev→127.0.0.1:"
                + V2RayEngine.SOCKS_PORT + ")");

        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

        // เหมือน SSH: ตรวจ connectivity จริงก่อนประกาศ CONNECTED
        try {
            NetworkBinder.forceReroute(this);
            setUnderlyingNetworks(null);
        } catch (Throwable t) {
            VpnLogger.w(TAG, "V2Ray reroute: " + t.getMessage());
        }

        StatusBus.post(StatusBus.State.TUN2SOCKS_READY, "กำลังตรวจสอบการเชื่อมต่อ...");
        updateNotification("กำลังตรวจสอบการเชื่อมต่อ...");

        connectivityChecker = new ConnectivityChecker(this);
        connectivityChecker.check(new ConnectivityChecker.Callback() {
            @Override
            public void onReady() {
                connected = true;
                setServiceRunning(true);
                VpnLogger.i(TAG, "[V2Ray] ✅ Connectivity verified");
                StatusBus.post(StatusBus.State.CONNECTED,
                        "เชื่อมต่อแล้ว: " + profile.name);
                ConnectFeedback.onConnected(ProxyVpnService.this);
                updateNotification("เชื่อมต่อแล้ว: " + profile.name);
                startNetworkMonitor();
                startHeartbeat();
            }

            @Override
            public void onFailed(String reason) {
                // ยังถือว่าเชื่อม (โหนดฟรีอาจช้า) แต่แจ้ง log ชัด
                connected = true;
                setServiceRunning(true);
                VpnLogger.w(TAG, "[V2Ray] ⚠️ Verify failed: " + reason
                        + " — โหนดอาจตัน/เน็ตไม่ออกจริง");
                StatusBus.post(StatusBus.State.CONNECTED,
                        "เชื่อมต่อแล้ว (ตรวจสอบเน็ตไม่ผ่าน): " + profile.name);
                ConnectFeedback.onConnected(ProxyVpnService.this);
                updateNotification("เชื่อมต่อแล้ว: " + profile.name);
                startNetworkMonitor();
                startHeartbeat();
            }
        });
    }


    /**
     * กันไม่ให้ทราฟฟิกไปยังเซิร์ฟเวอร์ VPN วิ่งเข้า TUN เอง (สำคัญเมื่อ protect() ของ core ไม่ถูกเรียก)
     * API 33+ ใช้ excludeRoute; ต่ำกว่านั้นพึ่ง protect()
     */
    private void excludeServerFromTunnel(Builder builder, Profile profile) {
        if (builder == null || profile == null || profile.host == null) return;
        if (android.os.Build.VERSION.SDK_INT < 33) {
            VpnLogger.i(TAG, "excludeRoute needs API 33+ — rely on protect()");
            return;
        }
        try {
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(profile.host.trim());
            int n = 0;
            for (java.net.InetAddress a : addrs) {
                if (a instanceof java.net.Inet4Address) {
                    try {
                        builder.excludeRoute(new IpPrefix(a, 32));
                        n++;
                        VpnLogger.i(TAG, "excludeRoute IPv4 " + a.getHostAddress() + "/32");
                    } catch (Throwable t) {
                        VpnLogger.w(TAG, "excludeRoute v4 fail: " + t.getMessage());
                    }
                } else if (a instanceof java.net.Inet6Address) {
                    try {
                        builder.excludeRoute(new IpPrefix(a, 128));
                        n++;
                        VpnLogger.i(TAG, "excludeRoute IPv6 " + a.getHostAddress() + "/128");
                    } catch (Throwable t) {
                        VpnLogger.w(TAG, "excludeRoute v6 fail: " + t.getMessage());
                    }
                }
            }
            // ถ้า v2rayHost เป็นคนละโดเมน (CDN) ต้อง exclude ด้วย
            String wh = profile.v2rayHost;
            if (wh != null && !wh.isEmpty()) {
                wh = com.example.vpn.tunnel.V2RayEngine.sanitizeWsHost(wh);
                if (!wh.isEmpty() && !wh.equalsIgnoreCase(profile.host.trim())) {
                    try {
                        for (java.net.InetAddress a : java.net.InetAddress.getAllByName(wh)) {
                            if (a instanceof java.net.Inet4Address) {
                                builder.excludeRoute(new IpPrefix(a, 32));
                                n++;
                                VpnLogger.i(TAG, "excludeRoute WS-Host " + a.getHostAddress());
                            }
                        }
                    } catch (Throwable t) {
                        VpnLogger.w(TAG, "exclude WS host: " + t.getMessage());
                    }
                }
            }
            VpnLogger.i(TAG, "excludeServerFromTunnel done, count=" + n);
        } catch (Throwable t) {
            VpnLogger.w(TAG, "excludeServerFromTunnel: " + t.getMessage());
        }
    }


        /** เขียน hev-config แบบเดียวกับที่ SSH ใช้แล้วออกเน็ตได้ */
    private void rewriteConfigPort(int port) {
        try {
            if (configFile == null) {
                configFile = new java.io.File(getFilesDir(), "hev-config.yml");
            }
            String yml = "tunnel:\n"
                    + "  mtu: 1500\n"
                    + "  ipv4: " + VPN_ADDRESS + "\n"
                    + "\n"
                    + "socks5:\n"
                    + "  port: " + port + "\n"
                    + "  address: 127.0.0.1\n"
                    + "  udp: 'tcp'\n"  // UDP-over-TCP ไป SOCKS — เสถียรกับ V2Ray กว่า udp ตรง
                    + "\n"
                    + "misc:\n"
                    + "  log-file: stderr\n"
                    + "  log-level: warn\n"
                    + "  task-stack-size: 65536\n"
                    + "  connect-timeout: 15000\n"
                    + "  read-write-timeout: 300000\n"
                    + "  tcp-buffer-size: 65536\n"
                    + "  udp-recv-buffer-size: 524288\n"
                    + "  limit-nofile: 65535\n";
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(configFile, false)) {
                fos.write(yml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            VpnLogger.i(TAG, "hev-config written → 127.0.0.1:" + port
                    + " tun=" + VPN_ADDRESS + " mtu=1500 file=" + configFile.getAbsolutePath());
        } catch (Exception e) {
            VpnLogger.w(TAG, "rewriteConfigPort error: " + e.getMessage());
        }
    }



    // ============================================================
    // ⭐ Heartbeat — กัน SSH/NAT ตัดตอน idle (เน็ตค้างจริง → reconnect เร็ว)
    // ============================================================

    private boolean isV2RayProfile() {
        Profile p = currentProfile;
        if (p == null || p.protocol == null) return false;
        return p.protocol == com.example.vpn.model.Protocol.V2RAY
                || p.protocol == com.example.vpn.model.Protocol.TROJAN
                || p.protocol == com.example.vpn.model.Protocol.SHADOWSOCKS;
    }

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
                        scheduleNextHeartbeat(isV2RayProfile() ? HEARTBEAT_INTERVAL_V2RAY_MS : HEARTBEAT_INTERVAL_MS);
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
                    int failMax = isV2RayProfile() ? HEARTBEAT_FAIL_MAX_V2RAY : HEARTBEAT_FAIL_MAX;
                    VpnLogger.w(TAG, "Heartbeat FAIL (" + heartbeatFailCount
                            + "/" + failMax + ")");
                    if (heartbeatFailCount >= failMax) {
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
                    scheduleNextHeartbeat(isV2RayProfile() ? HEARTBEAT_RETRY_V2RAY_MS : HEARTBEAT_RETRY_MS);
                }, "vpn-heartbeat").start();
            }
        };

        long hbInterval = isV2RayProfile() ? HEARTBEAT_INTERVAL_V2RAY_MS : HEARTBEAT_INTERVAL_MS;
        int hbFailMax = isV2RayProfile() ? HEARTBEAT_FAIL_MAX_V2RAY : HEARTBEAT_FAIL_MAX;
        heartbeatHandler.postDelayed(heartbeatRunnable, hbInterval);
        VpnLogger.i(TAG, "Heartbeat started (every " + hbInterval
                + "ms, failMax=" + hbFailMax
                + (isV2RayProfile() ? ", mode=V2Ray-SOCKS" : ", mode=SSH") + ")");
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
        // ── SSH: ใช้ channel ตรง ──
        SshTunnel tunnel = sshTunnel;
        if (tunnel != null) {
            if (!tunnel.isConnected()) {
                VpnLogger.w(TAG, "Heartbeat: SSH session not connected");
                return -1;
            }
            com.jcraft.jsch.ChannelDirectTCPIP channel = null;
            try {
                channel = tunnel.openTcp("1.1.1.1", 53);
                java.io.OutputStream out = channel.getOutputStream();
                if (out != null) {
                    out.write(new byte[]{
                            0x00, 0x00, 0x01, 0x00,
                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                    });
                    out.flush();
                }
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                return 1;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                VpnLogger.w(TAG, "Heartbeat SSH ping error: " + e.getMessage());
                if (!tunnel.isConnected()
                        || msg.contains("session is down")
                        || msg.contains("not connected")
                        || msg.contains("connection is closed")) {
                    return -1;
                }
                return 0;
            } finally {
                if (channel != null) {
                    try { channel.disconnect(); } catch (Exception ignored) {}
                }
            }
        }

        // ── V2Ray / Trojan / SS: อย่า TCP ตรงไป 1.1.1.1:53 (หลอก fail → reconnect วน)
        //    ตรวจว่า local SOCKS ยังรับ และลอง CONNECT ผ่าน SOCKS
        if (isV2RayProfile() || v2rayEngine != null) {
            return doV2RayHeartbeat();
        }

        // ── fallback (ไม่ควรถึง) ──
        return 0;
    }

    /** Heartbeat สำหรับ V2Ray: เช็ค SOCKS + CONNECT ผ่าน proxy */
    private int doV2RayHeartbeat() {
        java.net.Socket s = null;
        try {
            // 1) SOCKS ยังฟังอยู่ไหม
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 1081), 2_000);
            s.setSoTimeout(4_000);

            // 2) SOCKS5 handshake + CONNECT 1.1.1.1:80 (HTTP ชัดกว่า :53)
            java.io.OutputStream out = s.getOutputStream();
            java.io.InputStream in = s.getInputStream();

            // greeting: ver=5, 1 method, no-auth
            out.write(new byte[] { 0x05, 0x01, 0x00 });
            out.flush();
            byte[] resp = new byte[2];
            if (in.read(resp) < 2 || resp[0] != 0x05 || resp[1] != 0x00) {
                VpnLogger.w(TAG, "Heartbeat V2Ray: SOCKS auth rejected");
                return 0;
            }

            // CONNECT 1.1.1.1:80
            out.write(new byte[] {
                    0x05, 0x01, 0x00, 0x01,          // ver, connect, rsv, ipv4
                    0x01, 0x01, 0x01, 0x01,          // 1.1.1.1
                    0x00, 0x50                       // port 80
            });
            out.flush();
            byte[] crep = new byte[10];
            int n = in.read(crep);
            if (n >= 2 && crep[0] == 0x05 && crep[1] == 0x00) {
                VpnLogger.d(TAG, "Heartbeat V2Ray: SOCKS CONNECT OK");
                return 1;
            }
            // SOCKS ยังขึ้น แต่ CONNECT ล้ม — soft fail (โหนดอาจช้า อย่า hard)
            VpnLogger.w(TAG, "Heartbeat V2Ray: SOCKS CONNECT soft fail code="
                    + (n >= 2 ? (crep[1] & 0xff) : -1));
            return 0;
        } catch (Exception e) {
            VpnLogger.w(TAG, "Heartbeat V2Ray error: " + e.getMessage());
            // SOCKS ตาย / engine หยุด → soft fail (ให้ครบ failMax ค่อย reconnect)
            if (v2rayEngine == null || !v2rayEngine.isRunning()) {
                return 0;
            }
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
                ConnectFeedback.onError(ProxyVpnService.this);
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
            try { StatusBus.post(StatusBus.State.STOPPED, "หยุดแล้ว");
        ConnectFeedback.onDisconnected(this); } catch (Exception ignored) {}

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

    private static void addHostRoute(Builder builder, String hostOrIp) {
        if (hostOrIp == null || hostOrIp.trim().isEmpty()) return;
        String v = hostOrIp.trim();
        String[] parts = v.split(Pattern.quote("."));
        if (parts.length != 4) return;
        for (String part : parts) {
            try {
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) return;
            } catch (NumberFormatException e) {
                return;
            }
        }
        try {
            builder.addRoute(v, 32);
        } catch (Exception e) {
            VpnLogger.w(TAG, "addRoute DNS " + v + ": " + e.getMessage());
        }
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
