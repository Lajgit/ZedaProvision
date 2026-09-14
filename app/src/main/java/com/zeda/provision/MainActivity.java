package com.zeda.provision;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ZedaProvision";
    private static final String GROUP_NETWORK_NAME = "DIRECT-ZD-OTA_FACTORY";
    private static final String GROUP_PASSWORD = "12345678";

    private static final int PERMISSION_ACTION_NONE = 0;
    private static final int PERMISSION_ACTION_SCAN = 1;
    private static final int PERMISSION_ACTION_START = 2;

    private WifiManager wifiManager;
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel wifiP2pChannel;
    private final WifiConfigBroadcaster wifiConfigBroadcaster = new WifiConfigBroadcaster();

    private boolean groupRequested;
    private boolean credentialBroadcastStarted;
    private boolean stopRequested;
    private boolean scanInProgress;
    private boolean receiverRegistered;
    private int pendingPermissionAction = PERMISSION_ACTION_NONE;

    private String targetSsid = "";
    private String targetPassword = "";

    private TextInputLayout ssidInputLayout;
    private TextInputLayout passwordInputLayout;
    private TextInputEditText ssidEditText;
    private TextInputEditText passwordEditText;
    private TextView statusTextView;
    private Button scanButton;
    private Button startButton;
    private Button stopButton;

    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    wifiConfigBroadcaster.stop();
                    groupRequested = false;
                    credentialBroadcastStarted = false;
                    stopRequested = false;
                    updateStatus(getString(R.string.status_wifi_direct_disabled), true, false);
                } else if (!groupRequested && !scanInProgress) {
                    updateStatus(getString(R.string.status_ready), true, false);
                }
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                requestCurrentGroupInfo();
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)
                    && scanInProgress) {
                boolean resultsUpdated = intent.getBooleanExtra(
                        WifiManager.EXTRA_RESULTS_UPDATED,
                        false);
                showScanResults(resultsUpdated);
            }
        }
    };

    private final ActivityResultLauncher<String[]> wifiPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> continueAfterPermissionRequest());

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

        ssidInputLayout = findViewById(R.id.ssidInputLayout);
        passwordInputLayout = findViewById(R.id.passwordInputLayout);
        ssidEditText = findViewById(R.id.ssidEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        statusTextView = findViewById(R.id.statusTextView);
        scanButton = findViewById(R.id.scanButton);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);

        scanButton.setOnClickListener(v -> checkPermissionAndScanWifi());
        startButton.setOnClickListener(v -> prepareProvisioning());
        stopButton.setOnClickListener(v -> stopWifiDirectGroup());

        wifiManager = getSystemService(WifiManager.class);
        if (wifiManager == null) {
            updateStatus(getString(R.string.status_wifi_direct_unavailable), false, false);
            return;
        }

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
            wifiConfigBroadcaster.stop();
            groupRequested = false;
            credentialBroadcastStarted = false;
            stopRequested = false;
            wifiP2pChannel = null;
            updateStatus(getString(R.string.status_channel_disconnected), false, false);
        });
        if (wifiP2pChannel == null) {
            updateStatus(getString(R.string.status_wifi_direct_unavailable), false, false);
            return;
        }

        registerWifiReceiver();
    }

    private void checkPermissionAndScanWifi() {
        if (hasPermissions(getScanPermissions())) {
            startWifiScan();
            return;
        }

        pendingPermissionAction = PERMISSION_ACTION_SCAN;
        updateStatus(getString(R.string.status_requesting_permission), false, false);
        wifiPermissionLauncher.launch(getScanPermissions());
    }

    private void prepareProvisioning() {
        if (!captureTargetCredentials()) {
            return;
        }

        if (hasPermissions(getGroupPermissions())) {
            startWifiDirectGroup();
            return;
        }

        pendingPermissionAction = PERMISSION_ACTION_START;
        updateStatus(getString(R.string.status_requesting_permission), false, false);
        wifiPermissionLauncher.launch(getGroupPermissions());
    }

    private void continueAfterPermissionRequest() {
        int action = pendingPermissionAction;
        pendingPermissionAction = PERMISSION_ACTION_NONE;

        if (action == PERMISSION_ACTION_SCAN) {
            if (hasPermissions(getScanPermissions())) {
                startWifiScan();
            } else {
                updateStatus(getString(R.string.status_scan_permission_denied), true, false);
            }
        } else if (action == PERMISSION_ACTION_START) {
            if (hasPermissions(getGroupPermissions())) {
                startWifiDirectGroup();
            } else {
                updateStatus(getString(R.string.status_permission_denied), true, false);
            }
        }
    }

    private String[] getScanPermissions() {
        return new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
    }

    private String[] getGroupPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 本应用同时提供 Wi-Fi 扫描，没有声明 neverForLocation，创建 P2P 组时需同时授权定位。
            return new String[]{
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        return getScanPermissions();
    }

    private boolean hasPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean captureTargetCredentials() {
        String ssid = getInputText(ssidEditText);
        if (ssid.trim().isEmpty()) {
            ssidInputLayout.setError(getString(R.string.target_wifi_ssid_required));
            updateStatus(getString(R.string.target_wifi_ssid_required), true, false);
            return false;
        }

        ssidInputLayout.setError(null);
        targetSsid = ssid;
        targetPassword = getInputText(passwordEditText);
        return true;
    }

    private String getInputText(TextInputEditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString();
    }

    @SuppressWarnings("deprecation")
    private void startWifiScan() {
        scanInProgress = true;
        updateStatus(getString(R.string.status_scanning), false, false);

        try {
            // startScan 可能因系统限频返回 false，此时仍尝试展示系统缓存的扫描结果。
            if (!wifiManager.startScan()) {
                showScanResults(false);
            }
        } catch (SecurityException e) {
            scanInProgress = false;
            updateStatus(getString(R.string.status_scan_permission_denied), true, false);
        }
    }

    @SuppressWarnings("deprecation")
    private void showScanResults(boolean resultsUpdated) {
        scanInProgress = false;

        List<ScanResult> scanResults;
        try {
            scanResults = new ArrayList<>(wifiManager.getScanResults());
        } catch (SecurityException e) {
            updateStatus(getString(R.string.status_scan_permission_denied), true, false);
            return;
        }

        Collections.sort(scanResults, (left, right) -> Integer.compare(right.level, left.level));
        List<String> ssids = new ArrayList<>();
        List<String> displayItems = new ArrayList<>();
        Set<String> addedSsids = new HashSet<>();

        for (ScanResult scanResult : scanResults) {
            String ssid = scanResult.SSID;
            if (TextUtils.isEmpty(ssid) || !addedSsids.add(ssid)) {
                continue;
            }

            ssids.add(ssid);
            displayItems.add(ssid + "  (" + scanResult.level + " dBm)");
        }

        if (ssids.isEmpty()) {
            updateStatus(getString(R.string.status_scan_empty), true, false);
            return;
        }

        updateStatus(getString(R.string.status_scan_found, ssids.size()), true, false);
        new AlertDialog.Builder(this)
                .setTitle(R.string.scan_wifi_title)
                .setItems(displayItems.toArray(new String[0]), (dialog, which) -> {
                    String selectedSsid = ssids.get(which);
                    ssidEditText.setText(selectedSsid);
                    ssidEditText.setSelection(selectedSsid.length());
                    ssidInputLayout.setError(null);
                    updateStatus(
                            getString(R.string.status_wifi_selected, selectedSsid),
                            true,
                            false);
                })
                .setNegativeButton(R.string.scan_wifi_cancel, null)
                .show();

        if (!resultsUpdated) {
            Log.d(TAG, "show cached Wi-Fi scan results");
        }
    }

    private void startWifiDirectGroup() {
        if (wifiP2pManager == null || wifiP2pChannel == null) {
            updateStatus(getString(R.string.status_wifi_direct_unavailable), false, false);
            return;
        }

        if (TextUtils.isEmpty(targetSsid) && !captureTargetCredentials()) {
            return;
        }

        if (groupRequested) {
            requestCurrentGroupInfo();
            return;
        }

        try {
            // API 29 公共接口允许固定 Wi-Fi Direct 组名和密码；组名必须以 DIRECT-xy 开头。
            WifiP2pConfig.Builder configBuilder = new WifiP2pConfig.Builder()
                    .setNetworkName(GROUP_NETWORK_NAME)
                    .setPassphrase(GROUP_PASSWORD);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                // Android 16 明确使用 WPA2 兼容模式，供 RK3566 作为普通 Wi-Fi 客户端连接。
                configBuilder.setPccModeConnectionType(
                        WifiP2pConfig.PCC_MODE_CONNECTION_TYPE_LEGACY_ONLY);
            }
            WifiP2pConfig config = configBuilder.build();

            groupRequested = true;
            credentialBroadcastStarted = false;
            stopRequested = false;
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
                            credentialBroadcastStarted = false;
                            stopRequested = false;
                            updateStatus(getString(R.string.status_failed, reason), true, false);
                        }
                    });
        } catch (SecurityException e) {
            groupRequested = false;
            credentialBroadcastStarted = false;
            stopRequested = false;
            updateStatus(getString(R.string.status_permission_error), true, false);
        } catch (IllegalArgumentException e) {
            groupRequested = false;
            credentialBroadcastStarted = false;
            stopRequested = false;
            updateStatus(getString(R.string.status_invalid_configuration), true, false);
        }
    }

    private void requestCurrentGroupInfo() {
        if (!groupRequested || stopRequested || !hasPermissions(getGroupPermissions())
                || wifiP2pManager == null || wifiP2pChannel == null) {
            return;
        }

        try {
            wifiP2pManager.requestGroupInfo(wifiP2pChannel, group -> {
                if (group != null && group.isGroupOwner()) {
                    if (!credentialBroadcastStarted) {
                        updateStatus(
                                getString(R.string.status_group_ready,
                                        group.getNetworkName(),
                                        group.getPassphrase()),
                                false,
                                true);
                        startCredentialBroadcast(group);
                    }
                } else if (groupRequested) {
                    updateStatus(getString(R.string.status_starting), false, true);
                }
            });
        } catch (SecurityException e) {
            groupRequested = false;
            credentialBroadcastStarted = false;
            stopRequested = false;
            updateStatus(getString(R.string.status_permission_error), true, false);
        }
    }

    private void startCredentialBroadcast(WifiP2pGroup group) {
        if (credentialBroadcastStarted) {
            return;
        }

        credentialBroadcastStarted = true;
        wifiConfigBroadcaster.start(
                group.getInterface(),
                targetSsid,
                targetPassword,
                new WifiConfigBroadcaster.Listener() {
                    @Override
                    public void onStarted() {
                        runOnUiThread(() -> updateStatus(
                                getString(R.string.status_broadcasting, targetSsid),
                                false,
                                true));
                    }

                    @Override
                    public void onFailed(Throwable error) {
                        Log.e(TAG, "broadcast target Wi-Fi config failed", error);
                        runOnUiThread(() -> updateStatus(
                                getString(R.string.status_broadcast_failed),
                                false,
                                true));
                    }
                });
    }

    private void stopWifiDirectGroup() {
        wifiConfigBroadcaster.stop();
        credentialBroadcastStarted = false;

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
        stopRequested = true;
        try {
            // 删除当前 Wi-Fi Direct 组即停止本次临时配网网络。
            wifiP2pManager.removeGroup(wifiP2pChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    groupRequested = false;
                    stopRequested = false;
                    updateStatus(getString(R.string.status_stopped), true, false);
                }

                @Override
                public void onFailure(int reason) {
                    stopRequested = false;
                    updateStatus(getString(R.string.status_stop_failed, reason), false, true);
                }
            });
        } catch (SecurityException e) {
            stopRequested = false;
            updateStatus(getString(R.string.status_permission_error), false, true);
        }
    }

    private void registerWifiReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wifiReceiver, intentFilter);
        }
        receiverRegistered = true;
    }

    private void updateStatus(String status, boolean canStart, boolean canStop) {
        statusTextView.setText(status);
        scanButton.setEnabled(canStart);
        ssidInputLayout.setEnabled(canStart);
        passwordInputLayout.setEnabled(canStart);
        startButton.setEnabled(canStart);
        stopButton.setEnabled(canStop);
    }

    @Override
    protected void onDestroy() {
        wifiConfigBroadcaster.stop();

        if (receiverRegistered) {
            unregisterReceiver(wifiReceiver);
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
