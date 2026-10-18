package fyi.slot.android;

import android.content.ComponentName;
import android.content.pm.LauncherActivityInfo;
import android.os.UserHandle;

/** One launchable activity entry, identified by component and user. */
final class AppEntry {
    final ComponentName component;
    final UserHandle user;
    final String label;
    final String key;
    final LauncherActivityInfo info;

    AppEntry(ComponentName component, UserHandle user, String label, LauncherActivityInfo info) {
        this.component = component; this.user = user; this.label = label; this.info = info;
        key = component.flattenToShortString() + "#" + user.hashCode();
    }

    String packageName() { return component.getPackageName(); }
}
