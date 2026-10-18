package fyi.slot.android;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.provider.Settings;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.List;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the real Android Intent and preferences on the JVM. No emulator process is started. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class RetroArchLauncherTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Context context;
    private RetroArchPreferences preferences;
    private RetroArchLauncher launcher;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("retroarch", 0).edit().clear().commit();
        context.getSharedPreferences("game_cores", 0).edit().clear().commit();
        preferences = new RetroArchPreferences(context);
        launcher = new RetroArchLauncher(context, preferences);
    }

    private void install(String name, boolean enabled) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = name;
        info.applicationInfo.enabled = enabled;
        shadowOf(context.getPackageManager()).installPackage(info);
    }

    private RomRecord rom(Cartridge.Platform platform) throws Exception {
        File file = temp.newFile("Sample " + platform + "." + platform.name().toLowerCase(java.util.Locale.ROOT));
        return new RomRecord(temp.getRoot().getAbsolutePath(), file.getName(), platform,
                0, 0, new byte[512], null, false);
    }

    @Test public void noInstalledPackageHasNoSelection() {
        assertTrue(launcher.installedPackages().isEmpty());
        assertNull(launcher.selectedPackage(launcher.installedPackages()));
    }

    @Test public void only64BitIsSelected() {
        install(RetroArchLauncher.AARCH64, true);
        assertEquals(List.of(RetroArchLauncher.AARCH64), launcher.installedPackages());
        assertEquals(RetroArchLauncher.AARCH64, launcher.selectedPackage(launcher.installedPackages()));
    }

    @Test public void onlyStandardIsSelected() {
        install(RetroArchLauncher.STANDARD, true);
        assertEquals(List.of(RetroArchLauncher.STANDARD), launcher.installedPackages());
        assertEquals(RetroArchLauncher.STANDARD, launcher.selectedPackage(launcher.installedPackages()));
    }

    @Test public void bothPackagesPrefer64BitUntilUserSelectsStandard() {
        install(RetroArchLauncher.STANDARD, true); install(RetroArchLauncher.AARCH64, true);
        assertEquals(RetroArchLauncher.PACKAGES, launcher.installedPackages());
        assertEquals(RetroArchLauncher.AARCH64, launcher.selectedPackage(launcher.installedPackages()));
        preferences.selectPackage(RetroArchLauncher.STANDARD);
        assertEquals(RetroArchLauncher.STANDARD, launcher.selectedPackage(launcher.installedPackages()));
        assertEquals(RetroArchLauncher.STANDARD, new RetroArchPreferences(context).preferredPackage());
    }

    @Test public void unavailablePreferenceFallsBackToInstalledVersion() {
        preferences.selectPackage(RetroArchLauncher.AARCH64);
        install(RetroArchLauncher.STANDARD, true);
        assertEquals(RetroArchLauncher.STANDARD, launcher.selectedPackage(launcher.installedPackages()));
    }

    @Test public void disabledPackageIsNotOffered() {
        install(RetroArchLauncher.AARCH64, false); install(RetroArchLauncher.STANDARD, true);
        assertEquals(List.of(RetroArchLauncher.STANDARD), launcher.installedPackages());
    }

    @Test public void intentContainsCompleteContractForEachPackageAndPlatform() throws Exception {
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, "keyboard/.Ime");
        for (Cartridge.Platform platform : Cartridge.Platform.values()) {
            RomRecord rom = rom(platform);
            for (String name : RetroArchLauncher.PACKAGES) {
                Intent intent = launcher.buildIntent(rom, name);
                String data = "/data/data/" + name;
                String external = "/storage/emulated/0/Android/data/" + name + "/files";
                assertNull(intent.getAction());
                assertNull(intent.getCategories());
                assertEquals(name, intent.getComponent().getPackageName());
                assertEquals(RetroArchLauncher.ACTIVITY, intent.getComponent().getClassName());
                assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP, intent.getFlags());
                assertEquals(7, intent.getExtras().size());
                assertEquals(rom.file().getAbsolutePath(), intent.getStringExtra("ROM"));
                assertEquals(data + "/cores/mgba_libretro_android.so", intent.getStringExtra("LIBRETRO"));
                assertEquals(new File(rom.root, RetroArchConfig.FILENAME).getAbsolutePath(), intent.getStringExtra("CONFIGFILE"));
                assertTrue(new String(java.nio.file.Files.readAllBytes(new File(rom.root, RetroArchConfig.FILENAME).toPath()),
                        java.nio.charset.StandardCharsets.UTF_8)
                        .endsWith("#include \"" + external + "/retroarch.cfg\"\n"));
                assertEquals("keyboard/.Ime", intent.getStringExtra("IME"));
                assertEquals(data, intent.getStringExtra("DATADIR"));
                assertEquals("/storage/emulated/0", intent.getStringExtra("SDCARD"));
                assertEquals(external, intent.getStringExtra("EXTERNAL"));
            }
        }
    }

    @Test public void eachPresetProducesItsOfficialCorePath() throws Exception {
        for (Cartridge.Platform platform : Cartridge.Platform.values()) {
            RomRecord rom = rom(platform);
            for (RetroArchPreferences.Core core : RetroArchPreferences.cores(platform)) {
                preferences.selectCore(platform, core);
                for (String name : RetroArchLauncher.PACKAGES) {
                    assertEquals("/data/data/" + name + "/cores/" + core.id + "_libretro_android.so",
                            launcher.buildIntent(rom, name).getStringExtra("LIBRETRO"));
                }
            }
        }
    }

    @Test public void missingInputMethodUsesEmptyString() throws Exception {
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, null);
        assertEquals("", launcher.buildIntent(rom(Cartridge.Platform.GB), RetroArchLauncher.STANDARD).getStringExtra("IME"));
    }

    @Test public void inaccessibleInputMethodUsesEmptyString() throws Exception {
        Context denied = new ContextWrapper(context) {
            @Override public android.content.ContentResolver getContentResolver() { throw new SecurityException("Denied"); }
        };
        assertEquals("", new RetroArchLauncher(denied, preferences).buildIntent(
                rom(Cartridge.Platform.GB), RetroArchLauncher.STANDARD).getStringExtra("IME"));
    }

    @Test public void missingRomAndDirectoryNeverStartAnActivity() throws Exception {
        RomRecord missing = rom(Cartridge.Platform.GBA);
        assertTrue(missing.file().delete());
        assertEquals(RetroArchLauncher.Result.ROM_MISSING, launcher.launch(missing));
        install(RetroArchLauncher.STANDARD, true);
        assertEquals(RetroArchLauncher.Result.ROM_MISSING, launcher.launch(missing));
        assertTrue(missing.file().mkdir());
        assertEquals(RetroArchLauncher.Result.ROM_MISSING, launcher.launch(missing));
        assertEquals(RetroArchLauncher.Result.ROM_MISSING, launcher.launch(null));
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }

    @Test public void existingRomWithoutRetroArchReturnsNotInstalled() throws Exception {
        assertEquals(RetroArchLauncher.Result.NOT_INSTALLED, launcher.launch(rom(Cartridge.Platform.GBA)));
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }

    @Test public void successfulLaunchUsesSavedPackageAndCore() throws Exception {
        install(RetroArchLauncher.AARCH64, true); install(RetroArchLauncher.STANDARD, true);
        preferences.selectPackage(RetroArchLauncher.STANDARD);
        preferences.selectCore(Cartridge.Platform.GBC, RetroArchPreferences.Core.SAMEBOY);
        RomRecord rom = rom(Cartridge.Platform.GBC);
        assertEquals(RetroArchLauncher.Result.STARTED, launcher.launch(rom));
        Intent started = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertEquals(RetroArchLauncher.STANDARD, started.getComponent().getPackageName());
        assertEquals(rom.file().getAbsolutePath(), started.getStringExtra("ROM"));
        assertTrue(started.getStringExtra("LIBRETRO").endsWith("/sameboy_libretro_android.so"));
    }

    @Test public void missingActivityAndSecurityDenialReturnFailure() throws Exception {
        install(RetroArchLauncher.STANDARD, true);
        RomRecord rom = rom(Cartridge.Platform.GBA);
        for (RuntimeException failure : List.of(new ActivityNotFoundException("Missing"), new SecurityException("Denied"))) {
            Context failing = new ContextWrapper(context) {
                @Override public void startActivity(Intent intent) { throw failure; }
            };
            assertEquals(RetroArchLauncher.Result.FAILED, new RetroArchLauncher(failing, preferences).launch(rom));
        }
    }

    @Test public void launchesAlwaysResolveCurrentGameOverrideOrGlobal() throws Exception {
        install(RetroArchLauncher.AARCH64, true);
        GameCorePreferences games = new GameCorePreferences(context, preferences);
        for (Cartridge.Platform platform : Cartridge.Platform.values()) {
            RomRecord rom = rom(platform);
            RetroArchPreferences.Core override = RetroArchPreferences.cores(platform).get(2);
            games.select(rom, override);
            preferences.selectCore(platform, RetroArchPreferences.cores(platform).get(1));
            for (int launch = 0; launch < 2; launch++) {
                assertEquals(RetroArchLauncher.Result.STARTED, launcher.launch(rom));
                assertTrue(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity()
                        .getStringExtra("LIBRETRO").endsWith("/" + override.id + "_libretro_android.so"));
            }
            games.select(rom, null);
            assertEquals(RetroArchLauncher.Result.STARTED, launcher.launch(rom));
            assertTrue(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity()
                    .getStringExtra("LIBRETRO").endsWith("/" + preferences.core(platform).id + "_libretro_android.so"));
        }
    }

    @Test public void packageQuerySecurityDenialReturnsFailure() throws Exception {
        Context denied = new ContextWrapper(context) {
            @Override public android.content.pm.PackageManager getPackageManager() { throw new SecurityException("Denied"); }
        };
        assertEquals(RetroArchLauncher.Result.FAILED,
                new RetroArchLauncher(denied, preferences).launch(rom(Cartridge.Platform.GBA)));
    }

    @Test public void corePreferencesAreIndependentAndPersistAfterReconstruction() {
        for (Cartridge.Platform platform : Cartridge.Platform.values()) assertEquals(RetroArchPreferences.Core.MGBA, preferences.core(platform));
        preferences.cycleCore(Cartridge.Platform.GBA, 1);
        preferences.cycleCore(Cartridge.Platform.GBC, -1);
        RetroArchPreferences restored = new RetroArchPreferences(context);
        assertEquals(RetroArchPreferences.Core.VBAM, restored.core(Cartridge.Platform.GBA));
        assertEquals(RetroArchPreferences.Core.SAMEBOY, restored.core(Cartridge.Platform.GBC));
        assertEquals(RetroArchPreferences.Core.MGBA, restored.core(Cartridge.Platform.GB));
        preferences.cycleCore(Cartridge.Platform.GBC, 1);
        assertEquals(RetroArchPreferences.Core.MGBA, restored.core(Cartridge.Platform.GBC));
    }

    @Test public void rejectsUnsupportedCoreAndPackage() {
        assertThrows(IllegalArgumentException.class, () -> preferences.selectCore(Cartridge.Platform.GBA, RetroArchPreferences.Core.SAMEBOY));
        assertThrows(IllegalArgumentException.class, () -> preferences.selectCore(Cartridge.Platform.GB, RetroArchPreferences.Core.GPSP));
        assertThrows(IllegalArgumentException.class, () -> preferences.selectPackage("other.app"));
        assertThrows(IllegalArgumentException.class, () -> launcher.buildIntent(null, "other.app"));
    }

    @Test public void followingAllPresetsKeepsOriginalConfigIntent() throws Exception {
        for (RetroArchPreset preset : RetroArchPreset.values()) preferences.select(preset, RetroArchPreset.Option.FOLLOW);
        RomRecord rom = rom(Cartridge.Platform.GBA);
        for (String name : RetroArchLauncher.PACKAGES) {
            assertEquals(RetroArchConfig.originalPath(name), launcher.buildIntent(rom, name).getStringExtra("CONFIGFILE"));
        }
    }

    @Test public void configWriteFailureStillLaunchesWithOriginalPath() throws Exception {
        install(RetroArchLauncher.AARCH64, true);
        RomRecord rom = rom(Cartridge.Platform.GBA);
        assertTrue(new File(rom.root, RetroArchConfig.FILENAME).mkdir());
        assertTrue(new File(rom.root, RetroArchConfig.FILENAME + "/keep").createNewFile());
        assertEquals(RetroArchLauncher.Result.STARTED, launcher.launch(rom));
        assertEquals(RetroArchConfig.originalPath(RetroArchLauncher.AARCH64),
                shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity().getStringExtra("CONFIGFILE"));
    }

    @Test public void openRetroArchUsesSelectedMainMenuWithoutGameOrConfigExtras() {
        install(RetroArchLauncher.AARCH64, true); install(RetroArchLauncher.STANDARD, true);
        for (String name : RetroArchLauncher.PACKAGES) {
            preferences.selectPackage(name);
            assertEquals(RetroArchLauncher.Result.STARTED, launcher.open());
            Intent intent = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
            assertEquals(name, intent.getComponent().getPackageName());
            assertEquals(RetroArchLauncher.MENU_ACTIVITY, intent.getComponent().getClassName());
            assertNull(intent.getAction()); assertNull(intent.getCategories()); assertNull(intent.getExtras());
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP, intent.getFlags());
        }
    }

    @Test public void openRetroArchWithoutInstallationReturnsNotInstalled() {
        assertEquals(RetroArchLauncher.Result.NOT_INSTALLED, launcher.open());
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }

    @Test public void openRetroArchHandlesMissingActivityAndSecurityDenial() {
        install(RetroArchLauncher.STANDARD, true);
        for (RuntimeException failure : List.of(new ActivityNotFoundException("Missing"), new SecurityException("Denied"))) {
            Context failing = new ContextWrapper(context) {
                @Override public void startActivity(Intent intent) { throw failure; }
            };
            assertEquals(RetroArchLauncher.Result.FAILED, new RetroArchLauncher(failing, preferences).open());
        }
        Context denied = new ContextWrapper(context) {
            @Override public android.content.pm.PackageManager getPackageManager() { throw new SecurityException("Denied"); }
        };
        assertEquals(RetroArchLauncher.Result.FAILED, new RetroArchLauncher(denied, preferences).open());
    }
}
