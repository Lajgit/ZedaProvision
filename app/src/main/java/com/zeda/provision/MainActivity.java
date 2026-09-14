package com.zeda.provision;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private static final String HOTSPOT_SSID = "OTA_FACTORY";
    private static final String HOTSPOT_PASSWORD = "12345678";

    private WifiManager wifiManager;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private TextView statusTextView;
    private Button startButton;
    private Button stopButton;

    private final ActivityResultLauncher<String> nearbyWifiPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startHotspot();
                } else {
                    updateStatus(getString(R.string.status_permission_denied), true, false);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        wifiManager = getSystemService(WifiManager.class);
        statusTextView = findViewById(R.id.statusTextView);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);

        startButton.setOnClickListener(v -> checkPermissionAndStartHotspot());
        stopButton.setOnClickListener(v -> stopHotspot());
    }

    private void checkPermissionAndStartHotspot() {
        if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA_1) {
            updateStatus(getString(R.string.status_requires_android_16_1), true, false);
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                == PackageManager.PERMISSION_GRANTED) {
            startHotspot();
            return;
        }

        updateStatus(getString(R.string.status_requesting_permission), false, false);
        nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES);
    }

    private void startHotspot() {
        if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA_1) {
            updateStatus(getString(R.string.status_requires_android_16_1), true, false);
            return;
        }

        if (hotspotReservation != null) {
            updateStatus(getString(R.string.status_running, HOTSPOT_SSID, HOTSPOT_PASSWORD),
                    false, true);
            return;
        }

        // Android 16 公共 API 使用 WifiSsid 设置固定热点名称，并明确指定 WPA2-PSK。
        SoftApConfiguration configuration = new SoftApConfiguration.Builder()
                .setWifiSsid(WifiSsid.fromBytes(HOTSPOT_SSID.getBytes(StandardCharsets.UTF_8)))
                .setPassphrase(HOTSPOT_PASSWORD, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .build();

        updateStatus(getString(R.string.status_starting), false, false);
        try {
            wifiManager.startLocalOnlyHotspotWithConfiguration(
                    configuration,
                    getMainExecutor(),
                    new WifiManager.LocalOnlyHotspotCallback() {
                        @Override
                        public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                            // 必须保存 Reservation；关闭它才会释放本应用申请的本地热点。
                            hotspotReservation = reservation;
                            updateStatus(
                                    getString(R.string.status_running,
                                            HOTSPOT_SSID,
                                            HOTSPOT_PASSWORD),
                                    false,
                                    true);
                        }

                        @Override
                        public void onStopped() {
                            hotspotReservation = null;
                            updateStatus(getString(R.string.status_stopped), true, false);
                        }

                        @Override
                        public void onFailed(int reason) {
                            hotspotReservation = null;
                            updateStatus(getString(R.string.status_failed, reason), true, false);
                        }
                    });
        } catch (SecurityException e) {
            updateStatus(getString(R.string.status_permission_error), true, false);
        }
    }

    private void stopHotspot() {
        if (hotspotReservation == null) {
            updateStatus(getString(R.string.status_not_running), true, false);
            return;
        }

        // LocalOnlyHotspot 通过关闭 Reservation 停止，不使用隐藏 API。
        hotspotReservation.close();
        hotspotReservation = null;
        updateStatus(getString(R.string.status_stopped), true, false);
    }

    private void updateStatus(String status, boolean canStart, boolean canStop) {
        statusTextView.setText(status);
        startButton.setEnabled(canStart);
        stopButton.setEnabled(canStop);
    }

    @Override
    protected void onDestroy() {
        if (hotspotReservation != null) {
            hotspotReservation.close();
            hotspotReservation = null;
        }
        super.onDestroy();
    }
}
