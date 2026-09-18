package hev.htproxy;

public class TProxyService {
    // ✅ ใช้ public static native แต่มี exception handler
    private static boolean libLoaded = false;

    static {
        try {
            System.loadLibrary("hev-socks5-tunnel");
            libLoaded = true;
        } catch (Throwable t) {
            android.util.Log.e("TProxyService", "loadLibrary failed", t);
        }
    }

    public static boolean TProxyStartService(String config_path, int fd) {
        if (!libLoaded) return false;
        try {
            return nativeTProxyStartService(config_path, fd);
        } catch (Throwable t) {
            android.util.Log.e("TProxyService", "TProxyStartService", t);
            return false;
        }
    }

    public static boolean TProxyStopService() {
        if (!libLoaded) return false;
        try {
            return nativeTProxyStopService();
        } catch (Throwable t) {
            android.util.Log.e("TProxyService", "TProxyStopService", t);
            return false;
        }
    }

    public static boolean TProxyIsRunning() {
        if (!libLoaded) return false;
        try {
            return nativeTProxyIsRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    private static native boolean nativeTProxyStartService(String config_path, int fd);
    private static native boolean nativeTProxyStopService();
    private static native boolean nativeTProxyIsRunning();
}