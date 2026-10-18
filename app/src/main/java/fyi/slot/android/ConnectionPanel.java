package fyi.slot.android;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The settings bay's computer tab: turn the connection on or off and show how to reach it. */
final class ConnectionPanel implements ConnectionState.Listener {
    static final String GROUP = "connection";
    static final int REQUEST_NOTIFICATIONS = 44;
    private static final long ADDRESS_POLL = 4000;
    private final Activity activity;
    private final AppLanguage language;
    private final SettingsPanel settings;
    private final LibraryRepository library;
    private final Runnable openLibrary;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::pollAddresses;
    private List<String> addresses = List.of();
    private int addressIndex;
    private boolean started;

    ConnectionPanel(Activity activity, AppLanguage language, SettingsPanel settings, LibraryRepository library, Runnable openLibrary) {
        this.activity = activity; this.language = language; this.settings = settings;
        this.library = library; this.openLibrary = openLibrary;
    }

    void start() {
        if (started) return;
        started = true;
        ConnectionState.add(this);
        connectionChanged();
    }

    void stop() {
        started = false;
        ConnectionState.remove(this);
        main.removeCallbacks(poll);
    }

    @Override public void connectionChanged() {
        addresses = ConnectionState.phase() == ConnectionState.Phase.RUNNING ? ConnectionState.addresses() : List.of();
        addressIndex = Math.min(addressIndex, Math.max(0, addresses.size() - 1));
        main.removeCallbacks(poll);
        if (started && ConnectionState.phase() == ConnectionState.Phase.RUNNING) main.postDelayed(poll, ADDRESS_POLL);
        changed();
    }

    /** Networks come and go while the page is open; rows are rebuilt only when the list differs. */
    private void pollAddresses() {
        if (!started || ConnectionState.phase() != ConnectionState.Phase.RUNNING) return;
        List<String> next = ConnectionState.addresses();
        if (!Objects.equals(next, addresses)) {
            String current = addresses.isEmpty() ? null : addresses.get(addressIndex);
            addresses = next;
            addressIndex = Math.max(0, current == null ? 0 : next.indexOf(current));
            changed();
        }
        main.postDelayed(poll, ADDRESS_POLL);
    }

    void changed() { settings.replaceGroup(GROUP, rows()); }

    private boolean libraryReady() {
        LibraryRepository.Status status = library.status();
        return library.root() != null && status != LibraryRepository.Status.UNCONFIGURED
                && status != LibraryRepository.Status.PERMISSION;
    }

    List<SettingsPanel.Row> rows() {
        List<SettingsPanel.Row> rows = new ArrayList<>();
        ConnectionState.Phase phase = ConnectionState.phase();
        if (phase == ConnectionState.Phase.STARTING) {
            rows.add(info("state", R.string.connection_starting));
            return rows;
        }
        if (phase == ConnectionState.Phase.RUNNING) {
            if (addresses.isEmpty()) rows.add(info("network", R.string.connection_no_network));
            else {
                String url = addresses.get(addressIndex), pin = ConnectionState.pin();
                rows.add(new SettingsPanel.Row("card", (context, layout) -> ConnectionCard.create(context, layout, language, url, pin)));
            }
            if (addresses.size() > 1) rows.add(new SettingsPanel.Row("address", R.string.connection_next_address,
                    SettingsPanel.Kind.CHOICE, false, () -> (addressIndex + 1) + " / " + addresses.size(), direction -> {
                        addressIndex = Math.floorMod(addressIndex + direction, addresses.size());
                        changed();
                    }));
            rows.add(entry("stop", R.string.connection_stop, () -> ConnectionState.stop(activity)));
            return rows;
        }
        if (!libraryReady()) {
            rows.add(entry("library", R.string.connection_needs_library, openLibrary));
            return rows;
        }
        int state = phase == ConnectionState.Phase.FAILED ? R.string.connection_failed
                : phase == ConnectionState.Phase.TIMED_OUT ? R.string.connection_timed_out : R.string.connection_off;
        rows.add(info("state", state));
        rows.add(entry("start", R.string.connection_start, this::turnOn));
        return rows;
    }

    /** The system stops asking by itself once notifications have been refused twice. */
    private void turnOn() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        ConnectionState.start(activity);
    }

    private static SettingsPanel.Row info(String id, int label) {
        return new SettingsPanel.Row(id, label, SettingsPanel.Kind.INFO, false, null, direction -> {});
    }

    private static SettingsPanel.Row entry(String id, int label, Runnable action) {
        return new SettingsPanel.Row(id, label, SettingsPanel.Kind.ENTRY, false, null, direction -> action.run());
    }

    String address() { return addresses.isEmpty() ? null : addresses.get(addressIndex); }
}
