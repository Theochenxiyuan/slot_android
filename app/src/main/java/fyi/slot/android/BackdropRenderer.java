package fyi.slot.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import java.io.File;

/** Ground wallpaper and scrim layer behind the shelf, menus and seated cartridge view. */
final class BackdropRenderer {
    static final float SCRIM = .62f;
    private static final String WALLPAPER_FILENAME = "wallpaper.png";

    private final Context context;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    private Bitmap customWallpaper;
    private Bitmap cachedCover;
    private Bitmap cachedSource;
    private int cachedWidth, cachedHeight;

    BackdropRenderer(Context context) {
        this.context = context.getApplicationContext();
        reloadCustomWallpaper();
    }

    static File wallpaperFile(Context context) {
        return new File(context.getFilesDir(), WALLPAPER_FILENAME);
    }

    static boolean hasCustomWallpaper(Context context) {
        File file = wallpaperFile(context);
        return file.isFile() && file.length() > 0;
    }

    boolean hasCustom() {
        return customWallpaper != null && !customWallpaper.isRecycled();
    }

    void setCustomWallpaper(Bitmap wallpaper) {
        customWallpaper = wallpaper;
        cachedCover = null;
        cachedSource = null;
    }

    void reloadCustomWallpaper() {
        File file = wallpaperFile(context);
        Bitmap decoded = null;
        if (file.isFile() && file.length() > 0) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        }
        setCustomWallpaper(decoded);
    }

    void draw(Canvas c, HomeLayout layout, InteractionPreferences preferences, float extraScrim) {
        if (preferences.wallpaper == InteractionPreferences.Wallpaper.CUSTOM && hasCustom()) {
            int w = Math.max(1, Math.round(layout.width));
            int h = Math.max(1, Math.round(layout.height));
            Bitmap cover = coverFor(customWallpaper, w, h);
            box.set(0, 0, layout.width, layout.height);
            paint.setAlpha(255);
            c.drawBitmap(cover, null, box, paint);
            float tint = preferences.wallpaperTint.alpha;
            if (tint > 0f) {
                paint.setColor(Color.argb(Math.round(tint * 255f), 0, 0, 0));
                c.drawRect(0, 0, layout.width, layout.height, paint);
            }
        }
        float scrim = HomeLayout.clamp(extraScrim, 0f, 1f);
        if (scrim > 0f) {
            paint.setColor(Color.argb(Math.round(scrim * 255f), 0, 0, 0));
            c.drawRect(0, 0, layout.width, layout.height, paint);
        }
    }

    private Bitmap coverFor(Bitmap source, int targetW, int targetH) {
        if (cachedCover != null && !cachedCover.isRecycled()
                && cachedSource == source && cachedWidth == targetW && cachedHeight == targetH) {
            return cachedCover;
        }
        Bitmap scaled = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(scaled);
        float scale = Math.max((float) targetW / source.getWidth(), (float) targetH / source.getHeight());
        float w = source.getWidth() * scale, h = source.getHeight() * scale;
        box.set((targetW - w) / 2f, (targetH - h) / 2f, (targetW + w) / 2f, (targetH + h) / 2f);
        paint.setAlpha(255);
        canvas.drawBitmap(source, null, box, paint);
        cachedCover = scaled;
        cachedSource = source;
        cachedWidth = targetW;
        cachedHeight = targetH;
        return scaled;
    }
}
