package com.zeda.provision;

import android.util.Base64;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class WifiConfigBroadcaster {

    // 以下参数与 OTA_XLH3566 的批量配网接收协议保持一致。
    private static final int CONFIG_PORT = 19000;
    private static final long BROADCAST_INTERVAL_MS = 500L;
    private static final String CONFIG_SECRET = "PXD_WIFI_BATCH_CONFIG_SECRET_202606";
    private static final String HMAC_SHA256 = "HmacSHA256";

    private volatile boolean running;
    private DatagramSocket socket;
    private Thread workerThread;

    interface Listener {

        void onStarted();

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
            // 设备端按相同字段顺序计算 HMAC，顺序或分隔符不能改变。
            String signRaw = configUuid
                    + "|"
                    + ssid
                    + "|"
                    + password
                    + "|"
                    + timestamp;

            JSONObject json = new JSONObject();
            json.put("type", "wifi_config");
            json.put("configUuid", configUuid);
            json.put("ssid", ssid);
            json.put("password", password);
            json.put("timestamp", timestamp);
            json.put("signature", hmacSha256Base64Url(signRaw));

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

            DatagramPacket packet = new DatagramPacket(
                    data,
                    data.length,
                    broadcastAddress,
                    CONFIG_PORT);

            listener.onStarted();
            // 保持广播，直到用户停止配网或 Activity 被销毁。
            while (running) {
                activeSocket.send(packet);
                Thread.sleep(BROADCAST_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private String hmacSha256Base64Url(String text) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(
                CONFIG_SECRET.getBytes(StandardCharsets.UTF_8),
                HMAC_SHA256));
        byte[] signatureBytes = mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(
                signatureBytes,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}
