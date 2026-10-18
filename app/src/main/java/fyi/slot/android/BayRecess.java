package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Battery-bay recess in coordinates relative to the machine top: a crisp fill plus soft shading
 * (inner shadow, lit lower wall, outer rim) rendered at half resolution once per size and theme.
 */
final class BayRecess {
    private static final float CORNER = 14f, PAD = 16f, RESOLUTION = .5f;

    private final Path shape = new Path(), outside = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private float shapeWidth = -1, shapeHeight = -1;
    private Bitmap shading;
    private float shadingScale;
    private MachinePalette shadingPalette;

    void draw(Canvas c, HomeLayout layout, MachinePalette p, Paint grain) {
        Path bay = shape(layout);
        paint.setColor(p.bay);
        c.drawPath(bay, paint);
        c.save();
        c.clipPath(bay);
        c.drawPath(bay, grain);
        c.restore();
        c.drawBitmap(shading(layout, p), null, bounds, paint);
    }

    private Path shape(HomeLayout layout) {
        if (layout.width == shapeWidth && layout.height == shapeHeight) return shape;
        shapeWidth = layout.width; shapeHeight = layout.height;
        float cx = layout.centerX(), left = HomeLayout.BAY_SIDE, right = layout.width - HomeLayout.BAY_SIDE;
        float ridge = HomeLayout.BAY_RIDGE_TOP, body = HomeLayout.BAY_BODY_TOP, bottom = layout.bayBottomOffset();
        roundedPolygon(shape, CORNER, left, body, cx - HomeLayout.BAY_BASE_HALF, body,
                cx - HomeLayout.BAY_RIDGE_HALF, ridge, cx + HomeLayout.BAY_RIDGE_HALF, ridge,
                cx + HomeLayout.BAY_BASE_HALF, body, right, body, right, bottom, left, bottom);
        bounds.set(left - PAD, ridge - PAD, right + PAD, bottom + PAD);
        outside.reset();
        outside.addRect(bounds.left - 40, bounds.top - 40, bounds.right + 40, bounds.bottom + 40, Path.Direction.CW);
        outside.op(shape, Path.Op.DIFFERENCE);
        shading = null;
        return shape;
    }

    private Bitmap shading(HomeLayout layout, MachinePalette p) {
        if (shading != null && shadingScale == layout.scale && shadingPalette == p) return shading;
        shadingScale = layout.scale; shadingPalette = p;
        float scale = layout.scale * RESOLUTION;
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(bounds.width() * scale)),
                Math.max(1, (int) Math.ceil(bounds.height() * scale)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.scale(bitmap.getWidth() / bounds.width(), bitmap.getHeight() / bounds.height());
        c.translate(-bounds.left, -bounds.top);
        Paint soft = new Paint(Paint.ANTI_ALIAS_FLAG);

        c.save();
        c.clipPath(shape);
        soft.setColor(p.bayShadow);
        soft.setMaskFilter(new BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL));
        c.save(); c.translate(0, 3f); c.drawPath(outside, soft); c.restore();
        soft.setColor(p.bayLowerLight);
        soft.setMaskFilter(new BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL));
        c.save(); c.translate(0, -2.5f); c.drawPath(outside, soft); c.restore();
        c.restore();

        c.save();
        c.clipOutPath(shape);
        soft.setStyle(Paint.Style.STROKE);
        soft.setStrokeWidth(2f);
        soft.setColor(p.bayRim);
        soft.setMaskFilter(new BlurMaskFilter(1.2f, BlurMaskFilter.Blur.NORMAL));
        c.translate(0, 1.2f);
        c.drawPath(shape, soft);
        c.restore();

        shading = bitmap;
        return shading;
    }

    /** Closed polygon from x,y pairs with quadratic corners of the given radius. */
    private static void roundedPolygon(Path path, float radius, float... xy) {
        path.reset();
        int n = xy.length / 2;
        for (int i = 0; i < n; i++) {
            float px = xy[2 * i], py = xy[2 * i + 1];
            int prev = (i + n - 1) % n, next = (i + 1) % n;
            float inX = px - xy[2 * prev], inY = py - xy[2 * prev + 1];
            float outX = xy[2 * next] - px, outY = xy[2 * next + 1] - py;
            float inLength = (float) Math.hypot(inX, inY), outLength = (float) Math.hypot(outX, outY);
            float r = Math.min(radius, Math.min(inLength, outLength) / 2f);
            float ax = px - inX / inLength * r, ay = py - inY / inLength * r;
            float bx = px + outX / outLength * r, by = py + outY / outLength * r;
            if (i == 0) path.moveTo(ax, ay); else path.lineTo(ax, ay);
            path.quadTo(px, py, bx, by);
        }
        path.close();
    }
}
