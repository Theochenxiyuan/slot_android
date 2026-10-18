// SPDX-License-Identifier: GPL-3.0-or-later
package fyi.slot.android;

/** Slot's three-stage mechanical travel, separate from direct touch manipulation. */
final class CartridgeMotion {
    static final float TRAVEL_SECONDS = .45f, SEAT_HOLD_SECONDS = .28f;
    static final float CATCH_IN = .42f, CATCH_OUT = .62f, CREEP = .03f;
    static final float SEATED_WIDTH = 240f, SIDE_PART = 130f;

    private boolean running, inserting;
    private float phase;

    void start(float progress, Geometry geometry, boolean inserting) {
        this.inserting = inserting;
        phase = geometry.phaseAtProgress(progress);
        running = true;
    }

    float advance(float dt, Geometry geometry) {
        phase = clamp(phase + (inserting ? dt : -dt) / TRAVEL_SECONDS);
        if (inserting ? phase >= 1 : phase <= 0) running = false;
        return geometry.progressAtPhase(phase);
    }

    float secondsToSeat() { return (1 - phase) * TRAVEL_SECONDS; }
    boolean isRunning() { return running; }
    void stop() { running = false; }

    /** Progress measures top-edge displacement, so a held drag remains exactly under the finger. */
    static final class Geometry {
        final float restTop, restWidth, restHeight, seatedTop, seatedHeight;
        final float topDistance, catchTravel, catchProgress;

        Geometry(float restTop, float width, float height, float lipTop) {
            this.restTop = restTop; restWidth = width; restHeight = height;
            seatedTop = lipTop + 6f;
            seatedHeight = height * SEATED_WIDTH / width;
            topDistance = seatedTop - restTop;
            float foot = restTop + height;
            catchTravel = (lipTop - foot) / (seatedTop + seatedHeight - foot);
            catchProgress = (lipTop - seatedHeight - restTop) / topDistance;
        }

        float widthAtProgress(float progress) {
            return restWidth + (SEATED_WIDTH - restWidth) * clamp(progress / catchProgress);
        }

        float heightAtProgress(float progress) { return restHeight * widthAtProgress(progress) / restWidth; }
        float topAtProgress(float progress) { return restTop + topDistance * clamp(progress); }

        float progressAtPhase(float phase) {
            if (phase <= 0) return 0;
            if (phase >= 1) return 1;
            float travel = travel(clamp(phase), catchTravel);
            return clamp(progressAtTravel(travel));
        }

        float phaseAtProgress(float progress) {
            float p = clamp(progress);
            if (p == 0 || p == 1) return p;
            float travel = p < catchProgress ? catchTravel * p / catchProgress
                    : catchTravel + (1 - catchTravel) * (p - catchProgress) / (1 - catchProgress);
            float low = 0, high = 1;
            for (int i = 0; i < 24; i++) {
                float mid = (low + high) / 2;
                if (travel(mid, catchTravel) < travel) low = mid; else high = mid;
            }
            return (low + high) / 2;
        }

        private float progressAtTravel(float travel) {
            return travel < catchTravel ? catchProgress * travel / catchTravel
                    : catchProgress + (1 - catchProgress) * (travel - catchTravel) / (1 - catchTravel);
        }
    }

    static float travel(float phase, float catchTravel) {
        if (phase < CATCH_IN) return catchTravel * ease(phase / CATCH_IN);
        if (phase < CATCH_OUT) return catchTravel + CREEP * (phase - CATCH_IN) / (CATCH_OUT - CATCH_IN);
        float caught = catchTravel + CREEP;
        return caught + (1 - caught) * ease((phase - CATCH_OUT) / (1 - CATCH_OUT));
    }

    private static float ease(float u) { return u * u * u * (u * (u * 6 - 15) + 10); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
