package fyi.slot.android;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class RetroArchSettingsTest {
    private Context context;
    private AppLanguage language;
    private RetroArchPreferences preferences;
    private RetroArchSettings settings;
    private SettingsPanel panel;
    private final AtomicReference<List<String>> packages = new AtomicReference<>(List.of());
    private final AtomicBoolean failed = new AtomicBoolean();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("retroarch", 0).edit().clear().commit();
        context.getSharedPreferences("interaction", 0).edit().clear().commit();
        new InteractionPreferences(context);
        language = new AppLanguage(context);
        language.select(AppLanguage.Choice.ENGLISH);
        createPanel();
    }

    private void createPanel() {
        preferences = new RetroArchPreferences(context);
        settings = new RetroArchSettings(language, preferences, new RetroArchLauncher(context, preferences), () -> {
            if (failed.get()) throw new SecurityException("Denied");
            return packages.get();
        });
        panel = new SettingsPanel(context, language, List.of(settings.group()));
        settings.setListener(panel::refresh);
    }

    @After public void tearDown() { settings.release(); }

    private String value(String id) {
        String display = ((TextView) ((LinearLayout) panel.rowView(id)).getChildAt(1)).getText().toString();
        assertTrue("Choice affordances: " + display, display.startsWith("‹ ") && display.endsWith(" ›"));
        return display.substring(2, display.length() - 2);
    }

    private int rowIndex(String id) {
        List<SettingsPanel.Row> rows = settings.group().rows;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(id)) return i;
        throw new AssertionError("Missing row: " + id);
    }

    private void selectRow(String id) {
        int target = rowIndex(id);
        for (int step = 0; step < panel.rowCount() && panel.selectedRow() != target; step++)
            key(panel.selectedRow() < target ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(target, panel.selectedRow());
    }

    private void key(int code) { panel.handleKey(code, new KeyEvent(KeyEvent.ACTION_DOWN, code)); }

    private void awaitValue(String expected) throws Exception {
        long limit = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!expected.equals(value("retroarch_app")) && System.nanoTime() < limit) {
            Thread.sleep(5);
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertEquals(expected, value("retroarch_app"));
    }

    @Test public void loadingAndEmptyAvailabilityAreVisibleInBothLanguages() throws Exception {
        for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH)) {
            language.select(choice);
            settings.reload();
            assertEquals(language.text(R.string.retroarch_loading), value("retroarch_app"));
            awaitValue(language.text(R.string.retroarch_not_installed));
            for (Cartridge.Platform platform : Cartridge.Platform.values())
                assertEquals("mGBA", value("core_" + platform.name()));
        }
    }

    @Test public void bothVersionsCycleByTouchAndPersist() throws Exception {
        packages.set(RetroArchLauncher.PACKAGES);
        settings.reload(); awaitValue("64-bit");
        assertTrue(panel.rowView("retroarch_app").performClick());
        assertEquals("Standard", value("retroarch_app"));
        assertEquals(RetroArchLauncher.STANDARD, new RetroArchPreferences(context).preferredPackage());
        panel.rowView("retroarch_app").performClick();
        assertEquals("64-bit", value("retroarch_app"));
    }

    @Test public void singleVersionAndRemovedPreferenceUseAvailablePackage() throws Exception {
        preferences.selectPackage(RetroArchLauncher.AARCH64);
        packages.set(List.of(RetroArchLauncher.STANDARD));
        settings.reload(); awaitValue("Standard");
        panel.rowView("retroarch_app").performClick();
        assertEquals("Standard", value("retroarch_app"));
        assertEquals(RetroArchLauncher.AARCH64, preferences.preferredPackage());
    }

    @Test public void queryFailureCanRetryFromApplicationRow() throws Exception {
        for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH)) {
            language.select(choice);
            failed.set(true); settings.reload();
            awaitValue(language.text(R.string.retroarch_query_failed));
            failed.set(false); packages.set(RetroArchLauncher.PACKAGES);
            panel.rowView("retroarch_app").performClick();
            assertEquals(language.text(R.string.retroarch_loading), value("retroarch_app"));
            awaitValue(language.text(R.string.retroarch_64bit));
        }
    }

    @Test public void emptyApplicationRowCanDetectNewInstallation() throws Exception {
        settings.reload(); awaitValue("Not installed");
        packages.set(List.of(RetroArchLauncher.STANDARD));
        panel.rowView("retroarch_app").performClick(); awaitValue("Standard");
    }

    @Test public void touchCyclesEveryPlatformThroughOnlyItsPresets() {
        Cartridge.Platform[] platforms = {Cartridge.Platform.GBA, Cartridge.Platform.GBC, Cartridge.Platform.GB};
        for (int i = 0; i < platforms.length; i++) {
            List<RetroArchPreferences.Core> cores = RetroArchPreferences.cores(platforms[i]);
            for (int step = 1; step <= cores.size(); step++) {
                panel.rowView("core_" + platforms[i].name()).performClick();
                assertEquals(cores.get(step % cores.size()).label, value("core_" + platforms[i].name()));
                assertEquals(cores.get(step % cores.size()), new RetroArchPreferences(context).core(platforms[i]));
            }
        }
    }

    @Test public void directionKeysConfirmAndRepeatFollowExistingSettingsConvention() {
        panel.reset(true);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFalse(panel.isInRows());
        key(KeyEvent.KEYCODE_ENTER);
        assertTrue(panel.isInRows());
        for (Cartridge.Platform platform : Cartridge.Platform.values()) {
            String id = "core_" + platform.name();
            selectRow(id);
            key(KeyEvent.KEYCODE_BUTTON_A);
            String selected = value(id);
            assertNotEquals("mGBA", selected);
            panel.handleKey(KeyEvent.KEYCODE_BUTTON_A,
                    new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 2));
            assertEquals(selected, value(id));
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            assertNotEquals(selected, value(id));
            key(KeyEvent.KEYCODE_DPAD_LEFT);
            assertEquals(selected, value(id));
        }
        assertTrue(panel.isInRows());
        assertTrue(panel.back());
        assertFalse(panel.isInRows());
    }

    @Test public void rebuiltPanelKeepsCorePackageAndSelection() throws Exception {
        packages.set(RetroArchLauncher.PACKAGES);
        settings.reload(); awaitValue("64-bit");
        panel.rowView("retroarch_app").performClick();
        panel.reset(true); key(KeyEvent.KEYCODE_ENTER);
        selectRow("core_GBA"); key(KeyEvent.KEYCODE_BUTTON_A);
        Bundle saved = new Bundle(); panel.save(saved);
        settings.release(); createPanel(); panel.restore(saved);
        settings.reload(); awaitValue("Standard");
        assertEquals("VBA-M", value("core_GBA"));
        assertEquals(rowIndex("core_GBA"), panel.selectedRow());
        assertTrue(panel.isKeyMode() && panel.isInRows());
    }

    @Test public void releasedSettingsIgnoreQueuedResults() throws Exception {
        settings.reload(); settings.release();
        Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Checking…", value("retroarch_app"));
        settings.reload();
        assertEquals("Checking…", value("retroarch_app"));
    }

    @Test public void touchCyclesAllLaunchPresetsInBothLanguagesAndPersists() {
        assertEquals(11, panel.rowCount());
        for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH)) {
            language.select(choice); panel.refresh();
            for (RetroArchPreset preset : RetroArchPreset.values()) {
                String id = "preset_" + preset.id;
                for (int step = 1; step <= preset.options.size(); step++) {
                    panel.rowView(id).performClick();
                    RetroArchPreset.Option expected = preset.options.get(step % preset.options.size());
                    assertEquals(expected.label(language), value(id));
                    assertEquals(expected, new RetroArchPreferences(context).option(preset));
                }
            }
        }
    }

    @Test public void keysReachAndCycleEveryPresetAndRestoreLastRow() {
        panel.reset(true); key(KeyEvent.KEYCODE_ENTER);
        for (RetroArchPreset preset : RetroArchPreset.values()) {
            selectRow("preset_" + preset.id);
            key(KeyEvent.KEYCODE_ENTER);
            assertEquals(preset.options.get(1), preferences.option(preset));
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            assertEquals(preset.options.get(2 % preset.options.size()), preferences.option(preset));
            key(KeyEvent.KEYCODE_DPAD_LEFT);
            panel.handleKey(KeyEvent.KEYCODE_BUTTON_A, new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 2));
            assertEquals(preset.options.get(1), preferences.option(preset));
        }
        Bundle state = new Bundle(); panel.save(state);
        settings.release(); createPanel(); panel.restore(state);
        assertEquals(panel.rowCount() - 1, panel.selectedRow()); assertTrue(panel.isInRows());
        for (RetroArchPreset preset : RetroArchPreset.values()) assertEquals(preset.options.get(1), preferences.option(preset));
    }

    @Test public void openEntryShowsMissingAndFailureMessages() {
        java.util.concurrent.atomic.AtomicInteger message = new java.util.concurrent.atomic.AtomicInteger();
        settings.setMessageListener(message::set);
        panel.rowView("retroarch_open").performClick();
        assertEquals(R.string.launch_retroarch_missing, message.get());
        android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
        info.packageName = RetroArchLauncher.AARCH64;
        info.applicationInfo = new android.content.pm.ApplicationInfo();
        info.applicationInfo.packageName = info.packageName; info.applicationInfo.enabled = true;
        shadowOf(context.getPackageManager()).installPackage(info);
        Context denied = new android.content.ContextWrapper(context) {
            @Override public void startActivity(android.content.Intent intent) { throw new SecurityException("Denied"); }
        };
        RetroArchSettings failing = new RetroArchSettings(language, preferences, new RetroArchLauncher(denied, preferences));
        try {
            failing.setMessageListener(message::set);
            new SettingsPanel(context, language, List.of(failing.group())).rowView("retroarch_open").performClick();
            assertEquals(R.string.launch_retroarch_failed, message.get());
        } finally { failing.release(); }
    }

    @Test public void allBilingualPresetOptionsFitLandscapeAndSquareLayouts() {
        for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH)) {
            language.select(choice);
            for (int[] size : new int[][]{{960, 640}, {960, 960}, {1024, 768}, {1280, 720}}) {
                HomeLayout layout = HomeLayout.of(size[0], size[1], 0, 0, 0, 0, 1);
                panel.applyLayout(layout);
                for (RetroArchPreset preset : RetroArchPreset.values()) {
                    for (RetroArchPreset.Option option : preset.options) {
                        preferences.select(preset, option); panel.refresh();
                        int width = Math.round(layout.px(layout.bodyRight() - layout.bodyLeft()));
                        panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                                android.view.View.MeasureSpec.makeMeasureSpec(size[1] / 2, android.view.View.MeasureSpec.EXACTLY));
                        panel.layout(0, 0, width, size[1] / 2);
                        for (int row = 0; row < panel.rowCount(); row++) {
                            LinearLayout view = (LinearLayout) panel.rowView(row);
                            for (int child = 0; child < view.getChildCount(); child++) {
                                TextView text = (TextView) view.getChildAt(child);
                                assertNotNull(text.getLayout());
                                assertEquals(choice + " " + size[0] + "x" + size[1] + ": " + text.getText(), 0, text.getLayout().getEllipsisCount(0));
                                assertTrue(text.getText().toString(), text.getWidth() >= text.getPaint().measureText(text.getText().toString()) - 1);
                            }
                        }
                    }
                }
            }
        }
    }
}
