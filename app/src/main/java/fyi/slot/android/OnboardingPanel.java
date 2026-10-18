package fyi.slot.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Full-screen spotlight overlay for first-use onboarding.
 * Punches rounded cutouts over the live settings/shelf targets and docks a floating guide card beside them.
 */
@SuppressLint("ViewConstructor")
final class OnboardingPanel extends FrameLayout {
    record Action(String id, int label, boolean primary, Runnable run) {}

    private static final int[] TITLES = {
            R.string.guide_folder, R.string.guide_games, R.string.guide_emulator, R.string.guide_controls
    };

    private final Activity activity;
    private final AppLanguage language;
    private final SettingsPanel settings;
    private final LibraryRepository library;
    private final LibraryPanel libraryPanel;
    private final RetroArchSettings retroarch;
    private final OnboardingPreferences preferences;
    private final Consumer<String> openSettings;
    private final Runnable showShelf, finish, studio, connect;
    private final LinearLayout card, headerRow, actionsRow, navLeft, navRight, navRow;
    private final TextView badgeView, titleView, statusView, bodyView;
    private final List<Action> actions = new ArrayList<>();
    private final List<TextView> buttons = new ArrayList<>();
    private final List<RectF> cutouts = new ArrayList<>();
    private final RectF cardBounds = new RectF();
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private HomeLayout layout = HomeLayout.reference();
    private int step, focusedIndex;
    private boolean keyMode, touchingCard, syncing;

    OnboardingPanel(Activity activity, AppLanguage language, SettingsPanel settings, LibraryRepository library,
                    LibraryPanel libraryPanel, RetroArchSettings retroarch, Consumer<String> openSettings,
                    Runnable showShelf, Runnable finish, Runnable studio, Runnable connect) {
        super(activity);
        this.activity = activity;
        this.language = language;
        this.settings = settings;
        this.library = library;
        this.libraryPanel = libraryPanel;
        this.retroarch = retroarch;
        this.openSettings = openSettings;
        this.showShelf = showShelf;
        this.finish = finish;
        this.studio = studio;
        this.connect = connect;
        preferences = new OnboardingPreferences(activity);

        setWillNotDraw(false);
        setVisibility(GONE);
        scrimPaint.setColor(0xc408080c);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setColor(0x40c4ab59);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(0xffc4ab59);

        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);

        headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        badgeView = new TextView(activity);
        badgeView.setSingleLine(true);
        headerRow.addView(badgeView, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        titleView = new TextView(activity);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        headerRow.addView(titleView, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        statusView = new TextView(activity);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        headerRow.addView(statusView, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        card.addView(headerRow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        bodyView = new TextView(activity);
        card.addView(bodyView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        actionsRow = new LinearLayout(activity);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(actionsRow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        navRow = new LinearLayout(activity);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);
        navLeft = new LinearLayout(activity);
        navLeft.setOrientation(LinearLayout.HORIZONTAL);
        navLeft.setGravity(Gravity.CENTER_VERTICAL);
        navRight = new LinearLayout(activity);
        navRight.setOrientation(LinearLayout.HORIZONTAL);
        navRight.setGravity(Gravity.CENTER_VERTICAL);
        navRow.addView(navLeft, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        navRow.addView(navRight, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        card.addView(navRow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        addView(card, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        applyStyle();
    }

    void open() {
        step = 0;
        keyMode = false;
        setVisibility(VISIBLE);
        syncStep();
        refresh();
    }

    void hide() { setVisibility(GONE); }
    boolean isShowing() { return getVisibility() == VISIBLE; }
    int step() { return step; }
    boolean shouldOpen(boolean fixture) { return preferences.shouldOpen(fixture, library.root()); }
    void dismissed() { preferences.dismiss(); }

    boolean expectsGroup(String id) {
        return syncing || (step <= 1 && LibraryPanel.GROUP.equals(id)) || (step == 2 && RetroArchSettings.GROUP.equals(id));
    }

    void syncStep() {
        if (!isShowing()) return;
        syncing = true;
        try {
            if (step <= 1) {
                libraryPanel.resetPage();
                openSettings.accept(LibraryPanel.GROUP);
            } else if (step == 2) {
                openSettings.accept(RetroArchSettings.GROUP);
            } else {
                showShelf.run();
            }
        } finally {
            syncing = false;
        }
    }

    void save(Bundle out) {
        out.putBoolean("guide_open", isShowing());
        out.putInt("guide_step", step);
    }

    void restore(Bundle saved) {
        step = Math.max(0, Math.min(3, saved.getInt("guide_step")));
        if (saved.getBoolean("guide_open")) {
            setVisibility(VISIBLE);
            syncStep();
            refresh();
        } else {
            setVisibility(GONE);
        }
    }

    void refresh() {
        if (!isShowing()) return;
        applyStyle();
        int titleId = TITLES[step];
        badgeView.setText((step + 1) + " / 4");
        titleView.setText(language.text(titleId));
        card.setContentDescription(language.text(R.string.guide_progress, step + 1, language.text(titleId)));

        actions.clear();
        buttons.clear();
        actionsRow.removeAllViews();
        navLeft.removeAllViews();
        navRight.removeAllViews();

        List<Action> stepActions = new ArrayList<>();
        switch (step) {
            case 0 -> bindFolderStep(stepActions);
            case 1 -> bindGamesStep(stepActions);
            case 2 -> bindEmulatorStep(stepActions);
            case 3 -> bindControlsStep();
            default -> throw new IllegalStateException();
        }

        boolean inlineStepAction = stepActions.size() <= 1;
        actionsRow.setVisibility(!inlineStepAction && !stepActions.isEmpty() ? VISIBLE : GONE);
        if (!inlineStepAction) {
            for (Action action : stepActions) addButton(actionsRow, action);
        }
        addButton(navLeft, new Action("guide_skip", R.string.guide_skip, false, finish));
        if (inlineStepAction) {
            for (Action action : stepActions) addButton(navLeft, action);
        }
        if (step > 0) addButton(navRight, new Action("guide_previous", R.string.guide_previous, false, () -> move(-1)));
        if (step < 3) addButton(navRight, new Action("guide_next", R.string.guide_next, true, () -> move(1)));
        else addButton(navRight, new Action("guide_done", R.string.guide_done, true, finish));

        focusedIndex = Math.max(0, buttons.size() - 1);
        updateButtonFocus();
        requestLayout();
        invalidate();
    }

    private void bindFolderStep(List<Action> stepActions) {
        int state = !LibraryStorage.allowed() ? R.string.library_permission : libraryPanel.busy() ? R.string.apps_loading
                : library.status() == LibraryRepository.Status.READY || library.status() == LibraryRepository.Status.EMPTY
                ? R.string.guide_ready : LibraryPanel.statusText(library.status());
        statusView.setVisibility(VISIBLE);
        statusView.setText(language.text(state));
        bodyView.setText(language.text(R.string.guide_rules_text));
        stepActions.add(new Action("guide_choose", LibraryStorage.allowed() ? R.string.choose_folder : R.string.allow_files,
                false, libraryPanel::chooseForGuide));
        if (library.status() == LibraryRepository.Status.ERROR || library.status() == LibraryRepository.Status.OFFLINE) {
            stepActions.add(new Action("guide_retry", R.string.refresh_library, false, library::refresh));
        }
    }

    private void bindGamesStep(List<Action> stepActions) {
        boolean ready = library.root() != null && LibraryStorage.allowed()
                && (library.status() == LibraryRepository.Status.READY || library.status() == LibraryRepository.Status.EMPTY);
        statusView.setVisibility(VISIBLE);
        statusView.setText(ready
                ? language.text(R.string.guide_game_count, library.snapshot().size() + library.hidden().size())
                : language.text(LibraryPanel.statusText(library.status())));
        bodyView.setText(language.text(R.string.guide_games_text));
        if (ready) {
            stepActions.add(new Action("guide_import", R.string.import_games, false, libraryPanel::pickGames));
            stepActions.add(new Action("guide_studio", R.string.edit_cartridges, false, studio));
            stepActions.add(new Action("guide_connect", R.string.guide_connect, false, connect));
        } else {
            stepActions.add(new Action("guide_setup", R.string.guide_folder, false, () -> {
                step = 0;
                syncStep();
                refresh();
            }));
        }
    }

    private void bindEmulatorStep(List<Action> stepActions) {
        statusView.setVisibility(VISIBLE);
        statusView.setText("RetroArch · " + retroarch.applicationLabel());
        bodyView.setText(language.text(R.string.guide_core_text));
        if (retroarch.queryFailed()) {
            stepActions.add(new Action("guide_check", R.string.refresh_library, false, retroarch::reload));
        } else if (!retroarch.isChecking()) {
            stepActions.add(retroarch.isInstalled()
                    ? new Action("guide_open_retroarch", R.string.retroarch_open, false, retroarch::open)
                    : new Action("guide_download", R.string.guide_download, false, this::download));
        }
    }

    private void bindControlsStep() {
        statusView.setVisibility(GONE);
        statusView.setText("");
        InteractionPreferences interactions = new InteractionPreferences(activity);
        bodyView.setText(language.text(R.string.guide_controls_text,
                interactions.leftShoulderBadge(), interactions.rightShoulderBadge(),
                interactions.confirmBadge(), interactions.backBadge()));
    }

    private void addButton(LinearLayout parent, Action action) {
        TextView button = new TextView(activity);
        button.setText(language.text(action.label));
        button.setTag(action.id);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        int hPad = Math.round(layout.px(10)), vPad = Math.round(layout.px(5.5f));
        button.setPadding(hPad, vPad, hPad, vPad);
        button.setMinimumHeight(Math.round(layout.px(30, 32)));
        PanelStyle.text(button, layout, 13.5f, 11.5f, action.primary ? 0xff191710 : 0xfff0f0f5);
        if (action.primary) button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setBackground(buttonDrawable(action.primary));
        button.setClickable(true);
        button.setOnClickListener(view -> action.run.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        if (parent.getChildCount() > 0) params.leftMargin = Math.round(layout.px(6));
        parent.addView(button, params);
        actions.add(action);
        buttons.add(button);
    }

    private android.graphics.drawable.Drawable buttonDrawable(boolean primary) {
        float radius = layout.px(6);
        int fill = primary ? 0xffc4ab59 : 0xff32323c;
        int pressed = primary ? 0xffd6be6c : 0xff3e3e4a;
        int stroke = primary ? 0xffe5cf82 : 0xff525260;
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, PanelStyle.rounded(pressed, 0xfff5e096, 2, radius));
        states.addState(new int[]{android.R.attr.state_activated}, PanelStyle.rounded(fill, 0xffffe58f, 2, radius));
        states.addState(new int[]{}, PanelStyle.rounded(fill, stroke, 1, radius));
        return states;
    }

    private void applyStyle() {
        int pad = Math.round(layout.px(11));
        card.setPadding(pad, Math.round(layout.px(9)), pad, Math.round(layout.px(9)));
        card.setBackground(PanelStyle.rounded(0xf5202027, 0xff525260, 1, layout.px(10)));
        card.setElevation(layout.px(12));

        int badgeH = Math.round(layout.px(7)), badgeV = Math.round(layout.px(2));
        badgeView.setPadding(badgeH, badgeV, badgeH, badgeV);
        badgeView.setBackground(PanelStyle.rounded(0xffc4ab59, 0, 0, layout.px(4)));
        PanelStyle.text(badgeView, layout, 12f, 10.5f, 0xff191710);
        badgeView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        PanelStyle.text(titleView, layout, 15.5f, 13f, 0xfff4f4f8);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = (LinearLayout.LayoutParams) titleView.getLayoutParams();
        titleParams.leftMargin = Math.round(layout.px(8));
        titleParams.rightMargin = Math.round(layout.px(8));

        PanelStyle.text(statusView, layout, 12.5f, 11f, 0xffe0c878);
        statusView.setMaxWidth(Math.round(layout.px(220)));

        PanelStyle.text(bodyView, layout, 13f, 11.5f, 0xffd4d4dc);
        bodyView.setLineSpacing(layout.px(2f), 1f);
        LinearLayout.LayoutParams bodyParams = (LinearLayout.LayoutParams) bodyView.getLayoutParams();
        bodyParams.topMargin = Math.round(layout.px(5));

        LinearLayout.LayoutParams actionsParams = (LinearLayout.LayoutParams) actionsRow.getLayoutParams();
        actionsParams.topMargin = Math.round(layout.px(7));

        LinearLayout.LayoutParams navParams = (LinearLayout.LayoutParams) navRow.getLayoutParams();
        navParams.topMargin = Math.round(layout.px(7));

        haloPaint.setStrokeWidth(layout.px(4.5f));
        strokePaint.setStrokeWidth(layout.px(1.8f));
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec), height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        HomeLayout next = HomeLayout.forView(this, width, height);
        if (layout.width != next.width || layout.height != next.height || layout.scale != next.scale) {
            layout = next;
            if (isShowing()) refresh();
        } else {
            layout = next;
        }
        int bodyLeft = Math.round(layout.toScreenX(layout.bodyLeft()));
        int bodyRight = Math.round(layout.toScreenX(layout.bodyRight()));
        int rail = settings.railWidth() > 0 ? settings.railWidth() : Math.round(layout.px(150));
        int rightColWidth = Math.max(Math.round(layout.px(280)), bodyRight - bodyLeft - rail);
        int cardWidth = step == 3 ? Math.min(bodyRight - bodyLeft, Math.round(layout.px(480)))
                : Math.min(rightColWidth, Math.round(layout.px(500)));
        card.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left, height = bottom - top;
        RectF rowCutout = updateCutouts();
        int cardW = card.getMeasuredWidth(), cardH = card.getMeasuredHeight();
        int bodyLeft = Math.round(layout.toScreenX(layout.bodyLeft()));
        int bodyRight = Math.round(layout.toScreenX(layout.bodyRight()));
        int margin = Math.round(layout.px(8));
        int cardLeft, cardTop;
        if (step == 3) {
            cardLeft = Math.round((width - cardW) / 2f);
            float topLimit = cutouts.size() > 0 ? cutouts.get(0).bottom + margin : layout.frameTop + margin;
            float bottomLimit = cutouts.size() > 1 ? cutouts.get(1).top - margin : layout.frameBottom - margin;
            cardTop = Math.round(topLimit + Math.max(0f, (bottomLimit - topLimit - cardH) / 2f));
        } else {
            int rail = settings.railWidth() > 0 ? settings.railWidth() : Math.round(layout.px(150));
            cardLeft = Math.max(bodyLeft + rail + Math.round(layout.px(4)), bodyRight - cardW);
            float minTop = layout.frameTop + margin;
            float maxBottom = layout.frameBottom - margin;
            if (rowCutout != null) {
                float spaceBelow = maxBottom - (rowCutout.bottom + margin);
                float spaceAbove = (rowCutout.top - margin) - minTop;
                if (spaceBelow >= cardH || spaceBelow >= spaceAbove) {
                    cardTop = Math.round(Math.max(rowCutout.bottom + margin, maxBottom - cardH));
                } else {
                    cardTop = Math.round(Math.min(rowCutout.top - margin - cardH,
                            Math.max(minTop, rowCutout.top - margin - cardH)));
                }
            } else {
                cardTop = Math.round(maxBottom - cardH);
            }
        }
        card.layout(cardLeft, cardTop, cardLeft + cardW, cardTop + cardH);
        cardBounds.set(cardLeft, cardTop, cardLeft + cardW, cardTop + cardH);
    }

    private RectF updateCutouts() {
        cutouts.clear();
        if (step == 3) {
            float cx = layout.centerX();
            cutouts.add(new RectF(layout.toScreenX(cx - 124f), layout.toScreenY(10f),
                    layout.toScreenX(cx + 124f), layout.toScreenY(60f)));
            cutouts.add(new RectF(layout.toScreenX(cx - 186f), layout.toScreenY(layout.machineClosedTop() - 6f),
                    layout.toScreenX(cx + 186f), layout.toScreenY(layout.height - 6f)));
            return null;
        }
        String tabId = step == 0 ? LibraryPanel.GROUP : step == 1 ? ConnectionPanel.GROUP : RetroArchSettings.GROUP;
        addTabCutout(tabId);
        if (step == 0) return addRowCutout("status", "permission", "choose");
        if (step == 1) {
            return settings.rowView("import") != null
                    ? addRowCutout("import", "studio")
                    : addRowCutout("status", "permission", "choose");
        }
        return addRowCutout("retroarch_app", "core_GBA", "core_GBC", "core_GB");
    }

    private void addTabCutout(String groupId) {
        RectF bounds = new RectF();
        if (boundsInRoot(settings.tabView(groupId), bounds)) {
            insetOutward(bounds, layout.px(3f));
            cutouts.add(bounds);
        }
    }

    private RectF addRowCutout(String... ids) {
        RectF union = null;
        RectF temp = new RectF();
        for (String id : ids) {
            if (boundsInRoot(settings.rowView(id), temp)) {
                if (union == null) union = new RectF(temp);
                else union.union(temp);
            }
        }
        if (union != null) {
            insetOutward(union, layout.px(3f));
            cutouts.add(union);
        }
        return union;
    }

    private static void insetOutward(RectF rect, float pad) {
        rect.left -= pad;
        rect.top -= pad;
        rect.right += pad;
        rect.bottom += pad;
    }

    private boolean boundsInRoot(View view, RectF out) {
        if (view == null || view.getVisibility() == GONE || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        float left = 0f, top = 0f;
        View current = view;
        View root = getParent() instanceof View parent ? parent : null;
        while (current != null && current != root) {
            left += current.getLeft();
            top += current.getTop();
            if (current.getParent() instanceof View parent) {
                left -= parent.getScrollX();
                top -= parent.getScrollY();
                current = parent;
            } else {
                return false;
            }
        }
        if (current != root || root == null) return false;
        out.set(left, top, left + view.getWidth(), top + view.getHeight());
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!isShowing()) return;
        int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        float radius = layout.px(7f);
        for (RectF cutout : cutouts) {
            canvas.drawRoundRect(cutout, radius, radius, clearPaint);
            canvas.drawRoundRect(cutout, radius + layout.px(1.5f), radius + layout.px(1.5f), haloPaint);
            canvas.drawRoundRect(cutout, radius, radius, strokePaint);
        }
        canvas.restoreToCount(layer);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (!isShowing()) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (keyMode) { keyMode = false; updateButtonFocus(); }
            float x = event.getX(), y = event.getY();
            touchingCard = cardBounds.contains(x, y);
            if (!touchingCard) {
                for (RectF cutout : cutouts) {
                    if (cutout.contains(x, y)) return false;
                }
                return true;
            }
        }
        return touchingCard ? super.dispatchTouchEvent(event) : true;
    }

    boolean dispatchKey(KeyEvent event) {
        if (!isShowing()) return false;
        int key = event.getKeyCode();
        if (SlotView.entryForKey(key) == MachinePanelController.Content.APPS) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (event.getRepeatCount() > 0) return true;
        if (SlotView.isBackKey(key)) {
            finish.run();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_UP) {
            keyMode = true;
            focusedIndex = Math.max(0, focusedIndex - 1);
            updateButtonFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            keyMode = true;
            focusedIndex = Math.min(Math.max(0, buttons.size() - 1), focusedIndex + 1);
            updateButtonFocus();
            return true;
        }
        if (AppsDrawer.isConfirm(key) && focusedIndex >= 0 && focusedIndex < buttons.size()) {
            buttons.get(focusedIndex).performClick();
            return true;
        }
        return true;
    }

    private void updateButtonFocus() {
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).setActivated(keyMode && i == focusedIndex);
        }
    }

    private void download() {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.retroarch.com/?page=platforms")));
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(activity, language.text(R.string.guide_browser_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void move(int direction) {
        step = Math.max(0, Math.min(3, step + direction));
        syncStep();
        refresh();
    }

    // Inspection helpers for unit and device checks.
    List<Action> actions() { return List.copyOf(actions); }
    boolean hasAction(String id) { return actions.stream().anyMatch(a -> a.id.equals(id)); }
    void invokeAction(String id) {
        actions.stream().filter(a -> a.id.equals(id)).findFirst().orElseThrow().run.run();
    }
    String statusText() { return statusView.getText().toString(); }
    String bodyText() { return bodyView.getText().toString(); }
    List<RectF> cutouts() { return List.copyOf(cutouts); }
    RectF cardBounds() { return new RectF(cardBounds); }
}
