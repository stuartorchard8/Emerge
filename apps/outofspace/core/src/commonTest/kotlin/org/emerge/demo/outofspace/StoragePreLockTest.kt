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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pre-lock: **a tank commits to a species on a packet let go for it, and the commitment has to
 * be one it will actually receive.**
 *
 * An undecided tank locks at *dispatch* rather than at delivery, because nothing else survives the
 * step boundary — see `lockOnDispatch`, where that is argued. The lock is therefore a **prediction**,
 * and this file is about the two ways a prediction goes wrong.
 *
 * ⛔ **It was made on the wrong question.** `Whitelist.leadsTo` answers "is that appetite on this
 * tile's route list", which is a fact about the *shape* of the network. A packet does not consult
 * the shape: it rolls, and the first door on the way that admits it eats it. A tank sitting behind a
 * hungrier one of the same species is on every route and at the end of every queue, so it locked
 * onto packets it could never see — not occasionally, but every time, for ever.
 *
 * ⛔ **And the lock could not be undone.** Auto-unlock fired only on the edge a store *drained* to
 * empty, which is reached only from `pushOut` — i.e. only by a store that successfully ships
 * something out. A tank that locked on a prediction and never received a gram has nothing to ship,
 * so the switch meant to rescue it was unreachable in exactly the case it was needed.
 *
 * Stu's `prem.txt`, the silo at (9,14) with its door at (10,14): locked to pure water by a packet the
 * concentrator at (11,12) let go, which was eaten three hops short by the silo at (9,11). Empty,
 * locked to a species that could never arrive, and unable to let go.
 */
class StoragePreLockTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(12, 16))

    private val source = cfg.initialGrid.tile(4, 2)
    private val nearer = cfg.initialGrid.tile(4, 6)
    private val tank = cfg.initialGrid.tile(4, 10)
    private val tankDoor = cfg.initialGrid.tile(5, 10)

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap<PlayerId, OutofspaceInput>()) }
        return s
    }

    /**
     * One corridor down column 5, with three storages hanging off it to the left.
     *
     * The **source** at (4,2) ships pure oxygen onto (5,2). The corridor runs down to the
     * **tank**'s door at (5,10), and the tank is undecided — auto-lock on, no species, pure only —
     * so it publishes an appetite for one packet of pure anything and is a candidate for the
     * pre-lock.
     *
     * ⚠️ **[interceptor] is the whole variable.** With it, a warehouse locked to pure oxygen with
     * twenty tonnes of room stands at (5,6) — squarely between the source and the tank, on the only
     * route there, and never satisfied. Without it that stretch of corridor is bare and the tank is
     * the first door the oxygen meets.
     */
    private fun corridor(
        interceptor: Boolean,
        tankFilter: SpeciesFilter = SpeciesFilter(null, pure = true),
    ): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        // Facing Right: out at +ahead, so the source's outlet lands on the corridor at (5,2).
        deck += fixtureStorage(source, Direction.Right, SpeciesFilter(Species.Oxygen, pure = true))
        // Facing Left: in at -behind, so these two take delivery *from* the corridor.
        if (interceptor) deck += fixtureStorage(nearer, Direction.Left, SpeciesFilter(Species.Oxygen, pure = true))
        deck += Storage(tank, Direction.Left, filter = tankFilter, autoLock = true, autoUnlock = true)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinCol(grid, rails, 5, 2, 10)
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(
            source,
            Mixture.of(Species.Oxygen to 6L * Capacity.PACKET_MASS, energy = 0).atAmbient(),
        )
    }

    private fun filterOf(s: VesselState, at: org.emerge.demo.outofspace.world.TileIndex): SpeciesFilter? =
        (s.deck[at] as Storage).filter

    private fun heldBy(s: VesselState, at: org.emerge.demo.outofspace.world.TileIndex): Long =
        s.inStore(at, BufferRole.Inside)?.total ?: 0L

    /**
     * ⭐ **A tank behind a hungrier tank of the same species never locks onto what it cannot get.**
     *
     * ⛔ The regression. Every packet the source lets go is on a route that reaches the tank, so the
     * old rule locked the tank onto oxygen on the very first dispatch — and the interceptor ate that
     * packet and every one after it. Twenty tonnes of room means it is never satisfied, so this is
     * not a race the tank sometimes loses: it is one it can never win.
     *
     * ⚠️ **Asserted while the tank is still empty**, which is the only window the pre-lock lives in:
     * a tank that has taken delivery is entitled to lock onto what it is holding, and checking after
     * the fact cannot tell the two apart.
     */
    @Test
    fun `a tank does not lock onto a packet a nearer sink will eat`() {
        var s = corridor(interceptor = true)
        repeat(40 * RAIL_PERIOD) {
            s = run(s, 1)
            if (heldBy(s, tank) > 0L) return@repeat
            assertNull(
                filterOf(s, tank)?.species,
                "the empty tank locked onto a species the interceptor takes before it can arrive",
            )
        }
        assertTrue(
            heldBy(s, nearer) > 0L,
            "the interceptor never took anything — the fixture proved nothing",
        )
        assertEquals(0L, heldBy(s, tank), "the interceptor was supposed to take it all")
    }

    /**
     * The control: **with the road clear, the tank still commits at dispatch.**
     *
     * ⚠️ Without this the fix above is indistinguishable from switching the pre-lock off. What makes
     * it a pre-lock and not a delivery lock is that the tank is holding **nothing** at the moment it
     * decides — the packet is still in the corridor.
     */
    @Test
    fun `a tank still locks at dispatch when nothing stands in the way`() {
        var s = corridor(interceptor = false)
        var lockedWhileEmpty = false
        repeat(40 * RAIL_PERIOD) {
            s = run(s, 1)
            if (heldBy(s, tank) > 0L) return@repeat
            if (filterOf(s, tank)?.species == Species.Oxygen) lockedWhileEmpty = true
        }
        assertTrue(lockedWhileEmpty, "the tank never committed while the packet was on its way")
        assertEquals(Species.Oxygen, filterOf(s, tank)?.species, "the tank should be an oxygen tank")
    }

    /**
     * ⭐ **A lock with nothing to show for it is let go, even though the tank has never shipped.**
     *
     * ⛔ **This is the half that could not fire at all.** Auto-unlock hung off `drained`, which only
     * a store that ships something reaches — so a tank stranded by a bad prediction was the one tank
     * the switch could never reach. Stated as a state rather than an edge it covers both: the valve
     * that has drained dry, and the tank holding a promise that evaporated.
     *
     * ⚠️ **The pure standard is kept and only the species let go**, which is what the player set:
     * "one clean tank of something" is still what this tank is for.
     */
    @Test
    fun `a tank stranded by a lock it never received lets it go`() {
        // As if a dispatch had committed it to oxygen: empty, locked, and behind the interceptor.
        val s = run(corridor(interceptor = true, tankFilter = SpeciesFilter(Species.Oxygen, pure = true)), 20 * RAIL_PERIOD)

        assertEquals(0L, heldBy(s, tank), "the interceptor was supposed to take it all")
        val filter = assertNotNull(filterOf(s, tank), "the pure standard is the player's and is not thrown away")
        assertNull(filter.species, "the tank kept a lock it had nothing to show for")
        assertEquals(true, filter.pure, "the pure standard was thrown away with the species")
    }
}
