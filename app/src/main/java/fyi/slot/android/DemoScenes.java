package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Static 720×480 artwork used by the sample cartridges' labels and inserted previews. */
final class DemoScenes {
    static final int WIDTH = 720, HEIGHT = 480;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();

    Bitmap picture(Cartridge sample) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        switch (sample.scene) {
            case 0 -> drawDrive(c);
            case 1 -> drawMoss(c);
            case 2 -> drawOrbit(c);
            case 3 -> drawTide(c);
            default -> drawEmber(c);
        }
        if (sample.platform == Cartridge.Platform.GB) {
            int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            int[] shades = {0xff172c21, 0xff48604a, 0xff8da16f, 0xffd5ddaa};
            for (int i = 0; i < pixels.length; i++) {
                int rgb = pixels[i];
                int luminance = (Color.red(rgb) * 77 + Color.green(rgb) * 150 + Color.blue(rgb) * 29) >> 8;
                pixels[i] = shades[Math.min(3, luminance / 64)];
            }
            bitmap.setPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        }
        return bitmap;
    }

    private void drawDrive(Canvas c) {
        gradient(c, 0xff272440, 0xff795068);
        paint.setColor(0xffdfa971); c.drawCircle(480, 161, 58, paint);
        for (int y = 143; y < 220; y += 13) fill(c, 410, y, 550, y + 4, 0xff745062);
        mountain(c, 233, 0xff46445c, 32, 119, 221, 301, 437, 548, 640, 730);
        mountain(c, 279, 0xff292d41, -30, 97, 168, 332, 478, 608, 749);
        fill(c, 0, 276, 720, 480, 0xff353344);
        Path road = new Path(); road.moveTo(329, 276); road.lineTo(391, 276);
        road.lineTo(660, 480); road.lineTo(60, 480); road.close();
        paint.setColor(0xff171d2a); c.drawPath(road, paint);
        stroke(c, 329, 276, 60, 480, 2.5f, 0xffb49b8c);
        stroke(c, 391, 276, 660, 480, 2.5f, 0xffb49b8c);
        for (int y = 297; y < 480; y += 44) fill(c, 358, y, 363, y + (y - 270) * .11f, 0xffdcc99c);
        fill(c, 312, 342, 408, 363, 0xff718d94); fill(c, 321, 321, 399, 349, 0xff96b4b5);
        fill(c, 331, 324, 389, 340, 0xff222c3d); fill(c, 316, 354, 334, 360, 0xffe89579);
        fill(c, 386, 354, 404, 360, 0xffe89579); fill(c, 308, 352, 317, 369, 0xff10151d);
        fill(c, 403, 352, 412, 369, 0xff10151d);
    }

    private void drawMoss(Canvas c) {
        gradient(c, 0xff96a77c, 0xffd4d9a7);
        paint.setColor(0xffe9e5b7); c.drawCircle(511, 101, 51, paint);
        mountain(c, 266, 0xff728b68, -40, 104, 226, 319, 419, 553, 690, 780);
        mountain(c, 316, 0xff536f59, -40, 128, 243, 362, 448, 620, 760);
        fill(c, 0, 307, 720, 480, 0xff3d5948);
        Path river = new Path(); river.moveTo(345, 304); river.lineTo(371, 304);
        river.cubicTo(439, 353, 303, 382, 362, 480); river.lineTo(232, 480);
        river.cubicTo(198, 381, 389, 358, 345, 304); river.close();
        paint.setColor(0xff9cbd99); c.drawPath(river, paint);
        for (int i = 0; i < 17; i++) tree(c, i * 48 - 10, 260 + (i % 4) * 33, 28 + (i % 3) * 11);
        fill(c, 445, 317, 501, 355, 0xffb4b88c);
        Path roof = new Path(); roof.moveTo(438, 318); roof.lineTo(473, 287); roof.lineTo(508, 318);
        roof.close(); paint.setColor(0xff47494a); c.drawPath(roof, paint);
        fill(c, 467, 335, 479, 355, 0xff465744);
        fill(c, 449, 327, 460, 338, 0xffe6d59b);
    }

    private void drawOrbit(Canvas c) {
        gradient(c, 0xff161c36, 0xff343854);
        for (int i = 0; i < 75; i++) {
            float x = (i * 109 + 31) % 720, y = (i * 71 + 19) % 356;
            fill(c, x, y, x + (i % 3 == 0 ? 3 : 1.5f), y + (i % 3 == 0 ? 3 : 1.5f),
                    i % 3 == 0 ? 0xffb3bdcd : 0xff657386);
        }
        paint.setColor(0xffaf918f); c.drawCircle(470, 160, 91, paint);
        c.save(); c.clipRect(380, 60, 571, 253);
        for (int y = 96; y < 250; y += 21) fill(c, 377, y, 566, y + 6, 0xff947b85);
        c.restore();
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(9); paint.setColor(0xffc6b5ae);
        box.set(320, 145, 620, 193); c.save(); c.rotate(-17, 470, 165); c.drawOval(box, paint); c.restore();
        paint.setStyle(Paint.Style.FILL);
        mountain(c, 407, 0xff323b51, -90, 81, 212, 374, 516, 673, 798);
        mountain(c, 463, 0xff202b40, -90, 101, 287, 462, 609, 798);
        fill(c, 190, 275, 227, 320, 0xffdadfdc); fill(c, 196, 281, 221, 298, 0xff516b82);
        fill(c, 186, 313, 231, 341, 0xffafbfc2); fill(c, 188, 337, 201, 358, 0xffcad4d2);
        fill(c, 217, 337, 230, 358, 0xffcad4d2);
        stroke(c, 230, 321, 259, 312, 2, 0xff9faeaf);
    }

    private void drawTide(Canvas c) {
        gradient(c, 0xff8cafb4, 0xffdfdac2);
        paint.setColor(0xffeee4bd); c.drawCircle(183, 146, 44, paint);
        fill(c, 0, 246, 720, 480, 0xff517f8b);
        for (int i = 0; i < 20; i++) {
            int x = (i * 131) % 670, y = 257 + i * 10;
            fill(c, x, y, x + 32 + i * 3, y + 2, 0xff8eafb0);
        }
        Path island = new Path(); island.moveTo(404, 258); island.lineTo(475, 216);
        island.lineTo(541, 237); island.lineTo(608, 260); island.close();
        paint.setColor(0xff456763); c.drawPath(island, paint);
        fill(c, 489, 133, 513, 232, 0xffece0bc);
        fill(c, 489, 160, 513, 179, 0xffa66353); fill(c, 486, 127, 516, 138, 0xff48555b);
        fill(c, 493, 114, 509, 128, 0xfff2d9a0);
        Path boat = new Path(); boat.moveTo(245, 343); boat.lineTo(315, 343);
        boat.lineTo(302, 358); boat.lineTo(258, 358); boat.close();
        paint.setColor(0xffd5c09c); c.drawPath(boat, paint);
        stroke(c, 280, 285, 280, 345, 2, 0xffebe1ba);
        Path sail = new Path(); sail.moveTo(276, 287); sail.lineTo(276, 334); sail.lineTo(244, 334);
        sail.close(); paint.setColor(0xffdfdcc7); c.drawPath(sail, paint);
    }

    private void drawEmber(Canvas c) {
        gradient(c, 0xff61404a, 0xffdf9e77);
        paint.setColor(0xffefc79e); c.drawCircle(204, 135, 54, paint);
        mountain(c, 260, 0xffa77568, -80, 102, 219, 354, 517, 699, 811);
        mountain(c, 311, 0xff684f53, -30, 148, 259, 413, 550, 680, 800);
        fill(c, 0, 310, 720, 480, 0xff423c42);
        for (int i = 0; i < 23; i++) {
            float x = (i * 137) % 720, y = 316 + (i * 47) % 164;
            fill(c, x, y, x + 19 + i % 12, y + 4, 0xff8d6759);
        }
        fill(c, 439, 218, 451, 336, 0xffb79379); fill(c, 473, 218, 485, 336, 0xffb79379);
        fill(c, 433, 210, 491, 225, 0xffd0aa83); fill(c, 456, 261, 469, 281, 0xffe9bc80);
        fill(c, 445, 326, 480, 334, 0xffd1a476);
    }

    private void tree(Canvas c, float x, float y, float h) {
        fill(c, x - 2, y, x + 2, y + 18, 0xff3b5140);
        Path p = new Path(); p.moveTo(x, y - h); p.lineTo(x - h * .43f, y + 4);
        p.lineTo(x + h * .43f, y + 4); p.close(); paint.setColor(0xff294c3e); c.drawPath(p, paint);
    }

    private void mountain(Canvas c, float floor, int color, float... xs) {
        Path p = new Path(); p.moveTo(xs[0], floor + 90);
        for (int i = 0; i < xs.length; i++) p.lineTo(xs[i], floor - (i % 2 == 0 ? 0 : 45 + i % 3 * 21));
        p.lineTo(xs[xs.length - 1], floor + 150); p.close(); paint.setColor(color); c.drawPath(p, paint);
    }

    private void gradient(Canvas c, int top, int bottom) {
        paint.setShader(new LinearGradient(0, 0, 0, 480, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 720, 480, paint); paint.setShader(null);
    }

    private void fill(Canvas c, float l, float t, float r, float b, int color) {
        paint.setColor(color); c.drawRect(l, t, r, b, paint);
    }

    private void stroke(Canvas c, float x1, float y1, float x2, float y2, float width, int color) {
        paint.setColor(color); paint.setStrokeWidth(width); c.drawLine(x1, y1, x2, y2, paint);
    }
}
