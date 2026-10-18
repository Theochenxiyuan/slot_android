package fyi.slot.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Navigation-ranked background artwork with separate label publication and bounded memory. */
final class CartridgeCache {
    static final int MAX_CARD_BYTES = 20 * 1024 * 1024, MAX_LABEL_BYTES = 8 * 1024 * 1024;
    static final int MAX_PENDING = 12, BOXART_EDGE = 640;
    private final LruCache<String, Bitmap> cache = bitmapCache(MAX_CARD_BYTES);
    private final LruCache<String, Bitmap> labels = bitmapCache(MAX_LABEL_BYTES);
    private final LruCache<String, Bitmap> boxarts = new LruCache<>(3);
    private final java.util.Set<String> boxartPending = new java.util.HashSet<>(), boxartFailed = new java.util.HashSet<>();
    private final LinkedHashMap<String, Cartridge> pending = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CartridgeRenderer renderer;
    private final CartridgeDiskCache disk;
    private final Typeface face;
    private final Runnable ready;
    private boolean running, closed;
    private int generation;
    private String active;
    private Set<String> wanted;

    CartridgeCache(Context context, CartridgeRenderer renderer, Runnable ready) {
        this.renderer = renderer; this.ready = ready;
        disk = new CartridgeDiskCache(new File(context.getCacheDir(), "cartridges"));
        face = Typeface.createFromAsset(context.getAssets(), "label.ttf");
    }
    private static LruCache<String, Bitmap> bitmapCache(int bytes) {
        return new LruCache<>(bytes) {
            @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
        };
    }

    /** Rebuild only waiting work; an in-flight obsolete render cannot delay more than one card. */
    synchronized void prioritize(List<Cartridge> cartridges) {
        if (closed) return;
        wanted = new HashSet<>(); pending.clear();
        for (Cartridge cartridge : cartridges) {
            if (cartridge.rom == null || !wanted.add(cartridge.appearanceKey)) continue;
            if (pending.size() < MAX_PENDING && (cache.get(cartridge.appearanceKey) == null || labels.get(cartridge.appearanceKey) == null)
                    && !cartridge.appearanceKey.equals(active)) pending.put(cartridge.appearanceKey, cartridge);
        }
        startWorker();
    }

    private void startWorker() {
        if (!running && !pending.isEmpty()) { running = true; worker.execute(this::drain); }
    }

    synchronized Bitmap get(Cartridge cartridge) {
        Bitmap bitmap = cache.get(cartridge.appearanceKey);
        if (bitmap != null || closed) return bitmap;
        if (wanted != null && !wanted.contains(cartridge.appearanceKey)) return null;
        if (wanted != null && pending.size() >= MAX_PENDING) return null;
        if (!cartridge.appearanceKey.equals(active)) pending.put(cartridge.appearanceKey, cartridge);
        while (pending.size() > MAX_PENDING) pending.remove(pending.keySet().iterator().next());
        startWorker();
        return null;
    }
    Bitmap interior(Cartridge cartridge) {
        if (cartridge == null || cartridge.finish == CartridgeRenderer.Finish.SOLID) return null;
        return renderer.interior(cartridge.shape, cartridge.plastic);
    }
    synchronized Bitmap label(Cartridge cartridge) {
        if (cartridge == null) return null;
        Bitmap cached = labels.get(cartridge.appearanceKey);
        if (cached != null && !cached.isRecycled()) return cached;
        if (!closed && (wanted == null || wanted.contains(cartridge.appearanceKey))
                && !cartridge.appearanceKey.equals(active) && pending.size() < MAX_PENDING) {
            pending.put(cartridge.appearanceKey, cartridge); startWorker();
        }
        return null;
    }
    /** Box art for the seated screen, decoded off the UI thread; null while loading or when unreadable. */
    synchronized Bitmap boxart(Cartridge cartridge) {
        String key = boxartKey(cartridge);
        if (key == null || closed || boxartFailed.contains(key)) return null;
        Bitmap cached = boxarts.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        if (boxartPending.add(key)) {
            File file = cartridge.boxart; int token = generation;
            worker.execute(() -> {
                Bitmap bitmap = decode(file, BOXART_EDGE, BOXART_EDGE);
                synchronized (this) {
                    if (closed || generation != token) { if (bitmap != null) bitmap.recycle(); return; }
                    boxartPending.remove(key);
                    if (bitmap == null) boxartFailed.add(key); else boxarts.put(key, bitmap);
                    main.post(ready);
                }
            });
        }
        return null;
    }
    synchronized boolean boxartFailed(Cartridge cartridge) {
        String key = boxartKey(cartridge);
        return key != null && boxartFailed.contains(key);
    }
    private static String boxartKey(Cartridge cartridge) {
        if (cartridge == null || cartridge.boxart == null) return null;
        return cartridge.boxart.getAbsolutePath() + ":" + cartridge.boxart.lastModified() + ":" + cartridge.boxart.length();
    }
    private void drain() {
        for (;;) {
            Cartridge cartridge; int token;
            synchronized (this) {
                if (closed || pending.isEmpty()) { running = false; active = null; return; }
                String key = pending.keySet().iterator().next(); cartridge = pending.remove(key); active = key; token = generation;
            }
            Bitmap labelBitmap = renderLabel(cartridge, true);
            synchronized (this) {
                if (!accepts(cartridge, token)) { labelBitmap.recycle(); active = null; continue; }
                labels.put(cartridge.appearanceKey, labelBitmap);
                main.post(ready);
            }
            Bitmap bitmap;
            try { bitmap = disk.read(cartridge, renderer.spec(cartridge.shape)); }
            catch (RuntimeException error) { bitmap = null; }
            boolean generated = bitmap == null;
            if (generated) bitmap = renderer.render(cartridge.shape, cartridge.plastic, cartridge.finish, labelBitmap);
            if (cartridge.finish != CartridgeRenderer.Finish.SOLID) {
                renderer.interior(cartridge.shape, cartridge.plastic);
            }
            synchronized (this) {
                if (accepts(cartridge, token)) {
                    cache.put(cartridge.appearanceKey, bitmap);
                    main.post(ready);
                    if (generated) disk.write(cartridge, bitmap);
                } else {
                    bitmap.recycle();
                }
                active = null;
            }
        }
    }

    private boolean accepts(Cartridge cartridge, int token) {
        return !closed && generation == token && (wanted == null || wanted.contains(cartridge.appearanceKey));
    }
    Bitmap renderLabel(Cartridge cartridge, boolean useLabel) {
        CartridgeRenderer.Spec spec = renderer.spec(cartridge.shape);
        Bitmap label = Bitmap.createBitmap(spec.labelWidth, spec.labelHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(label);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Bitmap image = useLabel ? decode(cartridge.label, spec.labelWidth, spec.labelHeight) : null;
        if (image != null) {
            float scale = Math.max((float) spec.labelWidth / image.getWidth(), (float) spec.labelHeight / image.getHeight());
            float width = image.getWidth() * scale, height = image.getHeight() * scale;
            canvas.drawBitmap(image, null, new RectF((spec.labelWidth - width) / 2, (spec.labelHeight - height) / 2,
                    (spec.labelWidth + width) / 2, (spec.labelHeight + height) / 2), paint);
            image.recycle();
        } else {
            int hue = Math.floorMod(cartridge.title.hashCode(), 360);
            canvas.drawColor(Color.HSVToColor(new float[]{hue, .35f, .68f}));
            paint.setColor(0xfff4f1ea); paint.setTypeface(face); paint.setTextAlign(Paint.Align.LEFT);
            android.text.TextPaint text = new android.text.TextPaint(paint);
            android.text.StaticLayout lines = null;
            for (int size = 28; size >= 10; size--) {
                text.setTextSize(size);
                lines = android.text.StaticLayout.Builder.obtain(cartridge.title, 0, cartridge.title.length(), text, spec.labelWidth - 24)
                        .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setMaxLines(3)
                        .setEllipsize(android.text.TextUtils.TruncateAt.END).build();
                if (lines.getHeight() <= spec.labelHeight - 20 && lines.getEllipsisCount(lines.getLineCount() - 1) == 0) break;
            }
            canvas.save(); canvas.translate(12, (spec.labelHeight - lines.getHeight()) / 2f); lines.draw(canvas); canvas.restore();
        }
        return label;
    }
    static Bitmap decode(File file, int width, int height) {
        if (file == null || !file.isFile() || file.length() > 16 * 1024 * 1024) return null;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0 || (long) options.outWidth * options.outHeight > 32_000_000) return null;
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > width * 2 || options.outHeight / options.inSampleSize > height * 2) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }
    synchronized int bitmapBytes() { return cache.size() + labels.size(); }
    synchronized int pendingCount() { return pending.size(); }
    synchronized void clear() {
        generation++; pending.clear(); wanted = null; active = null; cache.evictAll(); labels.evictAll();
        boxarts.evictAll(); boxartPending.clear(); boxartFailed.clear();
    }
    synchronized void release() { closed = true; clear(); worker.shutdown(); disk.release(); main.removeCallbacksAndMessages(null); }
}
