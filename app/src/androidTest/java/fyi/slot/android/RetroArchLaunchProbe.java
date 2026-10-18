package fyi.slot.android;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.SystemClock;

/** Opt-in diagnostic against the user's real installed emulator and selected game. */
final class RetroArchLaunchProbe {
    private final InteractionChecks t;
    RetroArchLaunchProbe(InteractionChecks checks) { t = checks; }

    void run() {
        MainActivity activity = t.activity();
        SystemClock.sleep(1500); t.waitForIdleSync();
        StringBuilder report = new StringBuilder();
        RomRecord rom = activity.slotView.currentCartridge().rom;
        RetroArchPreferences preferences = new RetroArchPreferences(activity);
        RetroArchLauncher launcher = new RetroArchLauncher(activity, preferences);
        String packageName = launcher.selectedPackage(launcher.installedPackages());
        report.append("ROM ").append(rom.filename).append('\n');
        try {
            ActivityInfo info = activity.getPackageManager().getActivityInfo(new ComponentName(packageName, RetroArchLauncher.ACTIVITY), 0);
            report.append("ACTIVITY ").append(info.name).append(" exported=").append(info.exported).append(" enabled=").append(info.enabled).append('\n');
        } catch (Exception error) { report.append("ACTIVITY_QUERY ").append(error).append('\n'); }
        for (int variant = 0; variant < 3; variant++) {
            Intent intent = launcher.buildIntent(rom, packageName);
            if (variant < 2) intent.setAction(Intent.ACTION_MAIN);
            if (variant == 0) intent.addCategory(Intent.CATEGORY_LAUNCHER);
            String label = variant == 0 ? "MAIN+LAUNCHER" : variant == 1 ? "MAIN" : "COMPONENT_ONLY";
            report.append("RESOLVE ").append(label).append(' ').append(activity.getPackageManager().resolveActivity(intent, 0)).append('\n');
            Throwable[] failure = new Throwable[1];
            t.runOnMainSync(() -> {
                try { activity.startActivity(intent); }
                catch (RuntimeException error) { failure[0] = error; }
            });
            report.append("START ").append(label).append(' ').append(failure[0] == null ? "STARTED" : failure[0]).append('\n');
            SystemClock.sleep(2000);
            try (java.io.InputStream command = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    t.getUiAutomation().executeShellCommand(
                            "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n fyi.slot.android/.MainActivity"))) {
                byte[] buffer = new byte[1024];
                while (command.read(buffer) != -1) { }
            } catch (java.io.IOException error) { report.append("HOME ").append(error).append('\n'); }
            SystemClock.sleep(700);
        }
        t.note(report.toString());
    }
}
