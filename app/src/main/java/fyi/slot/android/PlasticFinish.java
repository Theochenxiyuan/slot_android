package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;

/** Moulded-plastic surface: a lit stipple tile sized to device pixels. */
final class PlasticFinish {
    private static final int TILE = 128;
    /** Device pixels per stipple texel. */
    private static final float TEXEL = 1f;
    private static Bitmap tile;

    private final Paint grain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private float grainScale = Float.NaN;

    PlasticFinish() {
        grain.setShader(new BitmapShader(stipple(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    /** Stipple paint at the given strength for the current layout scale. */
    Paint grain(HomeLayout layout, float strength) {
        float scale = TEXEL / Math.max(.01f, layout.scale);
        if (scale != grainScale) {
            grainScale = scale;
            matrix.setScale(scale, scale);
            grain.getShader().setLocalMatrix(matrix);
        }
        grain.setAlpha(Math.round(HomeLayout.clamp(strength, 0f, 1f) * 255));
        return grain;
    }

    /** A vertical gradient of fixed length whose start follows a moving edge. */
    static final class Shade {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final float length;
        private int[] colors;

        Shade(float length) { this.length = length; }

        Paint at(int[] stops, float top) {
            if (stops != colors) {
                colors = stops;
                paint.setShader(new LinearGradient(0, 0, 0, length, stops, null, Shader.TileMode.CLAMP));
            }
            matrix.setTranslate(0, top);
            paint.getShader().setLocalMatrix(matrix);
            return paint;
        }
    }

    /** Blurred random height field lit from the upper left, stored as white highlights and black shadows. */
    private static synchronized Bitmap stipple() {
        if (tile != null) return tile;
        int n = TILE;
        float[] height = new float[n * n];
        long seed = 91731;
        for (int i = 0; i < height.length; i++) {
            seed = (seed * 1664525 + 1013904223) & 0xffffffffL;
            height[i] = seed / 4294967296f;
        }
        height = blur(height, n);
        int[] pixels = new int[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                seed = (seed * 1664525 + 1013904223) & 0xffffffffL;
                float fine = seed / 4294967296f - .5f;
                float slope = at(height, n, x - 1, y - 1) - at(height, n, x + 1, y + 1);
                float value = slope * 3f + fine * .35f;
                int alpha = Math.min(255, Math.round(Math.abs(value) * 255f * (value < 0 ? 1.3f : 1f)));
                pixels[y * n + x] = value > 0 ? Color.argb(alpha, 255, 255, 255) : Color.argb(alpha, 0, 0, 0);
            }
        }
        tile = Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888);
        return tile;
    }

    private static float[] blur(float[] source, int n) {
        float[] out = new float[source.length];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float sum = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) sum += at(source, n, x + dx, y + dy);
                out[y * n + x] = sum / 9f;
            }
        }
        return out;
    }

    private static float at(float[] values, int n, int x, int y) {
        return values[Math.floorMod(y, n) * n + Math.floorMod(x, n)];
    }
}
