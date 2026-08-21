package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.ReactionKind
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.compositionOf
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
    fun `all three reaction tables reach the reference`() {
        // Heat alone.
        assertTrue(reactionsConsuming(Species.Calcite).any { it.kind == ReactionKind.Heat })
        // Heat with the room's oxygen.
        assertTrue(reactionsConsuming(Species.Carbon).any { it.kind == ReactionKind.Burn })
        // Heat with a solid reagent.
        assertTrue(reactionsConsuming(Species.Rutile).any { it.kind == ReactionKind.Reduce })
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
