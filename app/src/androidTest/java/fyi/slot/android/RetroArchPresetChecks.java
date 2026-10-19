package fyi.slot.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in launch-preset checks using owned files and intercepted emulator activities. */
final class RetroArchPresetChecks {
    static final int COUNT = 7;
    private final InteractionChecks t;

    RetroArchPresetChecks(InteractionChecks checks) { t = checks; }

    void run() {
        SharedPreferences storage = t.getTargetContext().getSharedPreferences("retroarch", 0);
        Map<String, ?> original = storage.getAll();
        AppLanguage.Choice originalLanguage = t.view().language().choice();
        File root = new File(t.getTargetContext().getFilesDir(), "retroarch-presets-" + System.nanoTime());
        t.require(root.mkdirs(), "Create owned configuration directory");
        try {
            t.runOnMainSync(() -> storage.edit().clear().commit());
            t.check(228, "launchPresetsTouchCyclesAndPersistsEveryOption", () -> {
                open(false);
                for (RetroArchPreset preset : RetroArchPreset.values()) {
                    int row = row("preset_" + preset.id);
                    for (int step = 1; step <= preset.options.size(); step++) {
                        tapRow(row);
                        RetroArchPreset.Option expected = preset.options.get(step % preset.options.size());
                        t.require(preferences().option(preset) == expected, "Touch must persist preset");
                        t.require(("‹ " + expected.label(t.view().language()) + " ›").equals(text(row, 1)), "Visible option must match preference");
                    }
                }
            });
            t.check(229, "launchPresetsKeysReachAllRowsAndIgnoreRepeat", () -> {
                for (RetroArchPreset preset : RetroArchPreset.values()) preferences().select(preset, preset.options.get(0));
                open(true); t.key(KeyEvent.KEYCODE_ENTER);
                for (RetroArchPreset preset : RetroArchPreset.values()) {
                    selectRow("preset_" + preset.id);
                    t.key(KeyEvent.KEYCODE_ENTER);
                    RetroArchPreset.Option selected = preferences().option(preset);
                    t.runOnMainSync(() -> t.home().dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 2)));
                    t.require(preferences().option(preset) == selected, "Repeat must keep the option");
                    t.key(KeyEvent.KEYCODE_DPAD_RIGHT);
                    t.require(preferences().option(preset) != selected, "Right adjusts the preset");
                    t.key(KeyEvent.KEYCODE_DPAD_LEFT);
                    t.require(preferences().option(preset) == selected && panel().isInRows(), "Left restores the preset without leaving rows");
                }
            });
            t.check(230, "launchPresetsSurviveActivityRecreation", () -> {
                open(true); t.key(KeyEvent.KEYCODE_ENTER);
                for (RetroArchPreset preset : RetroArchPreset.values()) preferences().select(preset, preset.options.get(1));
                selectRow("preset_" + RetroArchPreset.values()[RetroArchPreset.values().length - 1].id);
                t.recreateActivity();
                t.require(t.view().isSettingsOpen() && RetroArchSettings.GROUP.equals(panel().groupId()), "Emulator panel must restore");
                t.require(panel().isInRows() && panel().selectedRow() == 10, "Last preset row must restore");
                for (RetroArchPreset preset : RetroArchPreset.values()) {
                    t.require(preferences().option(preset) == preset.options.get(1), "Preset must persist after recreation");
                }
            });
            t.check(231, "launchPresetsBilingualOptionsFitLandscapeAndSquare", () -> t.runOnMainSync(() -> {
                RetroArchPreferences preferences = preferences();
                RetroArchSettings settings = new RetroArchSettings(t.view().language(), preferences,
                        new RetroArchLauncher(t.activity(), preferences));
                try {
                    SettingsPanel panel = new SettingsPanel(t.activity(), t.view().language(), List.of(settings.group()));
                    for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH)) {
                        t.view().language().select(choice);
                        for (int[] size : new int[][]{{960, 640}, {960, 960}, {1024, 768}, {1280, 720}}) {
                            HomeLayout layout = HomeLayout.of(size[0], size[1], 0, 0, 0, 0, 1);
                            panel.applyLayout(layout);
                            for (RetroArchPreset preset : RetroArchPreset.values()) {
                                for (RetroArchPreset.Option option : preset.options) {
                                    preferences.select(preset, option); panel.refresh();
                                    int width = Math.round(layout.px(layout.bodyRight() - layout.bodyLeft()));
                                    panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                            View.MeasureSpec.makeMeasureSpec(size[1] / 2, View.MeasureSpec.EXACTLY));
                                    panel.layout(0, 0, width, size[1] / 2);
                                    for (int row = 0; row < panel.rowCount(); row++) {
                                        LinearLayout view = (LinearLayout) panel.rowView(row);
                                        for (int child = 0; child < view.getChildCount(); child++) {
                                            TextView text = (TextView) view.getChildAt(child);
                                            t.require(text.getLayout() != null && text.getLayout().getEllipsisCount(0) == 0,
                                                    "Preset must fit " + size[0] + "x" + size[1] + " " + choice + ": " + text.getText());
                                            t.require(text.getWidth() >= text.getPaint().measureText(text.getText().toString()) - 1, "Preset must not clip");
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally { settings.release(); }
                t.home().onLanguageChanged();
            }));
            t.check(232, "launchWrapperWritesDoNotRescanLibrary", () -> {
                AtomicInteger changes = new AtomicInteger();
                LibraryWatcher watcher = new LibraryWatcher(new Handler(Looper.getMainLooper()), platforms -> changes.incrementAndGet());
                t.runOnMainSync(() -> watcher.start(root));
                try {
                    write(new File(root, "watcher-control"), "control");
                    long limit = SystemClock.uptimeMillis() + 5000;
                    while (changes.get() == 0 && SystemClock.uptimeMillis() < limit) t.settle(50);
                    t.require(changes.get() > 0, "Visible control file must prove watcher active");
                    t.settle(1000); changes.set(0);
                    RetroArchPreferences preferences = preferences();
                    RetroArchConfig config = new RetroArchConfig(preferences);
                    preferences.select(RetroArchPreset.VIDEO, RetroArchPreset.Option.VULKAN);
                    String resolved = config.resolve(rom(root), RetroArchLauncher.AARCH64);
                    t.require(canonical(new File(resolved)).equals(canonical(new File(root, RetroArchConfig.FILENAME))),
                            "Wrapper must be created in owned root: " + resolved + " / " + root);
                    preferences.select(RetroArchPreset.VIDEO, RetroArchPreset.Option.GLCORE);
                    config.resolve(rom(root), RetroArchLauncher.STANDARD);
                    config.resolve(rom(root), RetroArchLauncher.STANDARD);
                    t.settle(1600);
                    t.require(changes.get() == 0, "Hidden wrapper, replacements and temporary files must not trigger rescan");
                } finally { t.runOnMainSync(watcher::stop); }
            });
            t.check(233, "launchWrapperContentAndFallbackOnDevice", () -> {
                RetroArchPreferences preferences = preferences();
                RetroArchConfig config = new RetroArchConfig(preferences);
                preferences.select(RetroArchPreset.MENU_COMBO, RetroArchPreset.Option.START_SELECT);
                preferences.select(RetroArchPreset.QUIT_COMBO, RetroArchPreset.Option.START_SELECT);
                File wrapper = new File(config.resolve(rom(root), RetroArchLauncher.AARCH64));
                t.require(read(wrapper).equals(config.content(RetroArchLauncher.AARCH64)), "Device wrapper content must match");
                t.require(!read(wrapper).contains("input_quit_gamepad_combo"), "Conflicting quit must be omitted");
                long modified = wrapper.lastModified();
                t.settle(1100); config.resolve(rom(root), RetroArchLauncher.AARCH64);
                t.require(wrapper.lastModified() == modified, "Unchanged wrapper must not be written again");
                File absent = new File(root, "missing-root");
                t.require(config.resolve(rom(absent), RetroArchLauncher.STANDARD).equals(RetroArchConfig.originalPath(RetroArchLauncher.STANDARD)), "Write failure must use original path");
                t.require(!absent.exists(), "Missing library must not be recreated");
                for (RetroArchPreset preset : RetroArchPreset.values()) preferences.select(preset, RetroArchPreset.Option.FOLLOW);
                t.require(config.resolve(rom(root), RetroArchLauncher.AARCH64).equals(RetroArchConfig.originalPath(RetroArchLauncher.AARCH64)), "All following must use original path");
            });
            t.check(234, "openRetroArchEntryDispatchesMainMenuWithoutOverrides", () -> {
                open(true);
                RetroArchLauncher launcher = new RetroArchLauncher(t.activity(), preferences());
                String selected = launcher.selectedPackage(launcher.installedPackages());
                if (selected == null) {
                    t.note("Main menu dispatch skipped: requires installed RetroArch; missing and failure feedback have JVM coverage");
                    return;
                }
                Intent[] started = new Intent[1];
                Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
                    @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                        if (intent.getComponent() == null || !RetroArchLauncher.PACKAGES.contains(intent.getComponent().getPackageName())) return null;
                        started[0] = new Intent(intent);
                        return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                    }
                };
                t.addMonitor(monitor);
                try {
                    t.key(KeyEvent.KEYCODE_ENTER);
                    selectRow("retroarch_open");
                    t.key(KeyEvent.KEYCODE_ENTER);
                    t.require(started[0] != null && started[0].getComponent().getPackageName().equals(selected), "Entry must open selected application");
                    t.require(started[0].getComponent().getClassName().equals(RetroArchLauncher.MENU_ACTIVITY) && started[0].getExtras() == null,
                            "Menu must receive no ROM or launch configuration overrides");
                } finally { t.removeMonitor(monitor); }
            });
        } finally {
            t.runOnMainSync(() -> {
                SharedPreferences.Editor editor = storage.edit().clear();
                original.forEach((key, value) -> editor.putString(key, (String) value));
                editor.commit();
                t.view().language().select(originalLanguage); t.home().onLanguageChanged(); panel().refresh();
                t.getTargetContext().getSharedPreferences("language", 0).edit().putString("choice", originalLanguage.name()).commit();
            });
            deleteOwned(root);
            t.reset(1, false);
        }
    }

    private void open(boolean viaKey) {
        t.reset(1, false); t.key(KeyEvent.KEYCODE_BUTTON_Y); t.settle(550);
        t.runOnMainSync(() -> { panel().reset(viaKey); panel().showGroup(RetroArchSettings.GROUP); });
        t.settle(100);
    }

    private SettingsPanel panel() { return t.home().settingsPanel(); }
    private int row(String id) {
        for (int i = 0; i < panel().rowCount(); i++) if (panel().rowView(i) == panel().rowView(id)) return i;
        throw new AssertionError("Missing setting " + id);
    }
    private void selectRow(String id) {
        int target = row(id);
        for (int step = 0; step < panel().rowCount() && panel().selectedRow() != target; step++)
            t.key(panel().selectedRow() < target ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
        t.require(panel().selectedRow() == target, "Select setting " + id);
    }
    private void tapRow(int row) {
        t.runOnMainSync(() -> panel().scroller().scrollTo(0, panel().rowView(row).getTop()));
        t.settle(100); t.tapSettingsRow(row);
    }
    private RetroArchPreferences preferences() { return new RetroArchPreferences(t.getTargetContext()); }
    private RomRecord rom(File root) { return new RomRecord(root.getAbsolutePath(), "GBA/Sample.gba", Cartridge.Platform.GBA, 0, 0, new byte[512], null, false); }
    private String text(int row, int child) {
        String[] result = new String[1];
        t.runOnMainSync(() -> result[0] = ((TextView) ((LinearLayout) panel().rowView(row)).getChildAt(child)).getText().toString());
        return result[0];
    }
    private void write(File file, String content) {
        try { Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException error) { throw new AssertionError(error); }
    }
    private String canonical(File file) {
        try { return file.getCanonicalPath(); }
        catch (IOException error) { throw new AssertionError(error); }
    }
    private String read(File file) {
        try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
        catch (IOException error) { throw new AssertionError(error); }
    }
    private void deleteOwned(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteOwned(child);
        t.require(file.delete(), "Remove owned configuration fixture " + file);
    }
}
