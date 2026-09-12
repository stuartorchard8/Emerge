package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What names one row of the table**, now that its principal has been shown not to.
 *
 * [Reaction.id] is the key a save writes, a stamp carries and the recipe picker presses. Three
 * things have to be true of it or one of those three quietly loses a row — which is exactly the
 * failure it was written to end, so each is pinned here rather than left to the one caller that
 * would notice.
 */
class ReactionIdTest {

    @Test
    fun `no two rows share an id`() {
        // ⛔ **The whole point.** A collision drops a row out of [REACTION_BY_ID] and out of every
        // save that names it, silently — precisely what `principal` did to periclase's other two
        // rows. `associateBy` keeps the last of a colliding pair, so the map's size is the check.
        assertEquals(
            REACTIONS.size,
            REACTION_BY_ID.size,
            "two rows write the same id: " +
                REACTIONS.groupBy { it.id }.filter { it.value.size > 1 }.keys.joinToString(),
        )
    }

    @Test
    fun `an id has no spaces, because a save line is split on them`() {
        // A save writes `recipe=<id>` as one field on a space-separated `deckmachine` line. A space
        // in an id would split the record in two and the reader would see a truncated recipe and a
        // stray token — a corrupt save written by a table edit, with nothing to say it had happened.
        for (r in REACTIONS) {
            assertTrue(' ' !in r.id, "${r.principal.name}'s row writes an id with a space: '${r.id}'")
            assertTrue(r.id.isNotEmpty(), "${r.principal.name}'s row has an empty id")
        }
    }

    @Test
    fun `an id states both sides of the equation`() {
        // Two rows can share their reagents exactly and differ only in what they make — a
        // carbothermic reduction stopping at CO where another goes to CO2 (see `CarbothermicTest`).
        // An id built from the left-hand side alone would collide on that pair, so the shape is
        // spelled out here as well as asserted above.
        val row = REACTIONS.first { it.principal == Species.Periclase && it.reagents.any { r -> r.first == Species.Carbon } }
        assertEquals("1Periclase+1Carbon>1Magnesium+1CarbonMonoxide", row.id)
    }

    @Test
    fun `every row is reachable by its own id`() {
        for (r in REACTIONS) assertTrue(REACTION_BY_ID[r.id] === r, "${r.id} looks up a different row")
    }
}
