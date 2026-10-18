package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/** Timed target feedback, lifecycle clearing and rendered glyph checks. */
final class LetterHintChecks {
    private final InteractionChecks t;

    LetterHintChecks(InteractionChecks tests) { t = tests; }

    void run() {
        t.check(162, "letterHintHoldsFadesAndRefreshesToLatestInitial", () -> t.runOnMainSync(() -> {
            ShelfLetterHint hint = new ShelfLetterHint();
            hint.show("orbit", 1000);
            t.require("O".equals(hint.text(1000)) && hint.opacity(1500) == 1, "Target initial must appear immediately and hold");
            t.require(Math.abs(hint.opacity(1625) - .5f) < .001f && !hint.isVisible(1750), "Hint must fade then disappear");
            hint.show("007", 1625);
            t.require("#".equals(hint.text(1750)) && hint.opacity(1750) == 1, "New request replaces the letter and restarts its clock");
            hint.show("\u5B9D\u53EF\u68A6", 2000);
            t.require("\u5B9D".equals(hint.text(2000)), "Chinese initial must remain a whole character");
            hint.clear();
            t.require(hint.text(2000).isEmpty() && hint.opacity(2000) == 0, "Clearing removes the hint immediately");
        }));
        t.check(163, "jumpDrawsCenteredTargetGlyphAcrossLayouts", () -> t.runOnMainSync(() -> {
            SlotView view = new SlotView(t.getTargetContext(), ShelfModel.demo());
            try {
                view.layout(0, 0, 960, 640);
                view.handleKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
                view.handleKeyUp(KeyEvent.KEYCODE_DPAD_DOWN, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
                t.require("O".equals(view.letterHintText()), "Jump must display the destination initial");
                Bitmap frame = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
                try {
                    view.draw(new Canvas(frame));
                    HomeLayout layout = view.homeLayout();
                    int left = Math.round(layout.toScreenX(layout.centerX() - 40));
                    int top = Math.round(layout.toScreenY(layout.shelfCenterY() - 40));
                    t.require(Color.red(frame.getPixel(left, top)) < 50, "Dark hint surface must cover the cartridge clearly");
                    export(frame, "slot-letter-hint.png");
                } finally { frame.recycle(); }
            } finally { view.onDetachedFromWindow(); }
            for (int[] size : new int[][]{{960, 640}, {640, 640}, {1280, 720}}) {
                for (String title : List.of("Metroid", "007", "\u5B9D\u53EF\u68A6", "éclair")) {
                    Bitmap frame = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                    try {
                        HomeLayout layout = HomeLayout.of(size[0], size[1], 0, 0, 0, 0, 1);
                        Canvas canvas = new Canvas(frame); canvas.drawColor(Color.BLACK); canvas.scale(layout.scale, layout.scale);
                        ShelfLetterHint hint = new ShelfLetterHint(); hint.show(title, 1000); hint.draw(canvas, layout, 1000);
                        Rect ink = inkBounds(frame);
                        t.require(!ink.isEmpty() && Math.abs(ink.exactCenterX() - layout.toScreenX(layout.centerX())) <= 1.5f
                                && Math.abs(ink.exactCenterY() - layout.toScreenY(layout.shelfCenterY())) <= 1.5f,
                                "Whole target glyph must be readable and centered: " + title);
                    } finally { frame.recycle(); }
                }
            }
        }));
        t.check(164, "otherInteractionsClearLetterHintWithoutChangingSelection", () -> {
            t.reset(1, false); t.key(KeyEvent.KEYCODE_DPAD_DOWN); requireHint("O");
            t.key(KeyEvent.KEYCODE_DPAD_RIGHT); requireHint("");
            t.reset(1, false); t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.key(KeyEvent.KEYCODE_BUTTON_Y); requireHint("");
            t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(450);
            t.reset(1, false); t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.openCategories(); requireHint(""); t.key(KeyEvent.KEYCODE_BUTTON_B);
            t.reset(1, false); t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.runOnMainSync(() -> t.view().loseFocus()); requireHint("");
            t.reset(1, false); t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.runOnMainSync(() -> { t.view().pause(); t.view().resume(); }); requireHint("");
            t.reset(1, false); t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.runOnMainSync(() -> t.dispatchTap(t.view(), 10, 100)); requireHint("");
            t.require(t.view().selectedIndex() == 2, "Dismissing feedback must retain the chosen cartridge");
            t.reset(1, false); t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.key(KeyEvent.KEYCODE_BUTTON_A); requireHint("");
            t.reset(1, false);
        });
        t.check(165, "rapidJumpsRefreshHintAndAutoRepeatDoesNotExtendIt", () -> {
            t.reset(1, false);
            t.key(KeyEvent.KEYCODE_DPAD_DOWN); requireHint("O");
            t.key(KeyEvent.KEYCODE_DPAD_DOWN); requireHint("T");
            t.key(KeyEvent.KEYCODE_DPAD_UP); requireHint("O");
            t.settle(400);
            t.runOnMainSync(() -> t.activity().dispatchKeyEvent(new KeyEvent(0, SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 1)));
            t.settle(420); requireHint("");
            t.require(t.view().selectedIndex() == 2 && t.view().canBrowse(), "Hint expires without input lock or unwanted repeats");
        });
    }

    private void requireHint(String value) { t.require(value.equals(t.view().letterHintText()), "Expected target hint: " + value); }

    private static Rect inkBounds(Bitmap frame) {
        Rect bounds = new Rect();
        for (int y = 0; y < frame.getHeight(); y++) for (int x = 0; x < frame.getWidth(); x++) {
            int pixel = frame.getPixel(x, y);
            if (Color.red(pixel) > 160 && Color.green(pixel) > 160 && Color.blue(pixel) > 160) {
                if (bounds.isEmpty()) bounds.set(x, y, x + 1, y + 1); else bounds.union(x, y, x + 1, y + 1);
            }
        }
        return bounds;
    }

    private void export(Bitmap frame, String name) {
        File file = new File(t.getTargetContext().getExternalFilesDir(null), name);
        try (FileOutputStream output = new FileOutputStream(file)) { frame.compress(Bitmap.CompressFormat.PNG, 100, output); }
        catch (IOException error) { throw new AssertionError(error); }
    }
}
