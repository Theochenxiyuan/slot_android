package fyi.slot.android;

import android.app.Activity;
import android.view.KeyEvent;
import java.util.List;
import fyi.slot.android.RetroArchPreferences.Core;

/** A short single-choice list in the floating card, with separate navigation and confirmation. */
final class GameCoreDialog {
    private final Activity activity;
    private final AppLanguage language;
    private final GameCorePreferences preferences;
    private PanelDialog dialog;

    GameCoreDialog(Activity activity, AppLanguage language, GameCorePreferences preferences) {
        this.activity = activity; this.language = language; this.preferences = preferences;
    }

    void show(Cartridge cartridge) {
        dismiss();
        if (cartridge.rom == null) return;
        RomRecord rom = cartridge.rom;
        List<Core> cores = RetroArchPreferences.cores(rom.platform);
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        labels.add(language.text(R.string.game_core_global, preferences.inherited(rom).label));
        for (Core core : cores) labels.add(core.label);
        Core override = preferences.override(rom);
        int checked = override == null ? 0 : cores.indexOf(override) + 1;
        PanelDialog panel = new PanelDialog(activity, language, 460f);
        dialog = panel;
        panel.setTitle(language.text(R.string.game_core))
                .setRows(labels, checked)
                .setPick(index -> {
                    preferences.select(rom, index == 0 ? null : cores.get(index - 1));
                    dismiss();
                })
                .setCancelled(this::dismiss);
        panel.show();
    }

    void dismiss() { if (dialog != null) { dialog.dismiss(); dialog = null; } }
    boolean isShowing() { return dialog != null && dialog.isShowing(); }
    boolean dispatchKey(android.view.KeyEvent event) { return dialog != null && dialog.handleKey(event); }
    PanelDialog panel() { return dialog; }
}
