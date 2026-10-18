package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/** Focused initial navigation and bilingual entry alignment checks. */
final class LetterNavigationChecks {
    private final InteractionChecks t;

    LetterNavigationChecks(InteractionChecks tests) { t = tests; }

    void run() {
        t.check(153, "shelfUpDownJumpAndWrapWithoutInsertion", () -> {
            t.reset(1, false);
            t.key(KeyEvent.KEYCODE_DPAD_DOWN); t.settle(650);
            t.require(t.view().selectedIndex() == 2 && t.view().insertionProgress() == 0, "Down must jump M to O");
            t.key(KeyEvent.KEYCODE_DPAD_UP); t.settle(650);
            t.require(t.view().selectedIndex() == 1, "Up must jump O to M");
            t.reset(8, false); t.key(KeyEvent.KEYCODE_DPAD_DOWN); t.settle(650);
            t.require(t.view().selectedIndex() == 0, "Down must wrap last to first");
            t.key(KeyEvent.KEYCODE_DPAD_UP); t.settle(650);
            t.require(t.view().selectedIndex() == 8 && t.view().canBrowse(), "Up must wrap first to last");
        });
        t.check(154, "upReturnsToGroupStartBeforePreviousGroup", () -> t.runOnMainSync(() -> {
            ShelfModel model = new ShelfModel(List.of(card("a1", "Alpha", Cartridge.Platform.GBA),
                    card("a2", "Advance", Cartridge.Platform.GBA), card("b", "Beta", Cartridge.Platform.GBA)));
            model.current().select(1);
            SlotView view = new SlotView(t.getTargetContext(), model);
            try {
                key(view, KeyEvent.KEYCODE_DPAD_UP);
                t.require(view.selectedIndex() == 0, "First up returns to A start");
                key(view, KeyEvent.KEYCODE_DPAD_UP);
                t.require(view.selectedIndex() == 2 && view.shelfTargetPosition() == -1, "Second up wraps to B in reverse");
                key(view, KeyEvent.KEYCODE_DPAD_DOWN);
                t.require(view.selectedIndex() == 0 && view.insertionProgress() == 0, "Down wraps to A start");
            } finally { view.onDetachedFromWindow(); }
        }));
        t.check(155, "emptySingleAndSameLetterViewsDoNotInsert", () -> t.runOnMainSync(() -> {
            for (List<Cartridge> cards : List.of(List.<Cartridge>of(),
                    List.of(card("a", "Alpha", Cartridge.Platform.GBA)),
                    List.of(card("a", "Alpha", Cartridge.Platform.GBA), card("a2", "Advance", Cartridge.Platform.GBA)))) {
                SlotView view = new SlotView(t.getTargetContext(), new ShelfModel(cards));
                try {
                    key(view, KeyEvent.KEYCODE_DPAD_DOWN); key(view, KeyEvent.KEYCODE_DPAD_UP);
                    view.layout(0, 0, 960, 640);
                    Bitmap frame = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
                    try { view.draw(new Canvas(frame)); } finally { frame.recycle(); }
                    t.require(view.selectedIndex() == (cards.isEmpty() ? -1 : 0)
                            && view.insertionProgress() == 0 && view.currentMode() == SlotView.Mode.SHELF
                            && view.letterHintText().isEmpty(),
                            "Inert letter navigation must not insert or open a panel");
                } finally { view.onDetachedFromWindow(); }
            }
        }));
        t.check(156, "letterJumpStopsHorizontalHoldAndIgnoresAutoRepeat", () -> {
            t.reset(0, false); t.press(KeyEvent.KEYCODE_DPAD_RIGHT);
            t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            int selected = t.view().selectedIndex();
            t.runOnMainSync(() -> t.activity().dispatchKeyEvent(new KeyEvent(0, SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 1)));
            t.settle(900); t.release(KeyEvent.KEYCODE_DPAD_RIGHT);
            t.require(selected == 2 && t.view().selectedIndex() == selected && !t.view().isNavigationKeyHeld(),
                    "Letter jump must stop horizontal repeat and ignore system repeats");
        });
        t.check(157, "upDownKeepMenuAndSettingsNavigation", () -> {
            t.reset(1, false); t.openCategories(); t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.require(t.view().categoryMenuRow() == 1 && t.view().selectedIndex() == 1, "Menu down selects a category only");
            t.key(KeyEvent.KEYCODE_DPAD_UP); t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(450);
            t.key(KeyEvent.KEYCODE_BUTTON_Y); t.settle(450); t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.require("appearance".equals(t.home().settingsPanel().groupId()) && t.view().selectedIndex() == 1,
                    "Settings down must select a tab without moving the shelf");
            t.key(KeyEvent.KEYCODE_DPAD_UP); t.key(KeyEvent.KEYCODE_BUTTON_B); t.settle(450);
        });
        t.check(158, "confirmInsertsAndUpStillCancelsOrEjects", () -> {
            t.reset(1, false);
            t.runOnMainSync(() -> {
                key(t.view(), KeyEvent.KEYCODE_BUTTON_A);
                key(t.view(), KeyEvent.KEYCODE_DPAD_DOWN);
                key(t.view(), KeyEvent.KEYCODE_DPAD_UP);
            });
            t.settle(800);
            t.require(t.view().selectedIndex() == 1 && t.view().canBrowse(), "Up during insertion cancels without jumping");
            t.key(KeyEvent.KEYCODE_ENTER); t.settle(2000);
            t.require(t.view().currentMode() == SlotView.Mode.PREVIEW, "Enter still inserts");
            t.key(KeyEvent.KEYCODE_DPAD_DOWN);
            t.require(t.view().selectedIndex() == 1, "Down while inserted must not navigate");
            t.key(KeyEvent.KEYCODE_DPAD_UP); t.settle(2000);
            t.require(t.view().canBrowse() && t.view().selectedIndex() == 1, "Up while inserted still ejects");
        });
        t.check(159, "letterSelectionSurvivesRecreation", () -> {
            t.reset(1, false); t.key(KeyEvent.KEYCODE_DPAD_DOWN); t.recreateActivity();
            t.require(t.view().selectedIndex() == 2 && t.view().canBrowse() && !t.view().isNavigationKeyHeld()
                    && t.view().letterHintText().isEmpty(),
                    "Recreation retains the jumped selection at rest");
        });
        t.check(160, "letterGroupsRebuildAndRespectPlatform", () -> t.runOnMainSync(() -> {
            ShelfModel model = new ShelfModel(List.of(card("a", "Alpha", Cartridge.Platform.GBA),
                    card("b", "Beta", Cartridge.Platform.GB), card("z", "Zelda", Cartridge.Platform.GBA)));
            model.selectCategory(ShelfModel.Category.GBA);
            t.require(model.current().letterJumpDelta(1) == 1, "GBA jump excludes GB");
            model.replace(List.of(card("a", "Alpha", Cartridge.Platform.GBA),
                    card("a2", "Advance", Cartridge.Platform.GBA), card("c", "Castlevania", Cartridge.Platform.GBA)));
            t.require(model.current().letterJumpDelta(1) == 2, "Replacement rebuilds groups and retains selection");
        }));
        t.check(161, "bilingualEntryInkCentersWithinBothButtons", () -> t.runOnMainSync(() -> {
            for (String label : List.of("\u8BBE\u7F6E", "\u5E94\u7528", "\u8FD4\u56DE", "Settings", "Apps", "Back")) {
                Bitmap frame = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
                try {
                    Canvas canvas = new Canvas(frame); canvas.drawColor(MachineRenderer.HOUSING);
                    HomeLayout layout = HomeLayout.of(960, 640, 0, 0, 0, 0, 1);
                    canvas.scale(layout.scale, layout.scale);
                    MachineRenderer.Entry entry = new MachineRenderer.Entry("Y", label);
                    new MachineRenderer().drawEntries(canvas, layout, 419, entry, entry, MachinePanelController.Content.SETTINGS);
                    Paint paint = new Paint(); paint.setTextSize(17);
                    float width = 29 + paint.measureText(label);
                    for (float cx : new float[]{76, 644}) {
                        int split = Math.round((cx - width / 2 + 25) * layout.scale);
                        Rect badge = ink(frame, Math.round((cx - 56) * layout.scale), split);
                        Rect text = ink(frame, split, Math.round((cx + 56) * layout.scale));
                        t.require(!badge.isEmpty() && !text.isEmpty(), "Entry must contain visible badge and label");
                        float cy = (419 + HomeLayout.ENTRY_CENTER) * layout.scale;
                        t.require(Math.abs(badge.exactCenterY() - cy) <= 1 && Math.abs(text.exactCenterY() - cy) <= 1.5,
                                label + " vertical centers: badge=" + badge.exactCenterY() + ", text=" + text.exactCenterY() + ", expected=" + cy);
                        t.require(Math.abs((badge.left + text.right) / 2f - cx * layout.scale) <= 2,
                                label + " icon and label must be horizontally centered together");
                    }
                    if (label.equals("\u8BBE\u7F6E") || label.equals("Settings")) {
                        File file = new File(t.getTargetContext().getExternalFilesDir(null),
                                label.equals("\u8BBE\u7F6E") ? "slot-entry-centered-zh.png" : "slot-entry-centered-en.png");
                        try (FileOutputStream out = new FileOutputStream(file)) { frame.compress(Bitmap.CompressFormat.PNG, 100, out); }
                        catch (IOException error) { throw new AssertionError(error); }
                    }
                } finally { frame.recycle(); }
            }
        }));
        new LetterHintChecks(t).run();
        t.reset(1, false);
    }

    private static Cartridge card(String id, String title, Cartridge.Platform platform) {
        return new Cartridge(id, title, "", 0xff35353a, 0xffdfab61, 0, platform, CartridgeRenderer.Finish.SOLID);
    }

    private static void key(SlotView view, int key) {
        view.handleKeyDown(key, new KeyEvent(KeyEvent.ACTION_DOWN, key));
        view.handleKeyUp(key, new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    private static Rect ink(Bitmap frame, int left, int right) {
        Rect bounds = new Rect();
        for (int y = 570; y < 630; y++) for (int x = left; x < right; x++) {
            int pixel = frame.getPixel(x, y);
            if (Color.red(pixel) > 160 && Color.green(pixel) > 160 && Color.blue(pixel) > 160) {
                if (bounds.isEmpty()) bounds.set(x, y, x + 1, y + 1); else bounds.union(x, y, x + 1, y + 1);
            }
        }
        return bounds;
    }
}
