package fyi.slot.android;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;

/** Ordinary external RetroArch launching; never reads another application's private core files. */
final class RetroArchLauncher {
    static final String AARCH64 = "com.retroarch.aarch64", STANDARD = "com.retroarch";
    static final List<String> PACKAGES = List.of(AARCH64, STANDARD);
    static final String ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture";
    static final String MENU_ACTIVITY = "com.retroarch.browser.mainmenu.MainMenuActivity";
    enum Result { STARTED, NOT_INSTALLED, ROM_MISSING, FAILED }

    private final Context context;
    private final RetroArchPreferences preferences;
    private final GameCorePreferences gameCores;
    private final RetroArchConfig config;

    RetroArchLauncher(Context context, RetroArchPreferences preferences) {
        this(context, preferences, new GameCorePreferences(context, preferences));
    }

    RetroArchLauncher(Context context, RetroArchPreferences preferences, GameCorePreferences gameCores) {
        this.context = context;
        this.preferences = preferences;
        this.gameCores = gameCores;
        config = new RetroArchConfig(preferences);
    }

    List<String> installedPackages() {
        PackageManager manager = context.getPackageManager();
        List<String> installed = new ArrayList<>();
        for (String packageName : PACKAGES) {
            try {
                if (manager.getApplicationInfo(packageName, 0).enabled) installed.add(packageName);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return List.copyOf(installed);
    }

    String selectedPackage(List<String> installed) {
        String preferred = preferences.preferredPackage();
        if (installed.contains(preferred)) return preferred;
        for (String packageName : PACKAGES) if (installed.contains(packageName)) return packageName;
        return null;
    }

    Result launch(RomRecord rom) {
        try {
            if (rom == null || !rom.file().isFile()) return Result.ROM_MISSING;
            String packageName = selectedPackage(installedPackages());
            if (packageName == null) return Result.NOT_INSTALLED;
            context.startActivity(buildIntent(rom, packageName));
            return Result.STARTED;
        } catch (ActivityNotFoundException | SecurityException error) {
            android.util.Log.w("SlotRetroArch", "External launch failed", error);
            return Result.FAILED;
        }
    }

    Result open() {
        try {
            String packageName = selectedPackage(installedPackages());
            if (packageName == null) return Result.NOT_INSTALLED;
            context.startActivity(new Intent().setComponent(new ComponentName(packageName, MENU_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            return Result.STARTED;
        } catch (ActivityNotFoundException | SecurityException error) {
            android.util.Log.w("SlotRetroArch", "External menu launch failed", error);
            return Result.FAILED;
        }
    }

    // These paths belong to RetroArch's external launch contract, not Slot's own storage.
    @SuppressLint("SdCardPath")
    Intent buildIntent(RomRecord rom, String packageName) {
        if (!PACKAGES.contains(packageName)) throw new IllegalArgumentException("Unknown RetroArch package");
        String data = "/data/data/" + packageName;
        String sdcard = "/storage/emulated/0";
        String external = sdcard + "/Android/data/" + packageName + "/files";
        String ime;
        try { ime = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD); }
        catch (SecurityException ignored) { ime = null; }
        return new Intent()
                .setComponent(new ComponentName(packageName, ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("ROM", rom.file().getAbsolutePath())
                .putExtra("LIBRETRO", data + "/cores/" + gameCores.effective(rom).id + "_libretro_android.so")
                .putExtra("CONFIGFILE", config.resolve(rom, packageName))
                .putExtra("IME", ime == null ? "" : ime)
                .putExtra("DATADIR", data)
                .putExtra("SDCARD", sdcard)
                .putExtra("EXTERNAL", external);
    }
}
