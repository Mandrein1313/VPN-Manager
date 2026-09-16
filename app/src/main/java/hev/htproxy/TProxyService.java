package hev.htproxy;

public class TProxyService {
    private static native boolean TProxyStartService(String config_path, int fd);
    private static native boolean TProxyStopService();
    private static native boolean TProxyIsRunning();
    private static native long[] TProxyGetStats();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }
}