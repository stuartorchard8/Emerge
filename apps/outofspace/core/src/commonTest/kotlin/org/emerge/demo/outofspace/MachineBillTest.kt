package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.buildableFrom
import org.emerge.demo.outofspace.world.builtPermille
import org.emerge.demo.outofspace.world.conduitBillOfMaterials
import org.emerge.demo.outofspace.world.holdsFullBill
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machineBillOfMaterials
import org.emerge.demo.outofspace.world.tileBillOfMaterials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A bill of materials is one question however big the thing is — increment 5a of
 * `apps/outofspace/PLAN_self_building_rails.md`.
 *
 * A machine is built from the same rule as a length of track, so what these pin is that the two
 * genuinely share it: the same 95%, the same per-species completeness, the same permille.
 */
class MachineBillTest {

    private val grid = Grid(12, 8)

    /**
     * ⚠️ The load-bearing identity. A machine's bill has to be exactly what [DeckArray.plusAssign]
     * lays down — a tile's worth on each tile — and not the composition apportioned to the larger
     * total, which differs by the rounding. Off by a unit and a finished machine reads as forever
     * unfinished.
     */
    @Test
    fun machineBillIsTheTileBillOnceEachTile() {
        for (kind in DeckMachineKind.entries) {
            val tiles = 5
            val each = tileBillOfMaterials(kind)
            val whole = machineBillOfMaterials(kind, tiles)
            for (s in Species.ALL) {
                assertEquals(each[s] * tiles, whole[s], "${kind.label} wants ${s.name} x $tiles")
            }
        }
    }

    /** A one-tile machine costs exactly what one tile of it weighs — no special case. */
    @Test
    fun oneTileMachineCostsOneTile() {
        val kind = DeckMachineKind.Hull
        assertEquals(tileBillOfMaterials(kind).total, machineBillOfMaterials(kind, 1).total)
    }

    /** The deck answers "is this finished" over the whole footprint, summed, not tile by tile. */
    @Test
    fun aFullyStockedMachineHoldsItsBill() {
        val deck = DeckArray(grid)
        val centre = grid.tile(5, 4)
        val mill = Processor(center = centre, facing = Direction.Right)
        deck += mill
        assertTrue(deck.holdsFullBill(mill), "a machine laid whole holds its whole bill")
    }

    /**
     * The middle of a half-built machine is not a built machine. Casing spreads evenly as it is
     * absorbed, so a per-tile test would call a machine with one full tile finished.
     */
    @Test
    fun aMachineShortOnOneTileIsNotFinished() {
        val deck = DeckArray(grid)
        val centre = grid.tile(5, 4)
        val mill = Processor(center = centre, facing = Direction.Right)
        deck += mill
        val corner = mill.tiles(grid).first { it != centre }
        for (s in Species.ALL) deck.stuff[corner, s] = 0L
        assertFalse(deck.holdsFullBill(mill), "a footprint short one tile of casing is a ghost")
    }

    /**
     * ⛔ **The door is what keeps junk out, and it is the only thing that does.**
     *
     * Completion is a mass now — see [holdsFullBill] — so a footprint stuffed with the wrong species
     * *would* read as finished. What makes that unreachable is that the wrong species never gets in:
     * every gram is weighed against the bill by [buildableFrom] before it is allowed to become part
     * of anything. This is that guarantee, asserted where it now lives.
     *
     * ⚠️ Stated as a pair on purpose. Moving a rule from one place to another is only safe if the
     * new place actually holds it, and "the completion test no longer refuses this" is a fact about
     * the old place. The second half is the one that matters.
     */
    @Test
    fun junkCannotReachAMachineToFinishIt() {
        val deck = DeckArray(grid)
        val centre = grid.tile(5, 4)
        val hull = Hull(center = centre)
        deck += hull
        val bill = machineBillOfMaterials(hull.kind, hull.tiles(grid).size)
        val junk = Species.ALL.first { bill[it] == 0L }

        // Ten times the bill in the wrong species, poured straight into the fabric: finished, by
        // mass, because nothing here looks at what it is made of.
        for (s in Species.ALL) deck.stuff[centre, s] = 0L
        deck.stuff[centre, junk] = bill.total * 10L
        assertTrue(
            deck.holdsFullBill(hull),
            "completion is a mass — if this is false the rule has quietly moved back",
        )

        // And it can never arrive that way, which is the actual protection.
        assertFalse(
            buildableFrom(bill, Mixture.of(junk to bill.total * 10L, energy = 0L)),
            "ten times the mass in the wrong species is not something a hull may be built from",
        )
    }

    /** Track and machine ask one function, so 95% means one thing in both. */
    @Test
    fun purityIsTheSameRuleForBothBills() {
        val railBill = conduitBillOfMaterials(Conduit.Rail)
        assertTrue(buildableFrom(railBill, railBill), "a rail is buildable from exactly its own bill")
        assertEquals(
            buildableFrom(Conduit.Rail, railBill),
            buildableFrom(railBill, railBill),
            "the conduit overload is the bill overload",
        )
        assertFalse(buildableFrom(railBill, Mixture.EMPTY), "nothing is not a delivery")
    }

    /**
     * ⛔ The alloy anti-exploit. Pure iron is 100% of a species steel names, so an aggregate purity
     * test waves it through — and the hull then never finishes, because its carbon never comes, and
     * it goes on swallowing iron for ever. A matter sink reachable in ordinary play.
     */
    @Test
    fun `a steel bill refuses pure iron`() {
        val steel = machineBillOfMaterials(DeckMachineKind.Hull, 1)
        assertFalse(
            buildableFrom(steel, Mixture.of(Species.Iron to 1_000_000L, energy = 0L)),
            "a hull accepted a delivery with none of its carbon in it",
        )
        assertTrue(buildableFrom(steel, steel), "a hull refused exactly what it is made of")
    }

    /**
     * The thresholds are 95% of each species' *own* share: steel is 990:10, so iron must clear
     * 94.05% and carbon 0.95%. Those are the numbers a player keeps a storage at.
     */
    @Test
    fun `each species is judged against its own share of the recipe`() {
        val steel = machineBillOfMaterials(DeckMachineKind.Hull, 1)
        fun blend(ironPpm: Long, carbonPpm: Long) = buildableFrom(
            steel,
            Mixture.of(Species.Iron to ironPpm, Species.Carbon to carbonPpm, energy = 0L),
        )
        // Carbon just under its 0.95% floor, with iron in abundance: refused.
        assertFalse(blend(990_600L, 9_400L), "carbon below its share was admitted")
        // Carbon just over it: admitted.
        assertTrue(blend(990_400L, 9_600L), "carbon above its share was refused")
        // Iron below its 94.05% floor, however much carbon there is: refused.
        assertFalse(blend(940_000L, 60_000L), "iron below its share was admitted")
        // A carbon-rich but legal blend — the window is six-fold wide for a trace component.
        assertTrue(blend(945_000L, 55_000L), "a legal carbon-rich blend was refused")
    }

    /**
     * ⚠️ The generalisation has to leave track exactly as it was: a rail is made of one species, so
     * its threshold is 95% of 100%, which is the aggregate test it replaces.
     */
    @Test
    fun `a single-species bill is the old aggregate rule`() {
        val rail = conduitBillOfMaterials(Conduit.Rail)
        fun ironWith(junkPercent: Long) = buildableFrom(
            rail,
            Mixture.of(
                Species.Iron to (100L - junkPercent) * 1_000_000L,
                Species.Silicon to junkPercent * 1_000_000L,
                energy = 0L,
            ),
        )
        assertTrue(ironWith(5L), "a 95% delivery is exactly at the bar and is admitted")
        assertFalse(ironWith(6L), "a 94% delivery is under the bar")
    }

    /** Permille is matter held over matter wanted, so it reaches 1000 exactly when the bill is. */
    @Test
    fun permilleIsMatterHeldOverMatterWanted() {
        val bill = machineBillOfMaterials(DeckMachineKind.Processor, 9)
        assertEquals(1000, builtPermille(bill, bill.total), "the whole bill is finished")
        assertEquals(0, builtPermille(bill, 0L), "nothing is nothing")
        assertEquals(500, builtPermille(bill, bill.total / 2L), "half the matter is half built")
        // It cannot run past the end: a site holding more than its bill is finished, not 1100.
        assertEquals(1000, builtPermille(bill, bill.total * 2L), "over the bill still reads as done")
    }
}
