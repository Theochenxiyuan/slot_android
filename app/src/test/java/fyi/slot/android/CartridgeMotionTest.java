package fyi.slot.android;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CartridgeMotionTest {
    private static final float EPSILON = .0001f;

    private CartridgeMotion.Geometry geometry(boolean tall, float height) {
        float w = (tall ? 312 : 360) * .92f;
        float h = (tall ? 337 : 212) * .92f;
        float top = height - 61;
        float center = (72 + top - 29) / 2;
        return new CartridgeMotion.Geometry(center - h / 2, w, h, top);
    }

    @Test public void matchesOriginalThreeStageTravelSamples() {
        float caught = .25f;
        assertEquals(0, CartridgeMotion.travel(0, caught), EPSILON);
        assertEquals(.125f, CartridgeMotion.travel(.21f, caught), EPSILON);
        assertEquals(.25f, CartridgeMotion.travel(.42f, caught), EPSILON);
        assertEquals(.265f, CartridgeMotion.travel(.52f, caught), EPSILON);
        assertEquals(.28f, CartridgeMotion.travel(.62f, caught), EPSILON);
        assertEquals(.64f, CartridgeMotion.travel(.81f, caught), EPSILON);
        assertEquals(1, CartridgeMotion.travel(1, caught), EPSILON);
    }

    @Test public void reachesFullWidthReductionAtLipAcrossPlatformsAndLayouts() {
        for (boolean tall : new boolean[]{false, true}) {
            for (float height : new float[]{480, 540, 720}) {
                CartridgeMotion.Geometry g = geometry(tall, height);
                float p = g.progressAtPhase(CartridgeMotion.CATCH_IN);
                assertEquals(240, g.widthAtProgress(p), EPSILON);
                assertEquals(height - 61, g.topAtProgress(p) + g.heightAtProgress(p), EPSILON);
                assertEquals(240, g.widthAtProgress(g.progressAtPhase(.9f)), EPSILON);
                assertEquals(height - 55, g.topAtProgress(1), EPSILON);
                assertEquals(0, g.progressAtPhase(0), 0);
                assertEquals(1, g.progressAtPhase(1), 0);
            }
        }
    }

    @Test public void heldTopEdgeFollowsPhysicalDistanceWithoutTimingCurve() {
        for (boolean tall : new boolean[]{false, true}) {
            CartridgeMotion.Geometry g = geometry(tall, 480);
            for (float dy : new float[]{20, 85, 140, 200}) {
                float p = dy / g.topDistance;
                assertEquals(g.restTop + dy, g.topAtProgress(p), EPSILON);
            }
        }
    }

    @Test public void inverseMappingPreservesAnyReleasePosition() {
        for (boolean tall : new boolean[]{false, true}) {
            CartridgeMotion.Geometry g = geometry(tall, 480);
            for (int i = 0; i <= 100; i++) {
                float p = i / 100f;
                assertEquals(p, g.progressAtPhase(g.phaseAtProgress(p)), EPSILON);
            }
        }
    }

    @Test public void committedSwipeAndDragNeverJumpBackOnHandoff() {
        CartridgeMotion.Geometry g = geometry(false, 480);
        for (float p : new float[]{.18f, .64f, .82f, 1}) {
            CartridgeMotion motion = new CartridgeMotion();
            motion.start(p, g, true);
            assertEquals(p, motion.advance(0, g), EPSILON);
            assertTrue(motion.advance(1f / 60, g) >= p - EPSILON);
        }
    }

    @Test public void forwardAndReverseUseIdenticalPositions() {
        CartridgeMotion.Geometry g = geometry(true, 480);
        CartridgeMotion in = new CartridgeMotion(), out = new CartridgeMotion();
        in.start(0, g, true); out.start(1, g, false);
        float[] positions = new float[91];
        positions[0] = 0;
        for (int i = 1; i <= 90; i++) positions[i] = in.advance(.005f, g);
        for (int i = 1; i <= 90; i++) assertEquals(positions[90 - i], out.advance(.005f, g), EPSILON);
        assertFalse(in.isRunning()); assertFalse(out.isRunning());
    }

    @Test public void fullMotionEndsAt450MillisecondsAtDifferentFrameRates() {
        for (int fps : new int[]{30, 60, 90, 120}) {
            CartridgeMotion.Geometry g = geometry(false, 480);
            CartridgeMotion motion = new CartridgeMotion(); motion.start(0, g, true);
            int steps = 0;
            while (motion.isRunning() && steps < fps) { motion.advance(1f / fps, g); steps++; }
            assertTrue(steps / (float) fps >= .45f - EPSILON);
            assertTrue(steps / (float) fps <= .45f + 1f / fps + EPSILON);
        }
    }

    @Test public void audioLeadUsesRemainingMechanicalTimeIncludingLateRelease() {
        CartridgeMotion.Geometry g = geometry(false, 480);
        CartridgeMotion motion = new CartridgeMotion(); motion.start(0, g, true);
        motion.advance(.353f, g);
        assertEquals(.097f, motion.secondsToSeat(), EPSILON);
        motion.start(1, g, true);
        assertEquals(0, motion.secondsToSeat(), EPSILON);
        motion.start(.8f, g, true);
        assertTrue(motion.secondsToSeat() < .097f);
    }

    @Test public void cancellationStopsAutomaticTravelAndCanStartAgain() {
        CartridgeMotion.Geometry g = geometry(false, 480);
        CartridgeMotion motion = new CartridgeMotion(); motion.start(.18f, g, true);
        float p = motion.advance(.1f, g); motion.stop();
        assertFalse(motion.isRunning());
        motion.start(p, g, false);
        assertEquals(p, motion.advance(0, g), EPSILON);
        assertTrue(motion.advance(.02f, g) < p);
    }

    @Test public void slowCardCatchIsContinuousAndMonotonic() {
        for (boolean tall : new boolean[]{false, true}) {
            CartridgeMotion.Geometry g = geometry(tall, 480);
            float last = 0;
            for (int i = 0; i <= 1000; i++) {
                float p = g.progressAtPhase(i / 1000f);
                assertTrue(p + EPSILON >= last); assertTrue(p <= 1 + EPSILON); last = p;
            }
            assertEquals(g.progressAtPhase(.42f), g.progressAtPhase(.42001f), EPSILON);
            assertEquals(g.progressAtPhase(.62f), g.progressAtPhase(.62001f), EPSILON);
        }
    }
}
