package fyi.slot.android;

import android.view.KeyEvent;
import java.util.List;
import fyi.slot.android.RetroArchPreferences.Core;

/** Stage 6 game-specific choices through actual dialog touch and Android key injection. */
final class GameCoreChecks {
    private final InteractionChecks t;
    private final RetroArchFixture f;

    GameCoreChecks(InteractionChecks tests, RetroArchFixture fixture) { t = tests; f = fixture; }
    private GameCorePreferences preferences() { return new GameCorePreferences(t.activity(), new RetroArchPreferences(t.activity())); }
    private GameCoreDialog cores() { return t.home().gameCoreDialog(); }
    private void open() {
        open("Garden.gba");
    }
    private void open(String filename) {
        f.select(filename, false); t.key(KeyEvent.KEYCODE_MENU); t.settle(150);
        f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN); f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN); f.nativeKey(KeyEvent.KEYCODE_ENTER);
        t.require(cores().isShowing(), "MENU reaches game core list");
    }
    private void clickItem(PanelDialog dialog, int index) {
        t.require(dialog.rowView(index) != null, "Visible dialog item");
        f.nativeTap(dialog.rowView(index));
    }

    void run() {
        t.check(220, "longPressAndTouchSelectGameCore", () -> {
            f.select("Garden.gba", false);
            float cx = t.view().homeLayout().centerX(), y = t.view().selectedCardTop() + 35;
            t.dragStart(cx, y); t.settle(750);
            t.require(t.home().cartridgeDetails().isShowing(), "Long press opens cartridge menu");
            t.dragEnd(cx, y); clickItem(t.home().cartridgeDetails().panel(), 2);
            t.require(cores().isShowing() && cores().panel().checkedIndex() == 0, "Default inherits global");
            clickItem(cores().panel(), 2);
            t.require(!cores().isShowing() && preferences().override(f.cart("Garden.gba").rom) == Core.VBAM, "Touch saves VBA-M immediately");
        });
        t.check(221, "gameCoreKeysNavigateThenConfirmWithoutRepeatSave", () -> {
            open(); f.nativeKey(KeyEvent.KEYCODE_DPAD_DOWN);
            t.require(preferences().override(f.cart("Garden.gba").rom) == Core.VBAM, "Navigation does not save");
            f.nativeKey(KeyEvent.KEYCODE_BUTTON_A);
            t.require(preferences().override(f.cart("Garden.gba").rom) == Core.GPSP && !cores().isShowing(), "A confirms gpSP");
        });
        t.check(222, "gameCoreBackCancelsWithoutChangingOverride", () -> {
            for (int key : new int[]{KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK}) {
                open(); f.nativeKey(KeyEvent.KEYCODE_DPAD_UP); f.nativeKey(key);
                t.require(!cores().isShowing() && preferences().override(f.cart("Garden.gba").rom) == Core.GPSP, "Cancel does not save pending row");
            }
        });
        t.check(223, "useGlobalClearsOverrideAndLaunchesWithCurrentCore", () -> {
            t.runOnMainSync(() -> new RetroArchPreferences(t.activity()).selectCore(Cartridge.Platform.GBA, Core.VBAM));
            open();
            t.require(cores().panel().rowLabel(0).contains("VBA-M"), "Global label uses current value");
            clickItem(cores().panel(), 0); t.require(preferences().override(f.cart("Garden.gba").rom) == null, "Global clears override");
            int calls = f.launches.size(); t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(1900);
            t.require(f.launches.get(calls).getStringExtra("LIBRETRO").endsWith("/vbam_libretro_android.so"), "Initial launch inherits VBA-M");
            f.returnToSlot();
            t.runOnMainSync(() -> preferences().select(f.cart("Garden.gba").rom, Core.GPSP));
            t.key(KeyEvent.KEYCODE_ENTER); t.settle(200);
            t.require(f.launches.get(calls + 1).getStringExtra("LIBRETRO").endsWith("/gpsp_libretro_android.so"), "Relaunch resolves latest game override");
        });
        t.check(224, "gameCoreListAndMenuRemainShortInBothLanguages", () -> {
            t.runOnMainSync(() -> {
                new RetroArchPreferences(t.activity()).selectCore(Cartridge.Platform.GBA, Core.VBAM);
                preferences().select(f.cart("Garden.gba").rom, Core.GPSP);
            });
            for (AppLanguage.Choice choice : List.of(AppLanguage.Choice.CHINESE, AppLanguage.Choice.ENGLISH)) {
                t.runOnMainSync(() -> { t.view().language().select(choice); t.home().onLanguageChanged(); });
                open(); PanelDialog list = cores().panel();
                t.require(list.rowCount() == 4 && list.checkedIndex() == 3, "Only inheritance plus this platform's presets");
                String expected = choice == AppLanguage.Choice.CHINESE ? "\u4F7F\u7528\u5168\u5C40\uFF08VBA-M\uFF09" : "Use global (VBA-M)";
                t.require(list.rowLabel(0).equals(expected), "Localized inherited label");
                f.screenshot(choice == AppLanguage.Choice.CHINESE ? "game-core-zh" : "game-core-en");
                f.nativeKey(KeyEvent.KEYCODE_BUTTON_B);
                for (String filename : List.of("Moon.gb", "Lake.gbc")) {
                    open(filename); PanelDialog other = cores().panel();
                    List<Core> presets = RetroArchPreferences.cores(f.cart(filename).platform);
                    t.require(other.rowCount() == presets.size() + 1 && other.checkedIndex() == 0,
                            "GB and GBC independently inherit their own platform");
                    for (int row = 0; row < presets.size(); row++) {
                        t.require(presets.get(row).label.equals(other.rowLabel(row + 1)), "Only supported platform presets");
                    }
                    f.nativeKey(KeyEvent.KEYCODE_BUTTON_B);
                }
            }
        });
        t.check(225, "hidingAndRestoringGameKeepsCoreSelection", () -> {
            f.select("Garden.gba", false); RomRecord rom = f.cart("Garden.gba").rom;
            t.runOnMainSync(() -> t.home().library().setHidden(rom, true));
            f.await(() -> t.home().library().hidden().stream().anyMatch(item -> item.id.equals(rom.id)) && t.view().libraryCount() == 2);
            t.require(preferences().override(rom) == Core.GPSP, "Hide retains core");
            t.runOnMainSync(() -> t.home().library().setHidden(rom, false)); f.ready(); open();
            t.require(cores().panel().checkedIndex() == 3, "Restore retains checked gpSP");
            f.nativeKey(KeyEvent.KEYCODE_BUTTON_B);
        });
        t.check(226, "gameCoreChoiceSurvivesActivityAndPreferenceReconstruction", () -> {
            f.select("Garden.gba", false); int calls = f.launches.size(); t.recreateActivity(); f.ready(); f.interceptSuccess(); open();
            t.require(cores().panel().checkedIndex() == 3 && preferences().override(f.cart("Garden.gba").rom) == Core.GPSP, "Reconstructed preference and dialog retain game choice");
            t.require(f.launches.size() == calls, "Restoration never launches"); f.nativeKey(KeyEvent.KEYCODE_BUTTON_B);
        });
        t.check(227, "demoMenuHasNoCoreEntry", () -> {
            Cartridge demo = ShelfModel.demo().current().selectedCartridge();
            t.runOnMainSync(() -> t.home().onCartridgeMenu(demo)); t.settle(100);
            t.require(t.home().cartridgeDetails().panel().rowCount() == 1, "Demo has no game core action");
            t.runOnMainSync(() -> { t.home().cartridgeDetails().dismiss(); cores().show(demo); });
            t.require(!cores().isShowing(), "Demo cannot open game core list");
        });
    }
}
