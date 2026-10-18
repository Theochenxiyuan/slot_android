package fyi.slot.android;

/** Fade of the seated identity and controls, independent of cartridge travel and external dispatch. */
final class SeatedPresentation {
    private static final float FADE_IN_SECONDS = .26f, FADE_OUT_SECONDS = .12f;
    private float progress;
    private boolean hiddenForLaunch;
    private boolean visible;

    void reset(boolean seated) {
        hiddenForLaunch = false;
        visible = seated;
        progress = seated ? 1f : 0f;
    }

    void hideForAutomaticLaunch() {
        hiddenForLaunch = true;
        progress = 0f;
        visible = false;
    }

    void returned() {
        hiddenForLaunch = false;
        progress = 0f;
    }

    void advance(float dt, boolean seated) {
        visible = seated && !hiddenForLaunch;
        progress = visible ? Math.min(1f, progress + dt / FADE_IN_SECONDS)
                : Math.max(0f, progress - dt / FADE_OUT_SECONDS);
    }

    float opacity() { return progress * progress * (3f - 2f * progress); }
    boolean isAnimating() { return visible ? progress < 1f : progress > 0f; }
}
