package com.zeda.provision;

import android.os.SystemClock;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class WifiConfigBroadcaster {

    // 以下参数与 OTA_XLH3566 的批量配网接收协议保持一致。
    private static final int CONFIG_PORT = 19000;
    private static final long BROADCAST_INTERVAL_MS = 500L;
    private static final String CONFIG_SECRET = "PXD_WIFI_BATCH_CONFIG_SECRET_202606";
    private static final String ACK_STATUS_RECEIVED = "received";

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread workerThread;

    interface Listener {

        void onStarted();

        void onDeviceAcknowledged(String deviceNo, int acknowledgedCount);

        void onFailed(Throwable error);
    }

    synchronized void start(
            String interfaceName,
            String ssid,
            String password,
            Listener listener
    ) {
        if (workerThread != null) {
            return;
        }

        running = true;
        workerThread = new Thread(
                () -> broadcastBlocking(interfaceName, ssid, password, listener),
                "wifi-config-broadcaster");
        workerThread.start();
    }

    void stop() {
        Thread activeThread;
        synchronized (this) {
            running = false;
            if (socket != null) {
                socket.close();
            }
            activeThread = workerThread;
            if (activeThread != null) {
                activeThread.interrupt();
            }
        }

        if (activeThread != null && activeThread != Thread.currentThread()) {
            try {
                // 等待旧广播线程退出，避免用户立即重新配网时仍被旧任务占用。
                activeThread.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void broadcastBlocking(
            String interfaceName,
            String ssid,
            String password,
            Listener listener
    ) {
        try {
            String configUuid = UUID.randomUUID()
                    .toString()
                    .replace("-", "");
            long timestamp = System.currentTimeMillis();

            // v2 使用 AES-GCM，SSID 和密码不再以明文出现在 UDP 数据包中。
            ProvisionCrypto.EncryptedCredentials encrypted =
                    ProvisionCrypto.encryptCredentials(
                            CONFIG_SECRET,
                            configUuid,
                            timestamp,
                            ssid,
                            password);

            JSONObject json = new JSONObject();
            json.put("type", "wifi_config");
            json.put("protocolVersion", ProvisionCrypto.PROTOCOL_VERSION);
            json.put("configUuid", configUuid);
            json.put("timestamp", timestamp);
            json.put("iv", encrypted.iv);
            json.put("ciphertext", encrypted.ciphertext);

            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
            NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
            if (networkInterface == null || !networkInterface.isUp()) {
                throw new IllegalStateException("Wi-Fi Direct network interface unavailable");
            }

            InetAddress localAddress = findIpv4Address(networkInterface);
            InetAddress broadcastAddress = findBroadcastAddress(networkInterface);
            if (localAddress == null) {
                throw new IllegalStateException("Wi-Fi Direct IPv4 address unavailable");
            }
            if (broadcastAddress == null) {
                broadcastAddress = InetAddress.getByName("255.255.255.255");
            }

            // 绑定 Wi-Fi Direct 本地地址，避免 UDP 被手机默认移动网络或普通 Wi-Fi 接管。
            DatagramSocket activeSocket = new DatagramSocket(null);
            activeSocket.setReuseAddress(true);
            activeSocket.setBroadcast(true);
            activeSocket.bind(new InetSocketAddress(localAddress, 0));

            synchronized (this) {
                if (!running) {
                    activeSocket.close();
                    return;
                }
                socket = activeSocket;
            }
            activeSocket.setSoTimeout(100);

            DatagramPacket packet = new DatagramPacket(
                    data,
                    data.length,
                    broadcastAddress,
                    CONFIG_PORT);

            listener.onStarted();
            Set<String> acknowledgedDevices = new HashSet<>();
            long nextBroadcastAt = 0L;

            // 同一个 UDP Socket 既发送加密配置，也接收设备单播回执。
            while (running) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextBroadcastAt) {
                    activeSocket.send(packet);
                    nextBroadcastAt = now + BROADCAST_INTERVAL_MS;
                }

                byte[] ackBuffer = new byte[2048];
                DatagramPacket ackPacket = new DatagramPacket(
                        ackBuffer,
                        ackBuffer.length);
                try {
                    activeSocket.receive(ackPacket);
                    handleAcknowledgement(
                            ackPacket,
                            configUuid,
                            acknowledgedDevices,
                            listener);
                } catch (SocketTimeoutException ignored) {
                    // 短超时用于同时维持 500ms 广播节奏。
                }
            }
        } catch (Throwable e) {
            if (running) {
                listener.onFailed(e);
            }
        } finally {
            synchronized (this) {
                running = false;
                if (socket != null) {
                    socket.close();
                    socket = null;
                }
                workerThread = null;
            }
        }
    }

    private InetAddress findIpv4Address(NetworkInterface networkInterface) {
        Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
        while (addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                return address;
            }
        }
        return null;
    }

    private InetAddress findBroadcastAddress(NetworkInterface networkInterface) {
        for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
            InetAddress address = interfaceAddress.getAddress();
            if (address instanceof Inet4Address && interfaceAddress.getBroadcast() != null) {
                return interfaceAddress.getBroadcast();
            }
        }
        return null;
    }

    private void handleAcknowledgement(
            DatagramPacket packet,
            String expectedConfigUuid,
            Set<String> acknowledgedDevices,
            Listener listener
    ) {
        try {
            JSONObject json = new JSONObject(new String(
                    packet.getData(),
                    packet.getOffset(),
                    packet.getLength(),
                    StandardCharsets.UTF_8));

            if (!"wifi_config_ack".equals(json.optString("type", ""))
                    || json.optInt("protocolVersion", 0)
                    != ProvisionCrypto.PROTOCOL_VERSION
                    || !expectedConfigUuid.equals(json.optString("configUuid", ""))) {
                return;
            }

            String deviceNo = json.optString("deviceNo", "").trim();
            String status = json.optString("status", "").trim();
            long ackTimestamp = json.optLong("timestamp", 0L);
            String signature = json.optString("signature", "");
            if (deviceNo.isEmpty()
                    || !ACK_STATUS_RECEIVED.equals(status)
                    || ackTimestamp <= 0L
                    || !ProvisionCrypto.verifyAcknowledgement(
                    CONFIG_SECRET,
                    expectedConfigUuid,
                    deviceNo,
                    status,
                    ackTimestamp,
                    signature)) {
                return;
            }

            if (acknowledgedDevices.add(deviceNo)) {
                listener.onDeviceAcknowledged(deviceNo, acknowledgedDevices.size());
            }
        } catch (Throwable error) {
            // 非本协议或校验失败的 UDP 数据不影响持续广播。
        }
    }
}
