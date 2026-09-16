package com.example.vpn.util;

import android.net.Uri;

import com.example.vpn.model.Profile;
import com.example.vpn.model.Protocol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse config จาก clipboard text
 * รองรับ:
 *   1. ssh://user:pass@host:port
 *   2. user:pass@host:port
 *   3. host:port@user:pass
 *   4. host:port
 *   5. แบบมี [crlf] payload ต่อท้าย
 */
public class ConfigParser {

    public static class Result {
        public Profile profile;
        public String error;

        public boolean isSuccess() { return profile != null && error == null; }

        public static Result ok(Profile p) { Result r = new Result(); r.profile = p; return r; }
        public static Result fail(String msg) { Result r = new Result(); r.error = msg; return r; }
    }

    public static Result parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            return Result.fail("ไม่มีข้อมูลใน clipboard");
        }

        String text = input.trim();

        // ลองแบบ ssh:// URI ก่อน
        if (text.startsWith("ssh://")) {
            return parseSshUri(text);
        }

        // ลองแบบ user:pass@host:port
        if (text.contains("@") && text.contains(":")) {
            Result r = parseUserPassAtHost(text);
            if (r.isSuccess()) return r;
        }

        // ลองแบบ host:port@user:pass
        if (text.contains("@")) {
            Result r = parseHostAtUserPass(text);
            if (r.isSuccess()) return r;
        }

        // ลองแบบ host:port เปล่าๆ
        if (text.matches("^[\\w.-]+:\\d+.*$")) {
            return parseSimple(text);
        }

        return Result.fail("รูปแบบไม่ถูกต้อง — รองรับ ssh://, user:pass@host:port, host:port@user:pass");
    }

    // ============================================================
    // 1. ssh://user:pass@host:port
    // ============================================================
    private static Result parseSshUri(String text) {
        try {
            Uri uri = Uri.parse(text);
            String user = uri.getUserInfo();
            String pass = "";
            if (user != null && user.contains(":")) {
                String[] parts = user.split(":", 2);
                user = parts[0];
                pass = parts[1];
            }
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 22;

            if (host == null || host.isEmpty()) {
                return Result.fail("ไม่พบ host ใน URI");
            }
            if (user == null || user.isEmpty()) {
                return Result.fail("ไม่พบ username ใน URI");
            }

            Profile p = new Profile();
            p.name = host;
            p.protocol = Protocol.SSH;
            p.host = host;
            p.port = port;
            p.user = user;
            p.pass = pass;
            return Result.ok(p);
        } catch (Exception e) {
            return Result.fail("Parse URI ไม่สำเร็จ: " + e.getMessage());
        }
    }

    // ============================================================
    // 2. user:pass@host:port
    // ============================================================
    private static Result parseUserPassAtHost(String text) {
        try {
            // แยกที่ @ ตัวสุดท้าย
            int at = text.lastIndexOf('@');
            if (at <= 0) return Result.fail("ไม่พบ @");

            String before = text.substring(0, at);   // user:pass
            String after = text.substring(at + 1);   // host:port...

            // user:pass
            int colon1 = before.indexOf(':');
            if (colon1 <= 0) return Result.fail("ไม่พบ : ใน user:pass");
            String user = before.substring(0, colon1);
            String pass = before.substring(colon1 + 1);

            // host:port (อาจมี payload ต่อท้าย host:port ด้วย space หรือ newline)
            String hostPort = after.split("[\\s\\r\\n]")[0];
            int colon2 = hostPort.indexOf(':');
            String host = colon2 > 0 ? hostPort.substring(0, colon2) : hostPort;
            int port = 22;
            if (colon2 > 0) {
                try {
                    port = Integer.parseInt(hostPort.substring(colon2 + 1));
                } catch (NumberFormatException ignored) {}
            }

            Profile p = new Profile();
            p.name = host;
            p.protocol = Protocol.SSH;
            p.host = host;
            p.port = port;
            p.user = user;
            p.pass = pass;
            return Result.ok(p);
        } catch (Exception e) {
            return Result.fail("Parse ไม่สำเร็จ: " + e.getMessage());
        }
    }

    // ============================================================
    // 3. host:port@user:pass
    // ============================================================
    private static Result parseHostAtUserPass(String text) {
        try {
            int at = text.indexOf('@');
            if (at <= 0) return Result.fail("ไม่พบ @");

            String hostPart = text.substring(0, at);   // host:port
            String userPart = text.substring(at + 1);  // user:pass

            // host:port
            int c1 = hostPart.indexOf(':');
            String host = c1 > 0 ? hostPart.substring(0, c1) : hostPart;
            int port = 22;
            if (c1 > 0) {
                try {
                    port = Integer.parseInt(hostPart.substring(c1 + 1)
                            .split("[\\s\\r\\n]")[0]);
                } catch (NumberFormatException ignored) {}
            }

            // user:pass
            int c2 = userPart.indexOf(':');
            if (c2 <= 0) return Result.fail("ไม่พบ : ใน user:pass");
            String user = userPart.substring(0, c2);
            String pass = userPart.substring(c2 + 1).split("[\\s\\r\\n]")[0];

            Profile p = new Profile();
            p.name = host;
            p.protocol = Protocol.SSH;
            p.host = host;
            p.port = port;
            p.user = user;
            p.pass = pass;
            return Result.ok(p);
        } catch (Exception e) {
            return Result.fail("Parse ไม่สำเร็จ: " + e.getMessage());
        }
    }

    // ============================================================
    // 4. host:port เปล่าๆ
    // ============================================================
    private static Result parseSimple(String text) {
        try {
            String firstLine = text.split("[\\s\\r\\n]")[0];
            int c = firstLine.indexOf(':');
            if (c <= 0) return Result.fail("รูปแบบไม่ถูกต้อง");

            String host = firstLine.substring(0, c);
            int port;
            try {
                port = Integer.parseInt(firstLine.substring(c + 1));
            } catch (NumberFormatException e) {
                return Result.fail("Port ไม่ถูกต้อง");
            }

            Profile p = new Profile();
            p.name = host;
            p.protocol = Protocol.SSH;
            p.host = host;
            p.port = port;
            return Result.ok(p);
        } catch (Exception e) {
            return Result.fail("Parse ไม่สำเร็จ: " + e.getMessage());
        }
    }
}