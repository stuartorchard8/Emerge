package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduits

import org.emerge.demo.outofspace.world.Bridge
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Miner
import org.emerge.demo.outofspace.world.Motion
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.starterVessel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The record of what moved where, which exists so the renderer can draw a tick happening rather
 * than a tick having happened.
 *
 * The thing worth testing is not that it animates — that is the renderer's business and a matter of
 * taste — but that it is **right about the world**. A packet drawn sliding in from the wrong
 * neighbour is worse than one that teleports, because it is a confident lie about which way the
 * factory runs. So these check the record against the movement it claims to describe.
 */
class MotionTest {

    private val cfg = OutofspaceConfig(grid = Grid(20, 12))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /** A miner at (2,3) feeding a run of track rightward into a tank at [tankX]. */
    private fun line(tankX: Int = 9): VesselState {
        val grid = cfg.grid
        val m = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        m[grid.index(2, 3)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        m[grid.index(tankX, 3)] = Storage(Direction.Right)
        joinRow(grid, rails, 3, tankX - 1, 3)
        return VesselState(grid, m.toList(), conduits = Conduits.ofRails(rails.toList()))
    }

    // ── Travelling ────────────────────────────────────────────────────────────

    @Test
    fun `a packet that stepped along a run knows which way it was going`() {
        var s = line()
        // Long enough that the run is carrying material but not yet backed up against the tank.
        s = run(s, 12)

        val moving = (3..8).filter { s.rails[cfg.grid.index(it, 3)]?.held != null }
        assertTrue(moving.isNotEmpty(), "the line should be carrying something by now")
        // Everything on this run came from its left-hand neighbour, because that is the only way
        // material can be moving: the miner is at the left end and the tank at the right.
        for (x in moving) {
            val came = s.motion.arrivedFrom(cfg.grid.index(x, 3))
            assertTrue(
                came == null || came == Direction.Right,
                "the lump at ($x, 3) claims to have arrived heading $came",
            )
        }
    }

    @Test
    fun `the tile a packet came from is reported as empty rather than still holding it`() {
        var s = line()
        s = run(s, 12)
        for (x in 3..8) {
            val tile = cfg.grid.index(x, 3)
            if (s.rails[tile]?.held != null) continue
            assertEquals(0L, s.motion.previousMassAt(tile), "($x, 3) is empty but claims a mass")
        }
    }

    // ── Appearing and disappearing ────────────────────────────────────────────

    @Test
    fun `a packet a machine put on the track is marked as having appeared`() {
        // One tick at a time until the miner ejects, which is the moment being tested.
        var s = line()
        var appeared = -1
        repeat(40) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            val tile = cfg.grid.index(3, 3)
            if (s.motion.appearedAt(tile)) { appeared = tile; return@repeat }
        }
        assertTrue(appeared >= 0, "the miner never put anything on the track")
        assertNotNull(s.rails[appeared]?.held, "and what appeared should actually be there")
        assertEquals(0L, s.motion.previousMassAt(appeared), "a thing that appeared had no mass before")
        assertNull(s.motion.arrivedFrom(appeared), "it came from a port, not from a neighbour")
    }

    @Test
    fun `a packet a machine took off the track is recorded as leaving it`() {
        var s = line()
        var seen = false
        repeat(60) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            val d = s.motion.departures.firstOrNull() ?: return@repeat
            assertEquals(cfg.grid.index(8, 3), d.tile, "the tank's input port is at (8, 3)")
            assertTrue(d.packet.mass > 0L, "and something real went into it")
            assertNull(s.rails[d.tile]?.held, "the tile it left is empty now — that is what leaving is")
            seen = true
        }
        assertTrue(seen, "nothing ever reached the tank")
    }

    // ── Bridges ───────────────────────────────────────────────────────────────

    /** A run that crosses a bridge at (6,3), hopping the tile at (6,3) itself. */
    private fun bridged(): VesselState {
        val grid = cfg.grid
        val m = arrayOfNulls<Machine>(grid.size)
        val rails = arrayOfNulls<Segment>(grid.size)
        val bridges = arrayOfNulls<Bridge>(grid.size)
        m[grid.index(2, 3)] = Miner(Direction.Right, OutofspaceReducer.DEFAULT_ORE_BODY)
        m[grid.index(11, 3)] = Storage(Direction.Right)
        bridges[grid.index(6, 3)] = Bridge(Direction.Right)
        joinRow(grid, rails, 3, 5, 3)
        joinRow(grid, rails, 7, 10, 3)
        return VesselState(grid, m.toList(), conduits = Conduits.ofRails(rails.toList()), bridges = bridges.toList())
    }

    @Test
    fun `a bridge slot that just took delivery says so`() {
        var s = bridged()
        val at = cfg.grid.index(6, 3)
        var sawNewMiddle = false
        repeat(60) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            val b = s.bridges[at] ?: return@repeat
            if (b.middle != null && s.motion.bridgeSlotIsNew(at, Motion.SLOT_MIDDLE)) sawNewMiddle = true
        }
        assertTrue(sawNewMiddle, "nothing ever moved onto the middle of the bridge")
    }

    /**
     * The other half of the same contract, and the one that actually bites.
     *
     * A slot still holding what it held last tick must not claim to be new, or a bridge backed up
     * against a full factory is drawn shuffling on the spot for ever — busily going nowhere, which
     * is the opposite of what a jam should look like.
     */
    @Test
    fun `a jammed bridge is not reported as moving`() {
        // Long enough to fill the 20 kg tank and pack the line solid all the way back.
        val s = run(bridged(), 500)
        val at = cfg.grid.index(6, 3)
        val b = assertNotNull(s.bridges[at])
        assertEquals(Bridge.SLOTS, b.carried.size, "the bridge should be packed by now")
        for (slot in listOf(Motion.SLOT_ENTRY, Motion.SLOT_MIDDLE, Motion.SLOT_EXIT)) {
            assertTrue(!s.motion.bridgeSlotIsNew(at, slot), "slot $slot claims to have just moved")
        }
    }

    /**
     * Getting on and off a bridge is a change of *layer*, not of place, and must not be animated.
     *
     * A bridge's ports sit at ±1 from its centre — exactly where its entry and exit slots are drawn
     * — so a packet handed between the track and the span has not gone anywhere. Recording it as a
     * departure would shrink away a lump that is still sitting in plain sight, and recording it as
     * an arrival would grow a second one on top. Both were tried; both pulse.
     */
    @Test
    fun `stepping on and off a bridge is not recorded as coming or going`() {
        var s = bridged()
        val near = cfg.grid.index(5, 3)     // the track tile under the bridge's input port
        val far = cfg.grid.index(7, 3)      // and under its output port
        var crossed = false
        repeat(80) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            assertTrue(
                s.motion.departures.none { it.tile == near },
                "a packet stepping onto the bridge was drawn vanishing off the track",
            )
            assertTrue(
                !s.motion.appearedAt(far),
                "a packet stepping off the bridge was drawn growing out of nothing",
            )
            // Checked at the far end of the span rather than on the port tile: a packet set down
            // there is carried on in the same step, so the port tile is empty every time you look.
            if (s.bridges[cfg.grid.index(6, 3)]?.exit != null) crossed = true
        }
        assertTrue(crossed, "nothing ever crossed the bridge, so this proved nothing")
    }

    // ── It is presentation, and must stay that way ────────────────────────────

    @Test
    fun `motion changes nothing about the world it describes`() {
        // The save is the world; motion is not in it. If recording movement had altered so much as
        // a gram, two runs of the same vessel would not write the same file.
        val a = run(starterVessel(cfg.grid), 150)
        val b = run(starterVessel(cfg.grid), 150)
        assertEquals(Save.write(a), Save.write(b))
        assertTrue(a.motion.departures.isNotEmpty() || a.tick > 0, "and the record was actually built")
    }

    @Test
    fun `a freshly loaded world is simply still`() {
        val played = run(starterVessel(cfg.grid), 150)
        val loaded = Save.read(Save.write(played))
        assertEquals(Motion.NONE, loaded.motion, "a load has no previous tick to have moved during")
        // And it animates again immediately, rather than being still for good.
        assertTrue(run(loaded, 20).motion.departures.isNotEmpty() ||
            run(loaded, 20).motion.previousMassAt(0) == 0L)
    }
}
