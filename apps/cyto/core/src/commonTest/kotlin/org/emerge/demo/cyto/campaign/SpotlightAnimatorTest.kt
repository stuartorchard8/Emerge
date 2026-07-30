package org.emerge.demo.cyto.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The coach spotlight's timing. Nothing here draws — the point is the *sequencing*, which is what a
 * screenshot can't show: one box at a time, the old one fully gone before the new one starts, and an opacity
 * that never falls to nothing while a target is being pointed at.
 */
class SpotlightAnimatorTest {

    private val FADE = CampaignDirector.FADE_SECONDS

    @Test fun aNewSpotlightFadesInRatherThanAppearing() {
        val a = SpotlightAnimator()
        assertNotNull(a.advance(Spot("+ NEW GENE", 1), 0f))
        assertEquals(0f, a.fade(0f), "invisible at the instant it is asked for")
        assertTrue(a.fade(FADE / 2f) in 0.4f..0.6f, "halfway there halfway through")
        assertEquals(1f, a.fade(FADE), "fully arrived after the fade")
    }

    /**
     * The hand-off. A cross-fade would put two boxes on screen in different corners, reading as two
     * instructions at once; the old one must be *gone* before the new one starts.
     */
    @Test fun aChangedTargetLeavesBeforeTheNextArrives() {
        val a = SpotlightAnimator()
        val old = Spot("+ NEW GENE", 1)
        val new = Spot("Next >", 1)
        a.advance(old, 0f)
        assertEquals(1f, a.fade(FADE))

        // Task done: the coach now wants the Next button, but the old box is still the one on screen.
        assertEquals("+ NEW GENE", assertNotNull(a.advance(new, FADE)).target)
        assertTrue(a.fade(FADE + FADE / 2f) < 0.6f, "on its way out")
        assertEquals(0f, a.fade(FADE * 2f), "gone")

        val now = FADE * 2f
        assertEquals("Next >", assertNotNull(a.advance(new, now)).target, "only now does it swap")
        assertEquals(0f, a.fade(now), "and it starts from invisible, like any arrival")
        assertEquals(1f, a.fade(now + FADE))
    }

    /** Nothing wanted at all (an extinction offer takes over the coach) still leaves, then stays gone. */
    @Test fun wantingNothingFadesOutAndStopsDrawing() {
        val a = SpotlightAnimator()
        a.advance(Spot("CHEMISTRY", 1), 0f)
        assertNotNull(a.advance(null, FADE))
        assertEquals(0f, a.fade(FADE * 2f))
        assertNull(a.advance(null, FADE * 2f), "nothing left to draw")
    }

    /**
     * The player undoes what they just did, so the target the coach was leaving is wanted again. Resume from
     * the opacity it had reached — snapping back to invisible would make an undo look like a new instruction.
     */
    @Test fun aTargetWantedAgainMidFadeResumesInsteadOfSnapping() {
        val a = SpotlightAnimator()
        val target = Spot("+ GROW", 1)
        a.advance(target, 0f)
        a.advance(Spot("Next >", 1), FADE)          // starts leaving at full opacity
        val half = FADE + FADE / 2f
        val mid = a.fade(half)
        assertTrue(mid in 0.4f..0.6f, "halfway out")

        assertEquals("+ GROW", assertNotNull(a.advance(target, half)).target, "never actually left")
        assertTrue(a.fade(half) in 0.4f..0.6f, "picks up where it was, not from zero")
        assertEquals(1f, a.fade(half + FADE / 2f), "and finishes arriving from there")
    }

    /**
     * The pulse. A visible swing so the eye is drawn, with a floor so the box never disappears between
     * breaths — and it must not multiply a faded-out box back into visibility.
     */
    @Test fun theSpotlightBreathesWithoutEverGoingOut() {
        val a = SpotlightAnimator()
        a.advance(Spot("PAUSE", 1), 0f)
        val settled = FADE * 2f
        val samples = (0..40).map { a.alpha(settled + it * CampaignDirector.PULSE_SECONDS / 40f) }
        assertTrue(samples.min() >= CampaignDirector.PULSE_FLOOR - 0.01f, "never dimmer than the floor")
        assertTrue(samples.max() > 0.98f, "reaches full brightness")
        assertTrue(samples.max() - samples.min() > 0.2f, "and the swing is actually visible")
        // One second later, the same phase: what makes a scripted screenshot comparable frame to frame.
        assertEquals(a.alpha(settled), a.alpha(settled + CampaignDirector.PULSE_SECONDS), 0.001f)
    }

    @Test fun theBreathCannotReviveAFadedOutBox() {
        val a = SpotlightAnimator()
        a.advance(Spot("PAUSE", 1), 0f)
        a.advance(null, FADE)
        assertEquals(0f, a.alpha(FADE * 2f), "pulse times zero is still zero")
    }
}
