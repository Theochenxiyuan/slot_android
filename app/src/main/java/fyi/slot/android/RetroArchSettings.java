package fyi.slot.android;

import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.function.IntConsumer;

/** Emulator settings rows and asynchronous application availability. */
final class RetroArchSettings {
    static final String GROUP = "emulator";
    private enum Status { LOADING, READY, FAILED }

    private final AppLanguage language;
    private final RetroArchPreferences preferences;
    private final RetroArchLauncher launcher;
    private final Supplier<List<String>> query;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "slot-retroarch");
        thread.setDaemon(true);
        return thread;
    });
    private Status status = Status.LOADING;
    private List<String> installed = List.of();
    private Runnable changed = () -> {};
    private IntConsumer message = resource -> {};
    private int generation;
    private boolean released;

    RetroArchSettings(AppLanguage language, RetroArchPreferences preferences, RetroArchLauncher launcher) {
        this(language, preferences, launcher, launcher::installedPackages);
    }

    RetroArchSettings(AppLanguage language, RetroArchPreferences preferences, RetroArchLauncher launcher,
                      Supplier<List<String>> query) {
        this.language = language; this.preferences = preferences; this.launcher = launcher; this.query = query;
    }

    SettingsPanel.Group group() {
        List<SettingsPanel.Row> rows = new ArrayList<>(List.of(
                new SettingsPanel.Row("retroarch_app", R.string.retroarch_app, SettingsPanel.Kind.CHOICE, false,
                        this::applicationLabel, this::cycleApplication),
                new SettingsPanel.Row("retroarch_open", R.string.retroarch_open, SettingsPanel.Kind.ENTRY,
                        false, () -> "", direction -> open()),
                coreRow(Cartridge.Platform.GBA, R.string.gba_core, true),
                coreRow(Cartridge.Platform.GBC, R.string.gbc_core, false),
                coreRow(Cartridge.Platform.GB, R.string.gb_core, false)));
        boolean firstPreset = true;
        for (RetroArchPreset preset : RetroArchPreset.values()) {
            rows.add(new SettingsPanel.Row("preset_" + preset.id, preset.label, SettingsPanel.Kind.CHOICE,
                    firstPreset, () -> preferences.option(preset).label(language),
                    direction -> preferences.cycle(preset, direction)));
            firstPreset = false;
        }
        return new SettingsPanel.Group(GROUP, R.string.settings_emulator, rows);
    }

    void setListener(Runnable changed) { this.changed = changed; }
    void setMessageListener(IntConsumer message) { this.message = message; }

    void open() {
        RetroArchLauncher.Result result = launcher.open();
        if (result != RetroArchLauncher.Result.STARTED) message.accept(result == RetroArchLauncher.Result.NOT_INSTALLED
                ? R.string.launch_retroarch_missing : R.string.launch_retroarch_failed);
    }

    void reload() {
        if (released) return;
        int request = ++generation;
        status = Status.LOADING;
        changed.run();
        worker.execute(() -> {
            List<String> packages;
            try { packages = query.get(); }
            catch (RuntimeException error) { packages = null; }
            List<String> result = packages;
            main.post(() -> {
                if (released || request != generation) return;
                installed = result == null ? List.of() : List.copyOf(result);
                status = result == null ? Status.FAILED : Status.READY;
                changed.run();
            });
        });
    }

    void stop() { generation++; }

    void release() {
        released = true;
        stop();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }

    private SettingsPanel.Row coreRow(Cartridge.Platform platform, int label, boolean groupStart) {
        return new SettingsPanel.Row("core_" + platform.name(), label, SettingsPanel.Kind.CHOICE, groupStart,
                () -> preferences.core(platform).label, direction -> preferences.cycleCore(platform, direction));
    }

    boolean isChecking() { return status == Status.LOADING; }
    boolean queryFailed() { return status == Status.FAILED; }
    boolean isInstalled() { return status == Status.READY && launcher.selectedPackage(installed) != null; }
    String applicationLabel() {
        if (status == Status.LOADING) return language.text(R.string.retroarch_loading);
        if (status == Status.FAILED) return language.text(R.string.retroarch_query_failed);
        String selected = launcher.selectedPackage(installed);
        if (selected == null) return language.text(R.string.retroarch_not_installed);
        return language.text(RetroArchLauncher.AARCH64.equals(selected) ? R.string.retroarch_64bit : R.string.retroarch_standard);
    }

    private void cycleApplication(int direction) {
        if (status == Status.FAILED || status == Status.READY && installed.isEmpty()) { reload(); return; }
        if (status != Status.READY || installed.size() < 2) return;
        int selected = installed.indexOf(launcher.selectedPackage(installed));
        preferences.selectPackage(installed.get(Math.floorMod(selected + direction, installed.size())));
    }
}
