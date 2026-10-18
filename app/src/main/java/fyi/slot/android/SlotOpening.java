package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * Cartridge opening in the housing, in coordinates relative to the machine top.
 * The soft-shaded front band is rendered once per size and theme; the cavity behind is drawn live.
 */
final class SlotOpening {
    static final float HALF = 127f, LEDGE = 28.5f, BAND = 64f;
    private static final float BOWL_HALF = 96f, BOWL_DEPTH = 15.5f, BOWL_POWER = 5.2f, BOWL_EASE = 1.6f;
    private static final float CORNER = 6f, ABOVE = -40f;

    private final Path hole = new Path(), housing = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint wallPaint = new Paint(Paint.ANTI_ALIAS_FLAG), floorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float holeWidth = -1;
    private Bitmap band;
    private float bandWidth, bandScale;
    private MachinePalette bandPalette, cavityPalette;

    /** Opening outline: a straight-sided mouth, a ledge, and a flat-bottomed bowl blending tangentially into it. */
    Path hole(float width) {
        if (width == holeWidth) return hole;
        holeWidth = width;
        float cx = width / 2f;
        Path raw = new Path();
        raw.moveTo(cx - HALF, ABOVE);
        raw.lineTo(cx - HALF, LEDGE);
        for (float x = -BOWL_HALF; x <= BOWL_HALF; x += 1f) raw.lineTo(cx + x, LEDGE + bowl(x));
        raw.lineTo(cx + HALF, LEDGE);
        raw.lineTo(cx + HALF, ABOVE);
        raw.close();
        Paint shaper = new Paint();
        shaper.setPathEffect(new CornerPathEffect(CORNER));
        hole.reset();
        shaper.getFillPath(raw, hole);
        return hole;
    }

    private static float bowl(float x) {
        float t = Math.min(1f, Math.abs(x) / BOWL_HALF);
        return BOWL_DEPTH * (float) Math.pow(1 - Math.pow(t, BOWL_POWER), BOWL_EASE);
    }

    /** Rear wall and floor seen through the opening. */
    void drawCavity(Canvas c, HomeLayout layout, MachinePalette p, Paint grain) {
        if (cavityPalette != p) {
            cavityPalette = p;
            wallPaint.setShader(new LinearGradient(0, 0, 0, LEDGE, p.wall, MachinePalette.WALL_STOPS, Shader.TileMode.CLAMP));
            floorPaint.setShader(new LinearGradient(0, LEDGE, 0, 58f, MachinePalette.FLOOR, MachinePalette.FLOOR_STOPS,
                    Shader.TileMode.CLAMP));
        }
        float cx = layout.centerX(), left = cx - HALF - 1, right = cx + HALF + 1;
        c.drawRect(left, 0, right, LEDGE + .5f, wallPaint);
        c.drawRect(left, 0, right, LEDGE + .5f, grain);
        c.drawRect(left, LEDGE, right, BAND, floorPaint);
    }

    /** Housing band around the opening with its rounded top edge, lit lower rim and the shadow it casts inward. */
    Bitmap band(HomeLayout layout, MachinePalette p, Paint housingShade) {
        if (band != null && bandWidth == layout.width && bandScale == layout.scale && bandPalette == p) return band;
        bandWidth = layout.width; bandScale = layout.scale; bandPalette = p;
        float width = layout.width;
        band = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(width * layout.scale)),
                Math.max(1, (int) Math.ceil(BAND * layout.scale)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(band);
        c.scale(layout.scale, layout.scale);
        Path opening = hole(width);
        housing.reset();
        housing.addRect(-40, 0, width + 40, BAND + 40, Path.Direction.CW);
        housing.op(opening, Path.Op.DIFFERENCE);

        c.save();
        c.clipOutPath(opening);
        c.drawRect(0, 0, width, BAND, housingShade);
        paint.setShader(new LinearGradient(0, 0, 0, 6f, p.topHighlight, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, width, 6f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f);
        paint.setColor(p.rimLight);
        paint.setMaskFilter(new BlurMaskFilter(1.1f, BlurMaskFilter.Blur.NORMAL));
        c.translate(0, 1.3f);
        c.drawPath(opening, paint);
        c.restore();

        c.save();
        c.clipPath(opening);
        c.clipRect(0, 0, width, BAND);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(p.innerShadow);
        paint.setMaskFilter(new BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL));
        c.translate(0, 1f);
        c.drawPath(housing, paint);
        c.restore();
        paint.setMaskFilter(null);
        return band;
    }
}
