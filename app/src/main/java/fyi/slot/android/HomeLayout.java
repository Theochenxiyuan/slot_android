package fyi.slot.android;

import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

/**
 * Shared geometry for the Canvas shelf, the machine panel and its native content.
 * Logical units keep the 720×480 reference at 3:2; wider frames add width, taller frames add height.
 */
final class HomeLayout {
    static final float REFERENCE_WIDTH = 720f, REFERENCE_HEIGHT = 480f;
    static final float MIN_ASPECT = 1f, MAX_ASPECT = 16f / 9f;
    static final float HEADER_BOTTOM = 72f;
    static final float MACHINE_LIP = 61f, MACHINE_OPEN_TOP = 0f;
    static final float ENTRY_TOP = 10f, ENTRY_BOTTOM = 54f, ENTRY_CENTER = (ENTRY_TOP + ENTRY_BOTTOM) / 2f;
    static final float ENTRY_INSET = 20f, ENTRY_WIDTH = 112f;
    static final float BAY_SIDE = 24f, BAY_BODY_TOP = 125f, BAY_RIDGE_TOP = 69f, BAY_BOTTOM_EDGE = 10f;
    static final float BAY_RIDGE_HALF = 90f, BAY_BASE_HALF = 180f, TITLE_CENTER = 103f;
    private static final float REFERENCE_SHELF = 318f, MAX_CARD_SCALE = 1.1f;

    /** Logical size of the content frame. */
    final float width, height;
    /** Pixels per logical unit and the content frame origin in view pixels. */
    final float scale, originX, originY;
    /** Content frame in view pixels. */
    final float frameLeft, frameTop, frameRight, frameBottom;
    final float density;

    private HomeLayout(float width, float height, float scale, float originX, float originY, float density) {
        this.width = width; this.height = height; this.scale = scale;
        this.originX = originX; this.originY = originY; this.density = density;
        frameLeft = originX; frameTop = originY;
        frameRight = originX + width * scale; frameBottom = originY + height * scale;
    }

    static HomeLayout reference() {
        return new HomeLayout(REFERENCE_WIDTH, REFERENCE_HEIGHT, 1f, 0f, 0f, 1f);
    }

    /** Fits a 1:1–16:9 content frame inside the view after removing safe-area insets. */
    static HomeLayout of(float viewWidth, float viewHeight, float insetLeft, float insetTop,
                         float insetRight, float insetBottom, float density) {
        float availableWidth = viewWidth - insetLeft - insetRight;
        float availableHeight = viewHeight - insetTop - insetBottom;
        if (availableWidth <= 0 || availableHeight <= 0) return reference();
        float aspect = availableWidth / availableHeight;
        float frameWidth = availableWidth, frameHeight = availableHeight;
        if (aspect > MAX_ASPECT) frameWidth = availableHeight * MAX_ASPECT;
        else if (aspect < MIN_ASPECT) frameHeight = availableWidth / MIN_ASPECT;
        float frameAspect = frameWidth / frameHeight;
        float logicalWidth, logicalHeight;
        if (frameAspect >= REFERENCE_WIDTH / REFERENCE_HEIGHT) {
            logicalHeight = REFERENCE_HEIGHT; logicalWidth = REFERENCE_HEIGHT * frameAspect;
        } else {
            logicalWidth = REFERENCE_WIDTH; logicalHeight = REFERENCE_WIDTH / frameAspect;
        }
        float scale = frameWidth / logicalWidth;
        return new HomeLayout(logicalWidth, logicalHeight, scale,
                insetLeft + (availableWidth - frameWidth) / 2f,
                insetTop + (availableHeight - frameHeight) / 2f, density);
    }

    /** Uses the view's size and display cutout insets; transient system bars overlay without moving the desktop. */
    static HomeLayout forView(View view) { return forView(view, view.getWidth(), view.getHeight()); }

    static HomeLayout forView(View view, int width, int height) {
        float density = view.getResources().getDisplayMetrics().density;
        WindowInsets windowInsets = view.getRootWindowInsets();
        Insets insets = windowInsets == null ? Insets.NONE
                : windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
        return of(width, height, insets.left, insets.top, insets.right, insets.bottom, density);
    }

    float centerX() { return width / 2f; }
    float aspect() { return width / height; }

    // Shelf
    float machineClosedTop() { return height - MACHINE_LIP; }
    float dotsY() { return machineClosedTop() - 22f; }
    float shelfBottom() { return machineClosedTop() - 29f; }
    float shelfCenterY() { return (HEADER_BOTTOM + shelfBottom()) / 2f; }
    float swipeBottom() { return dotsY() + 13f; }
    float slotClipBottom() { return height; }

    float cardScale() {
        float extra = (shelfBottom() - HEADER_BOTTOM) / REFERENCE_SHELF - 1f;
        return clamp(1f + extra * .5f, 1f, MAX_CARD_SCALE);
    }

    /** Wider frames widen the pitch so neighbours keep the reference peek. */
    float pitch() { return 400f * cardScale() + Math.max(0f, width - REFERENCE_WIDTH) / 2f; }
    /** Adjacent GB/GBC pairs use a narrower centre-to-centre pitch; any pair involving GBA uses the GBA pitch. */
    float pitch(Cartridge.Platform a, Cartridge.Platform b) {
        boolean hasGba = a == null || b == null || a == Cartridge.Platform.GBA || b == Cartridge.Platform.GBA;
        float base = hasGba ? 400f : 368f;
        return base * cardScale() + Math.max(0f, width - REFERENCE_WIDTH) / 2f;
    }
    float visibleReach() { return Math.max(1.65f, (width / 2f + 200f * cardScale()) / pitch() + .15f); }
    float cardWidth(Cartridge.Platform platform) { return platform.width * cardScale(); }
    float cardHeight(Cartridge.Platform platform) { return platform.height * cardScale(); }
    float restY(Cartridge.Platform platform) { return shelfCenterY() - cardHeight(platform) / 2f; }
    CartridgeMotion.Geometry cartridgeGeometry(Cartridge.Platform platform) {
        return new CartridgeMotion.Geometry(restY(platform), cardWidth(platform), cardHeight(platform), machineClosedTop());
    }
    float insertionDistance(Cartridge.Platform platform) { return machineClosedTop() + 6f - restY(platform); }

    // Machine panel
    float machineTop(float progress) {
        return machineClosedTop() + (MACHINE_OPEN_TOP - machineClosedTop()) * clamp(progress, 0f, 1f);
    }
    float bayBottomOffset() { return height - BAY_BOTTOM_EDGE - MACHINE_OPEN_TOP; }
    float bodyLeft() { return BAY_SIDE + 16f; }
    float bodyRight() { return width - BAY_SIDE - 16f; }
    /** Content rectangle offsets relative to the machine top. */
    float bodyTopOffset() { return BAY_BODY_TOP + 10f; }
    float bodyBottomOffset() { return bayBottomOffset() - 8f; }

    // Conversion
    float toScreenX(float x) { return originX + x * scale; }
    float toScreenY(float y) { return originY + y * scale; }
    float toLogicalX(float x) { return (x - originX) / scale; }
    float toLogicalY(float y) { return (y - originY) / scale; }
    float px(float units) { return units * scale; }
    /** Text and touch sizes never fall below a density-based floor that fits the frame scale. */
    float px(float units, float minimumDp) { return Math.max(units * scale, minimumDp * Math.min(density, scale)); }
    boolean contains(float screenX, float screenY) {
        return screenX >= frameLeft && screenX <= frameRight && screenY >= frameTop && screenY <= frameBottom;
    }

    static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }

    /** Keeps an index inside a list of the given size. */
    static int clampIndex(int value, int size) { return size <= 0 ? 0 : Math.max(0, Math.min(size - 1, value)); }
}
