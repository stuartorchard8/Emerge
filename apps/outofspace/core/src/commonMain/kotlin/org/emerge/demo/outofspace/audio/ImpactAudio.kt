package org.emerge.demo.outofspace.audio

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Impact
import org.emerge.demo.outofspace.world.VesselState
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One bang, as much of it as the sim is willing to say: which clip bank, how loud, how high.
 *
 * The sim decides *that* something was hit and *how hard*; a host decides what that sounds like on
 * the machine it is running on. Nothing below this line knows about OpenAL, and nothing above it
 * knows about impulses.
 */
class ImpactSfxRequest(
    val clipIndex: Int,
    val volume: Float,
    val pitch: Float,
    /** A body against another body rather than against the hull — see [ImpactAudioEngine]. */
    val isRubble: Boolean,
)

/**
 * A host's speakers. Ported from scavengers' `CrashAudioEngine`, which is the same idea against a
 * different sim: two banks of clips, one request at a time, and no state the sim can see.
 *
 * Two banks, because the two sounds are genuinely different events and not the same one at another
 * volume: rock on **hull** rings, and rock on **rock** does not. Scavengers splits its banks on
 * whether the hit destroyed the thing; here nothing is destroyed by a hit, so the split is on what
 * was struck. A host that only has one bank reports zero for the other and gets no silence — see
 * [ImpactAudioSystem], which falls back.
 */
interface ImpactAudioEngine {
    /** Clips for a body striking the vessel. */
    val clangClipCount: Int

    /** Clips for a body striking another body. */
    val rubbleClipCount: Int

    fun play(request: ImpactSfxRequest)

    fun release()
}

/**
 * Turns [VesselState.impacts] into noises.
 *
 * Lives in `core` rather than in a host because none of it is platform-specific: the volume curve,
 * the falloff and the retrigger guard are decisions about *the game*, and a phone should make the
 * same judgements a desktop does. What a host supplies is an [ImpactAudioEngine].
 *
 * ⚠️ **Called once per rendered frame, not once per tick.** [VesselState.impacts] describes the tick
 * that produced the state, so a host running several ticks between frames will drop the quieter of
 * them — which is what a peak meter does anyway, and far better than a host that plays a tick's
 * worth of bangs at once when the display stutters.
 */
class ImpactAudioSystem(
    private val engine: ImpactAudioEngine,
    private val random: Random = Random.Default,
    /**
     * The impulse that plays at full volume, in the same units as `RigidBody.impulseX` — which are
     * `mass × velocity / `[Flight.PER_TILE], so at one tile a tick they read as mass exactly. This
     * is therefore *"two tonnes stopped dead from a tile a tick"*.
     *
     * ⚠️ **Calibrated, then tuned by ear — and only the first half has happened.** Measured off
     * `ImpactAudioTest`'s fixture: the game's own default ore body thrown at a bulkhead spends
     * 8.7e11 at an eighth of a tile a tick and 3.5e12 at a half, so two tonnes puts an ordinary hard
     * collision at the top of that range and leaves a drifting nudge audible above the `0.05` floor.
     * Whether that is how it should *sound* is Stu's ear and not arithmetic, which is why it is a
     * constructor argument rather than a `const`.
     *
     * ⚠️ [Budget.TONNE] is 1e12 and not 1e9 — a unit is a **microgram**. Written as `2_000L * TONNE`
     * on the first pass, this was a thousand times too high and every collision in the game came out
     * at the `0.05` floor: audible, uniform, and wrong in the way that reads as "the volume scaling
     * does not work" rather than as a unit error.
     */
    private val loudImpulse: Long = 2L * Budget.TONNE,
    /** Beyond this many tiles from the camera, nothing is heard. */
    private val maxAudibleTiles: Float = 48f,
    /**
     * The fewest ticks between two bangs from the same body — scavengers' guard, for scavengers'
     * reason: a rock settling into a corner rings its contacts on consecutive ticks, and the ear
     * hears that as a buzz rather than as several landings.
     */
    private val minTicksBetweenImpacts: Long = 3L,
) {
    private val lastTickByBody = HashMap<Int, Long>()

    /**
     * @param camX the camera centre, in **grid tiles** — which is what [Camera.camX] is, and which
     *   is why the impact's world point is turned into the grid below rather than the other way
     *   round. The listener is the view, not the ship: a player looking at the far end of a long
     *   vessel should hear what is happening there.
     */
    fun onFrame(state: VesselState, camX: Float, camY: Float) {
        if (engine.clangClipCount <= 0 && engine.rubbleClipCount <= 0) return
        val tick = state.tick
        for (impact in state.impacts) {
            if (!readyForPlayback(impact.bodyIndex, tick)) continue
            val volume = volumeOf(impact, state, camX, camY)
            if (volume <= INAUDIBLE) continue

            // Falls back to the other bank rather than going silent: a host with one bank loaded is
            // a host that is still better off making a noise.
            val rubble = !impact.againstHull && engine.rubbleClipCount > 0
            val clips = if (rubble) engine.rubbleClipCount else engine.clangClipCount
            if (clips <= 0) continue
            engine.play(
                ImpactSfxRequest(
                    clipIndex = random.nextInt(clips),
                    volume = volume,
                    // A spread of pitch, so that two identical landings are not two identical
                    // sounds. Rubble sits lower and wider than a hull strike, which is duller.
                    pitch =
                        if (rubble) 0.85f + random.nextFloat() * 0.20f
                        else 0.92f + random.nextFloat() * 0.16f,
                    isRubble = rubble,
                ),
            )
            lastTickByBody[impact.bodyIndex] = tick
        }
        // Body indices are positions in a list that shrinks, so an entry for a body that is gone
        // would be an entry against whatever takes its place. Cheap to keep honest, and the map
        // never holds more than the bodies do.
        if (lastTickByBody.size > state.bodies.size) {
            lastTickByBody.keys.retainAll { it < state.bodies.size }
        }
    }

    fun release() {
        lastTickByBody.clear()
        engine.release()
    }

    private fun readyForPlayback(bodyIndex: Int, tick: Long): Boolean {
        val last = lastTickByBody[bodyIndex] ?: return true
        return (tick - last) >= minTicksBetweenImpacts
    }

    private fun volumeOf(impact: Impact, state: VesselState, camX: Float, camY: Float): Float {
        val strength = strengthOf(impact.impulse)
        if (strength <= 0f) return 0f
        // ⚠️ World point into grid tiles, because the camera is a grid tile. A ship lying on its
        // side is the case that tells them apart, and it is the ordinary case in flight.
        val gx = state.pose.toLocalX(impact.x, impact.y).toFloat() / Flight.PER_TILE
        val gy = state.pose.toLocalY(impact.x, impact.y).toFloat() / Flight.PER_TILE
        val dx = gx - camX
        val dy = gy - camY
        val attenuation = attenuationAt(sqrt(dx * dx + dy * dy))
        // Rubble on rubble is a duller, quieter thing than metal being struck.
        val typeGain = if (impact.againstHull) 1f else 0.7f
        return (strength * attenuation * typeGain).coerceIn(0f, 1f)
    }

    /** Scavengers' curve exactly: a floor so that a faint hit is still a hit, then linear. */
    private fun strengthOf(impulse: Long): Float {
        if (loudImpulse <= 0L) return 0f
        val normalized = (impulse.toDouble() / loudImpulse.toDouble()).toFloat().coerceIn(0f, 1f)
        if (normalized <= 0f) return 0f
        return 0.05f + 0.95f * normalized
    }

    /** Cubed, so that the far half of the audible radius is nearly all of the silence. */
    private fun attenuationAt(tiles: Float): Float {
        if (maxAudibleTiles <= 0f) return 0f
        val linear = (1f - tiles / maxAudibleTiles).coerceIn(0f, 1f)
        return linear * linear * linear
    }

    private companion object {
        /** Below this, playing the clip costs a voice and buys nothing. */
        const val INAUDIBLE = 0.005f
    }
}
