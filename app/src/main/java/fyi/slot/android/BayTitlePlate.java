package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Title plate on the battery bay's ridge: a shallow moulded recess rather than an outlined pill.
 * A gradient floor, a shadow falling in from the top wall and a lit lower lip make it read as
 * pressed into the plastic. Shading is cached per size and theme.
 */
final class BayTitlePlate {
    private static final float HEIGHT = 28f, RADIUS = 9f, BLEED = 10f, RESOLUTION = .5f;
    private static final float TEXT_SIZE = 17f;

    private final Paint blit = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path shape = new Path(), outside = new Path();
    private final RectF dest = new RectF();
    private Bitmap image;
    private float scale = -1, width = -1;
    private MachinePalette palette;

    /** Draws the recess centred on cx, cy in view pixels, sized to the label it holds. */
    void draw(Canvas c, HomeLayout layout, MachinePalette p, float plateWidth, float cx, float cy) {
        float w = Math.min(HomeLayout.BAY_RIDGE_HALF * 2 - 22f, plateWidth);
        Bitmap bitmap = image(layout, p, w);
        float left = cx - w / 2f - BLEED, top = cy - HEIGHT / 2f - BLEED;
        dest.set(left, top, left + w + BLEED * 2, top + HEIGHT + BLEED * 2);
        c.drawBitmap(bitmap, null, dest, blit);
    }

    private Bitmap image(HomeLayout layout, MachinePalette p, float w) {
        if (image != null && palette == p && scale == layout.scale && width == w) return image;
        palette = p; scale = layout.scale; width = w;

        float plateWidth = w + BLEED * 2, plateHeight = HEIGHT + BLEED * 2;
        float density = layout.scale * RESOLUTION;
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(plateWidth * density)),
                Math.max(1, (int) Math.ceil(plateHeight * density)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.scale(bitmap.getWidth() / plateWidth, bitmap.getHeight() / plateHeight);
        c.translate(BLEED, BLEED);

        shape.reset();
        shape.addRoundRect(new RectF(0, 0, w, HEIGHT), RADIUS, RADIUS, Path.Direction.CW);
        outside.reset();
        outside.addRect(-BLEED, -BLEED, w + BLEED, HEIGHT + BLEED, Path.Direction.CW);
        outside.op(shape, Path.Op.DIFFERENCE);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setShader(new LinearGradient(0, 0, 0, HEIGHT, p.titlePlateTop, p.titlePlateBottom, Shader.TileMode.CLAMP));
        c.drawPath(shape, fill);

        Paint soft = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.save();
        c.clipPath(shape);
        // The top wall shades the upper floor; the lower lip catches a thin line of light.
        c.save();
        c.clipRect(0, 0, w, HEIGHT * .66f);
        soft.setColor(p.bayShadow);
        soft.setMaskFilter(new BlurMaskFilter(2.4f, BlurMaskFilter.Blur.NORMAL));
        c.save(); c.translate(0, 2.6f); c.drawPath(outside, soft); c.restore();
        c.restore();
        c.restore();

        Paint lip = new Paint(Paint.ANTI_ALIAS_FLAG);
        lip.setStyle(Paint.Style.STROKE);
        lip.setStrokeWidth(1.1f);
        lip.setColor(p.rimLight);
        lip.setAlpha(150);
        c.save();
        c.clipRect(RADIUS, HEIGHT - 1.7f, w - RADIUS, HEIGHT);
        c.drawPath(shape, lip);
        c.restore();

        // A faint hairline on the surrounding surface keeps the plate's edge readable on dark plastic.
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1f);
        edge.setColor(p.bayRim);
        c.save();
        c.clipOutPath(shape);
        c.save(); c.translate(0, -1f); c.drawPath(shape, edge); c.restore();
        c.restore();

        image = bitmap;
        return bitmap;
    }

    static float textSize() { return TEXT_SIZE; }
}
