package fyi.slot.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;

/** Explicit dismissal or completion suppresses future automatic openings. */
final class OnboardingPreferences {
    private final SharedPreferences preferences;
    OnboardingPreferences(Context context) { preferences = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE); }
    boolean isDismissed() { return preferences.getBoolean("dismissed", false); }
    boolean shouldOpen(boolean fixture, File root) { return !fixture && !isDismissed() && root == null; }
    void dismiss() { preferences.edit().putBoolean("dismissed", true).apply(); }
}
