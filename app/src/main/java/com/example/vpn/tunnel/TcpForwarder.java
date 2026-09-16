package com.example.vpn.tunnel;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TcpForwarder implements Runnable {

    private static final String TAG = "TcpForwarder";
    private static final String SOCKS_HOST = "127.0.0.1";
    private static final int SOCKS_PORT = 1080; // พอร์ตเดียวกับ Socks5Server

    private final ParcelFileDescriptor tunFd;
    private final VpnService.SocketProtector protector;
    private volatile boolean running = true;

    public TcpForwarder(ParcelFileDescriptor tunFd, VpnService.SocketProtector protector) {
        this.tunFd = tunFd;
        this.protector = protector;
    }

    @Override
    public void run() {
        Log.i(TAG, "TcpForwarder started");
        try (FileInputStream in = new FileInputStream(tunFd.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tunFd.getFileDescriptor())) {

            ByteBuffer packet = ByteBuffer.allocate(32767); // ขนาด buffer สูงสุดของ TUN

            while (running && !Thread.currentThread().isInterrupted()) {
                packet.clear();
                int length = in.read(packet.array());
                if (length <= 0) continue;

                packet.limit(length);

                // 1. ตรวจสอบว่าเป็น IPv4 หรือไม่
                byte versionAndHeaderLength = packet.get(0);
                int version = (versionAndHeaderLength >> 4) & 0x0F;
                if (version != 4) {
                    // ยังไม่รองรับ IPv6 (สามารถเพิ่มเติมภายหลังได้)
                    continue;
                }

                // 2. ตรวจสอบว่าเป็น TCP หรือไม่
                int protocol = packet.get(9) & 0xFF;
                if (protocol != 6) { // 6 = TCP
                    // ถ้าเป็น UDP หรืออื่นๆ ให้ข้ามไปก่อน
                    continue;
                }

                // 3. ดึงข้อมูลจาก Header
                int ipHeaderLength = (versionAndHeaderLength & 0x0F) * 4;
                int totalLength = ((packet.get(2) & 0xFF) << 8) | (packet.get(3) & 0xFF);
                int tcpHeaderLength = ((packet.get(ipHeaderLength + 12) >> 4) & 0x0F) * 4;
                int payloadLength = totalLength - ipHeaderLength - tcpHeaderLength;

                byte[] srcIp = new byte[4];
                byte[] dstIp = new byte[4];
                packet.position(12);
                packet.get(srcIp);
                packet.get(dstIp);

                int srcPort = ((packet.get(ipHeaderLength) & 0xFF) << 8) | (packet.get(ipHeaderLength + 1) & 0xFF);
                int dstPort = ((packet.get(ipHeaderLength + 2) & 0xFF) << 8) | (packet.get(ipHeaderLength + 3) & 0xFF);

                // 4. ส่งต่อไปยัง SOCKS5 (แบบง่าย: 1 connection ต่อ 1 packet)
                // สำหรับ Production ควรมี Session Management ที่ดีกว่านี้
                handleTcpPacket(packet, ipHeaderLength, tcpHeaderLength, payloadLength,
                                srcIp, srcPort, dstIp, dstPort);

                // เขียน packet ที่ประมวลผลแล้วกลับเข้า TUN (หรือเขียน response ในขั้นตอนอื่น)
                // ตัวอย่างนี้ยังไม่มีการเขียน response กลับ
            }

        } catch (IOException e) {
            Log.e(TAG, "TcpForwarder error", e);
        } finally {
            Log.i(TAG, "TcpForwarder stopped");
        }
    }

    private void handleTcpPacket(ByteBuffer packet, int ipHeaderLength, int tcpHeaderLength,
                                 int payloadLength, byte[] srcIp, int srcPort,
                                 byte[] dstIp, int dstPort) {
        if (payloadLength <= 0) return;

        byte[] payload = new byte[payloadLength];
        packet.position(ipHeaderLength + tcpHeaderLength);
        packet.get(payload);

        new Thread(() -> {
            try {
                // 5. เชื่อมต่อไปยัง SOCKS5 Server ในเครื่อง
                Socket socket = new Socket();
                if (protector != null) protector.protect(socket); // protect ก่อน connect
                socket.connect(new InetSocketAddress(SOCKS_HOST, SOCKS_PORT), 15000);

                // (ในที่นี้ต้อง implement SOCKS5 handshake และส่ง payload ไป)
                // ... (ดูรายละเอียดด้านล่าง)

                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "handleTcpPacket error", e);
            }
        }).start();
    }

    public void stop() {
        running = false;
    }

    // Interface สำหรับ VpnService.protect()
    public interface SocketProtector {
        boolean protect(Socket socket);
    }
}