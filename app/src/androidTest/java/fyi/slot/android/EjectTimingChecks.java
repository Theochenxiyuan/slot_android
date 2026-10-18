package fyi.slot.android;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.function.BooleanSupplier;

/** Response-time checks for real cartridges; sample-scene timing is covered separately. */
final class EjectTimingChecks {
    private final InteractionChecks t;

    EjectTimingChecks(InteractionChecks tests) { t = tests; }

    void run() {
        boolean originalSound = t.view().interactionPreferences().sound;
        boolean originalSwipe = t.view().interactionPreferences().swipeToInsert;
        java.util.function.Function<RomRecord, RetroArchLauncher.Result> originalLauncher = t.home().gameLauncher;
        try {
            t.runOnMainSync(() -> t.home().gameLauncher = rom -> RetroArchLauncher.Result.STARTED);
            t.check(130, "realKeyEjectionStartsPromptlyAcrossPlatformsAndSoundModes", () -> {
                await(() -> t.view().cartridgeAudio().ready(), 5000, "Audio loaded");
                for (Cartridge.Platform platform : Cartridge.Platform.values()) {
                    for (boolean sound : new boolean[]{true, false}) {
                        select(platform, true, sound, true);
                        require(t.view().revealProgress() == 0, "Restored real cartridge has no scene fade");
                        assertStartsPromptly(platform + " key sound=" + sound,
                                () -> t.key(KeyEvent.KEYCODE_BUTTON_B), sound);
                    }
                }
            });
            t.check(131, "freshlyInsertedRealCartridgeAcceptsEjectTouchImmediately", () -> {
                select(Cartridge.Platform.GBA, false, true, true);
                t.key(KeyEvent.KEYCODE_BUTTON_A);
                await(() -> t.view().currentMode() == SlotView.Mode.PREVIEW, 2000, "Real cartridge seated");
                require(t.view().revealProgress() == 0, "Fresh insertion has no invisible reveal animation");
                assertStartsPromptly("fresh insert touch",
                        () -> t.runOnMainSync(() -> t.dispatchTap(t.view(), 67.5f, 45)), true);
            });
            t.check(132, "cancelledRealEjectSwipeKeepsCardAndNextEjectStartsPromptly", () -> {
                for (boolean swipe : new boolean[]{true, false}) {
                    select(Cartridge.Platform.GBC, true, true, swipe);
                    float x = t.view().homeLayout().centerX();
                    int before = t.view().cartridgeAudio().playCount();
                    t.dragStart(x, 250); t.dragMove(x, 155);
                    t.touch(MotionEvent.ACTION_CANCEL, x, 155);
                    require(t.view().currentMode() == SlotView.Mode.PREVIEW && t.view().insertionProgress() == 1,
                            "Cancel keeps real cartridge seated");
                    require(t.view().cartridgeAudio().playCount() == before, "Cancelled swipe stays silent");
                    t.dragStart(x, 250); t.dragMove(x, 120);
                    assertStartsPromptly("swipe after cancel swipeMode=" + swipe,
                            () -> t.touch(MotionEvent.ACTION_UP, x, 120), true);
                }
            });
        } finally {
            t.runOnMainSync(() -> {
                t.view().interactionPreferences().sound = originalSound;
                t.view().interactionPreferences().swipeToInsert = originalSwipe;
                t.home().gameLauncher = originalLauncher;
                t.view().cancelInteraction();
            });
        }
    }

    private void select(Cartridge.Platform platform, boolean inserted, boolean sound, boolean swipe) {
        Cartridge card = t.home().library().snapshot().stream().filter(item -> item.platform == platform).findFirst().orElseThrow();
        t.runOnMainSync(() -> {
            t.view().interactionPreferences().sound = sound;
            t.view().interactionPreferences().swipeToInsert = swipe;
            Bundle state = new Bundle(); state.putString("shelf_selected_ALL", card.id); state.putBoolean("inserted", inserted);
            t.view().restore(state);
        });
        t.waitForIdleSync();
    }

    private void assertStartsPromptly(String source, Runnable request, boolean sound) {
        String id = t.view().selectedCartridgeId();
        int before = t.view().cartridgeAudio().playCount();
        long started = SystemClock.uptimeMillis();
        request.run();
        await(() -> t.view().insertionProgress() < .98f, 1000, "Ejection begins");
        long elapsed = SystemClock.uptimeMillis() - started;
        t.note("EJECT_RESPONSE " + source + " " + elapsed + " ms");
        require(elapsed <= 180, "Ejection must begin within 180 ms; actual=" + elapsed);
        await(() -> t.view().canBrowse(), 1500, "Shelf navigation restored");
        require(id.equals(t.view().selectedCartridgeId()), "Ejection retains cartridge identity");
        require(t.view().cartridgeAudio().playCount() == before + (sound ? 1 : 0), "One eject clip, or none when muted");
        if (sound) require(t.view().cartridgeAudio().lastEffect() == CartridgeAudio.Effect.EJECT, "Original eject clip");
    }

    private void await(BooleanSupplier condition, long timeout, String message) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(2);
        require(condition.getAsBoolean(), message);
    }

    private void require(boolean condition, String message) { t.require(condition, message); }
}
