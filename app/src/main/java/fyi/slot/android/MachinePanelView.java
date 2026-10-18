package fyi.slot.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/** Native content layer that moves with the drawn machine and fills the bay's body. */
@SuppressLint("ViewConstructor")
final class MachinePanelView extends FrameLayout implements MachinePanelController.Listener {
    private final MachinePanelController panel;
    private final FrameLayout bay;
    private final AppsDrawer apps;
    private final SettingsPanel settings;
    private final AppMenuView menu;
    private HomeLayout layout = HomeLayout.reference();

    MachinePanelView(Context context, MachinePanelController panel, AppsDrawer apps, SettingsPanel settings, AppMenuView menu) {
        super(context);
        this.panel = panel; this.apps = apps; this.settings = settings; this.menu = menu;
        setClipChildren(true);
        bay = new FrameLayout(context) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                // Content stays still while the machine is moving or a page is fading.
                if (!panel.isFullyOpen() || panel.isSwapping()) return true;
                return super.dispatchTouchEvent(event);
            }
        };
        bay.setClickable(true);
        bay.addView(apps, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        bay.addView(settings, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        bay.addView(menu, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(bay, new LayoutParams(0, 0));
        panel.addListener(this);
        onPanelChanged(panel);
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        requestLayout();
        return super.onApplyWindowInsets(insets);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int measuredWidth = MeasureSpec.getSize(widthMeasureSpec), measuredHeight = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(measuredWidth, measuredHeight);
        HomeLayout next = HomeLayout.forView(this, measuredWidth, measuredHeight);
        float top = HomeLayout.MACHINE_OPEN_TOP;
        int left = Math.round(next.toScreenX(next.bodyLeft()));
        int right = Math.round(next.toScreenX(next.bodyRight()));
        int bayTop = Math.round(next.toScreenY(top + next.bodyTopOffset()));
        int bayBottom = Math.round(next.toScreenY(top + next.bodyBottomOffset()));
        int width = Math.max(0, right - left), height = Math.max(0, bayBottom - bayTop);
        if (layout.width != next.width || layout.height != next.height || layout.scale != next.scale
                || bay.getMeasuredWidth() != width || bay.getMeasuredHeight() != height) {
            apps.applyLayout(next, width);
            settings.applyLayout(next);
            menu.applyLayout(next);
        }
        layout = next;
        LayoutParams params = (LayoutParams) bay.getLayoutParams();
        params.leftMargin = left; params.topMargin = bayTop;
        params.width = width; params.height = height;
        bay.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        updatePosition();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        LayoutParams params = (LayoutParams) bay.getLayoutParams();
        bay.layout(params.leftMargin, params.topMargin, params.leftMargin + bay.getMeasuredWidth(),
                params.topMargin + bay.getMeasuredHeight());
    }

    @Override public void onPanelChanged(MachinePanelController changed) {
        boolean visible = changed.progress() > 0;
        setVisibility(visible ? VISIBLE : INVISIBLE);
        boolean showApps = changed.content() == MachinePanelController.Content.APPS;
        View incoming = showApps ? apps : settings;
        View other = showApps ? settings : apps;
        MachinePanelController.Content previous = changed.previous();
        if (previous != null) {
            // The old page clears before the new one arrives, so labels never double-expose.
            View leaving = previous == MachinePanelController.Content.APPS ? apps : settings;
            incoming.setVisibility(VISIBLE);
            incoming.setAlpha(changed.enteringAlpha());
            leaving.setVisibility(VISIBLE);
            leaving.setAlpha(changed.leavingAlpha());
        } else {
            other.setVisibility(INVISIBLE);
            other.setAlpha(1f);
            incoming.setVisibility(VISIBLE);
            incoming.setAlpha(1f);
        }
        if (!showApps && menu.isShowing()) menu.hide();
        updatePosition();
    }

    private void updatePosition() {
        float offset = layout.machineTop(panel.progress()) - HomeLayout.MACHINE_OPEN_TOP;
        bay.setTranslationY(layout.px(offset));
    }

    /** Touches outside the bay fall through to the drawn machine. */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (getVisibility() != VISIBLE) return false;
        return super.dispatchTouchEvent(event);
    }

    View bay() { return bay; }

    void refreshTheme() {
        int width = bay.getMeasuredWidth();
        if (width > 0) apps.applyLayout(layout, width);
        int scrollY = settings.scroller().getScrollY();
        settings.applyLayout(layout);
        settings.scroller().post(() -> settings.scroller().scrollTo(0, scrollY));
        menu.applyLayout(layout);
    }
}
