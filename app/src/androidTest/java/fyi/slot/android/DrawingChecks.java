package fyi.slot.android;

import android.content.Intent;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Counts actual window draws, without requesting frames while observing idle periods. */
final class DrawingChecks {
    static final int COUNT = 10;
    private final InteractionChecks t;

    DrawingChecks(InteractionChecks tests) { t = tests; }

    void run() {
        t.check(176, "restingShelfDoesNotPollForFrames", () -> {
            t.reset(1, false);
            assertIdle("shelf");
        });
        t.check(177, "repeatedPanelAnimationsDoNotMultiplyIdleRefreshes", () -> {
            t.reset(1, false);
            for (int i = 0; i < 4; i++) {
                t.key(KeyEvent.KEYCODE_BUTTON_Y); t.settle(450);
                t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(450);
            }
            t.key(KeyEvent.KEYCODE_BUTTON_Y); t.settle(650);
            t.require(t.view().panel().isFullyOpen(), "Settings animation must complete");
            assertIdle("settings after repeated openings");
        });
        t.check(178, "appsAndSettingsSwitchAndBecomeIdle", () -> {
            t.reset(1, false);
            t.key(KeyEvent.KEYCODE_BUTTON_X); t.settle(1200);
            t.require(t.view().isAppsOpen(), "Apps must open");
            assertIdle("apps");
            t.key(KeyEvent.KEYCODE_BUTTON_Y); t.settle(600);
            t.require(t.view().isSettingsOpen(), "Settings must replace apps");
            assertIdle("settings after content switch");
            t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(600);
            assertIdle("closed panel");
        });
        t.check(179, "browsingAndHeldRepeatStillSettle", () -> {
            t.reset(1, false);
            t.key(KeyEvent.KEYCODE_DPAD_RIGHT); t.settle(900);
            t.require(t.view().selectedIndex() == 2 && Math.abs(t.view().shelfPosition() - 2) < .001f,
                    "One selection must settle without idle polling");
            t.press(KeyEvent.KEYCODE_DPAD_RIGHT); t.settle(1100);
            t.release(KeyEvent.KEYCODE_DPAD_RIGHT); t.settle(1000);
            t.require(!t.view().isNavigationKeyHeld() && t.view().selectedIndex() != 3,
                    "Held input must continue repeating");
            assertIdle("released navigation");
        });
        t.check(180, "insertionEjectionAndAudioFinishWithoutPolling", () -> {
            t.reset(1, false);
            int audio = t.view().cartridgeAudio().playCount();
            boolean sound = t.view().interactionPreferences().sound;
            t.runOnMainSync(() -> t.view().interactionPreferences().sound = true);
            try {
                t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(2000);
                t.require(t.view().currentMode() == SlotView.Mode.PREVIEW && t.view().revealProgress() == 1,
                        "Insertion and reveal must finish");
                assertIdle("preview");
                t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(2000);
                t.require(t.view().canBrowse() && t.view().insertionProgress() == 0
                        && t.view().cartridgeAudio().playCount() == audio + 2,
                        "Ejection must finish with both cues");
                assertIdle("ejected shelf");
            } finally { t.runOnMainSync(() -> t.view().interactionPreferences().sound = sound); }
        });
        t.check(181, "heldDragAndCancellationDoNotLeaveFrameLoop", () -> {
            t.reset(1, false);
            boolean swipe = t.view().interactionPreferences().swipeToInsert;
            t.runOnMainSync(() -> t.view().interactionPreferences().swipeToInsert = false);
            try {
                t.dragStart(360, 210); t.dragMove(360, 295); t.settle(200);
                assertIdle("stationary held drag");
                t.touch(MotionEvent.ACTION_CANCEL, 360, 295); t.settle(1000);
                t.require(t.view().canBrowse() && t.view().insertionProgress() == 0,
                        "Cancelled drag must return");
                assertIdle("cancelled drag");
            } finally { t.runOnMainSync(() -> t.view().interactionPreferences().swipeToInsert = swipe); }
        });
        t.check(182, "statusChangesRefreshOnceAndBatteryDuplicatesStayIdle", () -> {
            t.reset(1, false);
            int[] old = new int[2];
            t.runOnMainSync(() -> {
                ShelfStatusObserver status = t.view().statusObserver();
                old[0] = status.batteryPercent(); old[1] = status.isCharging() ? 1 : 0;
            });
            try {
                int draws = countDraws(250, () -> t.view().statusObserver().accept(battery(37, true)));
                t.require(draws > 0 && t.view().statusObserver().batteryPercent() == 37
                        && t.view().statusObserver().isCharging(), "Changed battery must draw and update");
                t.require(countDraws(250, () -> t.view().statusObserver().accept(battery(37, true))) == 0,
                        "Duplicate battery state must not redraw");
                for (String action : new String[]{Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED,
                        Intent.ACTION_TIMEZONE_CHANGED}) {
                    t.require(countDraws(250, () -> t.view().statusObserver().accept(new Intent(action))) > 0,
                            "Clock event must redraw: " + action);
                }
            } finally {
                t.runOnMainSync(() -> t.view().statusObserver().accept(battery(old[0], old[1] != 0)));
            }
            t.settle(200); assertIdle("status update");
        });
        t.check(183, "pauseResumeAndRecreationKeepStatusAndDrawingBounded", () -> {
            t.reset(1, false);
            t.key(KeyEvent.KEYCODE_BUTTON_Y); t.settle(100);
            t.runOnMainSync(() -> t.view().pause()); t.settle(250);
            t.require(!t.view().statusObserver().isStarted() && t.view().panel().isFullyOpen(),
                    "Pause must stop observation and finish panel animation");
            try { assertIdle("paused panel"); }
            finally { t.runOnMainSync(() -> t.view().resume()); }
            t.settle(400);
            t.require(t.view().statusObserver().isStarted(), "Resume must restore status observation");
            assertIdle("resumed panel");
            t.recreateActivity();
            t.require(t.view().isSettingsOpen() && t.view().statusObserver().isStarted(),
                    "Recreation must retain panel and observation");
            assertIdle("recreated panel");
            t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(600);
        });
        t.check(184, "watcherRestartsDoNotTriggerPhantomRescans", () -> {
            File root = watcherFixture();
            AtomicInteger updates = new AtomicInteger();
            LibraryWatcher[] watcher = new LibraryWatcher[1];
            try {
                t.runOnMainSync(() -> {
                    watcher[0] = new LibraryWatcher(new Handler(Looper.getMainLooper()), platforms -> {
                        updates.incrementAndGet(); watcher[0].start(root);
                    });
                    watcher[0].start(root); watcher[0].start(root);
                });
                t.settle(2500);
                t.require(updates.get() == 0, "Watcher replacement must not create a scan/restart loop");
                t.runOnMainSync(() -> { watcher[0].stop(); watcher[0].start(root); });
                t.settle(2500);
                t.require(updates.get() == 0, "Old watcher callbacks must stay ignored after restarting");
            } finally {
                if (watcher[0] != null) t.runOnMainSync(watcher[0]::stop);
                deleteWatcherFixture(root);
            }
        });
        t.check(185, "realLibraryWritesStillDebounceOnceAfterWatcherRestart", () -> {
            File root = watcherFixture();
            File rom = new File(root, "GBA/idle-check.gba");
            AtomicInteger updates = new AtomicInteger();
            AtomicReference<Set<Cartridge.Platform>> changed = new AtomicReference<>();
            LibraryWatcher[] watcher = new LibraryWatcher[1];
            try {
                t.runOnMainSync(() -> {
                    watcher[0] = new LibraryWatcher(new Handler(Looper.getMainLooper()), platforms -> {
                        updates.incrementAndGet(); changed.set(platforms); watcher[0].start(root);
                    });
                    watcher[0].start(root);
                });
                t.settle(200);
                write(rom); t.settle(2000);
                t.require(updates.get() == 1 && changed.get().equals(Set.of(Cartridge.Platform.GBA)),
                        "Create and close-write must debounce to one GBA update");
                t.settle(2000);
                t.require(updates.get() == 1, "Completed rescan must not start a phantom follow-up");
                write(rom); t.settle(2000);
                t.require(updates.get() == 2, "Replacement watcher must still see a later edit");
            } finally {
                if (watcher[0] != null) t.runOnMainSync(watcher[0]::stop);
                t.require(!rom.exists() || rom.delete(), "Owned ROM fixture cleanup");
                deleteWatcherFixture(root);
            }
        });
    }

    private void assertIdle(String label) {
        // Native scrollbar fades and initial icon delivery may outlast the machine's lift animation.
        t.settle(2000);
        int draws = countDraws(3000, null);
        t.note("IDLE " + label + ": " + draws + " window draws / 3000 ms");
        // Allow one clock tick plus an incidental device status event.
        t.require(draws <= 3, label + " must stop drawing, got " + draws);
    }

    private int countDraws(long millis, Runnable action) {
        AtomicInteger draws = new AtomicInteger();
        AtomicInteger[] buckets = new AtomicInteger[(int) (millis / 500) + 1];
        for (int i = 0; i < buckets.length; i++) buckets[i] = new AtomicInteger();
        long start = SystemClock.uptimeMillis();
        View root = t.activity().getWindow().getDecorView();
        ViewTreeObserver.OnDrawListener listener = () -> {
            draws.incrementAndGet();
            int bucket = (int) Math.min(buckets.length - 1, (SystemClock.uptimeMillis() - start) / 500);
            buckets[bucket].incrementAndGet();
        };
        t.runOnMainSync(() -> root.getViewTreeObserver().addOnDrawListener(listener));
        try {
            if (action != null) t.runOnMainSync(action);
            t.settle(millis);
            if (draws.get() > 3) {
                StringBuilder timeline = new StringBuilder("Draws per 500 ms:");
                for (AtomicInteger bucket : buckets) timeline.append(' ').append(bucket.get());
                t.note(timeline.toString());
            }
            return draws.get();
        } finally { t.runOnMainSync(() -> root.getViewTreeObserver().removeOnDrawListener(listener)); }
    }

    private static Intent battery(int percent, boolean charging) {
        return new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, percent)
                .putExtra(BatteryManager.EXTRA_SCALE, 100).putExtra(BatteryManager.EXTRA_PLUGGED, charging ? 1 : 0);
    }

    private File watcherFixture() {
        File root = new File(t.getTargetContext().getCacheDir(), "idle-watcher-" + System.nanoTime());
        t.require(new File(root, "GBA").mkdirs(), "Owned watcher fixture");
        return root;
    }

    private void deleteWatcherFixture(File root) {
        t.require(new File(root, "GBA").delete() && root.delete(),
                "Owned watcher directories cleanup");
    }

    private static void write(File file) {
        try { Files.write(file.toPath(), new byte[512]); }
        catch (IOException error) { throw new AssertionError(error); }
    }
}
