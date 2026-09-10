package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Auto-unlock: **a tank that empties is not necessarily a tank that is done.**
 *
 * Stu's `dump.txt`, the buffer at (10,6): locked to oxygen, auto-unlock on, and a source eleven
 * tiles away feeding it. It ships its last packet, empties, and unlocks — and unlocking is what
 * breaks it, because an unlocked store with auto-lock on has yet to decide what it holds and so asks
 * for **one packet** while it makes up its mind. The source sends that packet and then waits; the
 * packet lands; the tank re-locks and drains it; and the whole thing starts again. One packet per
 * round trip, for ever, down a line that should be running full.
 *
 * ⛔ **The condition was "I am empty", and empty is momentary for a store that is also a source.**
 * A tank being drained by something downstream passes through empty on its way to full; that is not
 * the same event as a player emptying a tank they have finished with, which is what auto-unlock is
 * for. The two were one test.
 */
class StorageAutoUnlockTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(16, 8))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap<PlayerId, OutofspaceInput>()) }
        return s
    }

    /**
     * A stocked tank at (2,3) feeding a locked one at (11,3), which drains overboard at (13,3).
     *
     * The middle tank is the one under test: it is a **valve**, filled from the left and emptied to
     * the right, so it passes through empty on almost every step.
     */
    private fun line(autoUnlock: Boolean, sourceStock: Long = 1L * Capacity.PACKET_MASS): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += fixtureStorage(grid.tile(2, 3), Direction.Right)
        deck += Storage(
            grid.tile(11, 3), Direction.Right,
            filter = SpeciesFilter(Species.Oxygen, pure = true),
            autoLock = true, autoUnlock = autoUnlock,
        )
        deck += openEjector(grid.tile(13, 3))
        // ⛔ **Two runs, not one, and (11,3) is deliberately bare.** Laid as a single row the tank's
        // own output loops back round to its own input — it is a store with room, so the graph sends
        // its ejected packet straight back at it — and every question this file asks answers "yes,
        // something is coming", truthfully and uselessly. Feeding a tank from its own outlet is not
        // the arrangement under test.
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, 10, 3)     // the source, into the tank's door
        joinRow(grid, rails, 12, 13, 3)    // the tank's outlet, overboard
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            .stocked(
                grid.tile(2, 3),
                Mixture.of(Species.Oxygen to sourceStock, energy = 0).atAmbient().takeIf { sourceStock > 0L },
            )
            // ⚠️ **Exactly one packet**, so the tank is empty the step after it ships and the gap is
            // the corridor's length rather than an accident of rates.
            .stocked(
                grid.tile(11, 3),
                Mixture.of(Species.Oxygen to Capacity.PACKET_MASS, energy = 0).atAmbient(),
            )
    }

    private fun sink(s: VesselState): Storage = s.deck[s.grid.tile(11, 3)] as Storage

    private fun held(s: VesselState): Long =
        s.inStore(s.grid.tile(11, 3), BufferRole.Inside)?.total ?: 0L

    /** Whether anything is standing on the inbound corridor — the seven tiles feeding the door. */
    private fun inbound(s: VesselState): Boolean =
        (3..10).any { x -> s.rail.massAt(s.grid.tile(x, 3)) > 0L }

    /**
     * ⭐ **A tank does not let go of its lock while the next packet is on the rails.**
     *
     * ⛔ **The old rule read `rest == null` and nothing else.** A store that is also a source passes
     * *through* empty on its way back to full — it is a valve, filled from one side and drained from
     * the other — and unlocking in that gap throws away the only fact keeping the right material
     * coming. Stu's `dump.txt`, the buffer at (10,6): locked to oxygen, drained overboard, unlocked
     * the instant it hit nought, and three thousand ticks later still empty, because an undecided
     * tank asks for one packet while it makes up its mind and the locked silo at (19,6) asking for
     * two tonnes won every race for the same oxygen.
     *
     * ⚠️ **Asserted at every tick the gap is open**, not once at the end: the tank re-locks the
     * moment the packet lands, so a check afterwards sees an oxygen tank and calls it well. The
     * counter is what stops the test passing vacuously on a fixture where the gap never happens.
     */
    @Test
    fun `a tank that runs dry keeps its lock while a packet is on its way`() {
        var s = line(autoUnlock = true, sourceStock = 4L * Capacity.PACKET_MASS)
        var sawTheGap = false
        repeat(60 * RAIL_PERIOD) {
            s = run(s, 1)
            if (held(s) > 0L || !inbound(s)) return@repeat
            sawTheGap = true
            assertEquals(
                Species.Oxygen,
                sink(s).filter?.species,
                "the tank unlocked with oxygen already on its way to it",
            )
        }
        assertTrue(sawTheGap, "the tank never stood empty with a packet on its way — nothing was tested")
    }

    /**
     * ⭐ **The supplier has not had its turn yet, and the tank must still wait for it.**
     *
     * ⛔ **The case in-transit alone cannot see, and the one on Stu's ship.** [pushOut] walks output
     * ports in ascending tile order, so a tank whose outlet sits at a *lower* tile than its
     * supplier's drains and asks the question first — and nothing is standing on the corridor,
     * because nobody has been asked to send anything yet. `dump.txt`, the buffer at (10,6): outlet
     * at tile 189, supplier's outlet at 198, and it unlocked every time into a corridor whose source
     * was holding 1.2 tonnes of exactly what it wanted.
     *
     * Here the tank's outlet is **(9,3) = 57** and the source's is **(12,3) = 60**, so the tank goes
     * first and the corridor is empty at the moment it asks.
     */
    @Test
    fun `a tank keeps its lock when its supplier has not had its turn yet`() {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        // The tank: fed from the right, drained to the left, outlet at (9,3).
        deck += Storage(
            grid.tile(10, 3), Direction.Left,
            filter = SpeciesFilter(Species.Oxygen, pure = true),
            autoLock = true, autoUnlock = true,
        )
        deck += openEjector(grid.tile(8, 3))
        // The source: further right, so its outlet at (12,3) is visited after the tank's.
        deck += fixtureStorage(grid.tile(13, 3), Direction.Left, SpeciesFilter(Species.Oxygen, pure = true))
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 8, 9, 3)      // the tank's outlet, overboard
        joinRow(grid, rails, 11, 12, 3)    // the source's outlet, into the tank's door
        val state = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            .stocked(grid.tile(10, 3), Mixture.of(Species.Oxygen to Capacity.PACKET_MASS, energy = 0).atAmbient())
            .stocked(
                grid.tile(13, 3),
                Mixture.of(Species.Oxygen to 4L * Capacity.PACKET_MASS, energy = 0).atAmbient(),
            )

        var s = state
        var sawTheGap = false
        repeat(20 * RAIL_PERIOD) {
            s = run(s, 1)
            // ⚠️ **Only while the supplier still has something.** Once it is empty the tank has
            // genuinely nothing to wait for and unlocking is the switch working, not failing — which
            // is the control below.
            val supplierHas = s.inStore(grid.tile(13, 3), BufferRole.Inside)?.total ?: 0L
            if (supplierHas <= 0L) return@repeat
            val tankHas = s.inStore(grid.tile(10, 3), BufferRole.Inside)?.total ?: 0L
            if (tankHas == 0L) sawTheGap = true
            assertEquals(
                Species.Oxygen,
                (s.deck[grid.tile(10, 3)] as Storage).filter?.species,
                "the tank unlocked while its supplier still held oxygen for it",
            )
        }
        assertTrue(sawTheGap, "the tank never stood empty while the supplier had stock — nothing was tested")
    }

    /**
     * The control: **with nothing left anywhere, the switch still does what it is for.**
     *
     * ⚠️ The rule is deliberately *sticky* — a tank keeps its lock while any source upstream could
     * still send it a gram — so this is the assertion that stops it being sticky for ever. The
     * source here is empty, so there is nothing to wait for and the tank is genuinely finished.
     */
    @Test
    fun `a tank that runs dry with nothing coming still unlocks`() {
        val s = run(line(autoUnlock = true, sourceStock = 0L), 30 * RAIL_PERIOD)

        assertEquals(0L, held(s), "the tank should have drained")
        assertEquals(null, sink(s).filter?.species, "nothing was coming and the tank kept its lock")
    }
}
