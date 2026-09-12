package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.REACTIONS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What the recipe picker calls each row**, now that the label is the whole reagent list.
 *
 * ⛔ **A row's label has to tell it apart from every other row**, which is the entire reason the
 * label moved off the principal: `PERICLASE` named three of them, so two thirds of the sheet were
 * unpressable and the player could not see which was which. Reagent lists are all distinct today
 * and nothing but this test would notice the day they stop being.
 */
class FurnaceSheetTest {

    @Test
    fun `no two rows write the same label`() {
        val labels = REACTIONS.map { OutofspaceHud.takenBy(it) }
        val collisions = labels.groupBy { it }.filter { it.value.size > 1 }.keys
        assertEquals(
            emptySet(), collisions,
            "two rows would read alike in the picker — only the YIELDS line under them would " +
                "differ. Either the label has to carry the products too, or these rows do.",
        )
        assertEquals(REACTIONS.size, labels.size)
    }

    @Test
    fun `the label column is wider than the longest label`() {
        // ⚠️ **Strictly wider**, which is `TradeSheetTest`'s rule: padding is the only separator
        // between these columns, so a label filling its cell exactly touches the temperature.
        val longest = REACTIONS.maxOf { OutofspaceHud.takenBy(it).length }
        assertTrue(
            OutofspaceHud.RECIPE_TAKES_W > longest,
            "the longest label is $longest characters and the column is " +
                "${OutofspaceHud.RECIPE_TAKES_W}; every row that long would shove the temperature " +
                "and RUN columns right by the overrun",
        )
    }
}
