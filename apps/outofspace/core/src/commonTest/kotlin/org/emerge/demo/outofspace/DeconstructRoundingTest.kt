package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.conduitBillOfMaterials
import org.emerge.demo.outofspace.world.machine.DeckArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeconstructRoundingTest {

    private val grid = Grid(8, 6)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private val marked = grid.tile(2, 3)
    private val ghost = grid.tile(3, 3)

    /**
     * A marked rail holding a whole rail's worth of **contaminated** metal, and a part-built ghost
     * next to it that is short of rather less than that.
     *
     * ⚠️ **The source must hold more than the site is short of**, which is Stu's shape exactly: a
     * full marked rail beside a ghost already 23% built. Give it less and the draw takes the lot by
     * the `take >= held` path, which is exact, and the rounding never shows.
     */
    private fun world(): VesselState {
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 2, 3, 3)
        rails[marked.index] = rails[marked.index]!!.copy(deconstructing = true)
        val s = VesselState(
            grid,
            DeckArray(grid),
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, DeckArray(grid)),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)

        val stuff = s.conduits.tracks[Conduit.Rail]

        // ⚠️ **Three species, because with one the arithmetic cannot go wrong.** A proportional draw
        // taken species by species rounds each one down on its own; with a single species there is
        // nothing to round. Track that has been built and rebuilt out of ordinary salvage is never
        // one species — Stu's belt reads iron 98%, titanium 1%, carbon the rest.
        val total = conduitBillOfMaterials(Conduit.Rail).total
        // ⚠️ **The shares must not divide the total exactly.** A blend of clean percentages scales
        // to a clean answer and truncates nothing, which is how a first attempt at this test passed
        // against the bug. Salvage is never clean — Stu's belt reads iron 98%, titanium 1%, carbon
        // the remainder, and none of those is a round number of micrograms.
        fun blend(tile: TileIndex, mass: Long) {
            val titanium = mass / 97 + 13
            val carbon = mass / 811 + 7
            stuff.release(tile)
            stuff[tile, Species.Iron] = mass - titanium - carbon
            stuff[tile, Species.Titanium] = titanium
            stuff[tile, Species.Carbon] = carbon
        }
        blend(marked, total)
        // ⚠️ **Short of exactly one packet**, which is Stu's save to the microgram: a full marked
        // rail beside a ghost 23% built. That makes the first draw headroom-capped rather than
        // demand-capped, so it goes down the proportional path and the source is left holding more
        // than the site still wants — which is what turns a rounding error into a deadlock.
        blend(ghost, total - Capacity.PACKET_MASS)
        return s
    }

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * ⛔ **A rail handing its metal back must hand back exactly what was asked for.** The site is
     * short of `n` grams, `n` grams are released, and the site is finished. A draw that rounds down
     * leaves the site a microgram short of a bill it can never reach: it reads 99% for ever, and
     * the next tick's request is for that microgram alone, which rounds to nothing at every species
     * and hands back nothing at all. Both ends stop, permanently, holding what the other needs.
     *
     * Found in Stu's save twice over — a rail ghost at 99% beside a marked rail stuck at 23%, and a
     * single microgram of iron standing on a finished tile in front of the material meant to cross
     * it.
     */
    @Test
    fun `a marked rail hands back exactly what the site is short of`() {
        var s = run(world(), RAIL_PERIOD * 40)

        assertTrue(
            s.conduits.isComplete(Conduit.Rail, ghost),
            "the ghost is short by ${conduitBillOfMaterials(Conduit.Rail).total - s.conduits.massAt(Conduit.Rail, ghost)}ug",
        )
        val bill = conduitBillOfMaterials(Conduit.Rail).total
        assertEquals(
            bill - Capacity.PACKET_MASS,
            s.conduits.massAt(Conduit.Rail, marked),
            "the marked rail handed back the wrong amount",
        )
    }

    /**
     * And nothing is left standing. A residue too small to print as grams is still a lump, and a
     * lump is a permanent blockage: packets never merge, so the tile it rests on can never take a
     * delivery again.
     */
    @Test
    fun `no residue is left standing on the run`() {
        val s = run(world(), RAIL_PERIOD * 40)
        for (t in grid.tiles) {
            assertEquals(0L, s.rail.massAt(t), "a residue is standing at $t: ${s.rail.resourceAt(t)}")
        }
    }
}
