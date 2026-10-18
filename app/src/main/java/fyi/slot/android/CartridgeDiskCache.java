package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Optional persistence never holds up artwork publication or the next render. */
final class CartridgeDiskCache {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final File directory;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), new ThreadPoolExecutor.DiscardPolicy());

    CartridgeDiskCache(File directory) { this.directory = directory; }

    Bitmap read(Cartridge cartridge, CartridgeRenderer.Spec spec) {
        File file = path(cartridge);
        if (!file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth != spec.width || bounds.outHeight != spec.height) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    void write(Cartridge cartridge, Bitmap bitmap) {
        writer.execute(() -> {
            File file = path(cartridge), temporary = null;
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) return;
                temporary = File.createTempFile(".cart-", ".tmp", directory);
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG encoding failed");
                }
                if (!temporary.renameTo(file)) throw new IOException("Cache rename failed");
                trim();
            } catch (IOException | RuntimeException error) {
                android.util.Log.w("SlotLibrary", "Disk cache unavailable", error);
            } finally { if (temporary != null) temporary.delete(); }
        });
    }

    private File path(Cartridge cartridge) { return new File(directory, hash("3|" + cartridge.appearanceKey) + ".png"); }

    private void trim() {
        File[] files = directory.listFiles(file -> file.getName().endsWith(".png"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long bytes = 0; for (File file : files) bytes += file.length();
        for (File file : files) { if (bytes <= MAX_BYTES) break; long size = file.length(); if (file.delete()) bytes -= size; }
    }

    private static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    void release() { writer.getQueue().clear(); writer.shutdown(); }
}
