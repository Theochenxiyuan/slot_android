package fyi.slot.android;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide state of the computer connection, shared by the service, the bay page and the status bar. */
final class ConnectionState {
    enum Phase { STOPPED, STARTING, RUNNING, FAILED, TIMED_OUT }
    interface Listener { void connectionChanged(); }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static Phase phase = Phase.STOPPED;
    private static String pin = "";

    private ConnectionState() { }

    static Phase phase() { return phase; }
    static boolean active() { return phase == Phase.RUNNING || phase == Phase.STARTING; }
    static String pin() { return pin; }
    static int port() { return DeviceServer.PORT; }
    static List<String> addresses() { return NetworkAddresses.urls(port()); }

    static void add(Listener listener) { LISTENERS.addIfAbsent(listener); }
    static void remove(Listener listener) { LISTENERS.remove(listener); }

    /** A new pairing code every time the connection is turned on. */
    static void start(Context context) {
        if (active()) return;
        pin = DeviceAuth.newPin(RANDOM);
        set(Phase.STARTING);
        Intent intent = new Intent(context, ConnectionService.class).putExtra(ConnectionService.EXTRA_PIN, pin);
        try { context.startForegroundService(intent); }
        catch (RuntimeException refused) { set(Phase.FAILED); }
    }

    static void stop(Context context) {
        pin = "";
        if (!active()) { if (phase != Phase.STOPPED) set(Phase.STOPPED); return; }
        set(Phase.STOPPED);
        context.stopService(new Intent(context, ConnectionService.class));
    }

    static void set(Phase next) {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(() -> set(next)); return; }
        if (phase == next) return;
        phase = next;
        for (Listener listener : LISTENERS) listener.connectionChanged();
    }
}
