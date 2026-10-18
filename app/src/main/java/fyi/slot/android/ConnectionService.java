package fyi.slot.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Foreground service that keeps the computer connection reachable while the desktop is used. */
public final class ConnectionService extends Service {
    static final String EXTRA_PIN = "pin", ACTION_STOP = "fyi.slot.android.connection.STOP";
    private static final String CHANNEL = "connection";
    private static final int NOTIFICATION = 1092;
    private static final long LOG_LIMIT = 256 * 1024, SYSTEM_LIMIT = 6 * 60 * 60 * 1000L;
    private DeviceServer server;
    private CrcCache crcs;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private File logFile;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            ConnectionState.set(ConnectionState.Phase.STOPPED);
            shutDown();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (server != null) return START_NOT_STICKY;
        String pin = intent.getStringExtra(EXTRA_PIN);
        if (pin == null || pin.length() != DeviceAuth.LENGTH) { fail(); return START_NOT_STICKY; }
        logFile = new File(getFilesDir(), "connection.log");
        crcs = new CrcCache();
        DeviceApi api = new DeviceApi(this::libraryRoot, LibraryEvents::post, crcs, 2500);
        DeviceServer next = new DeviceServer(DeviceServer.PORT, new DeviceAuth(pin, android.os.SystemClock::elapsedRealtime),
                api, path -> getAssets().open("studio/" + path), ArtProxy.network(), this::log);
        try {
            next.startServer();
            server = next;
            acquireLocks();
            log("started on " + next.getListeningPort());
            ConnectionState.set(ConnectionState.Phase.RUNNING);
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
        } catch (IOException error) {
            log("start failed: " + error);
            fail();
        }
        return START_NOT_STICKY;
    }

    /** Android 15 limits dataSync services; the page asks to turn the connection on again. */
    @Override public void onTimeout(int startId, int type) {
        log("timed out");
        ConnectionState.set(ConnectionState.Phase.TIMED_OUT);
        shutDown();
    }

    @Override public void onDestroy() {
        stopServer();
        if (ConnectionState.active()) ConnectionState.set(ConnectionState.Phase.STOPPED);
        super.onDestroy();
    }

    private void fail() {
        ConnectionState.set(ConnectionState.Phase.FAILED);
        shutDown();
    }

    private void shutDown() {
        stopServer();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopServer() {
        if (server != null) { server.stop(); server = null; log("stopped"); }
        if (crcs != null) { crcs.close(); crcs = null; }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wifiLock = null; wakeLock = null;
    }

    private void acquireLocks() {
        WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "slot:connection");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slot:connection");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(SYSTEM_LIMIT);
        }
    }

    /** The chosen library while files are reachable; uploads and edits wait otherwise. */
    private File libraryRoot() {
        SharedPreferences preferences = getSharedPreferences("library", MODE_PRIVATE);
        String saved = preferences.getString("root", null);
        if (saved == null) return null;
        File root = new File(saved);
        boolean permitted = LibraryStorage.allowed() || LibraryPaths.within(getFilesDir(), root);
        return permitted && LibraryStorage.online(this, root) && root.isDirectory() ? root : null;
    }

    private Notification notification() {
        AppLanguage language = new AppLanguage(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, language.text(R.string.connection_channel),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, ConnectionService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        List<String> addresses = server == null ? List.of() : ConnectionState.addresses();
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_connection)
                .setContentTitle(language.text(server == null ? R.string.connection_starting : R.string.connection_notification))
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, language.text(R.string.connection_stop), stop).build());
        if (!addresses.isEmpty()) builder.setContentText(addresses.get(0) + " · " + ConnectionState.pin());
        return builder.build();
    }

    private synchronized void log(String line) {
        if (logFile == null) return;
        try {
            if (logFile.length() > LOG_LIMIT) {
                File old = new File(logFile.getParentFile(), "connection.old.log");
                if (old.exists() && !old.delete()) return;
                if (!logFile.renameTo(old)) return;
            }
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + line + "\n");
            }
        } catch (IOException ignored) { }
    }
}
