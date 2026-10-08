package net.slickerius;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.SocketFactory;

public final class ShareService extends Service {
    public static final String ACTION_START = "net.slickerius.action.START";
    public static final String ACTION_STOP = "net.slickerius.action.STOP";
    public static final String ACTION_STATE = "net.slickerius.action.STATE";

    public static final String PREFS = "share_state";
    public static final String KEY_PHASE = "phase";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_DETAIL = "detail";
    public static final String KEY_SSID = "ssid";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_ADDRESS = "address";
    public static final String KEY_BYTES = "bytes";
    public static final String KEY_CLIENTS = "clients";
    public static final String KEY_CLIENT_COUNT = "client_count";

    public static final String PHASE_STOPPED = "stopped";
    public static final String PHASE_STARTING = "starting";
    public static final String PHASE_ACTIVE = "active";
    public static final String PHASE_ERROR = "error";

    public static final int PROXY_PORT = 8282;
    private static final int NOTIFICATION_ID = 82;
    private static final String CHANNEL_ID = "sharing";
    private static volatile boolean serviceAlive;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopping = new AtomicBoolean();
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver p2pReceiver;
    private Network upstreamNetwork;
    private HttpProxyServer proxy;
    private String networkName;
    private String passphrase;
    private int addressRetries;
    private int groupCreateAttempts;

    private final Runnable statisticsTask = new Runnable() {
        @Override
        public void run() {
            if (proxy == null) {
                return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_BYTES, proxy.getTransferredBytes())
                    .apply();
            sendStateBroadcast();
            updateNotification();
            requestGroupInfo();
            handler.postDelayed(this, 1_000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        serviceAlive = true;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Starting Wi‑Fi Direct…"));

        manager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (manager != null) {
            channel = manager.initialize(this, getMainLooper(), () -> {
                channel = manager.initialize(this, getMainLooper(), null);
                publish(PHASE_ERROR, "Wi‑Fi Direct channel restarted",
                        "Tap Stop, then Start sharing to retry.", "", "");
            });
        }
        registerP2pReceiver();
    }

    static boolean isServiceAlive() {
        return serviceAlive;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSharing();
        } else if (proxy == null && !PHASE_STARTING.equals(currentPhase())) {
            startSharing();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerP2pReceiver() {
        p2pReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)
                        || WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                    requestGroupInfo();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(p2pReceiver, filter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(p2pReceiver, filter);
        }
    }

    private void startSharing() {
        stopping.set(false);
        if (!hasNearbyPermission()) {
            fail("Nearby-device permission is missing",
                    "Open FreeShare and grant Nearby devices, then retry.");
            return;
        }
        if (manager == null || channel == null) {
            fail("Wi‑Fi Direct is unavailable", "This device did not expose a Wi‑Fi Direct manager.");
            return;
        }
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi == null || !wifi.isWifiEnabled()) {
            fail("Wi‑Fi must be turned on",
                    "Android requires Wi‑Fi to be enabled before it can create a Wi‑Fi Direct group.");
            return;
        }

        ConnectivityManager connectivity =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        upstreamNetwork = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = upstreamNetwork == null
                ? null : connectivity.getNetworkCapabilities(upstreamNetwork);
        if (upstreamNetwork == null || capabilities == null
                || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            fail("No upstream internet connection",
                    "Connect the phone to Wi‑Fi or cellular data before starting FreeShare.");
            return;
        }

        networkName = CredentialStore.getSsid(this);
        passphrase = CredentialStore.getOrCreatePassword(this);
        if (!CredentialStore.isValidSsid(networkName)
                || !CredentialStore.isValidPassword(passphrase)) {
            fail("Invalid Wi‑Fi credentials",
                    "Open FreeShare and correct the network name or password.");
            return;
        }
        addressRetries = 0;
        groupCreateAttempts = 0;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_BYTES, 0L)
                .apply();
        publish(PHASE_STARTING, "Preparing private link", "Creating the Wi‑Fi Direct group…",
                networkName, passphrase);
        removeExistingGroup(this::createGroup);
    }

    @SuppressLint("MissingPermission")
    private void removeExistingGroup(Runnable continuation) {
        manager.requestGroupInfo(channel, group -> {
            if (group == null) {
                continuation.run();
                return;
            }
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    handler.postDelayed(continuation, 500L);
                }

                @Override
                public void onFailure(int reason) {
                    handler.postDelayed(continuation, 500L);
                }
            });
        });
    }

    @SuppressLint("MissingPermission")
    private void createGroup() {
        if (stopping.get()) {
            return;
        }
        groupCreateAttempts++;
        WifiP2pConfig config;
        try {
            config = new WifiP2pConfig.Builder()
                    .setNetworkName(networkName)
                    .setPassphrase(passphrase)
                    .enablePersistentMode(false)
                    .build();
        } catch (IllegalArgumentException error) {
            fail("Invalid Wi‑Fi Direct configuration", error.getMessage());
            return;
        }
        manager.createGroup(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                publish(PHASE_STARTING, "Wi‑Fi Direct is starting",
                        "Waiting for the group-owner address…", networkName, passphrase);
                handler.postDelayed(ShareService.this::requestGroupInfo, 750L);
            }

            @Override
            public void onFailure(int reason) {
                if (reason == WifiP2pManager.BUSY && groupCreateAttempts < 5) {
                    publish(PHASE_STARTING, "Wi‑Fi Direct is busy",
                            "Android is releasing the previous group; retrying automatically…",
                            networkName, passphrase);
                    handler.postDelayed(
                            () -> removeExistingGroup(ShareService.this::createGroup),
                            1_000L * groupCreateAttempts);
                    return;
                }
                fail("Could not create Wi‑Fi Direct group", p2pReason(reason));
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void requestGroupInfo() {
        if (manager == null || channel == null || !hasNearbyPermission() || stopping.get()) {
            return;
        }
        manager.requestGroupInfo(channel, this::handleGroup);
    }

    private void handleGroup(WifiP2pGroup group) {
        if (group == null || !group.isGroupOwner() || stopping.get()) {
            return;
        }
        String groupName = group.getNetworkName();
        String groupPassword = group.getPassphrase();
        if (groupName != null && !groupName.isBlank()) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SSID, groupName).apply();
        }
        if (groupPassword != null && !groupPassword.isBlank()) {
            passphrase = groupPassword;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_PASSWORD, groupPassword).apply();
        }
        publishClients(group);

        InetAddress address = findIpv4Address(group.getInterface());
        if (address == null) {
            if (addressRetries++ < 12) {
                handler.postDelayed(this::requestGroupInfo, 750L);
            } else {
                fail("Wi‑Fi Direct has no IPv4 address",
                        "The group exists, but Android did not assign its local address.");
            }
            return;
        }
        startProxy(address);
    }

    private void publishClients(WifiP2pGroup group) {
        StringBuilder clients = new StringBuilder();
        int count = 0;
        for (WifiP2pDevice device : group.getClientList()) {
            if (count > 0) {
                clients.append('\n');
            }
            String name = device.deviceName == null ? "" : device.deviceName.trim();
            clients.append(name.isEmpty() ? device.deviceAddress : name);
            count++;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_CLIENT_COUNT, count)
                .putString(KEY_CLIENTS, clients.toString())
                .apply();
    }

    private void startProxy(InetAddress address) {
        if (proxy != null || stopping.get()) {
            return;
        }
        try {
            SocketFactory upstreamFactory = upstreamNetwork.getSocketFactory();
            HttpProxyServer server = new HttpProxyServer(
                    address,
                    PROXY_PORT,
                    upstreamFactory,
                    message -> handler.post(() -> fail("Proxy stopped", message)));
            server.start();
            proxy = server;
            publish(PHASE_ACTIVE, "Sharing at full speed",
                    "Proxy ready on the Wi‑Fi Direct interface. No application rate limit is applied.",
                    getState(KEY_SSID, networkName), passphrase, address.getHostAddress());
            handler.removeCallbacks(statisticsTask);
            handler.post(statisticsTask);
        } catch (IOException error) {
            if (addressRetries++ < 12) {
                handler.postDelayed(this::requestGroupInfo, 750L);
            } else {
                fail("Could not open proxy port " + PROXY_PORT,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void stopSharing() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        handler.removeCallbacks(statisticsTask);
        closeProxy();
        publish(PHASE_STOPPED, "Ready to share", "FreeShare is stopped.", "", "", "");
        if (manager != null && channel != null && hasNearbyPermission()) {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    finishStop();
                }

                @Override
                public void onFailure(int reason) {
                    finishStop();
                }
            });
            handler.postDelayed(this::finishStop, 2_000L);
        } else {
            finishStop();
        }
    }

    private void finishStop() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void fail(String message, String detail) {
        closeProxy();
        publish(PHASE_ERROR, message, detail == null ? "" : detail,
                getState(KEY_SSID, networkName == null ? "" : networkName),
                passphrase == null ? "" : passphrase);
        updateNotification();
    }

    private void closeProxy() {
        HttpProxyServer current = proxy;
        proxy = null;
        if (current != null) {
            current.close();
        }
    }

    private boolean hasNearbyPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static InetAddress findIpv4Address(String interfaceName) {
        if (interfaceName == null || interfaceName.isBlank()) {
            return null;
        }
        try {
            NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
            if (networkInterface == null) {
                return null;
            }
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            for (InetAddress address : Collections.list(addresses)) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    return address;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void createNotificationChannel() {
        NotificationManager notifications =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        notifications.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(
                this,
                1,
                new Intent(this, ShareService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_signal)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private void updateNotification() {
        String message = getState(KEY_MESSAGE, "FreeShare is running");
        NotificationManager notifications =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private String currentPhase() {
        return getState(KEY_PHASE, PHASE_STOPPED);
    }

    private String getState(String key, String fallback) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, fallback);
    }

    private void publish(String phase, String message, String detail, String ssid, String password) {
        publish(phase, message, detail, ssid, password, getState(KEY_ADDRESS, ""));
    }

    private void publish(
            String phase,
            String message,
            String detail,
            String ssid,
            String password,
            String address) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_PHASE, phase)
                .putString(KEY_MESSAGE, message)
                .putString(KEY_DETAIL, detail)
                .putString(KEY_SSID, ssid == null ? "" : ssid)
                .putString(KEY_PASSWORD, password == null ? "" : password)
                .putString(KEY_ADDRESS, address == null ? "" : address)
                .putInt(KEY_CLIENT_COUNT, PHASE_STOPPED.equals(phase) ? 0
                        : getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_CLIENT_COUNT, 0))
                .putString(KEY_CLIENTS, PHASE_STOPPED.equals(phase) ? ""
                        : getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CLIENTS, ""))
                .apply();
        sendStateBroadcast();
        updateNotification();
    }

    private void sendStateBroadcast() {
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()));
    }

    private static String p2pReason(int reason) {
        if (reason == WifiP2pManager.BUSY) {
            return "Android reports that Wi‑Fi Direct is busy. Stop other hotspot apps and retry.";
        }
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            return "Wi‑Fi Direct is not supported on this device.";
        }
        return "Android Wi‑Fi Direct error " + reason + ".";
    }

    @Override
    public void onDestroy() {
        serviceAlive = false;
        handler.removeCallbacksAndMessages(null);
        closeProxy();
        if (p2pReceiver != null) {
            try {
                unregisterReceiver(p2pReceiver);
            } catch (IllegalArgumentException ignored) {
            }
        }
        super.onDestroy();
    }
}
