package fyi.slot.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;

/** Foreground status updates without a polling or drawing loop. */
final class ShelfStatusObserver {
    private final Context context;
    private final Runnable changed;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { accept(intent); }
    };
    private int batteryPercent = 100;
    private boolean charging, started;

    ShelfStatusObserver(Context context, Runnable changed) {
        this.context = context; this.changed = changed;
        accept(context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
    }

    int batteryPercent() { return batteryPercent; }
    boolean isCharging() { return charging; }
    boolean isStarted() { return started; }

    void start() {
        if (started) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        started = true;
        Intent sticky = Build.VERSION.SDK_INT >= 33
                ? context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                : context.registerReceiver(receiver, filter);
        accept(sticky);
    }

    void stop() {
        if (!started) return;
        started = false;
        context.unregisterReceiver(receiver);
    }

    void accept(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
            int max = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int percent = max > 0 ? Math.max(0, Math.min(100, level * 100 / max)) : 100;
            boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (batteryPercent == percent && charging == plugged) return;
            batteryPercent = percent; charging = plugged;
        } else if (!Intent.ACTION_TIME_TICK.equals(intent.getAction())
                && !Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                && !Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) return;
        changed.run();
    }
}
