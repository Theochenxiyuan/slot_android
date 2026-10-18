package fyi.slot.android;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Modal card floating over the desktop, drawn in the bay's plastic language: dark plate, hairline
 * edge, bay rows with the focus fill, and one set of key rules for menus and detail pages.
 */
final class PanelDialog {
    interface OnPick { void picked(int index); }

    private record Row(LinearLayout view, TextView label, TextView mark, int icon) { }

    private final Activity activity;
    private final AppLanguage language;
    private final Dialog dialog;
    private final FrameLayout frame;
    private final LinearLayout card, header, hint, content;
    private final TextView titleView, hintKey, hintLabel;
    private final ScrollView scroll;
    private final List<Row> rows = new ArrayList<>();
    private final float widthUnits;
    private HomeLayout layout = HomeLayout.reference();
    private OnPick pick = index -> {};
    private Runnable cancelled = () -> {};
    private int selected, checked = -1;
    private boolean dpadDownSeen;

    PanelDialog(Activity activity, AppLanguage language, float widthUnits) {
        this.activity = activity;
        this.language = language;
        this.widthUnits = widthUnits;

        dialog = new Dialog(activity) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                return handleKey(event) || super.dispatchKeyEvent(event);
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        // A long press opens this card and releases the finger outside it, so outside taps must not close it.
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(shown -> cancelled.run());

        frame = new FrameLayout(activity);
        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);

        header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        titleView = new TextView(activity);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        hint = new LinearLayout(activity);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hintKey = new TextView(activity);
        hintKey.setText(new InteractionPreferences(activity).backBadge());
        hintKey.setGravity(Gravity.CENTER);
        hint.addView(hintKey, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        hintLabel = new TextView(activity);
        hintLabel.setText(language.text(R.string.back));
        hint.addView(hintLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        header.addView(hint, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int max = Math.round(getResources().getDisplayMetrics().heightPixels * .62f);
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFocusable(false);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        frame.addView(card, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        dialog.setContentView(frame);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(PanelStyle.isLight() ? .38f : .62f);
        }
    }

    PanelDialog setTitle(CharSequence title) { titleView.setText(title); return this; }

    /** Single-choice or action list; rows are chosen by touch or by the pad. */
    PanelDialog setRows(List<String> labels, int checked, int... icons) {
        if (icons.length != 0 && icons.length != labels.size()) {
            throw new IllegalArgumentException("Each row must have an icon when icons are provided");
        }
        content.removeAllViews();
        rows.clear();
        this.checked = checked;
        for (String label : labels) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            final int index = rows.size();
            row.setOnClickListener(clicked -> { select(index); activate(index); });
            row.setFocusable(false);
            row.setDefaultFocusHighlightEnabled(false);
            TextView text = new TextView(activity);
            text.setText(label);
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView mark = new TextView(activity);
            mark.setText("✓");
            if (checked < 0) mark.setVisibility(View.GONE);
            row.addView(mark, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            content.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            rows.add(new Row(row, text, mark, icons.length == 0 ? 0 : icons[index]));
        }
        selected = HomeLayout.clampIndex(checked, rows.size());
        bindRows();
        applySize();
        return this;
    }

    /** Replaces the list with free content such as a detail page. */
    PanelDialog setBody(View body) {
        content.removeAllViews();
        rows.clear();
        checked = -1;
        content.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        applySize();
        return this;
    }

    PanelDialog setPick(OnPick pick) { this.pick = pick; return this; }
    PanelDialog setCancelled(Runnable cancelled) { this.cancelled = cancelled; return this; }

    void show() {
        applySize();
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
    }

    void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }
    boolean isShowing() { return dialog.isShowing(); }

    int rowCount() { return rows.size(); }
    String rowLabel(int index) { return rows.get(index).label.getText().toString(); }
    int checkedIndex() { return checked; }
    int selectedIndex() { return selected; }
    View rowView(int index) { return rows.get(index).view; }
    HomeLayout layout() { return layout; }

    void select(int index) {
        selected = HomeLayout.clampIndex(index, rows.size());
        bindRows();
    }

    void activate(int index) {
        if (index < 0 || index >= rows.size()) return;
        pick.picked(index);
    }

    // Sizing and paint

    private void applySize() {
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        layout = HomeLayout.of(metrics.widthPixels, metrics.heightPixels, 0, 0, 0, 0, metrics.density);
        float cardWidth = Math.min(layout.px(widthUnits), metrics.widthPixels - layout.px(56));
        ViewGroup.LayoutParams cardParams = card.getLayoutParams();
        cardParams.width = Math.round(cardWidth);
        cardParams.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        card.setLayoutParams(cardParams);

        int pad = Math.round(layout.px(14));
        card.setPadding(pad, Math.round(layout.px(11)), pad, Math.round(layout.px(12)));
        card.setBackground(PanelStyle.rounded(PanelStyle.isLight() ? 0xf7f2f2f5 : 0xf5202027,
                PanelStyle.isLight() ? 0xff8b8b96 : 0xff525260, 1, layout.px(11)));
        card.setElevation(layout.px(14));

        PanelStyle.text(titleView, layout, 16.5f, 14, PanelStyle.INK);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        int keySize = Math.round(layout.px(17, 18));
        LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(keySize, keySize);
        hintKey.setLayoutParams(keyParams);
        hintKey.setBackground(PanelStyle.rounded(PanelStyle.INK, 0, 0, keySize / 2f));
        PanelStyle.text(hintKey, layout, 10.5f, 9.5f, PanelStyle.CARD);
        hintKey.setTypeface(Typeface.DEFAULT_BOLD);
        hintKey.setGravity(Gravity.CENTER);
        hintKey.setIncludeFontPadding(false);
        PanelStyle.text(hintLabel, layout, 12.5f, 11, PanelStyle.DIM);
        LinearLayout.LayoutParams hintParams = (LinearLayout.LayoutParams) hint.getLayoutParams();
        hintParams.leftMargin = Math.round(layout.px(10));
        LinearLayout.LayoutParams labelParams = (LinearLayout.LayoutParams) hintLabel.getLayoutParams();
        labelParams.leftMargin = Math.round(layout.px(5));

        LinearLayout.LayoutParams scrollParams = (LinearLayout.LayoutParams) scroll.getLayoutParams();
        scrollParams.topMargin = Math.round(layout.px(8));

        for (int i = 0; i < rows.size(); i++) styleRow(rows.get(i), i);
    }

    private void styleRow(Row row, int index) {
        int horizontal = Math.round(layout.px(12)), vertical = Math.round(layout.px(8));
        row.view.setPadding(horizontal, vertical, horizontal, vertical);
        row.view.setMinimumHeight(Math.round(layout.px(40, 42)));
        row.view.setBackground(PanelStyle.rowBackground(layout.px(6)));
        PanelStyle.text(row.label, layout, 16f, 13.5f, PanelStyle.INK);
        MenuIcon.apply(row.label, row.icon, layout);
        PanelStyle.text(row.mark, layout, 15f, 13, PanelStyle.FOCUS);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.view.getLayoutParams();
        params.topMargin = Math.round(layout.px(index == 0 ? 0 : 2));
    }

    private void bindRows() {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).view.setActivated(i == selected);
            rows.get(i).mark.setVisibility(i == checked ? View.VISIBLE : View.GONE);
        }
    }

    // Keys

    boolean handleKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (SlotView.isBackKey(key)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) dialog.cancel();
            return true;
        }
        if (!rows.isEmpty()) {
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                int step = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    dpadDownSeen = true;
                    if (event.getRepeatCount() == 0) select(selected + step);
                } else {
                    // The press that left touch mode never delivered its down event.
                    if (!dpadDownSeen) select(selected + step);
                    dpadDownSeen = false;
                }
                return true;
            }
            if (AppsDrawer.isConfirm(key)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) activate(selected);
                return true;
            }
        } else if (AppsDrawer.isConfirm(key)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) dialog.cancel();
            return true;
        }
        return SlotView.entryForKey(key) != null || AppsDrawer.isMenuKey(key)
                || key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_BUTTON_R1
                || key == KeyEvent.KEYCODE_Q || key == KeyEvent.KEYCODE_E;
    }
}
