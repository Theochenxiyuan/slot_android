package fyi.slot.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Per-app actions shown over the bay; the first round only offers system app information. */
@SuppressLint("ViewConstructor")
final class AppMenuView extends FrameLayout {
    interface Actions {
        void showDetails(AppEntry entry);
        void dismissMenu();
    }

    private final LinearLayout card;
    private final TextView name;
    private final TextView details;
    private final AppLanguage language;
    private Actions actions;
    private AppEntry entry;
    private View anchor;
    private int edgeGap;
    private final Rect sourceBounds = new Rect();
    private final Rect cardBounds = new Rect();
    private final int[] screenPosition = new int[2];
    private final ViewTreeObserver.OnScrollChangedListener scrollChanged = this::requestLayout;

    AppMenuView(Context context, AppLanguage language) {
        super(context);
        this.language = language;
        setVisibility(GONE);
        setClickable(true);
        setOnClickListener(view -> { if (actions != null) actions.dismissMenu(); });
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        name = new TextView(context);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);
        details = new TextView(context);
        details.setText(language.text(R.string.app_info));
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setOnClickListener(view -> { if (actions != null && entry != null) actions.showDetails(entry); });
        details.setFocusable(false);
        details.setDefaultFocusHighlightEnabled(false);
        card.addView(details);
        addView(card, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
    }

    void setActions(Actions actions) { this.actions = actions; }
    void onLanguageChanged() { details.setText(language.text(R.string.app_info)); }

    void applyLayout(HomeLayout layout) {
        int pad = Math.round(layout.px(12));
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(PanelStyle.rounded(PanelStyle.CARD, 0xff55555e, 1, layout.px(9)));
        card.setMinimumWidth(Math.round(layout.px(190, 160)));
        edgeGap = Math.max(1, Math.round(layout.px(6)));
        PanelStyle.text(name, layout, 14, 12, PanelStyle.MUTED);
        name.setMaxWidth(Math.round(layout.px(260, 200)));
        LinearLayout.LayoutParams nameParams = (LinearLayout.LayoutParams) name.getLayoutParams();
        nameParams.bottomMargin = Math.round(layout.px(10));
        name.setLayoutParams(nameParams);
        PanelStyle.text(details, layout, 16, 13, PanelStyle.INK);
        MenuIcon.apply(details, R.drawable.menu_info, layout);
        int rowPad = Math.round(layout.px(11));
        details.setPadding(rowPad, rowPad, rowPad, rowPad);
        details.setMinHeight(Math.round(layout.px(40, 40)));
        details.setBackground(PanelStyle.rowBackground(layout.px(6)));
        details.setActivated(true);
        details.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    void show(AppEntry next, View source) {
        hide();
        entry = next;
        anchor = source;
        if (anchor != null) anchor.getViewTreeObserver().addOnScrollChangedListener(scrollChanged);
        name.setText(next.label);
        setVisibility(VISIBLE);
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        card.measure(MeasureSpec.makeMeasureSpec(Math.max(0, getMeasuredWidth() - 2 * edgeGap), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(Math.max(0, getMeasuredHeight() - 2 * edgeGap), MeasureSpec.AT_MOST));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (anchor == null || !anchor.getLocalVisibleRect(sourceBounds)) { hide(); return; }
        anchor.getLocationOnScreen(screenPosition);
        sourceBounds.offset(screenPosition[0], screenPosition[1]);
        getLocationOnScreen(screenPosition);
        sourceBounds.offset(-screenPosition[0], -screenPosition[1]);
        place(sourceBounds, getWidth(), getHeight(), card.getMeasuredWidth(), card.getMeasuredHeight(), edgeGap, cardBounds);
        card.layout(cardBounds.left, cardBounds.top, cardBounds.right, cardBounds.bottom);
    }

    /** Prefer below the icon, flip above at the bottom, and keep every edge inside the bay. */
    static void place(Rect anchor, int width, int height, int cardWidth, int cardHeight, int gap, Rect bounds) {
        int x = Math.max(gap, Math.min(anchor.centerX() - cardWidth / 2, width - gap - cardWidth));
        int y = anchor.bottom + gap;
        if (y + cardHeight > height - gap) y = anchor.top - gap - cardHeight;
        y = Math.max(gap, Math.min(y, height - gap - cardHeight));
        bounds.set(x, y, x + cardWidth, y + cardHeight);
    }

    void hide() {
        if (anchor != null && anchor.getViewTreeObserver().isAlive()) {
            anchor.getViewTreeObserver().removeOnScrollChangedListener(scrollChanged);
        }
        entry = null;
        anchor = null;
        setVisibility(GONE);
    }

    @Override protected void onDetachedFromWindow() { hide(); super.onDetachedFromWindow(); }
    boolean isShowing() { return getVisibility() == VISIBLE; }

    boolean handleKey(int key, KeyEvent event) {
        if (event.getRepeatCount() > 0) return true;
        if (AppsDrawer.isConfirm(key)) { if (actions != null && entry != null) actions.showDetails(entry); }
        else if (AppsDrawer.isMenuKey(key)) { if (actions != null) actions.dismissMenu(); }
        return true;
    }
}
