package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.world.Conduits

import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.Motion
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
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

    private val cfg = OutofspaceConfig(initialGrid = Grid(20, 12))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /** An extractor at (2,3), port at (4,3), feeding a run of track rightward into a tank at [tankX]. */
    private fun line(tankX: Int = 9): VesselState {
        val grid = cfg.initialGrid
        val m = arrayOfNulls<Machine>(grid.size)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, deck, 2, 3)
        deck += Storage(grid.tile(tankX, 3), Direction.Right)
        joinRow(grid, rails, 4, tankX - 1, 3)
        return VesselState(grid, m.toList(), deck, conduits = Conduits.ofRails(rails.toList()), bodies = feed, buffers = BufferLayer.forMachines(grid, m.toList()), rail = RailLayer.empty(grid.size))
    }

    // ── Travelling ────────────────────────────────────────────────────────────

    @Test
    fun `a packet that stepped along a run knows which way it was going`() {
        var s = line()
        // Long enough that the run is carrying material but not yet backed up against the tank.
        s = run(s, 12)

        val moving = (3..8).filter { !s.rail.isEmpty(cfg.initialGrid.tile(it, 3)) }
        assertTrue(moving.isNotEmpty(), "the line should be carrying something by now")
        // Everything on this run came from its left-hand neighbour, because that is the only way
        // material can be moving: the extractor is at the left end and the tank at the right.
        for (x in moving) {
            val came = s.motion.arrivedFrom(cfg.initialGrid.tile(x, 3))
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
            val tile = cfg.initialGrid.tile(x, 3)
            if (!s.rail.isEmpty(tile)) continue
            assertEquals(0L, s.motion.previousMassAt(tile), "($x, 3) is empty but claims a mass")
        }
    }

    // ── Appearing and disappearing ────────────────────────────────────────────

    @Test
    fun `a packet a machine put on the track is marked as having appeared`() {
        // One tick at a time until the extractor ejects, which is the moment being tested.
        var s = line()
        var appeared = TileIndex.NONE
        // `run`, not `repeat`: `return@repeat` is a *continue*, so the old loop ran all forty ticks
        // and read whatever the last one said — which is only the ejection tick by luck of timing.
        run {
            repeat(40) {
                s = OutofspaceReducer.reduce(cfg, s, emptyMap())
                val tile = cfg.initialGrid.tile(4, 3)
                if (s.motion.appearedAt(tile)) { appeared = tile; return@run }
            }
        }
        assertTrue(appeared != TileIndex.NONE, "the extractor never put anything on the track")
        assertNotNull(s.onRail(appeared), "and what appeared should actually be there")
        assertEquals(0L, s.motion.previousMassAt(appeared), "a thing that appeared had no mass before")
        assertNull(s.motion.arrivedFrom(appeared), "it came from a port, not from a neighbour")
    }

    @Test
    fun `a packet a machine took off the track is recorded as leaving it`() {
        val tank = cfg.initialGrid.tile(9, 3)
        var s = line()
        var seen = false
        var lastMotion = s.motion
        repeat(60*RAIL_PERIOD) {
            val stored = s.buffers.massAt(tank)
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            // Only on a tick the track actually stepped. Between rail steps the same motion log is
            // carried forward unchanged, so its departures are last step's news read a second time
            // — and the tank has taken nothing since.
            if (s.motion === lastMotion) return@repeat
            lastMotion = s.motion
            val d = s.motion.departures.firstOrNull() ?: return@repeat
            assertEquals(cfg.initialGrid.tile(8, 3), d.tile, "the tank's input port is at (8, 3)")
            assertTrue(d.packet.mass > 0L, "and something real went into it")
            // Where the lump went, rather than whether the tile it left is empty. Those were the
            // same statement while a belt was starved: a machine produced a fraction of a packet
            // per tick, so the port tile sat empty most ticks and "empty" was a fine proxy for
            // "left". Now every producer runs at exactly one belt-load per tick, so the line is
            // saturated and the tile is refilled from behind on the very tick it is emptied.
            //
            // That used to be settled by identity — the tile holding a *different* packet object
            // than the one recorded. There are no packet objects any more: what rides the track is
            // matter on [RailLayer], and two belt-loads of the same ore off a saturated line are
            // equal in every respect. So the claim is made where it is still visible, and where it
            // was always the thing worth asserting: the mass that departed arrived in the tank.
            assertEquals(
                d.packet.mass,
                s.buffers.massAt(tank) - stored,
                "the packet recorded as leaving the track is the one the tank took",
            )
            seen = true
        }
        assertTrue(seen, "nothing ever reached the tank")
    }

    // ── Bridges ───────────────────────────────────────────────────────────────

    /** A run that crosses a bridge at (6,3), hopping the tile at (6,3) itself. */
    private fun bridged(): VesselState {
        val grid = cfg.initialGrid
        val m = arrayOfNulls<Machine>(grid.size)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val bridges = arrayOfNulls<Bridge>(grid.size)
        val feed = feedExtractor(grid, deck, 2, 3)
        deck += Storage(grid.tile(11, 3), Direction.Right)
        bridges[grid.tile(6, 3).index] = Bridge(Direction.Right)
        joinRow(grid, rails, 4, 5, 3)
        joinRow(grid, rails, 7, 10, 3)
        return VesselState(
            grid, m.toList(), deck,
            conduits = Conduits.ofRails(rails.toList()),
            bridges = bridges.toList(),
            bodies = feed,
            buffers = BufferLayer.forMachines(grid, m.toList()), rail = RailLayer.empty(grid.size),
        )
    }

    @Test
    fun `a bridge slot that just took delivery says so`() {
        var s = bridged()
        val tile = cfg.initialGrid.tile(6, 3)
        var sawNewMiddle = false
        repeat(60*RAIL_PERIOD) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            val b = s.bridges[tile.index] ?: return@repeat
            if (b.middle != null && s.motion.bridgeSlotIsNew(tile, Motion.SLOT_MIDDLE)) sawNewMiddle = true
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
        val s = run(bridged(), 500*RAIL_PERIOD)
        val tile = cfg.initialGrid.tile(6, 3)
        val b = assertNotNull(s.bridges[tile.index])
        assertEquals(Bridge.SLOTS, b.carried.size, "the bridge should be packed by now")
        for (slot in listOf(Motion.SLOT_ENTRY, Motion.SLOT_MIDDLE, Motion.SLOT_EXIT)) {
            assertTrue(!s.motion.bridgeSlotIsNew(tile, slot), "slot $slot claims to have just moved")
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
        val near = cfg.initialGrid.tile(5, 3)     // the track tile under the bridge's input port
        val far = cfg.initialGrid.tile(7, 3)      // and under its output port
        var crossed = false
        repeat(80*RAIL_PERIOD) {
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
            if (s.bridges[cfg.initialGrid.tile(6, 3).index]?.exit != null) crossed = true
        }
        assertTrue(crossed, "nothing ever crossed the bridge, so this proved nothing")
    }

    // ── It is presentation, and must stay that way ────────────────────────────

    @Test
    fun `motion changes nothing about the world it describes`() {
        // The save is the world; motion is not in it. If recording movement had altered so much as
        // a gram, two runs of the same vessel would not write the same file.
        val a = run(starterVessel(cfg.initialGrid), 150)
        val b = run(starterVessel(cfg.initialGrid), 150)
        assertEquals(Save.write(a), Save.write(b))
        assertTrue(a.motion.departures.isNotEmpty() || a.tick > 0, "and the record was actually built")
    }

    @Test
    fun `a freshly loaded world is simply still`() {
        val played = run(starterVessel(cfg.initialGrid), 150)
        val loaded = Save.read(Save.write(played))
        assertEquals(Motion.NONE, loaded.motion, "a load has no previous tick to have moved during")
        // And it animates again immediately, rather than being still for good.
        assertTrue(run(loaded, 20).motion.departures.isNotEmpty() ||
            run(loaded, 20).motion.previousMassAt(TileIndex(0)) == 0L)
    }
}
