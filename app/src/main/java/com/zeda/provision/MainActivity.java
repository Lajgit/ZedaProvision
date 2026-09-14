package com.zeda.provision;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
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

public class MainActivity extends AppCompatActivity {

    private static final String GROUP_NETWORK_NAME = "DIRECT-ZD-OTA_FACTORY";
    private static final String GROUP_PASSWORD = "12345678";

    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel wifiP2pChannel;
    private boolean groupRequested;
    private boolean receiverRegistered;
    private TextView statusTextView;
    private Button startButton;
    private Button stopButton;

    private final BroadcastReceiver wifiP2pReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    groupRequested = false;
                    updateStatus(getString(R.string.status_wifi_direct_disabled), true, false);
                } else if (!groupRequested) {
                    updateStatus(getString(R.string.status_ready), true, false);
                }
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                requestCurrentGroupInfo();
            }
        }
    };

    private final ActivityResultLauncher<String[]> wifiPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasWifiPermission()) {
                    startWifiDirectGroup();
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

        statusTextView = findViewById(R.id.statusTextView);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);

        startButton.setOnClickListener(v -> checkPermissionAndStartGroup());
        stopButton.setOnClickListener(v -> stopWifiDirectGroup());

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            updateStatus(getString(R.string.status_wifi_direct_unsupported), false, false);
            return;
        }

        wifiP2pManager = getSystemService(WifiP2pManager.class);
        if (wifiP2pManager == null) {
            updateStatus(getString(R.string.status_wifi_direct_unavailable), false, false);
            return;
        }

        wifiP2pChannel = wifiP2pManager.initialize(this, getMainLooper(), () -> {
            groupRequested = false;
            wifiP2pChannel = null;
            updateStatus(getString(R.string.status_channel_disconnected), false, false);
        });
        if (wifiP2pChannel == null) {
            updateStatus(getString(R.string.status_wifi_direct_unavailable), false, false);
            return;
        }

        registerWifiP2pReceiver();
    }

    private void checkPermissionAndStartGroup() {
        if (hasWifiPermission()) {
            startWifiDirectGroup();
            return;
        }

        updateStatus(getString(R.string.status_requesting_permission), false, false);
        wifiPermissionLauncher.launch(getRequiredWifiPermissions());
    }

    private String[] getRequiredWifiPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        return new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
    }

    private boolean hasWifiPermission() {
        for (String permission : getRequiredWifiPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startWifiDirectGroup() {
        if (wifiP2pManager == null || wifiP2pChannel == null) {
            updateStatus(getString(R.string.status_wifi_direct_unavailable), false, false);
            return;
        }

        if (groupRequested) {
            requestCurrentGroupInfo();
            return;
        }

        try {
            // API 29 公共接口允许固定 Wi-Fi Direct 组名和密码；组名必须以 DIRECT-xy 开头。
            WifiP2pConfig config = new WifiP2pConfig.Builder()
                    .setNetworkName(GROUP_NETWORK_NAME)
                    .setPassphrase(GROUP_PASSWORD)
                    .build();

            groupRequested = true;
            updateStatus(getString(R.string.status_starting), false, true);
            wifiP2pManager.createGroup(
                    wifiP2pChannel,
                    config,
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            // onSuccess 仅表示系统接受创建请求，实际参数通过组信息回调确认。
                            requestCurrentGroupInfo();
                        }

                        @Override
                        public void onFailure(int reason) {
                            groupRequested = false;
                            updateStatus(getString(R.string.status_failed, reason), true, false);
                        }
                    });
        } catch (SecurityException e) {
            groupRequested = false;
            updateStatus(getString(R.string.status_permission_error), true, false);
        } catch (IllegalArgumentException e) {
            groupRequested = false;
            updateStatus(getString(R.string.status_invalid_configuration), true, false);
        }
    }

    private void requestCurrentGroupInfo() {
        if (!groupRequested || !hasWifiPermission()
                || wifiP2pManager == null || wifiP2pChannel == null) {
            return;
        }

        try {
            wifiP2pManager.requestGroupInfo(wifiP2pChannel, group -> {
                if (group != null && group.isGroupOwner()) {
                    updateStatus(
                            getString(R.string.status_running,
                                    group.getNetworkName(),
                                    group.getPassphrase()),
                            false,
                            true);
                } else if (groupRequested) {
                    updateStatus(getString(R.string.status_starting), false, true);
                }
            });
        } catch (SecurityException e) {
            groupRequested = false;
            updateStatus(getString(R.string.status_permission_error), true, false);
        }
    }

    private void stopWifiDirectGroup() {
        if (!groupRequested) {
            updateStatus(getString(R.string.status_not_running), true, false);
            return;
        }

        if (wifiP2pManager == null || wifiP2pChannel == null) {
            groupRequested = false;
            updateStatus(getString(R.string.status_wifi_direct_unavailable), false, false);
            return;
        }

        updateStatus(getString(R.string.status_stopping), false, false);
        try {
            // 删除当前 Wi-Fi Direct 组即停止本次临时配网热点。
            wifiP2pManager.removeGroup(wifiP2pChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    groupRequested = false;
                    updateStatus(getString(R.string.status_stopped), true, false);
                }

                @Override
                public void onFailure(int reason) {
                    updateStatus(getString(R.string.status_stop_failed, reason), false, true);
                }
            });
        } catch (SecurityException e) {
            updateStatus(getString(R.string.status_permission_error), false, true);
        }
    }

    private void registerWifiP2pReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiP2pReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wifiP2pReceiver, intentFilter);
        }
        receiverRegistered = true;
    }

    private void updateStatus(String status, boolean canStart, boolean canStop) {
        statusTextView.setText(status);
        startButton.setEnabled(canStart);
        stopButton.setEnabled(canStop);
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(wifiP2pReceiver);
            receiverRegistered = false;
        }

        if (groupRequested && wifiP2pManager != null && wifiP2pChannel != null) {
            try {
                wifiP2pManager.removeGroup(wifiP2pChannel, null);
            } catch (SecurityException ignored) {
                // Activity 销毁阶段不再更新界面，只忽略权限异常。
            }
            groupRequested = false;
        }
        super.onDestroy();
    }
}
