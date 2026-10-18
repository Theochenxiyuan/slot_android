package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/** Draws the handheld body in moulded plastic: cartridge opening, side entries and the battery bay revealed when lifted. */
final class MachineRenderer {
    static final int HOUSING = Color.rgb(36, 36, 41);

    /** One side entry as currently labelled. */
    static final class Entry {
        final String key, label;
        Entry(String key, String label) { this.key = key; this.label = label; }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final PlasticFinish finish = new PlasticFinish();
    private final PlasticFinish.Shade housingShade = new PlasticFinish.Shade(160f);
    private final SlotOpening opening = new SlotOpening();
    private final BayRecess bay = new BayRecess();
    private final BayTitlePlate titlePlate = new BayTitlePlate();
    private final RectF box = new RectF();
    private final Rect textBounds = new Rect();
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private MachinePalette palette = MachinePalette.DARK;

    void setLight(boolean light) { palette = light ? MachinePalette.LIGHT : MachinePalette.DARK; }

    /** Cavity drawn behind an inserting cartridge. */
    void drawBack(Canvas c, HomeLayout layout, float top) {
        c.save();
        c.translate(0, top);
        opening.drawCavity(c, layout, palette, finish.grain(layout, palette.grain));
        c.restore();
    }

    /** Housing around the opening, drawn over an inserting cartridge. */
    void drawFront(Canvas c, HomeLayout layout, float top) {
        MachinePalette p = palette;
        float width = layout.width, below = layout.height - top;
        Paint shade = housingShade.at(p.housing, 0);
        c.save();
        c.translate(0, top);
        if (below > SlotOpening.BAND - 1) c.drawRect(0, SlotOpening.BAND - 1, width, below, shade);
        Bitmap band = opening.band(layout, p, shade);
        box.set(0, 0, band.getWidth() / layout.scale, band.getHeight() / layout.scale);
        paint.setAlpha(255);
        c.drawBitmap(band, null, box, paint);
        c.clipRect(0, 0, width, below);
        c.clipOutPath(opening.hole(width));
        c.drawRect(0, 0, width, below, finish.grain(layout, p.grain));
        c.restore();
    }

    void drawEntries(Canvas c, HomeLayout layout, float top, Entry left, Entry right,
                     MachinePanelController.Content pressed) {
        float y = top + HomeLayout.ENTRY_CENTER;
        if (pressed != null) {
            boolean isLeft = pressed == MachinePanelController.Content.SETTINGS;
            float l = isLeft ? HomeLayout.ENTRY_INSET : layout.width - HomeLayout.ENTRY_INSET - HomeLayout.ENTRY_WIDTH;
            box.set(l, top + HomeLayout.ENTRY_TOP + 2, l + HomeLayout.ENTRY_WIDTH, top + HomeLayout.ENTRY_BOTTOM - 2);
            paint.setColor(palette.pressed); c.drawRoundRect(box, 8, 8, paint);
        }
        drawEntry(c, left, HomeLayout.ENTRY_INSET + HomeLayout.ENTRY_WIDTH / 2f, y);
        drawEntry(c, right, layout.width - HomeLayout.ENTRY_INSET - HomeLayout.ENTRY_WIDTH / 2f, y);
    }

    private void drawEntry(Canvas c, Entry entry, float cx, float cy) {
        paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(17);
        float width = 20 + 9 + paint.measureText(entry.label);
        float left = cx - width / 2f;
        float baseline = centeredBaseline(entry.label, cy);
        drawKeyBadge(c, entry.key, left + 10, cy);
        text(c, entry.label, left + 29, baseline, 17, Typeface.DEFAULT, Paint.Align.LEFT);
    }

    private float centeredBaseline(String value, float cy) {
        paint.getTextBounds(value, 0, value.length(), textBounds);
        return cy - textBounds.exactCenterY();
    }

    /** Battery-bay recess below the opening, with the current title moulded into its ridge. */
    void drawBay(Canvas c, HomeLayout layout, float top, String title, String previous,
                 float leaving, float entering, float swap) {
        c.save();
        c.translate(0, top);
        bay.draw(c, layout, palette, finish.grain(layout, palette.bayGrain));
        c.restore();
        float cx = layout.centerX(), cy = top + HomeLayout.TITLE_CENTER;
        float width = titleWidth(title);
        if (previous != null) width = Math.round(width * swap + titleWidth(previous) * (1f - swap));
        titlePlate.draw(c, layout, palette, width, cx, cy);
        if (previous != null) {
            if (leaving > 0f) drawTitle(c, previous, cx, cy, leaving);
            if (entering > 0f) drawTitle(c, title, cx, cy, entering);
        } else {
            drawTitle(c, title, cx, cy, 1f);
        }
    }

    /** The recess hugs its label but never grows past the ridge it sits in. */
    private float titleWidth(String title) {
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(BayTitlePlate.textSize());
        return Math.round(Math.min(HomeLayout.BAY_RIDGE_HALF * 2 - 22f,
                Math.max(92f, paint.measureText(title) + 38f)));
    }

    private void drawTitle(Canvas c, String title, float cx, float cy, float alpha) {
        paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(BayTitlePlate.textSize());
        paint.setColor(palette.ink);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, alpha))));
        c.drawText(title, cx, cy - (paint.ascent() + paint.descent()) / 2, paint);
        paint.setAlpha(255);
    }

    void drawKeyBadge(Canvas c, String key, float x, float y) {
        paint.setColor(palette.ink);
        c.drawCircle(x, y, 10, paint);
        paint.setTypeface(bold); paint.setTextSize(12);
        float baseline = centeredBaseline(key, y);
        paint.setColor(palette.badgeInk); paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(key, x, baseline, paint);
    }

    private void text(Canvas c, String value, float x, float y, float size, Typeface face, Paint.Align align) {
        paint.setColor(palette.ink); paint.setTypeface(face); paint.setTextSize(size); paint.setTextAlign(align);
        c.drawText(value, x, y, paint);
    }
}
