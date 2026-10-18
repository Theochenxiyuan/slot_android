package fyi.slot.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.Set;

/** Observes connected Wi-Fi networks, including networks without internet access. */
final class WifiConnectionStatus {
    private final ConnectivityManager connectivity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Network> networks = new HashSet<>();
    private final Runnable changed;
    private ConnectivityManager.NetworkCallback callback;

    WifiConnectionStatus(Context context, Runnable changed) {
        connectivity = context.getSystemService(ConnectivityManager.class);
        this.changed = changed;
    }

    boolean isConnected() { return !networks.isEmpty(); }

    void start() {
        if (callback != null || connectivity == null) return;
        networks.clear();
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities current = active == null ? null : connectivity.getNetworkCapabilities(active);
        if (current != null && current.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) networks.add(active);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (callback == this && networks.add(network)) changed.run();
            }

            @Override public void onLost(Network network) {
                if (callback == this && networks.remove(network)) changed.run();
            }

            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (callback != this) return;
                boolean updated = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        ? networks.add(network) : networks.remove(network);
                if (updated) changed.run();
            }
        };
        NetworkRequest request = new NetworkRequest.Builder().clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        connectivity.registerNetworkCallback(request, callback, handler);
    }

    /** Stops observing; the last known state stays visible behind other windows until the next start. */
    void stop() {
        if (callback == null) return;
        ConnectivityManager.NetworkCallback previous = callback;
        callback = null;
        connectivity.unregisterNetworkCallback(previous);
    }
}
