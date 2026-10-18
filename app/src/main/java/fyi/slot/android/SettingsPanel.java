package fyi.slot.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Settings in the battery bay: a tab rail of groups beside the current group's rows.
 * Keys work on two levels: up/down move within the focused list, left/right adjust a row's value,
 * confirm enters the rows, back returns to the tabs.
 */
@SuppressLint("ViewConstructor")
final class SettingsPanel extends FrameLayout {
    enum Kind { SWITCH, CHOICE, ENTRY, INFO }

    /** One settings row; group starts add a gap above the row. */
    static final class Row {
        /** Builds a read-only row such as a code image. */
        interface ViewFactory { View create(Context context, HomeLayout layout); }
        final String id;
        final int label;
        final String text;
        final Kind kind;
        final boolean groupStart;
        final Supplier<String> value;
        final IntConsumer action;
        final ViewFactory view;

        Row(String id, int label, Kind kind, boolean groupStart, Supplier<String> value, IntConsumer action) {
            this.id = id; this.label = label; this.kind = kind; this.groupStart = groupStart;
            this.text = null; this.view = null;
            this.value = value; this.action = action;
        }
        Row(String id, String text, Kind kind, Supplier<String> value, IntConsumer action) {
            this.id = id; this.label = 0; this.text = text; this.kind = kind; this.groupStart = false;
            this.value = value; this.action = action; this.view = null;
        }
        Row(String id, ViewFactory view) {
            this.id = id; this.label = 0; this.text = null; this.kind = Kind.INFO; this.groupStart = false;
            this.value = null; this.action = direction -> {}; this.view = view;
        }
    }

    /** A tab and its rows; the selection is kept while other tabs are shown. */
    static final class Group {
        final String id;
        final int title;
        List<Row> rows;
        int selected;

        Group(String id, int title, List<Row> rows) { this.id = id; this.title = title; this.rows = List.copyOf(rows); }
    }

    interface Listener { void onGroupShown(String id); }

    private static final float RAIL_RATIO = .25f, RAIL_MIN = 96f, RAIL_MAX = 172f;

    private final List<Group> groups;
    private final LinearLayout rail, list;
    private final ScrollView scroll;
    private final List<TextView> tabViews = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();
    private final AppLanguage language;
    private Listener listener;
    private HomeLayout layout = HomeLayout.reference();
    private int groupIndex, railWidth;
    private boolean keyMode, inRows;

    SettingsPanel(Context context, AppLanguage language, List<Group> groups) {
        super(context);
        this.language = language;
        this.groups = List.copyOf(groups);
        rail = new LinearLayout(context);
        rail.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(context);
        scroll.setFocusable(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LinearLayout columns = new LinearLayout(context);
        columns.addView(rail, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        columns.addView(scroll, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        addView(columns, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        applyLayout(layout);
    }

    void setListener(Listener listener) { this.listener = listener; }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && keyMode) { keyMode = false; refresh(); }
        return super.onInterceptTouchEvent(event);
    }

    void applyLayout(HomeLayout next) {
        layout = next;
        float body = layout.px(layout.bodyRight() - layout.bodyLeft());
        railWidth = Math.round(HomeLayout.clamp(body * RAIL_RATIO, layout.px(RAIL_MIN), layout.px(RAIL_MAX)));
        rail.setPadding(0, 0, Math.round(layout.px(10)), 0);
        buildRail();
        buildRows();
        refresh();
    }

    /** Fresh openings start on the first tab with the tab rail focused. */
    void reset(boolean viaKey) {
        keyMode = viaKey; inRows = false; groupIndex = 0;
        for (Group group : groups) group.selected = 0;
        buildRows();
        scroll.scrollTo(0, 0);
        refresh();
    }

    /** Leaves the rows for the tab rail; returns false when back should close the panel. */
    boolean back() {
        if (!keyMode || !inRows) return false;
        inRows = false; refresh();
        return true;
    }

    /** Shows a tab with the rail focused and tells the listener even if it was already shown. */
    void showGroup(String id) {
        int index = indexOf(id);
        if (index < 0) return;
        inRows = false;
        if (index != groupIndex) { groupIndex = index; buildRows(); scroll.scrollTo(0, 0); }
        refresh();
        if (listener != null) listener.onGroupShown(id);
    }

    /** Replaces a tab's rows, keeping the selected row by id. */
    void replaceGroup(String id, List<Row> rows) {
        int index = indexOf(id);
        if (index < 0) return;
        Group group = groups.get(index);
        String selectedId = group.rows.isEmpty() ? null : group.rows.get(HomeLayout.clampIndex(group.selected, group.rows.size())).id;
        group.rows = List.copyOf(rows);
        group.selected = HomeLayout.clampIndex(group.selected, group.rows.size());
        for (int i = 0; i < group.rows.size(); i++) if (group.rows.get(i).id.equals(selectedId)) group.selected = i;
        if (index != groupIndex) return;
        if (group.rows.isEmpty()) inRows = false;
        int position = scroll.getScrollY();
        buildRows();
        refresh();
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    void refresh() {
        for (int i = 0; i < tabViews.size(); i++) {
            TextView tab = tabViews.get(i);
            tab.setText(language.text(groups.get(i).title));
            tab.setSelected(i == groupIndex);
            tab.setActivated(keyMode && !inRows && i == groupIndex);
        }
        List<Row> rows = current().rows;
        for (int i = 0; i < rowViews.size() && i < rows.size(); i++) bindRow(rowViews.get(i), rows.get(i), i);
    }

    boolean handleKey(int key, KeyEvent event) {
        boolean vertical = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean sideways = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean confirm = AppsDrawer.isConfirm(key);
        if (!vertical && !sideways && !confirm) return false;
        if (!keyMode) {
            keyMode = true;
            if (!confirm) { refresh(); return true; }
        }
        int step = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
        if (!inRows) {
            if (vertical) { selectTab(HomeLayout.clampIndex(groupIndex + step, groups.size())); return true; }
            if (event.getRepeatCount() > 0) return true;
            if (confirm) enterRows();
            return true;
        }
        Group group = current();
        if (vertical) {
            group.selected = move(group, group.selected, step);
            refresh();
            if (step < 0 && move(group, group.selected, -1) == group.selected) scroll.smoothScrollTo(0, 0);
            else reveal(group.selected);
            return true;
        }
        if (event.getRepeatCount() > 0) return true;
        if (sideways) {
            int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            adjust(group.selected, direction);
        }
        else if (confirm) activate(group.selected);
        return true;
    }

    void save(Bundle out) {
        out.putString("settings_group", current().id);
        out.putBoolean("settings_in_rows", inRows);
        out.putInt("settings_row", current().selected);
        out.putBoolean("settings_key_mode", keyMode);
    }

    void restore(Bundle saved) {
        keyMode = saved.getBoolean("settings_key_mode");
        groupIndex = Math.max(0, indexOf(saved.getString("settings_group")));
        current().selected = HomeLayout.clampIndex(saved.getInt("settings_row"), current().rows.size());
        inRows = saved.getBoolean("settings_in_rows") && !current().rows.isEmpty();
        buildRows();
        refresh();
        scroll.post(() -> reveal(current().selected));
    }

    String groupId() { return current().id; }
    boolean isInRows() { return inRows; }
    int selectedRow() { return current().selected; }
    int rowCount() { return current().rows.size(); }
    int groupCount() { return groups.size(); }
    View rowView(int index) { return rowViews.get(index); }
    View tabView(int index) { return tabViews.get(index); }
    int railWidth() { return railWidth; }
    View tabView(String id) {
        int index = indexOf(id);
        return index >= 0 && index < tabViews.size() ? tabViews.get(index) : null;
    }
    View rowView(String id) {
        List<Row> rows = current().rows;
        for (int i = 0; i < rows.size() && i < rowViews.size(); i++) {
            if (rows.get(i).id.equals(id)) return rowViews.get(i);
        }
        return null;
    }
    boolean isKeyMode() { return keyMode; }
    ScrollView scroller() { return scroll; }
    void focusRow(int row, int position) {
        current().selected = HomeLayout.clampIndex(row, current().rows.size());
        if (!current().rows.isEmpty() && current().rows.get(current().selected).view != null)
            current().selected = move(current(), current().selected, 1);
        refresh(); scroll.post(() -> scroll.scrollTo(0, position));
    }

    private Group current() { return groups.get(groupIndex); }

    private int indexOf(String id) {
        for (int i = 0; i < groups.size(); i++) if (groups.get(i).id.equals(id)) return i;
        return -1;
    }

    private void selectTab(int index) {
        if (index == groupIndex) { refresh(); return; }
        groupIndex = index;
        buildRows();
        scroll.scrollTo(0, 0);
        refresh();
        if (listener != null) listener.onGroupShown(current().id);
    }

    private void enterRows() {
        Group group = current();
        if (group.rows.isEmpty()) return;
        inRows = true;
        group.selected = HomeLayout.clampIndex(group.selected, group.rows.size());
        if (group.rows.get(group.selected).view != null) group.selected = move(group, group.selected, 1);
        refresh(); reveal(group.selected);
    }

    /** The next row in a direction, passing over read-only custom rows; stays put at the edge. */
    private static int move(Group group, int from, int step) {
        for (int i = from + step; i >= 0 && i < group.rows.size(); i += step)
            if (group.rows.get(i).view == null) return i;
        return from;
    }

    private void activate(int index) {
        Group group = current();
        if (index < 0 || index >= group.rows.size()) return;
        inRows = true;
        group.selected = index;
        refresh();
        group.rows.get(index).action.accept(1);
        refresh();
    }

    /** Sideways adjusts the focused value; rows that cannot be adjusted keep the key consumed. */
    private void adjust(int index, int direction) {
        Group group = current();
        if (index < 0 || index >= group.rows.size()) return;
        Row row = group.rows.get(index);
        if (row.kind != Kind.SWITCH && row.kind != Kind.CHOICE) return;
        group.rows.get(index).action.accept(direction);
        refresh();
    }

    private void reveal(int index) {
        if (index < 0 || index >= rowViews.size()) return;
        View row = rowViews.get(index);
        int top = row.getTop(), bottom = row.getBottom();
        int visibleTop = scroll.getScrollY(), visibleBottom = visibleTop + scroll.getHeight();
        if (top < visibleTop) scroll.smoothScrollTo(0, top);
        else if (bottom > visibleBottom) scroll.smoothScrollTo(0, bottom - scroll.getHeight());
    }

    private void buildRail() {
        rail.removeAllViews();
        tabViews.clear();
        for (int i = 0; i < groups.size(); i++) {
            TextView tab = createTab(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(railWidth, LayoutParams.WRAP_CONTENT);
            params.topMargin = Math.round(layout.px(i == 0 ? 0 : 2));
            rail.addView(tab, params);
            tabViews.add(tab);
        }
    }

    private void buildRows() {
        list.removeAllViews();
        rowViews.clear();
        List<Row> rows = current().rows;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).groupStart && i > 0) addDivider();
            View view = createRow(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            params.topMargin = Math.round(layout.px(i == 0 || rows.get(i).groupStart ? 0 : 2));
            list.addView(view, params);
            rowViews.add(view);
            bindRow(view, rows.get(i), i);
        }
    }

    /** A hairline inset to the row text, splitting a group's sections. */
    private void addDivider() {
        View divider = new View(getContext());
        divider.setBackgroundColor(PanelStyle.DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                Math.max(1, Math.round(layout.px(1))));
        int horizontal = Math.round(layout.px(14)), vertical = Math.round(layout.px(7));
        params.leftMargin = horizontal;
        params.rightMargin = horizontal;
        params.topMargin = vertical;
        params.bottomMargin = vertical;
        list.addView(divider, params);
    }

    private TextView createTab(int index) {
        TextView tab = new TextView(getContext());
        tab.setSingleLine(true);
        tab.setEllipsize(TextUtils.TruncateAt.END);
        tab.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        int horizontal = Math.round(layout.px(14)), vertical = Math.round(layout.px(10.5f));
        tab.setPadding(horizontal, vertical, horizontal, vertical);
        tab.setMinimumHeight(Math.round(layout.px(42, 44)));
        tab.setBackground(PanelStyle.tabBackground(layout.px(5)));
        PanelStyle.text(tab, layout, 16.5f, 14, PanelStyle.INK);
        tab.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_selected}, {}},
                new int[]{PanelStyle.INK, PanelStyle.MUTED}));
        tab.setOnClickListener(clicked -> { inRows = false; selectTab(index); });
        return tab;
    }

    private View createRow(int index) {
        Row row = current().rows.get(index);
        if (row.view != null) return row.view.create(getContext(), layout);
        LinearLayout view = new LinearLayout(getContext());
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        int horizontal = Math.round(layout.px(14)), vertical = Math.round(layout.px(10.5f));
        view.setPadding(horizontal, vertical, horizontal, vertical);
        view.setMinimumHeight(Math.round(layout.px(42, 44)));
        view.setBackground(PanelStyle.rowBackground(layout.px(5)));
        TextView label = new TextView(getContext());
        label.setMaxLines(row.text != null || row.kind == Kind.ENTRY ? 2 : 1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        PanelStyle.text(label, layout, 16.5f, 14, PanelStyle.INK);
        view.addView(label, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(getContext());
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.START);
        value.setMaxWidth(Math.round(layout.px(layout.bodyRight() - layout.bodyLeft() - RAIL_MAX) * .6f));
        PanelStyle.text(value, layout, 16, 13, PanelStyle.MUTED);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        valueParams.leftMargin = Math.round(layout.px(12));
        view.addView(value, valueParams);
        view.setClickable(current().rows.get(index).kind != Kind.INFO);
        view.setOnClickListener(clicked -> activate(index));
        return view;
    }

    private void bindRow(View view, Row row, int index) {
        if (row.view != null) return;
        String label = row.text == null ? language.text(row.label) : row.text;
        ((TextView) ((LinearLayout) view).getChildAt(0)).setText(label);
        TextView value = (TextView) ((LinearLayout) view).getChildAt(1);
        String text = row.value == null ? null : row.value.get();
        String display = text == null || text.isEmpty() ? "" : text;
        if (row.kind == Kind.CHOICE && !display.isEmpty()) display = "‹ " + display + " ›";
        value.setText(row.kind == Kind.ENTRY && display.isEmpty() ? "›" : display);
        view.setContentDescription(text == null || text.isEmpty() ? label : language.text(R.string.setting_description, label, text));
        view.setActivated(keyMode && inRows && index == current().selected);
    }
}
