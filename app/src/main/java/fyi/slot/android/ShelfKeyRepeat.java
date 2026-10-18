package fyi.slot.android;

/** One monotonic clock for both directions; late frames produce at most one step. */
final class ShelfKeyRepeat {
    static final long DELAY_MILLIS = 400;
    private static final int[] INTERVALS = {110, 85, 65, 50};
    private boolean leftHeld, rightHeld;
    private long nextAt;
    private int repeats;

    int direction() { return leftHeld == rightHeld ? 0 : leftHeld ? -1 : 1; }
    boolean isHeld() { return leftHeld || rightHeld; }
    long nextFrameDelay(long now) { return direction() == 0 ? Long.MAX_VALUE : Math.max(1, nextAt - now); }

    int press(int direction, long now) {
        if (direction < 0) {
            if (leftHeld) return 0;
            leftHeld = true;
        } else {
            if (rightHeld) return 0;
            rightHeld = true;
        }
        restart(now);
        return direction();
    }

    void release(int direction, long now) {
        if (direction < 0) {
            if (!leftHeld) return;
            leftHeld = false;
        } else {
            if (!rightHeld) return;
            rightHeld = false;
        }
        restart(now);
    }

    int poll(long now) {
        int direction = direction();
        if (direction == 0 || now < nextAt) return 0;
        repeats++;
        int interval = INTERVALS[Math.min((repeats - 1) / 4, INTERVALS.length - 1)];
        nextAt = now + interval;
        return direction;
    }

    private void restart(long now) {
        repeats = 0;
        nextAt = now + DELAY_MILLIS;
    }

    void clear() { leftHeld = rightHeld = false; nextAt = 0; repeats = 0; }
}
