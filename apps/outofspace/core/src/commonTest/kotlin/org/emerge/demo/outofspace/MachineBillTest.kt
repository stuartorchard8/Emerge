package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.buildableFrom
import org.emerge.demo.outofspace.world.builtPermille
import org.emerge.demo.outofspace.world.conduitBillOfMaterials
import org.emerge.demo.outofspace.world.holdsFullBill
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.machineBillOfMaterials
import org.emerge.demo.outofspace.world.tileBillOfMaterials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.materialBefore

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
            val each = tileBillOfMaterials(kind, materialBefore(kind))
            val whole = machineBillOfMaterials(kind, tiles, materialBefore(kind))
            for (s in Species.ALL) {
                assertEquals(each[s] * tiles, whole[s], "${kind.label} wants ${s.name} x $tiles")
            }
        }
    }

    /** A one-tile machine costs exactly what one tile of it weighs — no special case. */
    @Test
    fun oneTileMachineCostsOneTile() {
        val kind = DeckMachineKind.Hull
        assertEquals(tileBillOfMaterials(kind, materialBefore(kind)).total, machineBillOfMaterials(kind, 1, materialBefore(kind)).total)
    }

    /** The deck answers "is this finished" over the whole footprint, summed, not tile by tile. */
    @Test
    fun aFullyStockedMachineHoldsItsBill() {
        val deck = DeckArray(grid)
        val centre = grid.tile(5, 4)
        val mill = Concentrator(center = centre, facing = Direction.Right)
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
        val mill = Concentrator(center = centre, facing = Direction.Right)
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
        val bill = machineBillOfMaterials(hull.kind, hull.tiles(grid).size, materialBefore(hull.kind))
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

    /**
     * Track and machine ask one function, so the purity standard means one thing in both.
     *
     * ⚠️ This used to assert that `buildableFrom(Conduit.Rail, …)` agreed with the bill overload.
     * That overload is gone — it built the bill from the conduit's *default* material and so
     * answered for a metal the track may not have chosen — so the premise is restated rather than
     * dropped: one function, reached with a conduit's bill and with a machine's, gives the same
     * verdict about the same matter.
     */
    @Test
    fun purityIsTheSameRuleForBothBills() {
        val railBill = conduitBillOfMaterials(Conduit.Rail, Species.Iron)
        val casingBill = machineBillOfMaterials(DeckMachineKind.Hull, 1, Species.Steel)
        assertTrue(buildableFrom(railBill, railBill), "a rail is buildable from exactly its own bill")
        assertTrue(buildableFrom(casingBill, casingBill), "a casing is buildable from exactly its own bill")
        assertFalse(buildableFrom(railBill, casingBill), "steel is not iron, whichever bill is asking")
        assertFalse(buildableFrom(casingBill, railBill), "and iron is not steel, asked the other way round")
        assertFalse(buildableFrom(railBill, Mixture.EMPTY), "nothing is not a delivery")
    }

    /**
     * ⛔ The alloy anti-exploit. Pure iron is 100% of a species steel names, so an aggregate purity
     * test waves it through — and the hull then never finishes, because its carbon never comes, and
     * it goes on swallowing iron for ever. A matter sink reachable in ordinary play.
     */
    @Test
    fun `a steel bill refuses pure iron`() {
        val steel = machineBillOfMaterials(DeckMachineKind.Hull, 1, materialBefore(DeckMachineKind.Hull))
        assertFalse(
            buildableFrom(steel, Mixture.of(Species.Iron to 1_000_000L, energy = 0L)),
            "a hull accepted a delivery with none of its carbon in it",
        )
        assertTrue(buildableFrom(steel, steel), "a hull refused exactly what it is made of")
    }

    /**
     * The per-species rule, stated against a **synthetic** two-species bill.
     *
     * ⚠️ **No machine in the game has a bill like this any more**, and the test is written this way
     * on purpose rather than borrowed from a machine that happens to suit it. Steel and firebrick
     * became species, so every real bill is one species and the loop in [buildableFrom] degenerates
     * to "bill species and nothing else" — which is a *consequence* of the recipes, not a change to
     * the rule. Material selection would reintroduce multi-species bills, and the day it does this
     * rule has to already be the general one. So the bill is constructed here.
     *
     * At [org.emerge.demo.outofspace.world.BUILD_PURITY_PERCENT] of 100 each species must be at
     * least its exact proportional share, and the shares sum to the whole — so the only mixture that
     * passes is the recipe itself. Any surplus of one species is necessarily a shortfall of another.
     */
    @Test
    fun `each species is judged against its own share of the recipe`() {
        val bill = Mixture.of(Species.Iron to 990L, Species.Carbon to 10L, energy = 0L)
        fun blend(iron: Long, carbon: Long) = buildableFrom(
            bill,
            Mixture.of(Species.Iron to iron, Species.Carbon to carbon, energy = 0L),
        )
        assertTrue(blend(990_000L, 10_000L), "the recipe itself was refused")
        // A gram of carbon short. Under the old 95% tolerance this was comfortably inside the
        // window; the point of the change is that it no longer is.
        assertFalse(blend(990_001L, 9_999L), "a delivery under its carbon share was admitted")
        // And the mirror: iron short, carbon over. Refused on the iron.
        assertFalse(blend(989_999L, 10_001L), "a delivery under its iron share was admitted")
        // ⛔ The anti-exploit the per-species form exists for: 100% of *a* bill species is not the
        // bill. An aggregate reading of the same percentage would wave this through and the site
        // would swallow iron for ever.
        assertFalse(blend(1_000_000L, 0L), "pure iron was admitted against an alloy bill")
    }

    /**
     * ⚠️ A single-species bill — which, since steel and firebrick became species, is every bill in
     * the game — admits exactly its own species and nothing else.
     *
     * This used to assert a 95% bar with a 94% delivery under it. There is no bar now: the junk is
     * what is refused, however little of it there is.
     */
    @Test
    fun `a single-species bill admits its species and no junk at all`() {
        val rail = conduitBillOfMaterials(Conduit.Rail, materialBefore(Conduit.Rail))
        fun ironWith(junk: Long) = buildableFrom(
            rail,
            Mixture.of(Species.Iron to 1_000_000L - junk, Species.Silicon to junk, energy = 0L),
        )
        assertTrue(ironWith(0L), "pure iron was refused for an iron bill")
        assertFalse(ironWith(1L), "one unit of silicon in a million was admitted")
        assertFalse(ironWith(50_000L), "a 95% delivery is no longer at the bar")
    }

    /**
     * ⛔ **A volatile cannot get into a casing, which is the bug this whole change was for.**
     *
     * Stu's report: a machine incorporated a microgram of water ice at build time, the ice melted at
     * some temperature later on, and when the machine was deconstructed to be rebuilt elsewhere
     * there was no longer enough material to finish it.
     *
     * ⚠️ **The chain is worth stating, because none of its links looks wrong on its own.** A casing
     * is inert while it stands — `OutofspaceSim` gives the ambient sweep `rail.stuff` and
     * `buffers.stuff` and never the deck layer — so nothing happens to the ice in place. Taking the
     * machine apart puts its matter on a **rail**, which `offGas` does sweep, and the water leaves
     * as vapour. What lands at the new site is now short of the bill by exactly the water, and
     * [holdsFullBill] counts *matter*, so the site is short of a number no delivery is coming to
     * make up: 99% built, for ever.
     *
     * At `BUILD_PURITY_PERCENT = 100` the first link cannot form. There is nothing in a casing that
     * off-gassing can take, because there is nothing in a casing but the recipe.
     *
     * ⚠️ Asserted at the door rather than by playing the whole chain out, because the door is where
     * the guarantee is: the loop above is what makes every later link unreachable, and a test that
     * ran the chain would be pinning the absence of a symptom rather than the presence of a rule.
     */
    @Test
    fun `a volatile cannot get into a machine casing`() {
        val bill = machineBillOfMaterials(DeckMachineKind.Extractor, 1, materialBefore(DeckMachineKind.Extractor))
        val titanium = bill[Species.Titanium]
        assertTrue(titanium > 0L, "fixture: an extractor is made of titanium, or this proves nothing")

        assertTrue(
            buildableFrom(bill, Mixture.of(Species.Titanium to titanium, energy = 0L)),
            "clean titanium was refused for a titanium bill",
        )
        // One part in a million of water — a microgram in a kilogram, which is Stu's case.
        assertFalse(
            buildableFrom(
                bill,
                Mixture.of(
                    Species.Titanium to titanium - titanium / 1_000_000L,
                    Species.Water to titanium / 1_000_000L,
                    energy = 0L,
                ),
            ),
            "a trace of water ice was admitted into a casing",
        )
    }

    /** Permille is matter held over matter wanted, so it reaches 1000 exactly when the bill is. */
    @Test
    fun permilleIsMatterHeldOverMatterWanted() {
        val bill = machineBillOfMaterials(DeckMachineKind.Concentrator, 9, materialBefore(DeckMachineKind.Concentrator))
        assertEquals(1000, builtPermille(bill, bill.total), "the whole bill is finished")
        assertEquals(0, builtPermille(bill, 0L), "nothing is nothing")
        assertEquals(500, builtPermille(bill, bill.total / 2L), "half the matter is half built")
        // It cannot run past the end: a site holding more than its bill is finished, not 1100.
        assertEquals(1000, builtPermille(bill, bill.total * 2L), "over the bill still reads as done")
    }
}
