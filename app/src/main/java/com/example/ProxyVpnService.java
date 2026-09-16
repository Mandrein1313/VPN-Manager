package com.example.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.vpn.data.AppDatabase;
import com.example.vpn.data.ProfileRepository;
import com.example.vpn.model.Profile;
import com.example.vpn.tunnel.Socks5Server;
import com.example.vpn.tunnel.SshTunnel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import hev.htproxy.TProxyService;

public class ProxyVpnService extends VpnService {

    public static final String TAG = "ProxyVpnService";
    public static final String ACTION_START = "START_VPN";
    public static final String ACTION_STOP  = "STOP_VPN";
    public static final String EXTRA_PROFILE_ID = "profile_id";

    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIF_ID = 1001;
    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final String VPN_ROUTE   = "0.0.0.0";
    private static final int VPN_PREFIX     = 0;
    private static final int VPN_MTU        = 1500;

    private ParcelFileDescriptor tunFd;
    private Thread workerThread;
    private volatile boolean running = false;
    private File configFile;

    private SshTunnel sshTunnel;
    private Socks5Server socks5Server;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            long profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L);
            if (profileId <= 0) {
                Log.e(TAG, "Invalid profile id");
                stopSelf();
                return START_NOT_STICKY;
            }
            startForeground(NOTIF_ID, buildNotification("กำลังเชื่อมต่อ..."));
            loadProfileAndStart(profileId);
        }

        return START_STICKY;
    }

    private void loadProfileAndStart(long profileId) {
        ProfileRepository repo = new ProfileRepository(AppDatabase.get(this));
        repo.getById(profileId, profile -> {
            if (profile == null) {
                Log.e(TAG, "Profile not found: " + profileId);
                stopVpn();
                return;
            }
            workerThread = new Thread(() -> startVpn(profile), "vpn-worker");
            workerThread.start();
        });
    }

    // ⭐ helper ใหม่
    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private void startVpn(Profile profile) {
        try {
            // 1. สร้าง TUN interface
            Builder builder = new Builder()
                    .setSession(profile.name)
                    .addAddress(VPN_ADDRESS, 32)
                    .addRoute(VPN_ROUTE, VPN_PREFIX)
                    .addDnsServer(profile.dns1)
                    .addDnsServer(profile.dns2)
                    .setMtu(VPN_MTU)
                    .setBlocking(true);

            tunFd = builder.establish();

            if (tunFd == null) {
                Log.e(TAG, "Failed to establish TUN");
                stopVpn();
                return;
            }

            running = true;
            Log.i(TAG, "TUN established: fd=" + tunFd.getFd());

            // 2. เชื่อม SSH tunnel (รองรับ HTTP Proxy + Payload + SNI)
            updateNotification("กำลังเชื่อมต่อ SSH...");

            sshTunnel = new SshTunnel(
                    profile.host,
                    profile.port,
                    profile.user,
                    profile.pass,
                    emptyToNull(profile.httpProxy),   // ⭐ ใหม่
                    emptyToNull(profile.payload),     // ⭐ ใหม่
                    emptyToNull(profile.sni),         // ⭐ ใหม่
                    socket -> protect(socket)
            );

            if (!sshTunnel.isConnected()) {
                throw new IOException("SSH not connected");
            }

            updateNotification("SSH เชื่อมต่อแล้ว กำลังเปิด SOCKS...");
            Log.i(TAG, "SSH OK, starting SOCKS5 server...");

            // 3. เปิด SOCKS5 server
            socks5Server = new Socks5Server(sshTunnel);
            socks5Server.start();

            Log.i(TAG, "SOCKS5 ready on 127.0.0.1:" + Socks5Server.LOCAL_PORT);

            // 4. เริ่ม HevTunnel ผ่าน TProxyService
            updateNotification("กำลังเชื่อมต่อทราฟฟิก...");

            copyConfigFromAssets();

            boolean started = TProxyService.TProxyStartService(
                    configFile.getAbsolutePath(),
                    tunFd.getFd()
            );

            if (!started) {
                throw new IOException("TProxyStartService failed");
            }

            Log.i(TAG, "HevTunnel started — VPN is active");
            updateNotification("เชื่อมต่อแล้ว: " + profile.name);

        } catch (Exception e) {
            Log.e(TAG, "startVpn error", e);
            updateNotification("ผิดพลาด: " + e.getMessage());
            stopVpn();
        }
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

    private void stopVpn() {
        running = false;

        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }

        try {
            TProxyService.TProxyStopService();
        } catch (Exception ignored) {}

        if (socks5Server != null) {
            socks5Server.stop();
            socks5Server = null;
        }

        if (sshTunnel != null) {
            sshTunnel.disconnect();
            sshTunnel = null;
        }

        if (tunFd != null) {
            try { tunFd.close(); } catch (IOException ignored) {}
            tunFd = null;
        }

        if (configFile != null && configFile.exists()) {
            configFile.delete();
            configFile = null;
        }

        try {
            stopForeground(true);
        } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }

    @Override
    public void onRevoke() {
        stopVpn();
        super.onRevoke();
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

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VPN Manager")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "หยุด", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
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