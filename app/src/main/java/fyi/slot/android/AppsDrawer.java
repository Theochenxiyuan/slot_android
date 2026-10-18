package fyi.slot.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Grid of launchable apps inside the battery bay; selection and scrolling are owned here, never by the framework. */
@SuppressLint("ViewConstructor")
final class AppsDrawer extends FrameLayout {
    interface Actions {
        void launch(AppEntry entry, Rect sourceBounds);
        void showMenu(AppEntry entry);
        void retry();
    }

    private final ScrollView scroll;
    private final AppGridLayout grid;
    private final TextView status;
    private final AppIconCache icons;
    private final AppLanguage language;
    private Actions actions;
    private HomeLayout layout = HomeLayout.reference();
    private AppRepository.Status state = AppRepository.Status.LOADING;
    private List<AppEntry> apps = List.of();
    private int selected;
    private String selectedKey;
    private boolean keyMode;
    private int iconSize, labelSize, cellPadding;

    AppsDrawer(Context context, AppIconCache icons) {
        this(context, icons, new AppLanguage(context));
    }

    AppsDrawer(Context context, AppIconCache icons, AppLanguage language) {
        super(context);
        this.icons = icons;
        this.language = language;
        scroll = new ScrollView(context);
        scroll.setFocusable(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> bindVisibleIcons());
        grid = new AppGridLayout(context);
        grid.setFocusable(false);
        scroll.addView(grid, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        status = new TextView(context);
        status.setGravity(Gravity.CENTER);
        status.setOnClickListener(view -> { if (state == AppRepository.Status.FAILED && actions != null) actions.retry(); });
        addView(status, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        render();
    }

    void setActions(Actions actions) { this.actions = actions; }
    void onLanguageChanged() { render(); }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && keyMode) { keyMode = false; refreshFocus(); }
        return super.onInterceptTouchEvent(event);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        bindVisibleIcons();
    }

    void applyLayout(HomeLayout next, int width) {
        layout = next;
        int minimumCell = Math.round(next.px(100, 84));
        int columns = Math.max(3, Math.min(8, width / Math.max(1, minimumCell)));
        iconSize = Math.round(next.px(48, 40));
        labelSize = Math.round(next.px(13.5f, 12));
        cellPadding = Math.round(next.px(6));
        icons.setSize(iconSize);
        grid.setSpacing(columns, Math.round(next.px(6)), Math.round(next.px(10)));
        PanelStyle.text(status, next, 16, 13, PanelStyle.MUTED);
        int pad = Math.round(next.px(14));
        status.setPadding(pad, pad, pad, pad);
        for (int i = 0; i < grid.getChildCount(); i++) ((Cell) grid.getChildAt(i)).applyStyle();
        post(this::bindVisibleIcons);
    }

    void setApps(AppRepository.Status nextState, List<AppEntry> nextApps) {
        state = nextState;
        apps = nextApps;
        int index = indexOf(selectedKey);
        selected = apps.isEmpty() ? 0 : index >= 0 ? index : Math.min(selected, apps.size() - 1);
        selectedKey = apps.isEmpty() ? null : apps.get(selected).key;
        while (grid.getChildCount() > apps.size()) grid.removeViewAt(grid.getChildCount() - 1);
        while (grid.getChildCount() < apps.size()) grid.addView(new Cell(getContext(), grid.getChildCount()));
        for (int i = 0; i < apps.size(); i++) ((Cell) grid.getChildAt(i)).bind(apps.get(i));
        render();
        refreshFocus();
        post(this::bindVisibleIcons);
    }

    /** Fresh openings start at the first app. */
    void reset(boolean viaKey) {
        keyMode = viaKey;
        selected = 0;
        selectedKey = apps.isEmpty() ? null : apps.get(0).key;
        scroll.scrollTo(0, 0);
        refreshFocus();
    }

    boolean isKeyMode() { return keyMode; }
    int selectedIndex() { return apps.isEmpty() ? -1 : selected; }
    int columns() { return grid.columns(); }
    List<AppEntry> apps() { return apps; }
    AppRepository.Status state() { return state; }
    String statusText() { return status.getVisibility() == VISIBLE ? status.getText().toString() : null; }
    String selectedKey() { return selectedKey; }
    View cell(int index) { return index >= 0 && index < grid.getChildCount() ? grid.getChildAt(index) : null; }
    View selectedIcon() { return cell(selected) instanceof Cell cell ? cell.icon : null; }
    ScrollView scroller() { return scroll; }

    /** Restores a saved selection; a missing app keeps the nearest position. */
    void restoreSelection(String key, int fallback, boolean viaKey) {
        keyMode = viaKey;
        selectedKey = key;
        int index = indexOf(key);
        selected = apps.isEmpty() ? Math.max(0, fallback)
                : index >= 0 ? index : Math.min(Math.max(0, fallback), apps.size() - 1);
        if (!apps.isEmpty()) selectedKey = apps.get(selected).key;
        refreshFocus();
        post(() -> reveal(selected, false));
    }

    boolean handleKey(int key, KeyEvent event) {
        boolean confirm = isConfirm(key);
        if (apps.isEmpty()) {
            if (state == AppRepository.Status.FAILED && confirm && event.getRepeatCount() == 0 && actions != null) {
                actions.retry();
            }
            return isNavigation(key) || confirm || isMenuKey(key);
        }
        if (isNavigation(key)) {
            boolean wasKeyMode = keyMode;
            keyMode = true;
            select(wasKeyMode ? move(selected, key) : selected);
            return true;
        }
        if (event.getRepeatCount() > 0) return confirm || isMenuKey(key);
        if (confirm) {
            keyMode = true; refreshFocus();
            if (actions != null) actions.launch(apps.get(selected), boundsOf(selected));
            return true;
        }
        if (isMenuKey(key)) {
            keyMode = true; refreshFocus();
            if (actions != null) actions.showMenu(apps.get(selected));
            return true;
        }
        return false;
    }

    static boolean isConfirm(int key) {
        return key == InteractionPreferences.primaryConfirmKey() || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_SPACE;
    }

    static boolean isMenuKey(int key) {
        return key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_BUTTON_SELECT
                || key == KeyEvent.KEYCODE_BUTTON_START;
    }

    static boolean isNavigation(int key) {
        return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    /** Grid movement stops at every edge; moving down into a shorter last row lands on its final app. */
    int move(int from, int key) {
        int columns = grid.columns();
        int count = apps.size(), column = from % columns, row = from / columns, lastRow = (count - 1) / columns;
        return switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT -> column > 0 ? from - 1 : from;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> column < columns - 1 && from + 1 < count ? from + 1 : from;
            case KeyEvent.KEYCODE_DPAD_UP -> row > 0 ? from - columns : from;
            case KeyEvent.KEYCODE_DPAD_DOWN -> row < lastRow ? Math.min(from + columns, count - 1) : from;
            default -> from;
        };
    }

    private void select(int index) {
        selected = Math.max(0, Math.min(index, apps.size() - 1));
        selectedKey = apps.get(selected).key;
        refreshFocus();
        reveal(selected, true);
    }

    private void reveal(int index, boolean animate) {
        View cell = cell(index);
        if (cell == null || scroll.getHeight() == 0) return;
        int top = cell.getTop(), bottom = cell.getBottom();
        int visibleTop = scroll.getScrollY(), visibleBottom = visibleTop + scroll.getHeight();
        int target = top < visibleTop ? top : bottom > visibleBottom ? bottom - scroll.getHeight() : visibleTop;
        if (target == visibleTop) return;
        if (animate) scroll.smoothScrollTo(0, target); else scroll.scrollTo(0, target);
    }

    private Rect boundsOf(int index) {
        View cell = cell(index);
        if (cell == null) return null;
        Rect bounds = new Rect();
        return cell.getGlobalVisibleRect(bounds) ? bounds : null;
    }

    private void refreshFocus() {
        for (int i = 0; i < grid.getChildCount(); i++) grid.getChildAt(i).setActivated(keyMode && i == selected);
    }

    /** Icons are decoded for the visible rows plus one screen of margin; distant cells release theirs. */
    private void bindVisibleIcons() {
        int height = Math.max(1, scroll.getHeight());
        int from = scroll.getScrollY() - height, to = scroll.getScrollY() + 2 * height;
        for (int i = 0; i < grid.getChildCount() && i < apps.size(); i++) {
            Cell cell = (Cell) grid.getChildAt(i);
            boolean near = cell.getBottom() >= from && cell.getTop() <= to;
            if (near) cell.showIcon(apps.get(i)); else cell.releaseIcon();
        }
    }

    private int indexOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).key.equals(key)) return i;
        return -1;
    }

    private void render() {
        boolean empty = apps.isEmpty();
        scroll.setVisibility(empty ? INVISIBLE : VISIBLE);
        status.setVisibility(empty ? VISIBLE : GONE);
        status.setClickable(state == AppRepository.Status.FAILED);
        status.setText(language.text(state == AppRepository.Status.LOADING ? R.string.apps_loading
                : state == AppRepository.Status.FAILED ? R.string.apps_failed : R.string.apps_empty));
    }

    private final class Cell extends LinearLayout {
        private final int index;
        private final ImageView icon;
        private final TextView name;
        private String iconKey;

        Cell(Context context, int index) {
            super(context);
            this.index = index;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(icon);
            name = new TextView(context);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextColor(PanelStyle.INK);
            addView(name, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            setClickable(true);
            setLongClickable(true);
            setOnClickListener(view -> {
                selected = this.index; selectedKey = apps.get(this.index).key; refreshFocus();
                if (actions != null) actions.launch(apps.get(this.index), boundsOf(this.index));
            });
            setOnLongClickListener(view -> {
                selected = this.index; selectedKey = apps.get(this.index).key; refreshFocus();
                if (actions != null) actions.showMenu(apps.get(this.index));
                return true;
            });
            applyStyle();
        }

        void applyStyle() {
            LayoutParams params = new LayoutParams(iconSize, iconSize);
            params.bottomMargin = Math.round(layout.px(7));
            icon.setLayoutParams(params);
            setPadding(cellPadding, cellPadding + Math.round(layout.px(2)), cellPadding, cellPadding);
            setBackground(PanelStyle.cellBackground(layout.px(8), Math.max(1, Math.round(layout.px(1.2f)))));
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, labelSize);
            name.setLineSpacing(0, 1.05f);
            iconKey = null;
        }

        void bind(AppEntry entry) {
            name.setText(entry.label);
            setContentDescription(entry.label);
            if (iconKey != null && !iconKey.equals(entry.key)) releaseIcon();
        }

        void showIcon(AppEntry entry) {
            if (entry.key.equals(iconKey)) return;
            iconKey = entry.key;
            icons.bind(entry, icon);
        }

        void releaseIcon() {
            if (iconKey == null) return;
            iconKey = null;
            icon.setTag(null);
            icon.setImageDrawable(null);
        }
    }
}
