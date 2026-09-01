package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.audio.ImpactAudioEngine
import org.emerge.demo.outofspace.audio.ImpactAudioSystem
import org.emerge.demo.outofspace.audio.ImpactSfxRequest
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A collision is audible and a weight is not.
 *
 * That distinction is the whole of what makes this feature bearable rather than maddening, and it is
 * not a property of the audio code: it is decided in the solver, by which contacts the restitution
 * pass declined to bounce. A version that reported every contact would have sounded correct in the
 * first test below and turned a landed rock into a continuous roar in the second — which is exactly
 * the failure a listening test finds last, because the bang you *wanted* is there under it.
 *
 * Scaling by intensity is checked as a *comparison* rather than against a number. The volume curve
 * is tuned by ear (see [ImpactAudioSystem]), so any constant here would be a constant copied out of
 * the implementation; that a harder hit is louder than a softer one is the claim that survives it
 * being retuned.
 */
class ImpactAudioTest {
    init { RockSpawner.enabled = false }

    /** Every request the game asked for, in order, with nothing that needs a sound device. */
    private class Recorder(
        override val clangClipCount: Int = 4,
        override val rubbleClipCount: Int = 3,
    ) : ImpactAudioEngine {
        val played = ArrayList<ImpactSfxRequest>()
        override fun play(request: ImpactSfxRequest) { played += request }
        override fun release() { played.clear() }
    }

    @Test
    fun `a body thrown at the hull is heard, and harder is louder`() {
        // ⚠️ Both speeds must actually **reach** the wall inside [TICKS], and the first version of
        // this test had a gentle throw that did not: it asserted silence and got it, for the one
        // reason that says nothing about audio at all.
        val gentle = loudestBangFromThrowAt(Flight.PER_TILE / 8L)
        val hard = loudestBangFromThrowAt(Flight.PER_TILE / 2L)

        assertTrue(gentle > 0f, "a rock hit a bulkhead and made no sound at all")
        assertTrue(
            hard > gentle,
            "four times the approach speed was not louder: $gentle gently against $hard hard",
        )
    }

    @Test
    fun `a body lying on the deck is silent`() {
        val controller = OutofspaceController(CFG, vacuumHull().copy(gravity = VesselState.PLATING_ONE_G))
        controller.dropRock(18f, 12f)
        val recorder = Recorder()
        val audio = ImpactAudioSystem(recorder, Random(1), maxAudibleTiles = 1000f)

        // Let it fall and land, listening the whole way — the landing itself is allowed to be loud.
        repeat(TICKS) { controller.stepOnce(); audio.onFrame(controller.state, 18f, 12f) }
        recorder.played.clear()

        // And then another second of it simply lying there, which must be silence.
        repeat(TICKS) { controller.stepOnce(); audio.onFrame(controller.state, 18f, 12f) }

        assertTrue(
            recorder.played.isEmpty(),
            "a rock lying on the floor played ${recorder.played.size} bangs, which is a roar",
        )
    }

    @Test
    fun `distance quiets it`() {
        val near = loudestBangFromThrowAt(Flight.PER_TILE / 4L, camX = WALL_X.toFloat())
        val far = loudestBangFromThrowAt(Flight.PER_TILE / 4L, camX = WALL_X - 30f)

        assertTrue(near > far, "the far bang was not quieter: $near near against $far far")
        assertTrue(far > 0f, "the far bang was inaudible, which makes this test say nothing")
    }

    /** Throw a body at the starboard wall and report the loudest thing that came out of it. */
    private fun loudestBangFromThrowAt(speed: Long, camX: Float = WALL_X.toFloat()): Float {
        val controller = OutofspaceController(CFG, vacuumHull().copy(bodies = listOf(bodyAt(30, 16, speed))))
        val recorder = Recorder()
        // A radius that comfortably contains the box, so the first test is about the impulse alone.
        val audio = ImpactAudioSystem(recorder, Random(1), maxAudibleTiles = 48f)
        repeat(TICKS) {
            controller.stepOnce()
            audio.onFrame(controller.state, camX, 16f)
        }
        return recorder.played.maxOfOrNull { it.volume } ?: 0f
    }

    // ── Fixtures, the same box [RockContactTest] throws things at ──────────────────

    private fun bodyAt(x: Int, y: Int, velocityX: Long): RigidBody {
        val blank = RigidBody.rockBlob(
            radius = Edit.DEFAULT_ROCK_RADIUS,
            positionX = 0L, positionY = 0L,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
        )
        val half = (Edit.DEFAULT_ROCK_RADIUS * 2 + 1) * Flight.PER_TILE / 2L
        return RigidBody.rockBlob(
            radius = Edit.DEFAULT_ROCK_RADIUS,
            positionX = x * Flight.PER_TILE - half,
            positionY = y * Flight.PER_TILE - half,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
            impulseX = scaledRatio(velocityX, Flight.PER_TILE, blank.mass),
        )
    }

    private fun vacuumHull(): VesselState {
        val grid = CFG.initialGrid
        val deck = DeckArray(grid)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in 1..WALL_X) { put(x, 6); put(x, 26) }
        for (y in 6..26) { put(1, y); put(WALL_X, y) }
        val state = VesselState(
            grid = grid, deck = deck, gravity = VesselState.FREEFALL,
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
        return (state.copy(air = Stuff.gas(MassArray(grid.size)))).gridAtWorldOrigin()
    }

    private companion object {
        val CFG = OutofspaceConfig()
        const val WALL_X = 33
        const val TICKS = 40
    }
}
