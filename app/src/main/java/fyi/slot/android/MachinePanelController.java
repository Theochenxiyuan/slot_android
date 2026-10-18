package fyi.slot.android;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.animation.PathInterpolator;
import java.util.ArrayList;
import java.util.List;

/** Lift state shared by the drawn machine and its native bay content. */
final class MachinePanelController {
    enum Content { SETTINGS, APPS }
    enum State { CLOSED, OPENING, OPEN, CLOSING }

    interface Listener { void onPanelChanged(MachinePanelController panel); }

    static final long DURATION = 340, SWAP_DURATION = 160;
    /** Share of a switch spent fading the old page out; the new one fades in over the rest. */
    private static final float SWAP_DIP = .42f;
    private final List<Listener> listeners = new ArrayList<>();
    private final PathInterpolator easing = new PathInterpolator(.22f, .7f, .18f, 1f);
    /** A dissolve needs an even pace; the lift's curve front-loads too hard for alphas. */
    private final PathInterpolator swapEasing = new PathInterpolator(.4f, 0f, .2f, 1f);
    private ValueAnimator animator, swapAnimator;
    private float progress;
    private boolean open;
    private Content content = Content.SETTINGS;
    private Content previous;
    private float swap = 1f;
    private final AppLanguage language;

    MachinePanelController(AppLanguage language) { this.language = language; }

    void addListener(Listener listener) { listeners.add(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }

    float progress() { return progress; }
    boolean isTargetOpen() { return open; }
    boolean isClosed() { return !open && progress == 0f; }
    boolean isFullyOpen() { return open && progress == 1f; }
    boolean isAnimating() { return animator != null; }
    Content content() { return content; }
    /** Content being faded out during an in-place switch; null while resting. */
    Content previous() { return previous; }
    boolean isSwapping() { return previous != null; }
    /** 0 at the first frame of a switch, 1 once the new content has settled. */
    float swap() { return swap; }
    /** Old page: opaque, then gone before the new one starts. Pages never blend into a ghost. */
    float leavingAlpha() { return swap >= SWAP_DIP ? 0f : 1f - swap / SWAP_DIP; }
    /** New page: held back until the bay is clear, then faded in. */
    float enteringAlpha() { return swap <= SWAP_DIP ? 0f : (swap - SWAP_DIP) / (1f - SWAP_DIP); }

    State state() {
        if (open) return progress == 1f ? State.OPEN : State.OPENING;
        return progress == 0f ? State.CLOSED : State.CLOSING;
    }

    String title() {
        return language.text(content == Content.APPS ? R.string.apps : R.string.settings);
    }

    /** The outgoing title while a switch is fading, otherwise null. */
    String previousTitle() {
        if (previous == null) return null;
        return language.text(previous == Content.APPS ? R.string.apps : R.string.settings);
    }

    /** Opens with the content, or cross-fades in place while already open. */
    void open(Content next) {
        if (!open) { content = next; open = true; animateTo(1f); return; }
        if (content == next) return;
        startSwap(next);
    }

    void close() {
        if (!open) return;
        open = false; settleSwap(); animateTo(0f);
    }

    /** Places the machine at a resting state without animation. */
    void snap(boolean opened, Content next) {
        cancelAnimator();
        settleSwap();
        open = opened; content = next == null ? Content.SETTINGS : next;
        progress = opened ? 1f : 0f;
        notifyChanged();
    }

    /** Lifecycle interruptions settle on the requested state instead of resuming halfway. */
    void finishAnimation() {
        settleSwap();
        if (animator == null) return;
        cancelAnimator();
        progress = open ? 1f : 0f;
        notifyChanged();
    }

    private void startSwap(Content next) {
        settleSwap();
        previous = content;
        content = next;
        swap = 0f;
        ValueAnimator started = ValueAnimator.ofFloat(0f, 1f);
        started.setDuration(SWAP_DURATION);
        started.setInterpolator(swapEasing);
        started.addUpdateListener(animation -> {
            if (swapAnimator != animation) return;
            swap = (float) animation.getAnimatedValue();
            notifyChanged();
        });
        started.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (swapAnimator != animation) return;
                swapAnimator = null; swap = 1f; previous = null; notifyChanged();
            }
        });
        swapAnimator = started;
        notifyChanged();
        started.start();
    }

    /** Drops any in-flight switch at its end state so the next one starts from a clean pair. */
    private void settleSwap() {
        ValueAnimator running = swapAnimator;
        swapAnimator = null;
        if (running != null) running.cancel();
        previous = null;
        swap = 1f;
    }

    private void animateTo(float target) {
        cancelAnimator();
        float start = progress;
        long duration = Math.max(120, (long) (DURATION * Math.abs(target - start)));
        ValueAnimator next = ValueAnimator.ofFloat(start, target);
        next.setDuration(duration);
        next.setInterpolator(easing);
        next.addUpdateListener(animation -> {
            if (animator != animation) return;
            progress = (float) animation.getAnimatedValue();
            notifyChanged();
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animator != animation) return;
                animator = null; progress = target; notifyChanged();
            }
        });
        animator = next;
        notifyChanged();
        next.start();
    }

    private void cancelAnimator() {
        ValueAnimator previous = animator;
        animator = null;
        if (previous != null) previous.cancel();
    }

    private void notifyChanged() {
        for (Listener listener : new ArrayList<>(listeners)) listener.onPanelChanged(this);
    }
}
