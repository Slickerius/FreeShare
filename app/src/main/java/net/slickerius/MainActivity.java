package net.slickerius;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 41;

    private TextView statusText;
    private EditText networkValue;
    private EditText passwordValue;
    private TextView proxyValue;
    private TextView trafficValue;
    private TextView connectedCountText;
    private TextView connectedDevicesValue;
    private TextView guideTitle;
    private TextView guideSteps;
    private TextView detailText;
    private Button toggleButton;
    private Button refreshPasswordButton;
    private Button[] osButtons;
    private boolean running;
    private int selectedGuide;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            renderState();
            handler.postDelayed(this, 1_000L);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        networkValue = findViewById(R.id.networkValue);
        passwordValue = findViewById(R.id.passwordValue);
        proxyValue = findViewById(R.id.proxyValue);
        trafficValue = findViewById(R.id.trafficValue);
        connectedCountText = findViewById(R.id.connectedCountText);
        connectedDevicesValue = findViewById(R.id.connectedDevicesValue);
        guideTitle = findViewById(R.id.guideTitle);
        guideSteps = findViewById(R.id.guideSteps);
        detailText = findViewById(R.id.detailText);
        toggleButton = findViewById(R.id.toggleButton);
        refreshPasswordButton = findViewById(R.id.refreshPasswordButton);
        osButtons = new Button[] {
                findViewById(R.id.osMacButton),
                findViewById(R.id.osWindowsButton),
                findViewById(R.id.osIosButton),
                findViewById(R.id.osAndroidButton),
                findViewById(R.id.osLinuxButton)
        };
        reconcilePersistedState();
        networkValue.setText(CredentialStore.getSsid(this));
        passwordValue.setText(CredentialStore.getOrCreatePassword(this));

        toggleButton.setOnClickListener(view -> {
            if (running) {
                stopSharing();
            } else {
                requestPermissionsAndStart();
            }
        });
        findViewById(R.id.copyPasswordButton).setOnClickListener(view -> copyPassword());
        refreshPasswordButton.setOnClickListener(view -> refreshPassword());
        for (int index = 0; index < osButtons.length; index++) {
            final int guideIndex = index;
            osButtons[index].setOnClickListener(view -> selectGuide(guideIndex));
        }
        selectGuide(0);
    }

    private void reconcilePersistedState() {
        SharedPreferences state = getSharedPreferences(ShareService.PREFS, MODE_PRIVATE);
        String phase = state.getString(ShareService.KEY_PHASE, ShareService.PHASE_STOPPED);
        if (!ShareService.isServiceAlive()
                && (ShareService.PHASE_STARTING.equals(phase)
                || ShareService.PHASE_ACTIVE.equals(phase))) {
            state.edit()
                    .putString(ShareService.KEY_PHASE, ShareService.PHASE_STOPPED)
                    .putString(ShareService.KEY_MESSAGE, "Ready to share")
                    .putString(ShareService.KEY_DETAIL, "FreeShare is stopped.")
                    .putString(ShareService.KEY_ADDRESS, "")
                    .apply();
        }
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ShareService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        handler.post(refreshTask);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refreshTask);
        unregisterReceiver(stateReceiver);
        super.onStop();
    }

    private void requestPermissionsAndStart() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (Build.VERSION.SDK_INT <= 32
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi == null || !wifi.isWifiEnabled()) {
            Toast.makeText(this, R.string.wifi_needed, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.Panel.ACTION_WIFI));
            return;
        }
        startSharing();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        boolean nearbyGranted = Build.VERSION.SDK_INT >= 33
                ? checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                == PackageManager.PERMISSION_GRANTED
                : checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (nearbyGranted) {
            requestPermissionsAndStart();
        } else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show();
        }
    }

    private void startSharing() {
        String ssid = networkValue.getText().toString().trim();
        String password = passwordValue.getText().toString();
        if (!CredentialStore.isValidSsid(ssid)) {
            networkValue.setError(getString(R.string.ssid_invalid));
            networkValue.requestFocus();
            return;
        }
        if (!CredentialStore.isValidPassword(password)) {
            passwordValue.setError(getString(R.string.password_invalid));
            passwordValue.requestFocus();
            return;
        }
        CredentialStore.save(this, ssid, password);
        Intent intent = new Intent(this, ShareService.class).setAction(ShareService.ACTION_START);
        startForegroundService(intent);
    }

    private void stopSharing() {
        Intent intent = new Intent(this, ShareService.class).setAction(ShareService.ACTION_STOP);
        startService(intent);
    }

    private void copyPassword() {
        String password = passwordValue.getText().toString();
        if (password.isBlank() || password.equals(getString(R.string.waiting_value))) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("FreeShare password", password));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void refreshPassword() {
        String password = CredentialStore.refreshPassword(this);
        passwordValue.setText(password);
        passwordValue.setSelection(password.length());
        Toast.makeText(this, R.string.password_refreshed, Toast.LENGTH_SHORT).show();
    }

    private void selectGuide(int index) {
        selectedGuide = index;
        for (int buttonIndex = 0; buttonIndex < osButtons.length; buttonIndex++) {
            boolean selected = buttonIndex == index;
            osButtons[buttonIndex].setBackgroundTintList(getColorStateList(
                    selected ? R.color.signal : R.color.panel_high));
            osButtons[buttonIndex].setTextColor(getColor(
                    selected ? R.color.ink : R.color.paper));
        }
        renderGuide();
    }

    private void renderGuide() {
        String ssid = networkValue.getText().toString().trim();
        if (ssid.isBlank()) {
            ssid = CredentialStore.DEFAULT_SSID;
        }
        String proxyHost = "192.168.49.1";
        int title;
        int steps;
        switch (selectedGuide) {
            case 1:
                title = R.string.guide_windows_title;
                steps = R.string.guide_windows;
                break;
            case 2:
                title = R.string.guide_ios_title;
                steps = R.string.guide_ios;
                break;
            case 3:
                title = R.string.guide_android_title;
                steps = R.string.guide_android;
                break;
            case 4:
                title = R.string.guide_linux_title;
                steps = R.string.guide_linux;
                break;
            default:
                title = R.string.guide_macos_title;
                steps = R.string.guide_macos;
                break;
        }
        guideTitle.setText(title);
        guideSteps.setText(getString(steps, ssid, proxyHost, ShareService.PROXY_PORT));
    }

    private void renderState() {
        SharedPreferences state = getSharedPreferences(ShareService.PREFS, MODE_PRIVATE);
        String phase = state.getString(ShareService.KEY_PHASE, ShareService.PHASE_STOPPED);
        String message = state.getString(ShareService.KEY_MESSAGE, "Ready to share");
        String ssid = state.getString(ShareService.KEY_SSID, "");
        String password = state.getString(ShareService.KEY_PASSWORD, "");
        String address = state.getString(ShareService.KEY_ADDRESS, "");
        long bytes = state.getLong(ShareService.KEY_BYTES, 0L);
        int clientCount = state.getInt(ShareService.KEY_CLIENT_COUNT, 0);
        String clients = state.getString(ShareService.KEY_CLIENTS, "");

        running = ShareService.PHASE_STARTING.equals(phase)
                || ShareService.PHASE_ACTIVE.equals(phase);
        toggleButton.setText(running ? R.string.stop_sharing : R.string.start_sharing);
        toggleButton.setBackgroundTintList(getColorStateList(
                running ? R.color.warning : R.color.signal));

        statusText.setText(getString(R.string.status_format, message));
        if (ShareService.PHASE_ERROR.equals(phase)) {
            statusText.setTextColor(getColor(R.color.danger));
        } else if (ShareService.PHASE_ACTIVE.equals(phase)) {
            statusText.setTextColor(getColor(R.color.signal));
        } else if (ShareService.PHASE_STARTING.equals(phase)) {
            statusText.setTextColor(getColor(R.color.warning));
        } else {
            statusText.setTextColor(getColor(R.color.muted));
        }

        if (running) {
            setTextIfChanged(networkValue, ssid);
            setTextIfChanged(passwordValue, password);
        }
        networkValue.setEnabled(!running);
        passwordValue.setEnabled(!running);
        refreshPasswordButton.setEnabled(!running);
        refreshPasswordButton.setAlpha(running ? 0.45f : 1.0f);
        proxyValue.setText(address.isBlank()
                ? getString(R.string.waiting_value)
                : address + ":" + ShareService.PROXY_PORT);
        trafficValue.setText(formatBytes(bytes));
        if (!running) {
            connectedCountText.setText(R.string.connected_title);
            connectedDevicesValue.setText(R.string.connected_waiting);
        } else if (clientCount == 0) {
            connectedCountText.setText(R.string.connected_title);
            connectedDevicesValue.setText(R.string.connected_none);
        } else {
            String count = getResources().getQuantityString(
                    R.plurals.connected_count, clientCount, clientCount);
            connectedCountText.setText(getString(R.string.connected_title_count, count));
            connectedDevicesValue.setText(clients);
        }
        renderGuide();

        String detail = state.getString(ShareService.KEY_DETAIL, "");
        detailText.setText(detail.isBlank() ? getString(R.string.technical_note) : detail);
    }

    private static void setTextIfChanged(EditText view, String value) {
        if (!value.isBlank() && !view.getText().toString().equals(value)) {
            view.setText(value);
            view.setSelection(value.length());
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1_024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unit = -1;
        do {
            value /= 1_024.0;
            unit++;
        } while (value >= 1_024.0 && unit < units.length - 1);
        return String.format(Locale.US, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }
}
