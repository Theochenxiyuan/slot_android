package fyi.slot.android;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/** Transient target initial, independent of the shelf's spring animation. */
final class ShelfLetterHint {
    static final long HOLD_MS = 500, FADE_MS = 250;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    private final RectF box = new RectF();
    private final Typeface face = Typeface.create("sans-serif", Typeface.BOLD);
    private String letter = "";
    private long shownAt;

    void show(String title, long now) {
        letter = new String(Character.toChars(ShelfLetterIndex.initial(title)));
        shownAt = now;
    }

    void clear() { letter = ""; }

    float opacity(long now) {
        if (letter.isEmpty()) return 0;
        long elapsed = Math.max(0, now - shownAt);
        if (elapsed <= HOLD_MS) return 1;
        return Math.max(0, 1f - (elapsed - HOLD_MS) / (float) FADE_MS);
    }

    boolean isVisible(long now) { return opacity(now) > 0; }
    long nextFrameDelay(long now) {
        if (!isVisible(now)) return Long.MAX_VALUE;
        return Math.max(0, HOLD_MS - Math.max(0, now - shownAt));
    }
    String text(long now) { return isVisible(now) ? letter : ""; }

    void draw(Canvas canvas, HomeLayout layout, long now) {
        float alpha = opacity(now);
        if (alpha == 0) return;
        float cx = layout.centerX(), cy = layout.shelfCenterY();
        box.set(cx - 44, cy - 44, cx + 44, cy + 44);
        paint.setColor(0xee1b1b20); paint.setAlpha(Math.round(238 * alpha));
        canvas.drawRoundRect(box, 14, 14, paint);
        paint.setTypeface(face); paint.setTextSize(52); paint.setTextAlign(Paint.Align.LEFT);
        paint.getTextBounds(letter, 0, letter.length(), bounds);
        paint.setColor(0xffe0e0e1); paint.setAlpha(Math.round(255 * alpha));
        canvas.drawText(letter, cx - bounds.exactCenterX(), cy - bounds.exactCenterY(), paint);
        paint.setAlpha(255);
    }
}
