package fyi.slot.android;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;

/** Default-home status and the system path for choosing this app as the home screen. */
final class HomeRole {
    static final int REQUEST_CODE = 41;
    private final Activity activity;
    private final RoleManager roles;

    HomeRole(Activity activity) {
        this.activity = activity;
        roles = activity.getSystemService(RoleManager.class);
    }

    boolean isHeld() {
        return roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME);
    }

    /** Asks the system role dialog first and falls back to the default-apps settings. */
    boolean request() {
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            try {
                activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_CODE);
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        for (String action : new String[]{Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                Settings.ACTION_SETTINGS}) {
            try {
                activity.startActivity(new Intent(action));
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        return false;
    }
}
