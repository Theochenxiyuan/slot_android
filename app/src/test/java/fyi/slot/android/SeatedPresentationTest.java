package fyi.slot.android;

import static org.junit.Assert.*;
import org.junit.Test;

public final class SeatedPresentationTest {
    @Test public void manualSeatFadesInAndBecomesIdle() {
        SeatedPresentation presentation = new SeatedPresentation();
        assertEquals(0f, presentation.opacity(), 0f);
        presentation.advance(.10f, true);
        assertTrue(presentation.opacity() > 0f && presentation.opacity() < 1f);
        assertTrue(presentation.isAnimating());
        presentation.advance(.20f, true);
        assertEquals(1f, presentation.opacity(), 0f);
        assertFalse(presentation.isAnimating());
    }

    @Test public void automaticLaunchNeverFlashesEvenWhenDispatchIsDelayed() {
        SeatedPresentation presentation = new SeatedPresentation();
        presentation.hideForAutomaticLaunch();
        for (int i = 0; i < 100; i++) presentation.advance(.032f, true);
        assertEquals(0f, presentation.opacity(), 0f);
        assertFalse(presentation.isAnimating());
        presentation.returned();
        presentation.advance(.10f, true);
        assertTrue(presentation.opacity() > 0f && presentation.opacity() < 1f);
        presentation.advance(.20f, true);
        assertEquals(1f, presentation.opacity(), 0f);
    }

    @Test public void ejectFadesOutWithoutControllingTravel() {
        SeatedPresentation presentation = new SeatedPresentation();
        presentation.reset(true);
        presentation.advance(.05f, false);
        assertTrue(presentation.opacity() > 0f && presentation.opacity() < 1f);
        presentation.advance(.10f, false);
        assertEquals(0f, presentation.opacity(), 0f);
        assertFalse(presentation.isAnimating());
    }

    @Test public void newSeatAndRestorationClearAutomaticLaunchSuppression() {
        SeatedPresentation presentation = new SeatedPresentation();
        presentation.hideForAutomaticLaunch();
        presentation.reset(true);
        assertEquals(1f, presentation.opacity(), 0f);
        presentation.reset(false);
        presentation.advance(.10f, true);
        assertTrue(presentation.isAnimating());
    }
}
