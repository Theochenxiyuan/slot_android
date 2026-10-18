package fyi.slot.android;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Map;

/** Stage 5 settings checks; does not launch RetroArch or alter installed applications. */
final class RetroArchSettingsChecks {
    static final int COUNT = 5;
    private final InteractionChecks t;

    RetroArchSettingsChecks(InteractionChecks checks) { t = checks; }

    void run() {
        SharedPreferences storage = t.getTargetContext().getSharedPreferences("retroarch", 0);
        Map<String, ?> original = storage.getAll();
        AppLanguage.Choice originalLanguage = t.view().language().choice();
        try {
            t.runOnMainSync(() -> storage.edit().clear().commit());
            t.check(203, "retroArchApplicationReflectsInstalledVersions", () -> {
                open(false);
                awaitAvailability();
                RetroArchPreferences preferences = preferences();
                RetroArchLauncher launcher = new RetroArchLauncher(t.getTargetContext(), preferences);
                List<String> installed = launcher.installedPackages();
                String selected = launcher.selectedPackage(installed);
                int expected = selected == null ? R.string.retroarch_not_installed
                        : RetroArchLauncher.AARCH64.equals(selected) ? R.string.retroarch_64bit : R.string.retroarch_standard;
                t.require(value(0).equals(t.view().language().text(expected)), "Application row must match installed versions");
                if (installed.size() == 2) {
                    t.tapSettingsRow(0);
                    t.require(RetroArchLauncher.STANDARD.equals(preferences.preferredPackage()), "Touch must select standard version");
                    t.tapSettingsRow(0);
                    t.require(RetroArchLauncher.AARCH64.equals(preferences.preferredPackage()), "Touch must cycle back to 64-bit version");
                } else t.note("Package switching skipped: requires both RetroArch versions installed");
            });
            t.check(204, "retroArchTouchCyclesEachPlatformCore", () -> {
                open(false);
                Cartridge.Platform[] platforms = {Cartridge.Platform.GBA, Cartridge.Platform.GBC, Cartridge.Platform.GB};
                for (Cartridge.Platform platform : platforms) {
                    int row = row("core_" + platform.name());
                    List<RetroArchPreferences.Core> choices = RetroArchPreferences.cores(platform);
                    for (int step = 1; step <= choices.size(); step++) {
                        t.tapSettingsRow(row);
                        RetroArchPreferences.Core expected = choices.get(step % choices.size());
                        t.require(preferences().core(platform) == expected && value(row).equals(expected.label),
                                "Touch must cycle and save only this platform's presets");
                    }
                }
            });
            t.check(205, "retroArchKeysConfirmCycleAndIgnoreRepeat", () -> {
                open(true); t.key(KeyEvent.KEYCODE_ENTER);
                t.require(settings().isInRows(), "Confirm must enter rows");
                for (Cartridge.Platform platform : Cartridge.Platform.values()) {
                    selectRow("core_" + platform.name());
                    int row = settings().selectedRow();
                    t.key(KeyEvent.KEYCODE_BUTTON_A);
                    String selected = value(row);
                    t.require(!"mGBA".equals(selected), "A must cycle core");
                    t.runOnMainSync(() -> t.home().dispatchKeyEvent(
                            new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 2)));
                    t.require(selected.equals(value(row)), "Auto-repeat must not cycle core");
                    t.key(KeyEvent.KEYCODE_ENTER);
                    t.require(!selected.equals(value(row)), "Enter must cycle core");
                }
                String selected = value(settings().selectedRow());
                t.key(KeyEvent.KEYCODE_DPAD_RIGHT);
                t.require(!selected.equals(value(settings().selectedRow())), "Right adjusts the selected core");
                t.key(KeyEvent.KEYCODE_DPAD_LEFT);
                t.require(selected.equals(value(settings().selectedRow())) && settings().isInRows(), "Left adjusts back and keeps rows focused");
                t.key(KeyEvent.KEYCODE_BUTTON_B);
                t.require(!settings().isInRows(), "Back must return to tabs");
            });
            t.check(206, "retroArchChoicesAndPanelSurviveActivityRecreation", () -> {
                open(true); t.key(KeyEvent.KEYCODE_ENTER); selectRow("core_GBA");
                t.key(KeyEvent.KEYCODE_BUTTON_A);
                String selected = value(row("core_GBA"));
                String packageName = preferences().preferredPackage();
                t.recreateActivity();
                t.require(t.view().isSettingsOpen() && RetroArchSettings.GROUP.equals(settings().groupId()), "Emulator tab must restore");
                t.require(settings().isInRows() && settings().selectedRow() == row("core_GBA"), "Selected core row must restore");
                t.require(selected.equals(value(row("core_GBA"))) && selected.equals(preferences().core(Cartridge.Platform.GBA).label), "Core choice must persist");
                t.require(packageName.equals(preferences().preferredPackage()), "Application choice must persist");
            });
            t.check(207, "retroArchRowsUpdateInBothLanguages", () -> {
                open(false);
                for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH)) {
                    t.runOnMainSync(() -> { t.view().language().select(choice); t.home().onLanguageChanged(); });
                    boolean chinese = choice == AppLanguage.Choice.CHINESE;
                    t.require(label(0).equals(chinese ? "RetroArch \u5E94\u7528" : "RetroArch app"), "Application label must be translated");
                    for (Cartridge.Platform platform : Cartridge.Platform.values())
                        t.require(label(row("core_" + platform.name())).equals(platform.name() + (chinese ? " \u6838\u5FC3" : " core")), "Core label must be translated");
                    t.require(RetroArchSettings.GROUP.equals(settings().groupId()), "Language change must retain tab");
                }
            });
        } finally {
            t.runOnMainSync(() -> {
                SharedPreferences.Editor editor = storage.edit().clear();
                for (Map.Entry<String, ?> entry : original.entrySet()) editor.putString(entry.getKey(), (String) entry.getValue());
                editor.commit();
                t.view().language().select(originalLanguage); t.home().onLanguageChanged(); settings().refresh();
                t.getTargetContext().getSharedPreferences("language", 0).edit().putString("choice", originalLanguage.name()).commit();
            });
            t.reset(1, false);
        }
    }

    private void open(boolean viaKey) {
        t.reset(1, false);
        t.key(KeyEvent.KEYCODE_BUTTON_Y); t.settle(550);
        t.runOnMainSync(() -> { settings().reset(viaKey); settings().showGroup(RetroArchSettings.GROUP); });
        t.settle(100);
    }

    private void awaitAvailability() {
        long limit = SystemClock.uptimeMillis() + 5000;
        while (value(0).equals(t.view().language().text(R.string.retroarch_loading)) && SystemClock.uptimeMillis() < limit) t.settle(20);
        t.require(!value(0).equals(t.view().language().text(R.string.retroarch_loading)), "Application query must complete");
    }

    private SettingsPanel settings() { return t.home().settingsPanel(); }
    private RetroArchPreferences preferences() { return new RetroArchPreferences(t.getTargetContext()); }
    private int row(String id) {
        for (int i = 0; i < settings().rowCount(); i++) if (settings().rowView(i) == settings().rowView(id)) return i;
        throw new AssertionError("Missing setting " + id);
    }
    private void selectRow(String id) {
        int target = row(id);
        for (int step = 0; step < settings().rowCount() && settings().selectedRow() != target; step++)
            t.key(settings().selectedRow() < target ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
        t.require(settings().selectedRow() == target, "Select setting " + id);
    }
    private String value(int row) {
        String display = text(row, 1);
        t.require(display.startsWith("‹ ") && display.endsWith(" ›"), "Choice arrows");
        return display.substring(2, display.length() - 2);
    }
    private String label(int row) { return text(row, 0); }
    private String text(int row, int child) {
        String[] result = new String[1];
        t.runOnMainSync(() -> result[0] = ((TextView) ((LinearLayout) settings().rowView(row)).getChildAt(child)).getText().toString());
        return result[0];
    }
}
