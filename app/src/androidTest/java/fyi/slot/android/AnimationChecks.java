package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.File;
import java.util.List;

/** Mechanical travel, direct-touch handoff and slot foreground checks. */
final class AnimationChecks {
    private final InteractionChecks t;
    AnimationChecks(InteractionChecks tests) { t = tests; }

    void run() {
        boolean swipe = t.view().interactionPreferences().swipeToInsert;
        boolean sound = t.view().interactionPreferences().sound;
        try {
            t.check(186, "dragTopEdgeFollowsFingerAcrossAllCartridgeTypes", () -> {
                t.runOnMainSync(() -> t.view().interactionPreferences().swipeToInsert = false);
                for (int index : new int[]{1, 5, 7}) {
                    t.reset(index, false);
                    RectF before = bounds();
                    t.dragStart(360, before.top + 30); t.dragMove(360, before.top + 115);
                    t.require(Math.abs(bounds().top - before.top - 85) < .1f, "85 units of physical top-edge movement");
                    t.touch(MotionEvent.ACTION_CANCEL, 360, before.top + 115); t.settle(900);
                }
            });
            t.check(187, "swipeAndDragHandoffDoNotRestartOrJumpBack", () -> {
                for (boolean shortSwipe : new boolean[]{true, false}) {
                    t.runOnMainSync(() -> t.view().interactionPreferences().swipeToInsert = shortSwipe);
                    for (int index : new int[]{1, 5, 7}) {
                        t.reset(index, false); RectF rest = bounds();
                        float dy = shortSwipe ? 60 : t.view().homeLayout().insertionDistance(t.view().selectedPlatform()) * .7f;
                        t.dragStart(360, rest.top + 30); t.dragMove(360, rest.top + 30 + dy);
                        float heldTop = bounds().top;
                        t.touch(MotionEvent.ACTION_UP, 360, rest.top + 30 + dy);
                        t.require(bounds().top >= heldTop - .1f, "Release preserves held pose");
                        t.settle(40); t.require(bounds().top >= heldTop - .1f, "Automatic travel continues forward");
                        t.settle(1700); t.require(t.view().currentMode() == SlotView.Mode.PREVIEW, "Handoff finishes");
                    }
                }
            });
            t.check(188, "neighboursPartAndReturnWithCartridgeTravel", () -> {
                t.reset(1, false);
                t.runOnMainSync(() -> t.view().interactionPreferences().swipeToInsert = false);
                RectF left = adjacent(-1), right = adjacent(1);
                t.dragStart(360, 210); t.dragMove(360, 295);
                float part = 260 * t.view().insertionPhase();
                t.require(Math.abs(adjacent(-1).left - left.left + part) < .1f, "Left neighbour retreats");
                t.require(Math.abs(adjacent(1).left - right.left - part) < .1f, "Right neighbour retreats");
                t.touch(MotionEvent.ACTION_CANCEL, 360, 295); t.settle(900);
                t.require(Math.abs(adjacent(-1).left - left.left) < .1f, "Cancelled travel closes shelf");
            });
            t.check(189, "seatedCartRemainsVisibleInsideScoopAndHiddenBelowHousing", () -> {
                t.reset(1, false);
                t.runOnMainSync(() -> t.view().interactionPreferences().swipeToInsert = false);
                t.dragStart(360, 210); t.dragMove(360, 540);
                Bitmap frame = t.frame();
                Bitmap empty = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    t.runOnMainSync(() -> {
                        HomeLayout layout = t.view().homeLayout();
                        Canvas c = new Canvas(empty); c.translate(layout.originX, layout.originY); c.scale(layout.scale, layout.scale);
                        MachineRenderer machine = new MachineRenderer();
                        machine.drawBack(c, layout, layout.machineClosedTop()); machine.drawFront(c, layout, layout.machineClosedTop());
                    });
                    HomeLayout l = t.view().homeLayout(); float top = l.machineClosedTop();
                    int visible = 0;
                    for (int y = (int) l.toScreenY(top + 18); y < l.toScreenY(top + 38); y++) {
                        for (int x = (int) l.toScreenX(330); x < l.toScreenX(390); x++) {
                            if (frame.getPixel(x, y) != empty.getPixel(x, y)) visible++;
                        }
                    }
                    t.require(visible > 100, "Central scoop reveals cartridge beyond old 10-unit crop");
                    for (int y = (int) l.toScreenY(top + 48); y < l.toScreenY(top + 56); y++) {
                        for (int x = (int) l.toScreenX(330); x < l.toScreenX(390); x++) {
                            t.require(frame.getPixel(x, y) == empty.getPixel(x, y), "Foreground hides lower cartridge");
                        }
                    }
                } finally { frame.recycle(); empty.recycle(); }
                t.touch(MotionEvent.ACTION_CANCEL, 360, 540); t.settle(900);
            });
            t.check(190, "backCancelsAutomaticInsertionAndNextCycleWorks", () -> {
                t.reset(1, false); t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(80);
                t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(1000);
                t.require(t.view().canBrowse() && t.view().insertionProgress() == 0, "Back cancels committed travel");
                t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(1900);
                t.require(t.view().currentMode() == SlotView.Mode.PREVIEW, "Next insertion completes");
            });
            t.check(191, "lipContactCompletesShrinkForAllLayoutsAndPlatforms", () -> {
                for (int[] size : new int[][]{{960, 640}, {960, 960}, {1280, 720}, {1024, 768}}) {
                    HomeLayout l = HomeLayout.of(size[0], size[1], 0, 0, 0, 0, 1);
                    for (Cartridge.Platform p : Cartridge.Platform.values()) {
                        CartridgeMotion.Geometry g = l.cartridgeGeometry(p);
                        float progress = g.progressAtPhase(.42f);
                        t.require(Math.abs(g.widthAtProgress(progress) - 240) < .001f, "Width matches opening before entry");
                        t.require(Math.abs(g.topAtProgress(progress) + g.heightAtProgress(progress) - l.machineClosedTop()) < .001f,
                                "Bottom edge meets lip at catch");
                    }
                }
            });
            t.check(192, "pauseAndRecreationCleanUpMechanicalAnimation", () -> {
                t.reset(1, false); t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(100);
                t.runOnMainSync(() -> t.view().pause()); float progress = t.view().insertionProgress();
                t.settle(250); t.require(t.view().insertionProgress() == progress, "Pause freezes travel");
                t.runOnMainSync(() -> t.view().resume()); t.settle(1900);
                t.require(t.view().currentMode() == SlotView.Mode.PREVIEW, "Resume completes travel");
                t.reset(1, false); t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(100); t.recreateActivity();
                t.require(t.view().canBrowse() && t.view().insertionProgress() == 0, "Recreation discards unfinished insertion");
            });
            t.check(193, "realCartridgeReverseStartsPromptlyAndKeepsOneCue", this::realEject);
            t.check(194, "windowEjectionRunsToExactEndpointAcrossPlatforms", () -> {
                for (int index : new int[]{1, 5, 7}) {
                    t.reset(index, true); t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(1800);
                    t.require(t.view().canBrowse() && t.view().insertionProgress() == 0,
                            "Window keeps final mechanical frames: platform=" + t.view().selectedPlatform()
                                    + ", progress=" + t.view().insertionProgress());
                }
            });
            runTailCheck();
        } finally {
            t.runOnMainSync(() -> {
                t.view().interactionPreferences().swipeToInsert = swipe;
                t.view().interactionPreferences().sound = sound;
                t.view().cancelInteraction();
            });
        }
    }

    private void realEject() {
        for (Cartridge.Platform platform : Cartridge.Platform.values()) {
            for (boolean sound : new boolean[]{true, false}) {
                SlotView[] target = new SlotView[1]; Bitmap[] frame = new Bitmap[1];
                t.runOnMainSync(() -> {
                    File root = t.getTargetContext().getFilesDir();
                    RomRecord rom = new RomRecord(root.getPath(), "animation-" + platform + ".gba", platform, 512, 0,
                            new byte[0x150], null, false);
                    Cartridge cart = new Cartridge(rom, ShellConfig.defaults(rom), null);
                    target[0] = new SlotView(t.getTargetContext(), new ShelfModel(List.of(cart)));
                    target[0].layout(0, 0, 960, 640); target[0].interactionPreferences().sound = sound;
                    Bundle b = new Bundle(); b.putString("shelf_selected_ALL", cart.id);
                    b.putBoolean("inserted", true); target[0].restore(b);
                    frame[0] = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
                });
                SlotView v = target[0];
                try {
                    long ready = SystemClock.uptimeMillis() + 5000;
                    while (!v.cartridgeAudio().ready() && SystemClock.uptimeMillis() < ready) SystemClock.sleep(5);
                    t.require(v.cartridgeAudio().ready(), "Real cue loaded");
                    int before = v.cartridgeAudio().playCount();
                    long start = SystemClock.uptimeMillis();
                    t.runOnMainSync(() -> v.handleKeyDown(KeyEvent.KEYCODE_BUTTON_B, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)));
                    while (v.insertionProgress() >= .98f && SystemClock.uptimeMillis() - start < 1000) draw(v, frame[0]);
                    long elapsed = SystemClock.uptimeMillis() - start;
                    t.note("MECHANICAL_EJECT_RESPONSE " + platform + " sound=" + sound + " " + elapsed + " ms");
                    t.require(elapsed <= 180, "No hidden pre-eject wait");
                    while ((!v.canBrowse() || v.insertionProgress() != 0) && SystemClock.uptimeMillis() - start < 1600) draw(v, frame[0]);
                    t.require(v.canBrowse() && v.insertionProgress() == 0, "Reverse restores shelf: mode="
                            + v.currentMode() + ", insertion=" + v.insertionProgress() + ", reveal=" + v.revealProgress());
                    t.require(v.cartridgeAudio().playCount() == before + (sound ? 1 : 0), "Exactly one eject cue, or mute");
                } finally {
                    t.runOnMainSync(v::onDetachedFromWindow); frame[0].recycle();
                }
            }
        }
    }

    private void draw(SlotView v, Bitmap frame) { SystemClock.sleep(16); t.runOnMainSync(() -> v.draw(new Canvas(frame))); }

    void runTailCheck() {
        t.check(195, "librarySnapshotWaitsForLastMechanicalFrame", () -> {
            SlotView[] target = new SlotView[1]; Bitmap[] frame = new Bitmap[1];
            t.runOnMainSync(() -> {
                File root = t.getTargetContext().getFilesDir();
                RomRecord rom = new RomRecord(root.getPath(), "animation-tail.gba", Cartridge.Platform.GBA,
                        512, 0, new byte[0x150], null, false);
                Cartridge cart = new Cartridge(rom, ShellConfig.defaults(rom), null);
                target[0] = new SlotView(t.getTargetContext(), new ShelfModel(List.of(cart)));
                target[0].layout(0, 0, 960, 640); target[0].interactionPreferences().sound = false;
                Bundle b = new Bundle(); b.putString("shelf_selected_ALL", cart.id);
                b.putBoolean("inserted", true); target[0].restore(b);
                frame[0] = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
                target[0].handleKeyDown(KeyEvent.KEYCODE_BUTTON_B, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B));
            });
            SlotView v = target[0]; long start = SystemClock.uptimeMillis();
            try {
                while (v.insertionProgress() > .001f && SystemClock.uptimeMillis() - start < 2000) {
                    SystemClock.sleep(1); t.runOnMainSync(() -> v.draw(new Canvas(frame[0])));
                }
                t.require(v.insertionProgress() > 0 && v.insertionProgress() <= .001f, "Observe active tail before exact zero");
                t.runOnMainSync(() -> v.updateLibrary(LibraryRepository.Status.EMPTY, List.of()));
                t.require(v.libraryCount() == 1, "Pending snapshot retains cartridge until travel ends");
                while (v.insertionProgress() != 0 && SystemClock.uptimeMillis() - start < 3000) draw(v, frame[0]);
                t.require(v.insertionProgress() == 0, "Mechanical travel ends safely with pending empty snapshot");
                draw(v, frame[0]);
                t.require(v.libraryCount() == 0 && v.canOpenPanel(), "Empty snapshot applies after exact endpoint");
            } finally { t.runOnMainSync(v::onDetachedFromWindow); frame[0].recycle(); }
        });
    }
    private RectF bounds() {
        RectF result = new RectF(); t.runOnMainSync(() -> t.view().selectedCardBounds(result)); return result;
    }
    private RectF adjacent(int offset) {
        RectF result = new RectF(); t.runOnMainSync(() -> t.view().adjacentCardBounds(offset, result)); return result;
    }
}
