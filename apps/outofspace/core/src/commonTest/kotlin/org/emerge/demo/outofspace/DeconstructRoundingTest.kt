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
import org.emerge.demo.outofspace.world.buildableFrom
import org.emerge.demo.outofspace.world.conduitBillOfMaterials
import org.emerge.demo.outofspace.world.machine.DeckArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.materialBefore

class DeconstructRoundingTest {

    private val grid = Grid(8, 6)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private val marked = grid.tile(2, 3)
    private val ghost = grid.tile(3, 3)

    /**
     * A marked rail holding a whole rail's worth of metal, and a part-built ghost next to it that is
     * short of rather less than that.
     *
     * ⚠️ **The source must hold more than the site is short of**, which is Stu's shape exactly: a
     * full marked rail beside a ghost already 23% built. Give it less and the draw takes the lot by
     * the `take >= held` path, which is exact, and the rounding never shows.
     *
     * ⚠️⚠️ **This fixture used to be a three-species blend and cannot be one any longer.** Stu's belt
     * read iron 98%, titanium 1%, carbon the rest, and the blend was the point: a proportional draw
     * rounds each species down on its own, so with one species there is nothing to round and the bug
     * could not be reproduced. At `BUILD_PURITY_PERCENT = 100` that lump is refused at the ghost's
     * door outright, so the multi-species draw into a construction site is unreachable and there is
     * no way to state the old fixture at all.
     *
     * ⛔ **Which means the rounding this test was written for is now guarded by the door instead**,
     * and that is a weaker guard in one specific way worth writing down: it prevents contaminated
     * salvage from *entering* a site rather than making the arithmetic exact once it has. Track
     * built under the new rule is pure iron and stays pure, so nothing in a fresh world can produce
     * the old fixture — but a **save written before the change** has contaminated track on it, and
     * that track can no longer be recycled into anything. See the note in `Material.kt`.
     */
    private fun world(shortBy: Long = Capacity.PACKET_MASS): VesselState {
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

        // One species, because a rail's bill names one species and the door admits nothing else. See
        // the fixture's own note: the three-species blend this used to carry is unstateable now.
        val total = conduitBillOfMaterials(Conduit.Rail, materialBefore(Conduit.Rail)).total
        fun blend(tile: TileIndex, mass: Long) {
            stuff.release(tile)
            stuff[tile, Species.Iron] = mass
        }
        blend(marked, total)
        // ⚠️ **Short of exactly one packet**, which is Stu's save to the microgram: a full marked
        // rail beside a ghost 23% built. That makes the first draw headroom-capped rather than
        // demand-capped, so it goes down the proportional path and the source is left holding more
        // than the site still wants — which is what turns a rounding error into a deadlock.
        blend(ghost, total - shortBy)
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
            "the ghost is short by ${conduitBillOfMaterials(Conduit.Rail, materialBefore(Conduit.Rail)).total - s.conduits.massAt(Conduit.Rail, ghost)}ug",
        )
        val bill = conduitBillOfMaterials(Conduit.Rail, materialBefore(Conduit.Rail)).total
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

    /**
     * ⛔ **A thing coming apart never hands over a packet the thing being built cannot accept.**
     *
     * The gate on this pass asks whether what the rail *holds* is wanted. That is not the same
     * question as whether what it is about to hand *over* can be accepted, and the two part company
     * at small draws: a proportional slice is only representative while it is big enough to carry
     * every species. Take one microgram off track that is 98% iron, 1% titanium and a trace of
     * carbon and the apportionment puts that microgram on whichever species the running total lands
     * on — so the rail mints a speck of pure carbon, which no iron ghost will admit.
     *
     * Worse than handing back nothing: packets never merge, so the speck owns the tile it lands on
     * for good and the corridor behind it is dead. Stu's save, first rail tick, `(20,31)`.
     */
    @Test
    fun `a rail hands back nothing rather than a speck nothing can use`() {
        val bill = conduitBillOfMaterials(Conduit.Rail, materialBefore(Conduit.Rail))
        val s = run(world(shortBy = 1L), RAIL_PERIOD * 20)

        for (t in grid.tiles) {
            val lump = s.rail.resourceAt(t) ?: continue
            assertTrue(
                buildableFrom(bill, lump),
                "a lump nothing on the network can use is standing at $t: $lump",
            )
        }
    }
}