package fyi.slot.android;

import android.graphics.RectF;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Release-triggered swipes, deliberately independent of drag feedback. */
final class SwipeChecks {
    static final int COUNT = 7;
    private static final int[] PLATFORMS = {1, 5, 7};
    private final InteractionChecks t;

    SwipeChecks(InteractionChecks tests) { t = tests; }

    void run() {
        boolean swipe = t.view().interactionPreferences().swipeToInsert;
        boolean sound = t.view().interactionPreferences().sound;
        try {
            preferences(true, true);
            t.check(196, "swipeKeepsShelfStillAndStartsFullInsertionOnRelease", () -> {
                for (int index : PLATFORMS) {
                    t.reset(index, false); awaitAudio();
                    RectF card = bounds(0), left = bounds(-1), right = bounds(1);
                    int cues = t.view().cartridgeAudio().playCount();
                    float y = card.top + 30;
                    t.dragStart(360, y); t.dragMove(385, y + 100); t.settle(350);
                    t.require(bounds(0).equals(card) && bounds(-1).equals(left) && bounds(1).equals(right),
                            "Held swipe must not move, shrink or part any cartridge");
                    t.require(t.view().insertionProgress() == 0 && t.view().cartridgeAudio().playCount() == cues,
                            "No insertion or cue before release, even beyond threshold");
                    // Dispatch and inspect before the next window frame can advance the animation.
                    releaseAndInspect(385, y + 100, () -> {
                        RectF released = new RectF(); t.view().selectedCardBounds(released);
                        t.require(t.view().insertionPhase() == 0 && released.equals(card), "Starts at phase zero like confirm");
                    });
                    t.settle(200);
                    t.require(t.view().insertionPhase() > 0 && t.view().insertionPhase() < .65f,
                            "Full approach and catch run after release");
                    t.require(t.view().cartridgeAudio().playCount() == cues, "Cue stays near seating, not gesture recognition");
                    t.settle(1600);
                    t.require(t.view().currentMode() == SlotView.Mode.PREVIEW && t.view().insertionProgress() == 1,
                            "Released swipe seats cartridge");
                    t.require(t.view().cartridgeAudio().playCount() == cues + 1, "One insertion cue");
                }
            });
            t.check(197, "upwardSwipeKeepsPreviewStillUntilRelease", () -> {
                for (int index : PLATFORMS) {
                    t.reset(index, true); awaitAudio();
                    float reveal = t.view().revealProgress(); RectF card = bounds(0);
                    int cues = t.view().cartridgeAudio().playCount();
                    t.dragStart(360, 300); t.dragMove(385, 200); t.settle(350);
                    t.require(t.view().revealProgress() == reveal && bounds(0).equals(card)
                            && t.view().insertionProgress() == 1, "Held upward swipe does not fade preview or pull cartridge");
                    t.require(t.view().cartridgeAudio().playCount() == cues, "No eject cue before release");
                    t.touch(MotionEvent.ACTION_UP, 385, 200); t.settle(1800);
                    t.require(t.view().canBrowse() && t.view().insertionProgress() == 0, "Release completes ejection");
                    t.require(t.view().cartridgeAudio().playCount() == cues + 1, "One ejection cue");
                }
            });
            t.check(198, "shortReversedCancelledAndDiagonalSwipesStayStillAndSilent", () -> {
                for (boolean inserted : new boolean[]{false, true}) {
                    for (int kind = 0; kind < 5; kind++) {
                        t.reset(1, inserted); awaitAudio();
                        float direction = inserted ? -1 : 1;
                        RectF card = bounds(0); float reveal = t.view().revealProgress();
                        int cues = t.view().cartridgeAudio().playCount();
                        t.dragStart(360, 260);
                        float dy = direction * (kind == 0 ? 20 : 80);
                        t.dragMove(360, 260 + dy);
                        float x = 360;
                        if (kind == 1) { dy = direction * 10; t.dragMove(x, 260 + dy); }
                        if (kind == 4) { x = 470; t.dragMove(x, 260 + dy); }
                        int action = kind == 2 ? MotionEvent.ACTION_CANCEL
                                : kind == 3 ? MotionEvent.ACTION_POINTER_UP : MotionEvent.ACTION_UP;
                        t.touch(action, x, 260 + dy); t.settle(450);
                        t.require(bounds(0).equals(card) && t.view().revealProgress() == reveal,
                                "Invalid swipe has no displacement or rebound: inserted=" + inserted + ", kind=" + kind);
                        t.require(t.view().currentMode() == (inserted ? SlotView.Mode.PREVIEW : SlotView.Mode.SHELF)
                                && t.view().cartridgeAudio().playCount() == cues, "Invalid swipe stays silent and preserves state");
                    }
                }
            });
            t.check(199, "swipeModeRetainsHorizontalShelfNavigation", () -> {
                t.reset(1, false);
                t.dragStart(530, 250); t.dragMove(190, 254); t.dragEnd(190, 254); t.settle(900);
                t.require(t.view().selectedIndex() == 2 && t.view().insertionProgress() == 0
                        && t.view().currentMode() == SlotView.Mode.SHELF, "Horizontal swipes still browse only");
            });
            t.check(200, "dragModeStillFollowsFingerAndResumesFromHeldPose", () -> {
                preferences(false, true);
                for (int index : PLATFORMS) {
                    t.reset(index, false); RectF card = bounds(0); float y = card.top + 30;
                    t.dragStart(360, y); t.dragMove(360, y + 85);
                    t.require(Math.abs(bounds(0).top - card.top - 85) < .1f, "Drag remains directly manipulated");
                    t.touch(MotionEvent.ACTION_CANCEL, 360, y + 85); t.settle(900);
                    t.require(t.view().canBrowse() && bounds(0).equals(card), "Cancelled drag springs back");
                    float dy = t.view().homeLayout().insertionDistance(t.view().selectedPlatform()) * .7f;
                    t.dragStart(360, y); t.dragMove(360, y + dy);
                    float held = t.view().insertionProgress();
                    releaseAndInspect(360, y + dy, () -> {
                        t.require(t.view().insertionProgress() == held, "Drag release preserves held progress");
                    });
                    t.settle(1800); t.require(t.view().currentMode() == SlotView.Mode.PREVIEW, "Drag insertion finishes");
                    t.dragStart(360, 300); t.dragMove(360, 200);
                    t.require(t.view().revealProgress() < .6f, "Drag ejection still fades with finger");
                    t.touch(MotionEvent.ACTION_CANCEL, 360, 200); t.settle(900);
                    t.require(t.view().revealProgress() == 1, "Cancelled drag ejection restores preview");
                }
                preferences(true, true);
            });
            t.check(201, "focusLossCancelsPendingSwipeAndMutedNextCycleCompletes", () -> {
                preferences(true, false); t.reset(1, false);
                int cues = t.view().cartridgeAudio().playCount();
                t.dragStart(360, 260); t.dragMove(360, 340);
                t.runOnMainSync(() -> t.view().pause()); t.settle(200);
                t.runOnMainSync(() -> t.view().resume()); t.settle(300);
                t.require(t.view().canBrowse() && t.view().insertionProgress() == 0, "Pending swipe is discarded on pause");
                t.dragStart(360, 260); t.dragMove(360, 320); t.dragEnd(360, 320); t.settle(1800);
                t.require(t.view().currentMode() == SlotView.Mode.PREVIEW, "Muted insertion still runs");
                t.dragStart(360, 300); t.dragMove(360, 240); t.dragEnd(360, 240); t.settle(1800);
                t.require(t.view().canBrowse() && t.view().insertionProgress() == 0
                        && t.view().cartridgeAudio().playCount() == cues, "Muted swipe cycle returns without cues");
                t.key(KeyEvent.KEYCODE_BUTTON_A); t.settle(1800);
                t.require(t.view().currentMode() == SlotView.Mode.PREVIEW, "Confirm key remains available");
            });
            t.check(202, "swipeKeepsDotsAndCounterVisibleUntilInsertionMoves", this::positionIndicator);
        } finally {
            t.runOnMainSync(() -> {
                t.view().cancelInteraction();
                t.view().interactionPreferences().swipeToInsert = swipe;
                t.view().interactionPreferences().sound = sound;
            });
            t.reset(1, false);
        }
    }

    private void preferences(boolean swipe, boolean sound) {
        t.runOnMainSync(() -> {
            t.view().interactionPreferences().swipeToInsert = swipe;
            t.view().interactionPreferences().sound = sound;
        });
    }

    private void positionIndicator() {
        for (int count : new int[]{2, 10}) {
            SlotView[] target = new SlotView[1]; Bitmap[] frame = new Bitmap[1];
            int[][] ground = new int[1][];
            t.runOnMainSync(() -> {
                SlotView empty = new SlotView(t.getTargetContext(), new ShelfModel(List.of()));
                empty.layout(0, 0, 960, 640);
                Bitmap emptyFrame = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
                empty.draw(new Canvas(emptyFrame));
                ground[0] = positionPixels(empty, emptyFrame);
                emptyFrame.recycle(); empty.onDetachedFromWindow();
                List<Cartridge> carts = new ArrayList<>();
                for (int i = 0; i < count; i++) carts.add(new Cartridge("indicator-" + i, "Indicator " + i,
                        "01", 0xff35353a, 0xffdfab61, 0, Cartridge.Platform.GBA, CartridgeRenderer.Finish.SOLID));
                target[0] = new SlotView(t.getTargetContext(), new ShelfModel(carts));
                target[0].layout(0, 0, 960, 640);
                target[0].interactionPreferences().swipeToInsert = true;
                target[0].interactionPreferences().sound = false;
                frame[0] = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
            });
            SlotView v = target[0]; Bitmap image = frame[0];
            try {
                t.runOnMainSync(() -> {
                    v.draw(new Canvas(image)); int[] resting = positionPixels(v, image);
                    t.require(!Arrays.equals(resting, ground[0]), "Indicator rendered for count=" + count);
                    for (boolean cancel : new boolean[]{true, false}) {
                        indicatorTouch(v, MotionEvent.ACTION_DOWN, 240);
                        indicatorTouch(v, MotionEvent.ACTION_MOVE, cancel ? 320 : 260);
                        v.draw(new Canvas(image));
                        t.require(Arrays.equals(resting, positionPixels(v, image)), "Held swipe keeps exact indicator pixels");
                        indicatorTouch(v, cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, cancel ? 320 : 260);
                        v.draw(new Canvas(image));
                        t.require(Arrays.equals(resting, positionPixels(v, image)), "Cancelled and short swipes preserve indicator");
                    }
                    indicatorTouch(v, MotionEvent.ACTION_DOWN, 240);
                    indicatorTouch(v, MotionEvent.ACTION_MOVE, 320);
                    v.draw(new Canvas(image));
                    t.require(Arrays.equals(resting, positionPixels(v, image)), "Confirmed-distance swipe remains visible while held");
                    indicatorTouch(v, MotionEvent.ACTION_UP, 320);
                    t.require(v.insertionProgress() == 0, "Indicator only fades once automatic travel begins");
                });
                long until = SystemClock.uptimeMillis() + 2000;
                while (v.insertionProgress() < .08f && SystemClock.uptimeMillis() < until) {
                    SystemClock.sleep(16); t.runOnMainSync(() -> v.draw(new Canvas(image)));
                }
                t.runOnMainSync(() -> {
                    t.require(v.insertionProgress() >= .08f, "Automatic insertion advances");
                    t.require(Arrays.equals(positionPixels(v, image), ground[0]),
                            "Indicator fades out with automatic insertion");
                    v.cancelInteraction();
                    android.os.Bundle state = new android.os.Bundle(); v.restore(state);
                    v.interactionPreferences().swipeToInsert = false;
                    indicatorTouch(v, MotionEvent.ACTION_DOWN, 240);
                    indicatorTouch(v, MotionEvent.ACTION_MOVE, 260);
                    v.draw(new Canvas(image));
                    t.require(Arrays.equals(positionPixels(v, image), ground[0]),
                            "Drag mode retains immediate hiding");
                });
            } finally { t.runOnMainSync(v::onDetachedFromWindow); image.recycle(); }
        }
    }

    private static int[] positionPixels(SlotView v, Bitmap frame) {
        HomeLayout layout = v.homeLayout();
        int x = (int) layout.toScreenX(layout.centerX() - 90), y = (int) layout.toScreenY(layout.dotsY() - 12);
        int width = (int) layout.px(180), height = (int) layout.px(24);
        int[] pixels = new int[width * height]; frame.getPixels(pixels, 0, width, x, y, width, height);
        return pixels;
    }

    private static void indicatorTouch(SlotView v, int action, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, v.screenX(360), v.screenY(y), 0);
        try { v.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    private RectF bounds(int offset) {
        RectF bounds = new RectF();
        t.runOnMainSync(() -> {
            if (offset == 0) t.view().selectedCardBounds(bounds);
            else t.view().adjacentCardBounds(offset, bounds);
        });
        return bounds;
    }

    private void awaitAudio() {
        long until = SystemClock.uptimeMillis() + 5000;
        while (!t.view().cartridgeAudio().ready() && SystemClock.uptimeMillis() < until) SystemClock.sleep(5);
        t.require(t.view().cartridgeAudio().ready(), "Audio is ready");
    }

    private void releaseAndInspect(float x, float y, Runnable inspect) {
        t.runOnMainSync(() -> {
            long now = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP,
                    t.view().screenX(x), t.view().screenY(y), 0);
            try { t.view().dispatchTouchEvent(up); inspect.run(); }
            finally { up.recycle(); }
        });
    }
}
