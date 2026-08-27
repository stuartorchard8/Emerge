package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.ALL_REACTIONS
import org.emerge.demo.outofspace.chem.DECOMPOSITIONS
import org.emerge.demo.outofspace.chem.REACTIONS
import org.emerge.demo.outofspace.chem.REDUCTIONS
import org.emerge.demo.outofspace.chem.ReactionKind
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.abundanceOf
import org.emerge.demo.outofspace.chem.abundanceRank
import org.emerge.demo.outofspace.chem.compositionOf
import org.emerge.demo.outofspace.chem.occursNaturally
import org.emerge.demo.outofspace.chem.reactionsConsuming
import org.emerge.demo.outofspace.chem.reactionsProducing
import org.emerge.demo.outofspace.world.Grid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The in-game reference: what an article says about a species, and how the reading wanders.
 *
 * The cases below are the ones that would each make the panel lie. A reaction table that the
 * flattening missed is a species the player is told nothing happens to; a history that keeps a
 * branch after a new page is a forward button that walks somewhere the player never went.
 */
class SpeciesReferenceTest {

    private fun controller() = OutofspaceController(OutofspaceConfig(initialGrid = Grid(9, 9)))

    @Test
    fun `a mineral knows what it is made of, richest element first`() {
        val parts = compositionOf(Species.Calcite)
        assertEquals(listOf(Species.Oxygen, Species.Calcium, Species.Carbon), parts.map { it.element })
        assertEquals(3, parts.first { it.element == Species.Oxygen }.atoms)
        // CaCO3 is 48% oxygen, 40% calcium, 12% carbon, near enough.
        assertEquals(480, parts.first { it.element == Species.Oxygen }.partsPerThousand)
    }

    @Test
    fun `an element is made of itself and so lists nothing`() {
        assertTrue(compositionOf(Species.Iron).isEmpty())
    }

    @Test
    fun `all four reaction tables reach the reference`() {
        // Heat alone.
        assertTrue(reactionsConsuming(Species.Calcite).any { it.kind == ReactionKind.Heat })
        // Heat with the room's oxygen.
        assertTrue(reactionsConsuming(Species.Carbon).any { it.kind == ReactionKind.Burn })
        // Heat with a solid reagent.
        assertTrue(reactionsConsuming(Species.Rutile).any { it.kind == ReactionKind.Reduce })
        // A fuel burning in the air it is already mixed with.
        assertTrue(reactionsConsuming(Species.Methane).any { it.kind == ReactionKind.Fire })
        // And the fifth table, whose rows state no kind at all — this one is derived from the fact
        // that ammonia cracking has a single reagent, so it is heat and nothing else.
        assertTrue(reactionsConsuming(Species.Ammonia).any { it.kind == ReactionKind.Heat })
    }

    @Test
    fun `every row of every table is in the flattening`() {
        // ⚠️ The test the previous one could not be. Naming a reaction per table catches a table
        // that was never wired up at all, and misses the case that actually happened: a *fifth*
        // table nobody thought to name here. Counting is what makes the reference structurally
        // unable to fall behind the chemistry — a new table is a red test on the day it lands, not
        // an article that quietly says nothing happens to methane.
        // ⚠️ It did its job twice before becoming a tautology, which is the right way for a guard
        // like this to end. It caught `REACTIONS` on the commit that introduced it — a day's notice
        // instead of the five days the gas fires got — and then increment 4 collapsed the four
        // tables into that one, so the flattening it was guarding is the identity function now.
        //
        // Kept because "the reference shows every reaction" is still the claim worth making, and it
        // is one line whichever way the tables go next.
        assertEquals(REACTIONS.size, ALL_REACTIONS.size)
    }

    @Test
    fun `a gas fire is reachable from the fuel and from what it leaves behind`() {
        // Ammonia is the one worth reading about: it burns back to two things the vessel wants.
        val burn = reactionsConsuming(Species.Ammonia).single { it.kind == ReactionKind.Fire }
        assertEquals(924, burn.onsetKelvin)
        assertFalse(burn.isEndothermic)
        assertTrue(burn.consumes(Species.Oxygen))
        assertTrue(burn.produces(Species.Nitrogen))
        // And the trail runs the other way: a player looking at the water in the air can find out
        // that burning the ammonia is where it came from.
        assertTrue(reactionsProducing(Species.Water).any { it.consumes(Species.Ammonia) })
    }

    @Test
    fun `a catalyst is a reactant on both sides, and reads as one`() {
        // `100 ALGAE + 6 WATER + 6 CO2 -> 101 ALGAE + 6 OXYGEN`. The hundred is the bloom that has
        // to already be there and the hundred and one is it plus the one it made, which is the
        // whole of what a catalyst is — no third kind of ingredient, and nothing for the panel to
        // learn. The row's own documentation writes the formula exactly this way.
        val photosynthesis = reactionsProducing(Species.Algae).single()
        assertEquals(100, photosynthesis.inputs.single { it.first == Species.Algae }.second)
        assertEquals(101, photosynthesis.products.single { it.first == Species.Algae }.second)
        // ⚠️ Added to the product entry the row already had, not appended beside it — two chips
        // reading `1 ALGAE` and `100 ALGAE` on one side would read as two different substances.
        assertEquals(1, photosynthesis.products.count { it.first == Species.Algae })
        assertTrue(photosynthesis.consumes(Species.Water))
    }

    @Test
    fun `a catalyst is reachable from its own article`() {
        // Both directions find the row, which is what makes an article on algae a page about
        // something rather than a substance that takes part in nothing. TAKES PART IN also carries
        // the pyrolysis that cooks a dead bloom, so the bloom's two fates are on one page.
        val consuming = reactionsConsuming(Species.Algae)
        assertTrue(consuming.any { it.produces(Species.Oxygen) })
        assertTrue(consuming.any { it.kind == ReactionKind.Heat })
        assertEquals(1, reactionsProducing(Species.Algae).size)
    }

    @Test
    fun `a reagent is an ingredient of the reaction it is consumed by`() {
        // Magnesium reduces titania, so the titanium article must be reachable from magnesium.
        val rows = reactionsConsuming(Species.Magnesium)
        assertTrue(rows.any { it.produces(Species.Titanium) })
        // And the exothermic one is named as giving heat back rather than taking it.
        assertFalse(rows.first { it.produces(Species.Titanium) }.isEndothermic)
    }

    @Test
    fun `a species knows what makes it`() {
        val routes = reactionsProducing(Species.Titanium)
        assertEquals(1, routes.size)
        assertTrue(routes.single().consumes(Species.Rutile))
        assertEquals(1100, routes.single().onsetKelvin)
    }

    @Test
    fun `reactions are listed coldest first`() {
        val onsets = reactionsConsuming(Species.Carbon).map { it.onsetKelvin }
        assertEquals(onsets.sorted(), onsets)
    }

    @Test
    fun `abundance is stated in the unit its own size deserves`() {
        // Percent while there is a percent to see: forsterite is 28% of a reference rock.
        assertEquals("28.0%", abundanceOf(Species.Forsterite))
        // Carbon is half a percent, and the tenth is what stops it reading as zero.
        assertEquals("0.5%", abundanceOf(Species.Carbon))
        // Platinum is half a part per million, and gold a hundred and forty parts per billion —
        // the whole reason one scale cannot serve: they are three orders of magnitude apart.
        assertEquals("500 ppb", abundanceOf(Species.Platinum))
        assertEquals("140 ppb", abundanceOf(Species.Gold))
        assertEquals("1 ppm", abundanceOf(Species.Copper))
    }

    @Test
    fun `an element that never occurs loose says so instead of reading as zero`() {
        // The two-tier model's whole point: aluminium is commoner than gold and has to be made.
        assertFalse(Species.Aluminum.occursNaturally)
        assertEquals("", abundanceOf(Species.Aluminum))
        assertEquals(0, abundanceRank(Species.Aluminum))
        assertTrue(Species.Gold.occursNaturally)
    }

    @Test
    fun `rank counts only what a rock can contain, commonest first`() {
        val commonest = Species.NATURAL.maxByOrNull { it.relativeAbundance }!!
        assertEquals(1, abundanceRank(commonest))
        assertTrue(abundanceRank(Species.Gold) > abundanceRank(Species.Iron))
        assertTrue(abundanceRank(Species.Gold) <= Species.NATURAL.size)
    }

    @Test
    fun `opening a species opens the panel`() {
        val c = controller()
        assertNull(c.wikiSpecies)
        c.openWiki(Species.Ilmenite)
        assertEquals(Species.Ilmenite, c.wikiSpecies)
        assertFalse(c.canWikiBack)
        assertFalse(c.canWikiForward)
    }

    @Test
    fun `back and forward walk the trail`() {
        val c = controller()
        c.openWiki(Species.Ilmenite)
        c.openWiki(Species.Rutile)
        c.openWiki(Species.Titanium)
        c.wikiBack()
        assertEquals(Species.Rutile, c.wikiSpecies)
        assertTrue(c.canWikiForward)
        c.wikiBack()
        assertEquals(Species.Ilmenite, c.wikiSpecies)
        assertFalse(c.canWikiBack)
        c.wikiBack()
        assertEquals(Species.Ilmenite, c.wikiSpecies)
        c.wikiForward()
        c.wikiForward()
        assertEquals(Species.Titanium, c.wikiSpecies)
        assertFalse(c.canWikiForward)
    }

    @Test
    fun `opening from the middle of the trail drops the branch never taken`() {
        val c = controller()
        c.openWiki(Species.Ilmenite)
        c.openWiki(Species.Rutile)
        c.wikiBack()
        c.openWiki(Species.Carbon)
        assertFalse(c.canWikiForward)
        c.wikiBack()
        assertEquals(Species.Ilmenite, c.wikiSpecies)
    }

    @Test
    fun `re-opening what is already showing is not a navigation`() {
        val c = controller()
        c.openWiki(Species.Iron)
        c.openWiki(Species.Iron)
        assertFalse(c.canWikiBack)
    }

    @Test
    fun `closing forgets where it has been`() {
        val c = controller()
        c.openWiki(Species.Ilmenite)
        c.openWiki(Species.Rutile)
        c.closeWiki()
        assertNull(c.wikiSpecies)
        assertFalse(c.canWikiBack)
        c.openWiki(Species.Iron)
        assertFalse(c.canWikiBack)
    }
}
