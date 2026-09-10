package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Squash
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.machine.MACHINE_BUFFER_CAP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A lump may be merged into again — but only by one that cannot change what it is.**
 *
 * Belt blending was withdrawn on 2026-08-19 and the reason was specific: a lump's composition
 * changed under whatever happened to be routed at it, so a delivery a construction site had already
 * admitted could turn into one it would have refused, and "what is on its way to this tile" stopped
 * having a stable answer. That is untenable with demand-based flow.
 *
 * ⛔ **The gate here is what makes the objection not apply**, and it is two disjoint arms:
 *
 *  - **pure + pure of the same species** — composition is *identical*, so every filter in the game
 *    gives the same answer before and after. Safe by proof rather than by argument.
 *  - **blend + blend** — composition changes, and nothing left in the game can tell. Since
 *    `SpeciesFilter` collapsed to pure/mixed/no-opinion there is no filter that admits some blends
 *    and refuses others: a construction site at `BUILD_PURITY_PERCENT` wants its recipe, an
 *    electrolyzer wants pure water, a concentrator asks for `MIXED` — and two blends can never
 *    combine into a pure lump, so `MIXED` is invariant too.
 *
 * ⛔ **Pure and blend never combine**, and the exclusion is what keeps the first arm's proof intact:
 * pouring grit into a pure lump is exactly the change no filter may see.
 *
 * ⚠️ **This revived dead code.** `advanceSegments` has always had a squash-forward block, and it
 * skips any successor that is empty — so it only ever called `squashInto` with an occupied
 * destination, which refused every time. The loop could not do anything at all.
 */
class RailMergeTest {

    private val rail get() = RailLayer.empty(8)
    private val a = TileIndex(1)
    private val b = TileIndex(2)

    private fun pure(species: Species, mass: Long) = Mixture.of(species to mass, energy = mass)
    private fun blend(iron: Long, quartz: Long) =
        Mixture.of(Species.Iron to iron, Species.Quartz to quartz, energy = iron + quartz)

    private fun squash(ahead: Mixture, incoming: Mixture): Pair<Squash, RailLayer> {
        val r = rail
        r.put(b, ahead)
        r.put(a, incoming)
        return r.squashInto(a, b) to r
    }

    private val kg = Capacity.PACKET_MASS / 100L

    @Test
    fun `two lumps of the same pure species combine`() {
        val (outcome, r) = squash(pure(Species.Iron, 30L * kg), pure(Species.Iron, 40L * kg))

        assertEquals(Squash.Complete, outcome)
        assertEquals(70L * kg, r.massAt(b), "the two did not become one")
        assertTrue(r.isEmpty(a), "the tile behind was not freed, so the jam did not shorten")
    }

    @Test
    fun `two blends combine`() {
        val (outcome, r) = squash(blend(30L * kg, 10L * kg), blend(10L * kg, 20L * kg))

        assertEquals(Squash.Complete, outcome)
        assertEquals(40L * kg, r.resourceAt(b)!![Species.Iron])
        assertEquals(30L * kg, r.resourceAt(b)!![Species.Quartz])
    }

    /**
     * ⛔ **The exclusion the first arm's proof rests on.** A pure lump whose composition can change
     * is a pure lump that a species lock, a construction site or an electrolyzer may have already
     * been promised and would now refuse.
     */
    @Test
    fun `a pure lump and a blend never combine`() {
        assertEquals(
            Squash.Refused, squash(pure(Species.Iron, 30L * kg), blend(10L * kg, 10L * kg)).first,
            "grit was poured into a pure lump",
        )
        assertEquals(
            Squash.Refused, squash(blend(10L * kg, 10L * kg), pure(Species.Iron, 30L * kg)).first,
            "a pure lump was poured into a blend, which is the same change from the other end",
        )
    }

    @Test
    fun `two different pure species never combine`() {
        assertEquals(
            Squash.Refused, squash(pure(Species.Iron, 30L * kg), pure(Species.Copper, 30L * kg)).first,
            "two metals were blended on a belt",
        )
    }

    /**
     * ⚠️ **A packet is still a packet.** What will not fit stays where it is, and the pair is
     * [Squash.Partial] — which could not happen while merging was banned and can again.
     */
    @Test
    fun `a merge never makes a lump bigger than a packet`() {
        val (outcome, r) = squash(pure(Species.Iron, 60L * kg), pure(Species.Iron, 60L * kg))

        assertEquals(Squash.Partial, outcome)
        assertEquals(Capacity.PACKET_MASS, r.massAt(b), "the lump ahead is not full, so the top-up was short")
        assertEquals(20L * kg, r.massAt(a), "the remainder did not stay behind")
    }

    @Test
    fun `a full lump ahead takes nothing`() {
        val (outcome, r) = squash(pure(Species.Iron, Capacity.PACKET_MASS), pure(Species.Iron, 30L * kg))

        assertEquals(Squash.Refused, outcome)
        assertEquals(30L * kg, r.massAt(a), "material was pushed into a lump with no room in it")
    }

    @Test
    fun `merging conserves mass and energy`() {
        for (pair in listOf(
            pure(Species.Iron, 30L * kg) to pure(Species.Iron, 40L * kg),
            pure(Species.Iron, 60L * kg) to pure(Species.Iron, 60L * kg),
            blend(30L * kg, 10L * kg) to blend(10L * kg, 20L * kg),
        )) {
            val before = pair.first.total + pair.second.total
            val beforeEnergy = pair.first.energy + pair.second.energy
            val (_, r) = squash(pair.first, pair.second)
            assertEquals(before, r.massAt(a) + r.massAt(b), "mass went astray merging $pair")
            assertEquals(
                beforeEnergy,
                (r.resourceAt(a)?.energy ?: 0L) + (r.resourceAt(b)?.energy ?: 0L),
                "energy went astray merging $pair",
            )
        }
    }

    /**
     * And the same thing through the reducer: a run backed up against a consumer that cannot take
     * another gram compacts instead of standing as a line of runts.
     *
     * ⚠️ **The sink is full but its appetite is not**, which is the ordinary way a line jams: the
     * network keeps routing the length of the run while the door refuses at the brim, so the lumps
     * converge and there is something to merge.
     *
     * ⛔ **This used to be a full warehouse and cannot be any more.** A store states the room it has
     * — see `Acceptance.upTo` — so a full one stops being a destination at all and the queue never
     * moves off the tiles it was placed on. Nothing converges, so nothing merges, and the test
     * passed or failed on a property it was not about. [fixtureStalledSink] is a jam that is still a
     * jam.
     */
    @Test
    fun `a run jammed against a full sink compacts itself`() {
        val grid = Grid(14, 6)
        val cfg = OutofspaceConfig(initialGrid = grid)
        val deck = DeckArray(grid)
        val tank = grid.tile(10, 3)
        deck += fixtureStalledSink(tank, Direction.Right)      // input port at (9,3)

        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, 9, 3)

        val queued = listOf(grid.tile(4, 3), grid.tile(5, 3), grid.tile(6, 3), grid.tile(7, 3))
        var s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            // ⛔ **Both stores, or it is not full.** A stalled furnace still does the one thing that
            // needs no signal: it slides a waiting charge from the input into the empty chamber. Fill
            // only the input and the first tick empties it again, and the run is fed rather than
            // jammed. A chamber with anything at all in it is a chamber that takes nothing.
            .stocked(tank, Mixture.of(Species.Iron to MACHINE_BUFFER_CAP, energy = 0L).atAmbient())
            .stocked(tank, Mixture.of(Species.Iron to MACHINE_BUFFER_CAP, energy = 0L).atAmbient(), BufferRole.Inside)
        for (t in queued) s = s.riding(t, pure(Species.Iron, 25L * kg))

        val before = queued.sumOf { s.rail.massAt(it) }
        val occupiedBefore = grid.tiles.count { s.rail.massAt(it) > 0L }

        repeat(RAIL_PERIOD * 20) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }

        var after = 0L
        var occupiedAfter = 0
        for (t in grid.tiles) {
            val mass = s.rail.massAt(t)
            if (mass == 0L) continue
            occupiedAfter++
            after += mass
            assertTrue(mass <= Capacity.PACKET_MASS, "a lump at $t grew past a packet: $mass")
        }

        assertEquals(before, after, "the jam lost or invented mass while compacting")
        assertTrue(
            occupiedAfter < occupiedBefore,
            "the queue still occupies $occupiedBefore tiles; nothing merged",
        )
    }
}
