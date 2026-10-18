package fyi.slot.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

/** Starts apps, their system information pages and system settings, reporting failures briefly. */
class AppLauncher {
    private final Activity activity;
    private final LauncherApps launcherApps;
    private final AppLanguage language;

    AppLauncher(Activity activity) {
        this(activity, new AppLanguage(activity));
    }

    AppLauncher(Activity activity, AppLanguage language) {
        this.activity = activity;
        this.language = language;
        launcherApps = activity.getSystemService(LauncherApps.class);
    }

    boolean launch(AppEntry entry, Rect sourceBounds) {
        try {
            launcherApps.startMainActivity(entry.component, entry.user, sourceBounds, null);
            return true;
        } catch (ActivityNotFoundException | SecurityException | IllegalStateException error) {
            notice(language.text(R.string.open_app_failed, entry.label));
            return false;
        }
    }

    boolean showDetails(AppEntry entry) {
        try {
            launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null);
            return true;
        } catch (ActivityNotFoundException | SecurityException | IllegalStateException error) {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", entry.packageName(), null));
            if (start(fallback)) return true;
            notice(language.text(R.string.open_info_failed));
            return false;
        }
    }

    boolean openAppInfo() {
        if (start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.getPackageName(), null)))) return true;
        notice(language.text(R.string.open_info_failed));
        return false;
    }

    boolean openSystemSettings() {
        if (start(new Intent(Settings.ACTION_SETTINGS))) return true;
        notice(language.text(R.string.open_settings_failed));
        return false;
    }

    boolean openWifiSettings() {
        if (start(new Intent(Settings.ACTION_WIFI_SETTINGS)) || start(new Intent(Settings.ACTION_SETTINGS))) return true;
        notice(language.text(R.string.open_wifi_failed));
        return false;
    }

    boolean start(Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            return false;
        }
    }

    void notice(String message) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }
}
