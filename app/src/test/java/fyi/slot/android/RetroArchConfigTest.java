package fyi.slot.android;

import android.content.Context;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import static org.junit.Assert.*;
import static fyi.slot.android.RetroArchPreset.*;
import static fyi.slot.android.RetroArchPreset.Option.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class RetroArchConfigTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Context context;
    private RetroArchPreferences preferences;
    private RetroArchConfig config;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("retroarch", 0).edit().clear().commit();
        preferences = new RetroArchPreferences(context);
        config = new RetroArchConfig(preferences);
    }

    private RomRecord rom(File root) {
        return new RomRecord(root.getAbsolutePath(), "GBA/Sample.gba", Cartridge.Platform.GBA,
                0, 0, new byte[512], null, false);
    }

    private void followAll() {
        for (RetroArchPreset preset : RetroArchPreset.values()) preferences.select(preset, FOLLOW);
    }

    private String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test public void defaultsHaveOnlyTheApprovedOverridesBeforeTheInclude() {
        assertEquals("video_driver = \"vulkan\"\nquit_on_close_content = \"1\"\n"
                + "input_menu_toggle_gamepad_combo = \"4\"\ninput_overlay_hide_when_gamepad_connected = \"true\"\n"
                + "config_save_on_exit = \"false\"\n#include \"" + RetroArchConfig.originalPath(RetroArchLauncher.AARCH64) + "\"\n",
                config.content(RetroArchLauncher.AARCH64));
    }

    @Test public void eachFollowingPresetOmitsAllItsKeys() {
        for (RetroArchPreset preset : RetroArchPreset.values()) {
            preferences.select(preset, FOLLOW);
            String text = config.content(RetroArchLauncher.STANDARD);
            for (String key : preset.keys) assertFalse(text.contains(key + " ="));
        }
        assertEquals("", config.content(RetroArchLauncher.STANDARD));
    }

    @Test public void eachOptionHasTheExactRetroArchValue() {
        followAll();
        for (RetroArchPreset preset : RetroArchPreset.values()) {
            for (Option option : preset.options) {
                preferences.select(preset, option);
                String text = config.content(RetroArchLauncher.STANDARD);
                for (String key : preset.keys) {
                    if (option == FOLLOW) assertFalse(text.contains(key));
                    else assertTrue(text.contains(key + " = \"" + option.value + "\"\n"));
                }
            }
            preferences.select(preset, FOLLOW);
        }
    }

    @Test public void everyExplicitComboConflictKeepsMenuAndOmitsQuit() {
        for (Option option : MENU_COMBO.options) {
            if (option == FOLLOW) continue;
            preferences.select(MENU_COMBO, option); preferences.select(QUIT_COMBO, option);
            String text = config.content(RetroArchLauncher.AARCH64);
            assertTrue(text.contains("input_menu_toggle_gamepad_combo = \"" + option.value + "\""));
            assertFalse(text.contains("input_quit_gamepad_combo"));
            assertEquals(option, preferences.option(QUIT_COMBO));
        }
    }

    @Test public void nonConflictingOrInheritedMenuDoesNotSuppressQuit() {
        preferences.select(QUIT_COMBO, STICKS);
        assertTrue(config.content(RetroArchLauncher.AARCH64).contains("input_quit_gamepad_combo = \"2\""));
        preferences.select(MENU_COMBO, FOLLOW);
        assertTrue(config.content(RetroArchLauncher.AARCH64).contains("input_quit_gamepad_combo = \"2\""));
    }

    @Test public void allFollowingUsesOriginalWithoutCreatingOrChangingWrapper() throws Exception {
        File wrapper = new File(config.resolve(rom(temp.getRoot()), RetroArchLauncher.AARCH64));
        byte[] original = Files.readAllBytes(wrapper.toPath());
        followAll();
        assertEquals(RetroArchConfig.originalPath(RetroArchLauncher.AARCH64), config.resolve(rom(temp.getRoot()), RetroArchLauncher.AARCH64));
        assertArrayEquals(original, Files.readAllBytes(wrapper.toPath()));
        File emptyRoot = temp.newFolder();
        assertEquals(RetroArchConfig.originalPath(RetroArchLauncher.STANDARD), config.resolve(rom(emptyRoot), RetroArchLauncher.STANDARD));
        assertEquals(0, emptyRoot.list().length);
    }

    @Test public void wrapperLivesAtLibraryRootAndIsUtf8() throws Exception {
        File root = temp.newFolder("\u6E38\u620F\u5E93 with spaces");
        File wrapper = new File(config.resolve(rom(root), RetroArchLauncher.AARCH64));
        assertEquals(new File(root, RetroArchConfig.FILENAME), wrapper);
        assertEquals(config.content(RetroArchLauncher.AARCH64), read(wrapper));
        assertArrayEquals(new String[]{RetroArchConfig.FILENAME}, root.list());
    }

    @Test public void unchangedContentDoesNotWriteAgain() throws Exception {
        RomRecord rom = rom(temp.getRoot());
        File wrapper = new File(config.resolve(rom, RetroArchLauncher.AARCH64));
        FileTime old = FileTime.fromMillis(1000000000L);
        Files.setLastModifiedTime(wrapper.toPath(), old);
        assertEquals(wrapper.getAbsolutePath(), config.resolve(rom, RetroArchLauncher.AARCH64));
        assertEquals(old, Files.getLastModifiedTime(wrapper.toPath()));
    }

    @Test public void preferencesAndSelectedPackageRegenerateTheWrapper() throws Exception {
        RomRecord rom = rom(temp.getRoot());
        File wrapper = new File(config.resolve(rom, RetroArchLauncher.AARCH64));
        preferences.select(VIDEO, GLCORE);
        assertEquals(wrapper.getAbsolutePath(), config.resolve(rom, RetroArchLauncher.STANDARD));
        assertEquals(config.content(RetroArchLauncher.STANDARD), read(wrapper));
        assertFalse(read(wrapper).contains(RetroArchLauncher.AARCH64));
    }

    @Test public void differentRootsHaveIndependentWrappers() throws Exception {
        File first = new File(config.resolve(rom(temp.newFolder()), RetroArchLauncher.AARCH64));
        byte[] before = Files.readAllBytes(first.toPath());
        preferences.select(VIDEO, GL);
        File second = new File(config.resolve(rom(temp.newFolder()), RetroArchLauncher.STANDARD));
        assertNotEquals(first, second);
        assertArrayEquals(before, Files.readAllBytes(first.toPath()));
    }

    @Test public void missingRootAndFileInsteadOfRootFallBackWithoutCreatingDirectories() throws Exception {
        File absent = new File(temp.getRoot(), "absent");
        for (File root : new File[]{absent, temp.newFile()}) {
            assertEquals(RetroArchConfig.originalPath(RetroArchLauncher.AARCH64), config.resolve(rom(root), RetroArchLauncher.AARCH64));
        }
        assertFalse(absent.exists());
    }

    @Test public void failedReplacementFallsBackAndCleansOnlyItsTemporaryFile() throws Exception {
        File blocked = new File(temp.getRoot(), RetroArchConfig.FILENAME);
        assertTrue(blocked.mkdir());
        File ownedByUser = new File(blocked, "keep");
        Files.write(ownedByUser.toPath(), "keep".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(RetroArchConfig.originalPath(RetroArchLauncher.STANDARD), config.resolve(rom(temp.getRoot()), RetroArchLauncher.STANDARD));
        assertEquals("keep", read(ownedByUser));
        assertArrayEquals(new String[]{RetroArchConfig.FILENAME}, temp.getRoot().list());
    }

    @Test public void sharedStorageAssetsLiveAtTheVolumeRoot() throws Exception {
        File volume = new File("/storage/emulated/0").getCanonicalFile();
        File root = new File(volume, "Emulation/Roms");
        assertEquals(volume, LibraryPaths.volumeRoot(root));
        assertEquals(new File(volume, "Slot"), LibraryPaths.assetsRoot(root));
        assertEquals(volume, LibraryPaths.volumeRoot(new File(volume, "GBA")));
    }

    @Test public void missingRomsRootDoesNotCreateAssetDirectory() throws Exception {
        File volume = temp.newFolder();
        File root = new File(volume, "Roms");
        assertEquals(RetroArchConfig.originalPath(RetroArchLauncher.AARCH64),
                config.resolve(rom(root), RetroArchLauncher.AARCH64));
        assertEquals(0, volume.list().length);
    }

    @Test public void presetChoicesCycleInBothDirectionsAndPersist() {
        for (RetroArchPreset preset : RetroArchPreset.values()) {
            assertEquals(preset.options.get(0), preferences.option(preset));
            preferences.cycle(preset, -1);
            assertEquals(preset.options.get(preset.options.size() - 1), new RetroArchPreferences(context).option(preset));
            for (int step = 0; step < preset.options.size(); step++) {
                preferences.cycle(preset, 1);
                assertEquals(preset.options.get(step), new RetroArchPreferences(context).option(preset));
            }
        }
    }

    @Test public void invalidSelectionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> preferences.select(VIDEO, ON));
        assertThrows(IllegalArgumentException.class, () -> config.content("other.package"));
    }
}
