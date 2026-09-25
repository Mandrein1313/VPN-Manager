package com.example.vpn.util;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * วัด latency แบบ TCP connect ไป host:port (ไม่ต้อง root / ICMP)
 */
public final class LatencyProbe {

    public interface Callback {
        void onResult(long profileId, int latencyMs); // -1 = fail
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final int TIMEOUT_MS = 4000;

    private LatencyProbe() {}

    public static void measure(long profileId, String host, int port, Callback cb) {
        if (host == null || host.trim().isEmpty() || port <= 0 || cb == null) {
            if (cb != null) cb.onResult(profileId, -1);
            return;
        }
        final String h = host.trim();
        final int p = port;
        POOL.execute(() -> {
            int ms = tcpPing(h, p);
            cb.onResult(profileId, ms);
        });
    }

    /** @return milliseconds หรือ -1 ถ้าล้มเหลว */
    public static int tcpPing(String host, int port) {
        Socket socket = null;
        long start = System.nanoTime();
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            return (int) Math.max(1, elapsed);
        } catch (Exception e) {
            return -1;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }
}
