package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** The seated card shows box art as a square picture, and keeps the label when there is none or it cannot be read. */
final class SeatedBoxArtChecks {
    private static final int SIZE_W = 960, SIZE_H = 640;

    private SeatedBoxArtChecks() { }

    static void run(InteractionChecks t) throws IOException {
        File folder = t.getTargetContext().getCacheDir();
        File art = new File(folder, "seated-box.png"), broken = new File(folder, "seated-broken.png");
        SlotView[] views = new SlotView[3];
        try {
            Bitmap picture = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            picture.eraseColor(0xffd02020);
            try (FileOutputStream out = new FileOutputStream(art)) { picture.compress(Bitmap.CompressFormat.PNG, 100, out); }
            picture.recycle();
            Files.write(broken.toPath(), "not an image".getBytes(StandardCharsets.UTF_8));
            t.runOnMainSync(() -> {
                views[0] = seated(t, art);
                views[1] = seated(t, broken);
                views[2] = seated(t, null);
                for (SlotView view : views) frame(view);
            });
            t.settle(700);
            t.runOnMainSync(() -> {
                int[] boxed = sample(views[0]), unreadable = sample(views[1]), plain = sample(views[2]);
                t.require(red(boxed[0]), "Box art fills the middle of the seated card");
                t.require(!red(boxed[1]) && boxed[1] == 0xff000000, "Box art card is square, narrower than a label card");
                t.require(!red(unreadable[0]) && unreadable[0] != 0xff000000, "Unreadable box art keeps the label card");
                t.require(unreadable[1] != 0xff000000 && unreadable[1] == plain[1], "Fallback card is as wide as the plain label card");
                t.require(plain[0] != 0xff000000 && !red(plain[0]), "A cart with no box art shows its label card");
            });
        } finally {
            t.runOnMainSync(() -> { for (SlotView view : views) if (view != null) view.onDetachedFromWindow(); });
            art.delete();
            broken.delete();
        }
    }

    private static SlotView seated(InteractionChecks t, File boxart) {
        RomRecord rom = new RomRecord(t.getTargetContext().getFilesDir().getPath(), "Box Art Sample.gba",
                Cartridge.Platform.GBA, 512, 0, new byte[0x150], null, false);
        Cartridge cart = new Cartridge(rom, new ShellConfig.Choice("auto", 0xff2f5cc0, CartridgeRenderer.Finish.SOLID), null, boxart);
        SlotView view = new SlotView(t.getTargetContext(), new ShelfModel(List.of(cart)));
        view.layout(0, 0, SIZE_W, SIZE_H);
        view.language().select(AppLanguage.Choice.ENGLISH);
        view.onLanguageChanged();
        view.interactionPreferences().lightTheme = false;
        view.interactionPreferences().wallpaper = InteractionPreferences.Wallpaper.NONE;
        view.interactionPreferences().showTitles = false;
        android.os.Bundle state = new android.os.Bundle();
        state.putString("shelf_selected_ALL", cart.id);
        state.putBoolean("inserted", true);
        view.restore(state);
        return view;
    }

    private static Bitmap frame(SlotView view) {
        Bitmap bitmap = Bitmap.createBitmap(SIZE_W, SIZE_H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    /** The card's middle, and a point beyond a square card's edge that a label card still covers. */
    private static int[] sample(SlotView view) {
        Bitmap bitmap = frame(view);
        float cx = view.homeLayout().centerX(), y = view.homeLayout().machineClosedTop() / 2f;
        int[] pixels = {bitmap.getPixel(Math.round(view.screenX(cx)), Math.round(view.screenY(y))),
                bitmap.getPixel(Math.round(view.screenX(cx + 105f)), Math.round(view.screenY(y)))};
        bitmap.recycle();
        return pixels;
    }

    private static boolean red(int pixel) {
        return Math.abs(Color.red(pixel) - 0xd0) < 10 && Color.green(pixel) < 0x40 && Color.blue(pixel) < 0x40;
    }
}
