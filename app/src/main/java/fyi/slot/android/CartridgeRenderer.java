package fyi.slot.android;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

/** Material and compositing port of slot-ui/cart.rs, bitmaps.rs and silhouette.rs, GPL-3.0-or-later.
 * Upstream: BrandonKowalski/slot 34ac38031fd903eb2a0a0157f1dea4d3a5546195 (v1.7.0).
 * Hand-drawn shell, interior and board textures by @MarkusMaribu; see tools/cart-assets and THIRD_PARTY.md.
 */
final class CartridgeRenderer {
    enum Finish { SOLID, TRANSLUCENT, GLITTER }
    private static final int CLEAR_ALPHA = 204, NEUTRAL = 128;
    private final Spec[] specs = new Spec[Cartridge.Platform.values().length];
    private final Bitmap[] backings = new Bitmap[Cartridge.Platform.values().length];
    private final Bitmap[] shadows = new Bitmap[Cartridge.Platform.values().length];
    private final LruCache<String, Bitmap> interiors = new LruCache<>(16);

    static final class Spec {
        final int width, height, labelX, labelY, labelWidth, labelHeight;
        final byte[] mask;
        final int[] solidShell, clearShell, interior, board;

        Spec(AssetManager assets, Cartridge.Platform platform) throws IOException {
            String key = platform.name().toLowerCase(Locale.ROOT);
            try (DataInputStream in = new DataInputStream(assets.open("cartridges/" + key + ".mask"))) {
                width = in.readInt(); height = in.readInt();
                int expectedWidth = platform == Cartridge.Platform.GBA ? 360 : 312;
                int expectedHeight = platform == Cartridge.Platform.GBA ? 212 : 337;
                if (width != expectedWidth || height != expectedHeight) throw new IOException("Invalid mask size");
                mask = new byte[width * height];
                in.readFully(mask);
                if (in.read() != -1) throw new IOException("Invalid mask length");
            }
            if (platform == Cartridge.Platform.GBA) {
                labelX = (width * 133 + 500) / 1000;
                labelY = (height * 236 + 500) / 1000;
                labelWidth = (width * 867 + 500) / 1000 - labelX;
                labelHeight = (height * 877 + 500) / 1000 - labelY;
            } else if (platform == Cartridge.Platform.GB) {
                labelX = (width * 147 + 500) / 1000;
                labelY = (height * 291 + 500) / 1000;
                labelWidth = (width * 854 + 500) / 1000 - labelX;
                labelHeight = (height * 868 + 500) / 1000 - labelY;
            } else {
                labelX = 45;
                labelY = 110;
                labelWidth = 223;
                labelHeight = 199;
            }
            solidShell = decodePixels(assets, "cartridges/" + key + "_neutral.png", width, height);
            String clearName = platform == Cartridge.Platform.GB ? "gb_neutral.png" : key + "_clear.png";
            clearShell = platform == Cartridge.Platform.GB
                    ? solidShell : decodePixels(assets, "cartridges/" + clearName, width, height);
            interior = decodePixels(assets, "cartridges/" + key + "_interior.png", width, height);
            String boardName = platform == Cartridge.Platform.GBA ? "board.png" : "gb_board.png";
            board = decodePixels(assets, "cartridges/" + boardName, width, height);
        }
    }

    CartridgeRenderer(AssetManager assets) {
        try {
            for (Cartridge.Platform platform : Cartridge.Platform.values()) {
                specs[platform.ordinal()] = new Spec(assets, platform);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to load cartridge assets", error);
        }
    }

    Spec spec(Cartridge.Platform platform) { return specs[platform.ordinal()]; }

    Bitmap render(Cartridge.Platform platform, int plastic, Finish finish, Bitmap label) {
        return renderFace(platform, plastic, finish, label);
    }

    Bitmap renderFace(Cartridge.Platform platform, int plastic, Finish finish, Bitmap label) {
        Spec s = spec(platform);
        int[] pixels = renderPixels(platform, plastic, finish, label);
        return Bitmap.createBitmap(pixels, s.width, s.height, Bitmap.Config.ARGB_8888);
    }

    Bitmap renderInterior(Cartridge.Platform platform, int plastic) {
        Spec s = spec(platform);
        int[] pixels = renderInteriorPixels(platform, plastic);
        return Bitmap.createBitmap(pixels, s.width, s.height, Bitmap.Config.ARGB_8888);
    }

    synchronized Bitmap interior(Cartridge.Platform platform, int plastic) {
        String key = platform.name() + "|" + (plastic & 0x00ffffff);
        Bitmap cached = interiors.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap rendered = renderInterior(platform, plastic);
        interiors.put(key, rendered);
        return rendered;
    }

    Bitmap renderBacking(Cartridge.Platform platform) {
        Spec s = spec(platform);
        int[] pixels = renderBackingPixels(platform);
        return Bitmap.createBitmap(pixels, s.width, s.height, Bitmap.Config.ARGB_8888);
    }

    synchronized Bitmap backing(Cartridge.Platform platform) {
        int index = platform.ordinal();
        if (backings[index] == null || backings[index].isRecycled()) {
            backings[index] = renderBacking(platform);
        }
        return backings[index];
    }

    Bitmap renderShadow(Cartridge.Platform platform) {
        Spec s = spec(platform);
        int[] pixels = renderShadowPixels(platform);
        return Bitmap.createBitmap(pixels, s.width, s.height, Bitmap.Config.ARGB_8888);
    }

    synchronized Bitmap shadow(Cartridge.Platform platform) {
        int index = platform.ordinal();
        if (shadows[index] == null || shadows[index].isRecycled()) {
            shadows[index] = renderShadow(platform);
        }
        return shadows[index];
    }

    // Kept before Android's premultiplied bitmap conversion so the port can be checked
    // pixel for pixel against frames generated by the original Rust functions.
    int[] renderPixels(Cartridge.Platform platform, int plastic, Finish finish, Bitmap label) {
        Spec s = spec(platform);
        boolean clear = finish != Finish.SOLID;
        int[] src = clear ? s.clearShell : s.solidShell;
        int[] pixels = new int[s.width * s.height];
        int pr = red(plastic), pg = green(plastic), pb = blue(plastic);
        for (int y = 0; y < s.height; y++) {
            boolean inY = y >= s.labelY && y < s.labelY + s.labelHeight;
            for (int x = 0; x < s.width; x++) {
                boolean onLabel = inY && x >= s.labelX && x < s.labelX + s.labelWidth;
                int i = y * s.width + x;
                int p = src[i];
                int r = red(p), g = green(p), b = blue(p), own = p >>> 24;
                if (!onLabel) {
                    r = overlay(r, pr);
                    g = overlay(g, pg);
                    b = overlay(b, pb);
                }
                int a = onLabel ? 255 : clear ? (own * CLEAR_ALPHA + 127) / 255 : own;
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        if (finish == Finish.GLITTER) {
            for (int y = 0; y < s.height; y++) {
                for (int x = 0; x < s.width; x++) {
                    if (!fleck(x, y)) continue;
                    int i = y * s.width + x;
                    int p = pixels[i];
                    if ((p >>> 24) == 0) continue;
                    pixels[i] = lerp(p, 0xffffffff, 100, 255);
                }
            }
        }
        if (label != null) pasteLabel(s, pixels, label);
        for (int i = 0; i < pixels.length; i++) {
            int a = ((pixels[i] >>> 24) * (s.mask[i] & 255) + 127) / 255;
            pixels[i] = (pixels[i] & 0x00ffffff) | (a << 24);
        }
        return pixels;
    }

    int[] renderInteriorPixels(Cartridge.Platform platform, int plastic) {
        Spec s = spec(platform);
        boolean masked = platform != Cartridge.Platform.GBA;
        int[] pixels = new int[s.width * s.height];
        int pr = red(plastic), pg = green(plastic), pb = blue(plastic);
        for (int i = 0; i < pixels.length; i++) {
            int p = s.interior[i];
            int a = p >>> 24;
            if (masked) a = (a * (s.mask[i] & 255) + 127) / 255;
            if (a == 0) {
                pixels[i] = p & 0x00ffffff;
                continue;
            }
            int r = overlay(red(p), pr);
            int g = overlay(green(p), pg);
            int b = overlay(blue(p), pb);
            a = (a * CLEAR_ALPHA + 127) / 255;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return pixels;
    }

    int[] renderBackingPixels(Cartridge.Platform platform) {
        Spec s = spec(platform);
        if (platform == Cartridge.Platform.GBA) return s.board.clone();
        int[] pixels = new int[s.width * s.height];
        for (int i = 0; i < pixels.length; i++) {
            int p = s.board[i];
            int a = ((p >>> 24) * (s.mask[i] & 255) + 127) / 255;
            pixels[i] = (p & 0x00ffffff) | (a << 24);
        }
        return pixels;
    }

    int[] renderShadowPixels(Cartridge.Platform platform) {
        Spec s = spec(platform);
        int[] pixels = new int[s.width * s.height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (s.mask[i] & 255) << 24;
        }
        return pixels;
    }

    static int[] decodePixels(AssetManager assets, String path, int expectedWidth, int expectedHeight) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPremultiplied = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = assets.open(path)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap == null || bitmap.getWidth() != expectedWidth || bitmap.getHeight() != expectedHeight) {
                if (bitmap != null) bitmap.recycle();
                throw new IOException("Invalid bitmap: " + path);
            }
            byte[] rgba = new byte[expectedWidth * expectedHeight * 4];
            bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rgba));
            bitmap.recycle();
            int[] pixels = new int[expectedWidth * expectedHeight];
            for (int i = 0; i < pixels.length; i++) {
                int j = i * 4;
                pixels[i] = ((rgba[j + 3] & 255) << 24)
                        | ((rgba[j] & 255) << 16)
                        | ((rgba[j + 1] & 255) << 8)
                        | (rgba[j + 2] & 255);
            }
            return pixels;
        }
    }

    static Bitmap decodeBitmap(AssetManager assets, String path) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = assets.open(path)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap == null) throw new IOException("Unable to decode bitmap: " + path);
            return bitmap;
        }
    }

    private static int overlay(int base, int colour) {
        int out = base >= NEUTRAL
                ? colour + ((255 - colour) * (base - NEUTRAL) + 63) / 127
                : colour - (colour * (NEUTRAL - base) + 64) / NEUTRAL;
        return Math.max(0, Math.min(255, out));
    }

    private static boolean fleck(int x, int y) {
        int h = x * 0x9e3779b9 ^ y * 0x85ebca6b;
        h ^= h >>> 15; h *= 0x2c1b3c6d; h ^= h >>> 12;
        return Integer.remainderUnsigned(h, 100) == 0;
    }

    private static int lerp(int a, int b, int num, int den) {
        return rgb((red(a) * (den - num) + red(b) * num) / den,
                (green(a) * (den - num) + green(b) * num) / den,
                (blue(a) * (den - num) + blue(b) * num) / den);
    }

    private static int mix(int from, int to, int alpha) {
        return rgb((red(to) * alpha + red(from) * (255 - alpha) + 127) / 255,
                (green(to) * alpha + green(from) * (255 - alpha) + 127) / 255,
                (blue(to) * alpha + blue(from) * (255 - alpha) + 127) / 255);
    }

    private static void pasteLabel(Spec s, int[] pixels, Bitmap label) {
        if (label.getWidth() != s.labelWidth || label.getHeight() != s.labelHeight) {
            throw new IllegalArgumentException("Invalid label size");
        }
        int[] source = new int[s.labelWidth * s.labelHeight];
        label.getPixels(source, 0, s.labelWidth, 0, 0, s.labelWidth, s.labelHeight);
        for (int y = 0; y < s.labelHeight; y++) for (int x = 0; x < s.labelWidth; x++) {
            int c = source[y * s.labelWidth + x], a = c >>> 24;
            int d = (y + s.labelY) * s.width + x + s.labelX;
            if (a > 0) {
                int blended = mix(pixels[d], c, a);
                pixels[d] = (pixels[d] & 0xff000000) | (blended & 0x00ffffff);
            }
        }
    }

    private static int red(int c) { return (c >>> 16) & 255; }
    private static int green(int c) { return (c >>> 8) & 255; }
    private static int blue(int c) { return c & 255; }
    private static int rgb(int r, int g, int b) { return 0xff000000 | (r << 16) | (g << 8) | b; }
}
