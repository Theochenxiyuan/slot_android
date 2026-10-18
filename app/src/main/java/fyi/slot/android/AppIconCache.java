package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Decodes app icons off the main thread for the cells currently bound, with a bounded bitmap cache. */
final class AppIconCache {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "slot-icons");
        thread.setDaemon(true);
        return thread;
    });
    private final LruCache<String, Bitmap> cache;
    private final int densityDpi;
    private int size;

    AppIconCache(int densityDpi) {
        this.densityDpi = densityDpi;
        int bytes = (int) Math.min(Runtime.getRuntime().maxMemory() / 16, 24L * 1024 * 1024);
        cache = new LruCache<>(bytes) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
        };
    }

    void setSize(int pixels) {
        if (pixels == size) return;
        size = pixels; cache.evictAll();
    }

    void bind(AppEntry entry, ImageView target) {
        String key = entry.key + "@" + size;
        target.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) { target.setImageBitmap(cached); return; }
        target.setImageDrawable(null);
        int pixels = size;
        if (pixels <= 0) return;
        try {
            worker.execute(() -> {
                Bitmap bitmap = render(entry, pixels);
                main.post(() -> {
                    if (bitmap == null) return;
                    cache.put(key, bitmap);
                    if (key.equals(target.getTag())) target.setImageBitmap(bitmap);
                });
            });
        } catch (RejectedExecutionException ignored) { }
    }

    void release() { worker.shutdownNow(); cache.evictAll(); }

    private Bitmap render(AppEntry entry, int pixels) {
        try {
            Drawable icon = entry.info.getIcon(densityDpi);
            if (icon == null) return null;
            Bitmap bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            icon.setBounds(0, 0, pixels, pixels);
            icon.draw(canvas);
            return bitmap;
        } catch (RuntimeException error) {
            return null;
        }
    }
}
