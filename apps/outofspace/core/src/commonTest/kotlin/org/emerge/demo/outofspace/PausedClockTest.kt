package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a stopped game does — the controller's half of [FrozenTickTest].
 *
 * A paused world used to be genuinely stopped: the clock stood still, so every interpolation in the
 * game froze wherever it had got to, and a packet caught mid-tile stayed mid-tile. It also had no
 * way for an edit to reach the world except by running a whole live tick, so placing a ghost or
 * marking a machine for demolition moved the game on by a tick of physics.
 *
 * Both come from the same place, and so does the fix: keep running the loop, and make the ticks
 * frozen ones.
 */
class PausedClockTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(40, 28))

    private fun controller() = OutofspaceController(cfg, workingVessel(cfg.initialGrid))

    /** One frame at 60 Hz, which is not the tick rate — that mismatch is the whole job of `tick`. */
    private fun frame(c: OutofspaceController, frames: Int = 1) = repeat(frames) { c.tick(1f / 60f) }

    /** As [FrozenTickTest.beside]: a tile the track already reaches, so building there grows nothing. */
    private fun beside(s: VesselState): TileIndex = s.grid.tiles.first { tile ->
        s.railAt(tile) == null && s.deck[tile] == null && Direction.ALL.any { d ->
            val n = s.grid.neighbour(tile, d)
            n != TileIndex.NONE && s.railAt(n) != null
        }
    }

    // ── The clock ─────────────────────────────────────────────────────────────

    /**
     * The one that makes a half-drawn slide finish instead of stranding.
     *
     * `simTime` is what every [org.emerge.demo.outofspace.world.Cadence] is measured against, so
     * this is the whole of "the interpolations run on to their resting values": the clock moves,
     * nothing restamps, and `progress` therefore climbs to 1 and clamps.
     */
    @Test
    fun `the clock keeps running while the game is paused`() {
        val c = controller()
        frame(c, 60)
        c.paused = true

        val stamp = c.state.motion.cadence
        val before = c.simTime
        frame(c, 30)                                  // half a second of real time
        val after = c.simTime

        assertTrue(after > before, "a paused clock stood still and every animation with it")
        assertEquals(stamp, c.state.motion.cadence, "a paused tick restamped the rail")
        assertTrue(
            stamp.progress(after) > stamp.progress(before),
            "the clock moved and the slide did not follow it",
        )
    }

    @Test
    fun `a paused animation settles and then stays settled`() {
        val c = controller()
        frame(c, 60)
        c.paused = true

        // Comfortably longer than the longest span, so whatever was in flight has arrived.
        frame(c, 120)
        val stamp = c.state.motion.cadence
        assertEquals(1f, stamp.progress(c.simTime), "a paused world never finished its slide")

        frame(c, 120)
        assertEquals(1f, stamp.progress(c.simTime), "and it must not go anywhere after that")
    }

    /**
     * ⛔ The clock must never go backwards across the pause, at either end.
     *
     * This is why the clock a paused game advances has to be the *same* one the passes stamp
     * against. A separate view clock would have to be reconciled on resume, `progress` would fall
     * from 1 back to mid-span, and every packet in the game would jump backwards on the frame the
     * player pressed play — which is the exact bug the stamps were introduced to fix.
     */
    @Test
    fun `the clock never runs backwards across a pause`() {
        val c = controller()
        val seen = ArrayList<Double>()
        repeat(40) { frame(c); seen.add(c.simTime) }
        c.paused = true
        repeat(40) { frame(c); seen.add(c.simTime) }
        c.paused = false
        repeat(40) { frame(c); seen.add(c.simTime) }

        for (i in 1 until seen.size) {
            assertTrue(seen[i] >= seen[i - 1], "the clock went backwards at frame $i: ${seen[i - 1]} then ${seen[i]}")
        }
        assertTrue(seen.last() > seen.first(), "this proves nothing unless the clock ran at all")
    }

    // ── And no time passes ────────────────────────────────────────────────────

    @Test
    fun `a paused world does not move`() {
        val c = controller()
        frame(c, 120)
        c.paused = true
        val stopped = c.state

        frame(c, 120)
        assertEquals(stopped.extractedMass, c.state.extractedMass, "the extractor ran while paused")
        assertEquals(stopped.positionX, c.state.positionX, "the ship flew while paused")
        assertEquals(stopped.bodies, c.state.bodies, "a rock drifted while paused")
        assertTrue(c.state.tick > stopped.tick, "but the clock did move")
    }

    /**
     * The bug this started from: placing or marking used to need a live tick, so a click on a
     * stopped world stepped the physics.
     */
    @Test
    fun `an edit lands while paused without stepping the world`() {
        val c = controller()
        frame(c, 120)
        c.paused = true
        val stopped = c.state
        val empty = beside(stopped)

        c.brush = Brush.Run(Conduit.Rail)
        c.place(empty)
        frame(c, 2)

        assertTrue(c.state.railAt(empty) != null, "the track the player laid while paused is not there")
        assertEquals(stopped.extractedMass, c.state.extractedMass, "and the world took a step to do it")
        assertEquals(stopped.positionX, c.state.positionX)
    }

    @Test
    fun `marking for demolition while paused does not step the world`() {
        val c = controller()
        frame(c, 120)
        c.paused = true
        val stopped = c.state
        val track = stopped.grid.tiles.first { stopped.railAt(it) != null }

        c.removeAt(track, DeleteLayer.Rail)
        frame(c, 2)

        assertTrue(c.state.railAt(track)?.deconstructing == true, "the mark did not land")
        assertEquals(stopped.extractedMass, c.state.extractedMass, "and the world took a step to do it")
    }

    // ── The readout ───────────────────────────────────────────────────────────

    /**
     * `state.tick` counts frozen ticks, because everything is stamped against it. What the HUD shows
     * must not, or a stopped game displays time passing.
     */
    @Test
    fun `the tick readout does not climb while paused`() {
        val c = controller()
        frame(c, 60)
        val lived = c.livedTicks
        assertTrue(lived > 0, "this proves nothing unless the world ran")

        c.paused = true
        frame(c, 120)
        assertEquals(lived, c.livedTicks, "the readout counted ticks in which nothing happened")
        assertTrue(c.state.tick > lived, "and the clock underneath it did keep going")

        c.paused = false
        frame(c, 60)
        assertTrue(c.livedTicks > lived, "and it starts counting again on resume")
    }

    // ── Resuming ──────────────────────────────────────────────────────────────

    @Test
    fun `the world runs again after a pause`() {
        val c = controller()
        frame(c, 120)
        c.paused = true
        frame(c, 60)
        val stopped = c.state

        c.paused = false
        frame(c, 120)
        // The hull radiating into space, rather than anything a machine chose to do: it happens on
        // every heat pass, of any vessel, and so it is the least conditional evidence that time is
        // passing again.
        assertTrue(c.state.radiatedEnergy > stopped.radiatedEnergy, "the world did not start again")
        assertTrue(c.livedTicks > 0, "and it is counting lived ticks again")
    }

    /**
     * A paused world settles at the rate it was running at.
     *
     * ⚠️ **The dial applies to a stopped game as well**, which reads like a contradiction and is not:
     * what a pause stops is the passes, and what the dial sets is how fast the clock turns. An
     * animation half-way through when the player hit pause was proceeding at this rate and should go
     * on proceeding at it. Settling at a flat 1× instead — which is what this did first — makes a
     * world paused at 0.25× visibly speed up as it comes to rest, and one paused at 4× drag.
     *
     * The clock advances by `real seconds × speed × ticksPerSecond` exactly: every tick stepped takes
     * one tick's worth out of the accumulator and puts 1 on the counter, so nothing is lost between
     * the two terms of `simTime`.
     */
    @Test
    fun `a paused world settles at the speed it was running at`() {
        fun clockRanBy(speed: Float): Double {
            val c = controller()
            frame(c, 60)
            c.speed = speed
            c.paused = true
            val from = c.simTime
            frame(c, 30)                              // half a second of real time
            return c.simTime - from
        }
        val quarter = clockRanBy(0.25f)
        val normal = clockRanBy(1f)
        val quadruple = clockRanBy(4f)

        // Half a second at 64 ticks a second is 32 ticks of clock, times the dial.
        assertEquals(8.0, quarter, 0.01, "a paused world at quarter speed did not settle at quarter speed")
        assertEquals(32.0, normal, 0.01)
        assertEquals(128.0, quadruple, 0.01, "a paused world at quadruple speed did not settle at quadruple speed")
    }
}
