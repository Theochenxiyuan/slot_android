package fyi.slot.android;

import android.graphics.Bitmap;
import android.graphics.Color;
import io.nayuki.qrcodegen.QrCode;

/** Crisp black-on-white QR codes with a quiet zone, scaled by whole modules. */
final class QrBitmap {
    private static final int BORDER = 3;

    private QrBitmap() { }

    static Bitmap create(String text, int targetPx) {
        QrCode code = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int modules = code.size + BORDER * 2;
        int scale = Math.max(1, targetPx / modules);
        int size = modules * scale;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean dark = code.getModule(x / scale - BORDER, y / scale - BORDER);
                pixels[y * size + x] = dark ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }
}
