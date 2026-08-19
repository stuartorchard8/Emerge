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
import org.emerge.demo.outofspace.world.machine.Smelter
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
        val smelter = Smelter(center = centre, facing = Direction.Right)
        deck += smelter
        assertTrue(deck.holdsFullBill(smelter), "a machine laid whole holds its whole bill")
    }

    /**
     * The middle of a half-built furnace is not a built furnace. Casing spreads evenly as it is
     * absorbed, so a per-tile test would call a machine with one full tile finished.
     */
    @Test
    fun aMachineShortOnOneTileIsNotFinished() {
        val deck = DeckArray(grid)
        val centre = grid.tile(5, 4)
        val smelter = Smelter(center = centre, facing = Direction.Right)
        deck += smelter
        val corner = smelter.tiles(grid).first { it != centre }
        for (s in Species.ALL) deck.stuff[corner, s] = 0L
        assertFalse(deck.holdsFullBill(smelter), "a footprint short one tile of casing is a ghost")
    }

    /** Junk counts toward mass and toward nothing else — the anti-exploit, asked of a machine. */
    @Test
    fun junkDoesNotFinishAMachine() {
        val deck = DeckArray(grid)
        val centre = grid.tile(5, 4)
        val hull = Hull(center = centre)
        deck += hull
        val bill = machineBillOfMaterials(hull.kind, hull.tiles(grid).size)
        val short = Species.ALL.first { bill[it] > 0L }
        val junk = Species.ALL.first { bill[it] == 0L }
        deck.stuff[centre, short] = bill[short] - 1L
        deck.stuff[centre, junk] = bill.total * 10L
        assertFalse(deck.holdsFullBill(hull), "ten times the mass in the wrong species is not a hull")
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

    /** Permille is the worst species, so it reaches 1000 exactly when the bill is held. */
    @Test
    fun permilleTracksTheWorstSpecies() {
        val bill = machineBillOfMaterials(DeckMachineKind.Smelter, 9)
        assertEquals(1000, builtPermille(bill) { bill[it] }, "the whole bill is finished")
        assertEquals(0, builtPermille(bill) { 0L }, "nothing is nothing")
        // The worst species is the answer: starving one of them alone reads exactly as starving
        // all of them. That is the whole reason this is a minimum and not a total over a total.
        val worst = Species.ALL.first { bill[it] > 0L }
        assertEquals(
            builtPermille(bill) { bill[it] / 2L },
            builtPermille(bill) { if (it == worst) bill[it] / 2L else bill[it] },
            "half of one species is as unbuilt as half of every species",
        )
        // Junk cannot make up the shortfall, however much of it there is.
        val junk = Species.ALL.first { bill[it] == 0L }
        assertEquals(
            builtPermille(bill) { if (it == worst) 0L else bill[it] },
            builtPermille(bill) { if (it == junk) bill.total * 10L else if (it == worst) 0L else bill[it] },
            "ten times the mass in the wrong species moves nothing",
        )
    }
}
