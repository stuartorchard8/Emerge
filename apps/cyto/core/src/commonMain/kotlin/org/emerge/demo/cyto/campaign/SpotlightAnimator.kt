package org.emerge.demo.cyto.campaign

import kotlin.math.PI
import kotlin.math.cos

/**
 * The widget the coach is ringing: either a label plus which match of it, or — when [byKey] — a
 * [org.emerge.demo.cyto.ui.GeneKeys] identity, which no more needs an occurrence than a name needs an index.
 */
class Spot(val target: String, val occurrence: Int, val byKey: Boolean = false)

/**
 * When the campaign coach's spotlight is on what, and how brightly — the whole of its animation, kept apart
 * from the drawing so it can be exercised without a GL context.
 *
 * Driven by a wall clock the host feeds in (`Ui.clockSeconds`) rather than a frame count, because a
 * one-second pulse means one second at any draw rate, and cyto's draw rate moves with the sim.
 */
class SpotlightAnimator {
    private var shown: Spot? = null
    private var since: Float = 0f
    private var leaving: Boolean = false

    /**
     * Move towards [want] and return what to draw this frame, or null while nothing is showing.
     *
     * One spotlight at a time, and a change is a **hand-off, not a cut**: the current box fades out, and only
     * once it is gone does the new one fade in. Two boxes cross-fading in different corners of the screen
     * would read as two instructions at once, which is the opposite of the point.
     *
     * Reversing mid-fade — the player undoes what they just did, so the old target is wanted again — resumes
     * from the opacity already reached instead of snapping; that is what rebasing [since] buys.
     */
    fun advance(want: Spot?, now: Float): Spot? {
        val cur = shown
        if (cur == null) {
            if (want != null) { shown = want; since = now; leaving = false }
            return shown
        }
        val same = want != null && want.target == cur.target && want.occurrence == cur.occurrence &&
            want.byKey == cur.byKey
        when {
            leaving && same -> { since = now - fade(now) * CampaignDirector.FADE_SECONDS; leaving = false }
            !leaving && !same -> { since = now - (1f - fade(now)) * CampaignDirector.FADE_SECONDS; leaving = true }
            leaving && now - since >= CampaignDirector.FADE_SECONDS -> {
                shown = want
                if (want != null) { since = now; leaving = false }
            }
        }
        return shown
    }

    /** The fade alone: 0 invisible, 1 fully arrived. */
    fun fade(now: Float): Float {
        val t = ((now - since) / CampaignDirector.FADE_SECONDS).coerceIn(0f, 1f)
        return if (leaving) 1f - t else t
    }

    /**
     * What to draw at: the fade, times a one-second **breath**.
     *
     * The pulse is what makes a static box on a static panel read as "here, now". It is a nudge and not an
     * alarm, so it swings over the top third of the range only, and never reaches nothing — a marker that
     * blinks out is a marker the eye loses.
     */
    fun alpha(now: Float): Float {
        val breath = 0.5f - 0.5f * cos(now * (2f * PI.toFloat() / CampaignDirector.PULSE_SECONDS))
        return fade(now) * (CampaignDirector.PULSE_FLOOR + (1f - CampaignDirector.PULSE_FLOOR) * breath)
    }
}
