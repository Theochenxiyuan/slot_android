package fyi.slot.android;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Launchable activities for the current user, refreshed on package changes and when the desktop returns. */
final class AppRepository {
    enum Status { LOADING, READY, FAILED }

    interface Listener { void onAppsChanged(Status status, List<AppEntry> apps); }

    private static final long REFRESH_DELAY = 250;
    private final LauncherApps launcherApps;
    private final String selfPackage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "slot-apps");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Listener> listeners = new ArrayList<>();
    private final Runnable reload = this::reloadNow;
    private Status status = Status.LOADING;
    private List<AppEntry> apps = List.of();
    private int generation;
    private boolean started;

    private final LauncherApps.Callback callback = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String packageName, UserHandle user) { scheduleReload(); }
        @Override public void onPackageAdded(String packageName, UserHandle user) { scheduleReload(); }
        @Override public void onPackageChanged(String packageName, UserHandle user) { scheduleReload(); }
        @Override public void onPackagesAvailable(String[] packageNames, UserHandle user, boolean replacing) { scheduleReload(); }
        @Override public void onPackagesUnavailable(String[] packageNames, UserHandle user, boolean replacing) { scheduleReload(); }
        @Override public void onPackagesSuspended(String[] packageNames, UserHandle user) { scheduleReload(); }
        @Override public void onPackagesUnsuspended(String[] packageNames, UserHandle user) { scheduleReload(); }
    };

    AppRepository(Context context) {
        launcherApps = context.getSystemService(LauncherApps.class);
        selfPackage = context.getPackageName();
    }

    void addListener(Listener listener) {
        listeners.add(listener);
        listener.onAppsChanged(status, apps);
    }

    Status status() { return status; }
    List<AppEntry> apps() { return apps; }

    /** Starts observing package changes and refreshes the list. */
    void start() {
        if (!started && launcherApps != null) {
            launcherApps.registerCallback(callback, main);
            started = true;
        }
        reloadNow();
    }

    void stop() {
        main.removeCallbacks(reload);
        if (started) { launcherApps.unregisterCallback(callback); started = false; }
    }

    void release() { stop(); worker.shutdownNow(); }

    void scheduleReload() {
        main.removeCallbacks(reload);
        main.postDelayed(reload, REFRESH_DELAY);
    }

    void reloadNow() {
        main.removeCallbacks(reload);
        int request = ++generation;
        if (apps.isEmpty() && status != Status.LOADING) publish(Status.LOADING, apps);
        try {
            worker.execute(() -> {
                List<AppEntry> loaded;
                try { loaded = query(); }
                catch (RuntimeException error) { loaded = null; }
                List<AppEntry> result = loaded;
                main.post(() -> {
                    if (request != generation) return;
                    if (result == null) publish(apps.isEmpty() ? Status.FAILED : Status.READY, apps);
                    else publish(Status.READY, result);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    private List<AppEntry> query() {
        if (launcherApps == null) throw new IllegalStateException("LauncherApps unavailable");
        UserHandle user = Process.myUserHandle();
        List<AppEntry> result = new ArrayList<>();
        for (LauncherActivityInfo info : launcherApps.getActivityList(null, user)) {
            if (selfPackage.equals(info.getComponentName().getPackageName())) continue;
            CharSequence label = info.getLabel();
            String name = label == null || label.toString().trim().isEmpty()
                    ? info.getComponentName().getPackageName() : label.toString().trim();
            result.add(new AppEntry(info.getComponentName(), info.getUser(), name, info));
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.SECONDARY);
        result.sort(Comparator.<AppEntry, String>comparing(entry -> entry.label, collator)
                .thenComparing(entry -> entry.key));
        return List.copyOf(result);
    }

    private void publish(Status next, List<AppEntry> list) {
        status = next; apps = list;
        for (Listener listener : new ArrayList<>(listeners)) listener.onAppsChanged(status, apps);
    }
}
