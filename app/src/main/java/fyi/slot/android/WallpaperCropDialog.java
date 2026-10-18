package fyi.slot.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Local gallery picker and interactive aspect-locked crop dialog for custom desktop wallpapers. */
final class WallpaperCropDialog {
    static final int PICK_WALLPAPER = 44;
    private static final int MAX_DECODE_EDGE = 2560;
    private static final int MAX_OUTPUT_WIDTH = 1920;
    private static final float MAX_ZOOM = 4f;

    private final Activity activity;
    private final AppLanguage language;
    private final Consumer<Bitmap> onApplied;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private CropView cropView;
    private View loadingView;
    private Bitmap sourceBitmap;
    private boolean saving, released;
    private int requestToken;

    WallpaperCropDialog(Activity activity, AppLanguage language, Consumer<Bitmap> onApplied) {
        this.activity = activity;
        this.language = language;
        this.onApplied = onApplied;
    }

    void pick() {
        Intent openDoc = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        try {
            activity.startActivityForResult(openDoc, PICK_WALLPAPER);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
        Intent getContent = new Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        try {
            activity.startActivityForResult(getContent, PICK_WALLPAPER);
        } catch (ActivityNotFoundException | SecurityException error) {
            toast(R.string.wallpaper_picker_unavailable);
        }
    }

    void onPicked(int resultCode, Intent data, float aspect) {
        if (released || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        open(data.getData(), aspect);
    }

    void open(Uri uri, float aspect) {
        if (released || uri == null) return;
        dismiss();
        float targetAspect = HomeLayout.clamp(aspect, HomeLayout.MIN_ASPECT, HomeLayout.MAX_ASPECT);
        int token = ++requestToken;
        saving = false;
        sourceBitmap = null;

        FrameLayout container = new FrameLayout(activity);
        int pad = dp(16);
        container.setPadding(pad, dp(8), pad, dp(8));

        LinearLayout loading = new LinearLayout(activity);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        ProgressBar spinner = new ProgressBar(activity);
        loading.addView(spinner, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView loadingLabel = new TextView(activity);
        loadingLabel.setText(language.text(R.string.apps_loading));
        loadingLabel.setTextColor(0xffb9b9c0);
        loadingLabel.setTextSize(14);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(10);
        loading.addView(loadingLabel, labelParams);
        loadingView = loading;

        int viewHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels * .52f);
        container.addView(loading, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Math.max(dp(180), viewHeight), Gravity.CENTER));

        cropView = new CropView(activity, targetAspect);
        cropView.setVisibility(View.INVISIBLE);
        container.addView(cropView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Math.max(dp(180), viewHeight), Gravity.CENTER));

        dialog = new AlertDialog.Builder(activity)
                .setTitle(language.text(R.string.crop_wallpaper))
                .setView(container)
                .setNegativeButton(language.text(R.string.back), (shown, which) -> dismiss())
                .setPositiveButton(language.text(R.string.apply_crop), null)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((shown, key, event) -> handleDialogKey(key, event));
        dialog.show();

        Button applyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        applyButton.setEnabled(false);
        applyButton.setOnClickListener(view -> confirmCrop());
        if (Build.VERSION.SDK_INT >= 33 && dialog.getWindow() != null) {
            dialog.getWindow().getOnBackInvokedDispatcher()
                    .registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::dismiss);
        }

        worker.execute(() -> {
            Bitmap decoded = decodeUri(activity, uri);
            main.post(() -> {
                if (released || token != requestToken || dialog == null || !dialog.isShowing()) return;
                if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
                    dismiss();
                    toast(R.string.wallpaper_load_failed);
                    return;
                }
                sourceBitmap = decoded;
                loadingView.setVisibility(View.GONE);
                cropView.setBitmap(decoded);
                cropView.setVisibility(View.VISIBLE);
                applyButton.setEnabled(true);
                applyButton.requestFocus();
            });
        });
    }

    private boolean handleDialogKey(int key, KeyEvent event) {
        if (SlotView.isBackKey(key)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) dismiss();
            return true;
        }
        if (AppsDrawer.isConfirm(key)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) confirmCrop();
            return true;
        }
        if (cropView != null && sourceBitmap != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { cropView.panStep(-1f, 0f); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { cropView.panStep(1f, 0f); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_UP) { cropView.panStep(0f, -1f); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { cropView.panStep(0f, 1f); return true; }
            if (key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_Q || key == KeyEvent.KEYCODE_MINUS) {
                cropView.zoomBy(1f / 1.15f);
                return true;
            }
            if (key == KeyEvent.KEYCODE_BUTTON_R1 || key == KeyEvent.KEYCODE_E || key == KeyEvent.KEYCODE_EQUALS) {
                cropView.zoomBy(1.15f);
                return true;
            }
        }
        return false;
    }

    void confirmCrop() {
        if (saving || sourceBitmap == null || cropView == null || dialog == null || !dialog.isShowing()) return;
        saving = true;
        int token = requestToken;
        Button applyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (applyButton != null) applyButton.setEnabled(false);
        Bitmap snapshotSource = sourceBitmap;
        float aspect = cropView.aspect;
        float cx = cropView.centerX, cy = cropView.centerY, zoom = cropView.zoom;
        worker.execute(() -> {
            Bitmap cropped = cropBitmap(snapshotSource, aspect, cx, cy, zoom);
            boolean saved = saveWallpaperFile(activity, cropped);
            main.post(() -> {
                if (released || token != requestToken) return;
                saving = false;
                dismiss();
                if (saved && cropped != null) {
                    onApplied.accept(cropped);
                } else {
                    toast(R.string.wallpaper_save_failed);
                }
            });
        });
    }

    static Bitmap cropBitmap(Bitmap source, float aspect, float cx, float cy, float zoom) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) return null;
        int srcW = source.getWidth(), srcH = source.getHeight();
        float safeAspect = HomeLayout.clamp(aspect, HomeLayout.MIN_ASPECT, HomeLayout.MAX_ASPECT);
        float baseW, baseH;
        if ((float) srcW / srcH >= safeAspect) {
            baseH = srcH;
            baseW = srcH * safeAspect;
        } else {
            baseW = srcW;
            baseH = srcW / safeAspect;
        }
        float safeZoom = HomeLayout.clamp(zoom, 1f, MAX_ZOOM);
        float cropW = baseW / safeZoom, cropH = baseH / safeZoom;
        float clampedX = HomeLayout.clamp(cx, cropW / 2f, srcW - cropW / 2f);
        float clampedY = HomeLayout.clamp(cy, cropH / 2f, srcH - cropH / 2f);

        int outW = Math.min(MAX_OUTPUT_WIDTH, Math.max(1, Math.round(cropW)));
        int outH = Math.max(1, Math.round(outW / safeAspect));
        Bitmap output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float scale = outW / cropW;
        float left = (cropW / 2f - clampedX) * scale;
        float top = (cropH / 2f - clampedY) * scale;
        RectF dst = new RectF(left, top, left + srcW * scale, top + srcH * scale);
        canvas.drawBitmap(source, null, dst, paint);
        return output;
    }

    static boolean saveWallpaperFile(Context context, Bitmap bitmap) {
        if (bitmap == null) return false;
        File target = BackdropRenderer.wallpaperFile(context);
        File temp = new File(context.getFilesDir(), "wallpaper.tmp.png");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return false;
            out.flush();
        } catch (IOException error) {
            temp.delete();
            return false;
        }
        if (target.exists() && !target.delete()) {
            temp.delete();
            return false;
        }
        return temp.renameTo(target);
    }

    private static Bitmap decodeUri(Context context, Uri uri) {
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                int maxEdge = Math.max(w, h);
                if (maxEdge > MAX_DECODE_EDGE) {
                    float scale = (float) MAX_DECODE_EDGE / maxEdge;
                    decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                }
            });
        } catch (IOException | SecurityException | IllegalArgumentException error) {
            return null;
        }
    }

    void dismiss() {
        requestToken++;
        saving = false;
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        cropView = null;
        loadingView = null;
        sourceBitmap = null;
    }

    void release() {
        released = true;
        dismiss();
        worker.shutdown();
    }

    boolean isShowing() { return dialog != null && dialog.isShowing(); }
    boolean isReady() { return isShowing() && sourceBitmap != null && cropView != null && cropView.getVisibility() == View.VISIBLE; }
    AlertDialog window() { return dialog; }
    void panBy(float dxStep, float dyStep) { if (cropView != null) cropView.panStep(dxStep, dyStep); }
    void zoomBy(float factor) { if (cropView != null) cropView.zoomBy(factor); }

    private void toast(int message) {
        Toast.makeText(activity, language.text(message), Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** Touch and gamepad-enabled viewport that locks the crop box to the desktop aspect ratio. */
    private static final class CropView extends View {
        final float aspect;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF frame = new RectF();
        private final RectF imageBox = new RectF();
        private final ScaleGestureDetector scaleDetector;
        private Bitmap bitmap;
        float centerX, centerY, zoom = 1f;
        private float lastTouchX, lastTouchY;
        private boolean dragging;

        CropView(Context context, float aspect) {
            super(context);
            this.aspect = aspect;
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    zoomBy(detector.getScaleFactor());
                    return true;
                }
            });
        }

        void setBitmap(Bitmap next) {
            bitmap = next;
            zoom = 1f;
            centerX = next.getWidth() / 2f;
            centerY = next.getHeight() / 2f;
            clampCrop();
            invalidate();
        }

        void panStep(float stepX, float stepY) {
            if (bitmap == null) return;
            float[] size = cropDimensions();
            centerX += stepX * size[0] * .08f;
            centerY += stepY * size[1] * .08f;
            clampCrop();
            invalidate();
        }

        void zoomBy(float factor) {
            if (bitmap == null) return;
            zoom = HomeLayout.clamp(zoom * factor, 1f, MAX_ZOOM);
            clampCrop();
            invalidate();
        }

        private float[] cropDimensions() {
            int srcW = bitmap.getWidth(), srcH = bitmap.getHeight();
            float baseW, baseH;
            if ((float) srcW / srcH >= aspect) {
                baseH = srcH;
                baseW = srcH * aspect;
            } else {
                baseW = srcW;
                baseH = srcW / aspect;
            }
            return new float[]{baseW / zoom, baseH / zoom};
        }

        private void clampCrop() {
            if (bitmap == null) return;
            float[] size = cropDimensions();
            float halfW = size[0] / 2f, halfH = size[1] / 2f;
            centerX = HomeLayout.clamp(centerX, halfW, bitmap.getWidth() - halfW);
            centerY = HomeLayout.clamp(centerY, halfH, bitmap.getHeight() - halfH);
        }

        private void updateFrame() {
            float pad = 12f * getResources().getDisplayMetrics().density;
            float availW = Math.max(1f, getWidth() - pad * 2f);
            float availH = Math.max(1f, getHeight() - pad * 2f);
            float boxW, boxH;
            if (availW / availH >= aspect) {
                boxH = availH;
                boxW = boxH * aspect;
            } else {
                boxW = availW;
                boxH = boxW / aspect;
            }
            float left = (getWidth() - boxW) / 2f, top = (getHeight() - boxH) / 2f;
            frame.set(left, top, left + boxW, top + boxH);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0xff141418);
            if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) return;
            updateFrame();
            float[] cropSize = cropDimensions();
            float displayScale = frame.width() / cropSize[0];
            float imgLeft = frame.centerX() - centerX * displayScale;
            float imgTop = frame.centerY() - centerY * displayScale;
            imageBox.set(imgLeft, imgTop, imgLeft + bitmap.getWidth() * displayScale, imgTop + bitmap.getHeight() * displayScale);

            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            canvas.drawBitmap(bitmap, null, imageBox, paint);

            // Dim outside the crop frame so the active desktop viewport stands out clearly.
            paint.setColor(0xb0000000);
            canvas.drawRect(0, 0, getWidth(), frame.top, paint);
            canvas.drawRect(0, frame.bottom, getWidth(), getHeight(), paint);
            canvas.drawRect(0, frame.top, frame.left, frame.bottom, paint);
            canvas.drawRect(frame.right, frame.top, getWidth(), frame.bottom, paint);

            // Rule-of-thirds guide lines and crisp crop frame border.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f * getResources().getDisplayMetrics().density);
            paint.setColor(0x40ffffff);
            for (int i = 1; i <= 2; i++) {
                float gx = frame.left + frame.width() * i / 3f;
                float gy = frame.top + frame.height() * i / 3f;
                canvas.drawLine(gx, frame.top, gx, frame.bottom, paint);
                canvas.drawLine(frame.left, gy, frame.right, gy, paint);
            }
            paint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
            paint.setColor(Color.WHITE);
            canvas.drawRect(frame, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (bitmap == null) return false;
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    dragging = true;
                }
                case MotionEvent.ACTION_POINTER_DOWN -> dragging = false;
                case MotionEvent.ACTION_MOVE -> {
                    if (dragging && !scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                        updateFrame();
                        float[] cropSize = cropDimensions();
                        float displayScale = Math.max(.001f, frame.width() / cropSize[0]);
                        centerX -= dx / displayScale;
                        centerY -= dy / displayScale;
                        clampCrop();
                        invalidate();
                    }
                }
                case MotionEvent.ACTION_UP -> {
                    dragging = false;
                    performClick();
                }
                case MotionEvent.ACTION_CANCEL -> dragging = false;
            }
            return true;
        }

        @Override public boolean performClick() {
            return super.performClick();
        }
    }
}
