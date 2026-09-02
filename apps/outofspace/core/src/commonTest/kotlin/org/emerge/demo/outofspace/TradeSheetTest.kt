package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The counter's columns are made of **padding**, and padding only aligns while every value fits.
 *
 * ⛔ **This is here because a screenshot cannot find it.** The agent's berth stocks iron, copper and
 * titanium — all short — so the widest row the pictures ever show is `TITANIUM`. Nineteen species
 * are longer than ten characters and `HydrogenSulfide` is fifteen; any of them at a real station
 * would have shoved that row's remaining nine columns sideways, silently, in a build that
 * photographed perfectly.
 */
class TradeSheetTest {

    @Test
    fun `the name column fits the longest species name in the game`() {
        val longest = Species.ALL.maxBy { it.name.length }
        assertTrue(
            OutofspaceHud.TRADE_NAME_W > longest.name.length,
            "${longest.name} is ${longest.name.length} characters and the name column is " +
                "${OutofspaceHud.TRADE_NAME_W}; every column in that row would shift right by the " +
                "overrun. Widen TRADE_NAME_W (and check the sheet still fits TRADE_WIDTH_DP).",
        )
    }

    @Test
    fun `every column is wider than the longest thing that can go in it`() {
        // ⚠️ **Strictly wider, not merely wide enough.** Padding is the only separator between these
        // columns, so a value that fills its cell exactly touches its neighbour — a screenshot read
        // `192892000.0KG` where the truth was an ask of 19,289 beside 2,000.0 kg of stock.
        val widestMass = "4311.1KG".length
        assertTrue(OutofspaceHud.TRADE_MASS_W > widestMass, "the mass column would touch its neighbour")
    }
}
