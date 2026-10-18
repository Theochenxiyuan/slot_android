package fyi.slot.android;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/** Colours and density-aware sizes shared by the bay's native content. */
final class PanelStyle {
    static int INK = 0xffe0e0e1, MUTED = 0xffbdbdc6, DIM = 0xff8a8a94;
    static int SELECTED = 0xff303036, PRESSED = 0xff28282e, CURRENT = 0xff232328, FOCUS = 0xffc4ab59, CARD = 0xff303037;
    static int DIVIDER = 0xff43434c;
    private static int CELL_ACTIVATED = 0xff25252a;
    private static boolean light;

    private PanelStyle() { }

    static boolean isLight() { return light; }

    static void setLight(boolean light) {
        PanelStyle.light = light;
        if (light) {
            INK = 0xff1c1c22;
            MUTED = 0xff4a4a54;
            DIM = 0xff686874;
            SELECTED = 0xffb2b2bc;
            PRESSED = 0xffbabac4;
            CURRENT = 0xffbdbdc7;
            FOCUS = 0xff8a6d1b;
            CARD = 0xffbcbcc6;
            DIVIDER = 0xff9d9da8;
            CELL_ACTIVATED = 0xffb8b8c2;
        } else {
            INK = 0xffe0e0e1;
            MUTED = 0xffbdbdc6;
            DIM = 0xff8a8a94;
            SELECTED = 0xff303036;
            PRESSED = 0xff28282e;
            CURRENT = 0xff232328;
            FOCUS = 0xffc4ab59;
            CARD = 0xff303037;
            DIVIDER = 0xff43434c;
            CELL_ACTIVATED = 0xff25252a;
        }
    }

    static void text(TextView view, HomeLayout layout, float units, float minimumSp, int color) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, layout.px(units, minimumSp));
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
    }

    /** Activation shows a filled row; touch presses show a lighter fill. */
    static Drawable rowBackground(float radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_activated}, rounded(SELECTED, 0, 0, radius));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(PRESSED, 0, 0, radius));
        states.addState(new int[]{}, rounded(0, 0, 0, radius));
        return states;
    }

    /** Tabs share the row focus fill; the current tab keeps a quieter fill while its rows are used. */
    static Drawable tabBackground(float radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_activated}, rounded(SELECTED, 0, 0, radius));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(PRESSED, 0, 0, radius));
        states.addState(new int[]{android.R.attr.state_selected}, rounded(CURRENT, 0, 0, radius));
        states.addState(new int[]{}, rounded(0, 0, 0, radius));
        return states;
    }

    /** App cells mark the selected cell with a thin gold outline. */
    static Drawable cellBackground(float radius, int stroke) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_activated}, rounded(CELL_ACTIVATED, FOCUS, stroke, radius));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(PRESSED, 0, 0, radius));
        states.addState(new int[]{}, rounded(0, 0, 0, radius));
        return states;
    }

    static GradientDrawable rounded(int fill, int strokeColor, int strokeWidth, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }
}
