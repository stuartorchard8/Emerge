package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard on [HALF_REACTIONS], and increment 0 of `PLAN_electrochemistry.md`.
 *
 * ⛔ **A potential nobody can check is a potential that is eventually wrong**, and a wrong one is
 * invisible: the couple still balances, the cell still runs, and it runs at the wrong voltage for
 * ever. That is [FORMATION_ENTHALPY]'s lesson — scoring that table when it landed found ten rows
 * that had drifted, two by a factor of three, every one of them passing every test that existed.
 * This file exists so the same audit is impossible to need twice.
 */
class HalfReactionTest {

    private fun couple(species: Species): HalfReaction =
        HALF_REACTIONS.first { it.principal == species && it.oxidised.size == 1 }

    private val water: HalfReaction = HALF_REACTIONS.first { it.reduced.any { p -> p.first == Species.Water } }
    private val hydrogen: HalfReaction = couple(Species.Hydrogen)

    // ── The table is internally sound ────────────────────────────────────────

    /**
     * Mass balances across every couple, [MINERALS]' argument applied to charge.
     *
     * ⚠️ This is what catches `2 H⁺` being written as two units of [Species.Hydrogen] — a natural
     * mistake, since the game's hydrogen is H₂ and the formula says two protons. Two protons weigh
     * two grams and one unit of H₂ weighs two grams; writing two units doubles the row silently.
     */
    @Test
    fun everyCoupleConservesMass() {
        for (h in HALF_REACTIONS) {
            assertEquals(h.oxidisedMass, h.reducedMass, "${h.formula()} does not balance")
        }
    }

    /** The charge on each metal ion, derived from its electron count rather than stated. */
    @Test
    fun everyMetalIonCarriesTheChargeItsElectronCountImplies() {
        val expected = mapOf(
            Species.Sodium to 1, Species.Magnesium to 2, Species.Aluminum to 3,
            Species.Zinc to 2, Species.Iron to 2, Species.Nickel to 2,
            Species.Tin to 2, Species.Lead to 2, Species.Copper to 2, Species.Silver to 1,
        )
        for ((species, charge) in expected) {
            assertEquals(charge, couple(species).chargeOn, "$species carries the wrong charge")
        }
    }

    /** Declaration order is the electrochemical series, which is what makes the file readable. */
    @Test
    fun theTableIsOrderedAsTheElectrochemicalSeries() {
        val volts = HALF_REACTIONS.map { it.standardMillivolts }
        assertEquals(volts.sorted(), volts, "HALF_REACTIONS is out of order")
    }

    // ── The table agrees with a textbook ─────────────────────────────────────

    /**
     * Every cell voltage the game can currently form, in one place.
     *
     * ⚠️ **A transcription on purpose**, exactly as `FormationTest` transcribes every reaction's
     * energy. It is the one test that would notice a potential being edited: move copper off +340
     * and electrowinning moves with it, and nothing else in the suite has an opinion.
     */
    @Test
    fun everyCellIsWorthWhatItsHalvesSay() {
        // Driven cells: negative, and the magnitude is what must be applied.
        assertEquals(-1230, cellMillivolts(hydrogen, water), "splitting water")
        assertEquals(-890, cellMillivolts(couple(Species.Copper), water), "copper electrowinning")
        assertEquals(-430, cellMillivolts(couple(Species.Silver), water), "silver electrowinning")

        // Galvanic cells: positive, and would run on their own. ⚠️ Not reachable until batteries;
        // here because the arithmetic is the same and a textbook can check it.
        assertEquals(1100, cellMillivolts(couple(Species.Copper), couple(Species.Zinc)), "Daniell cell")
        assertEquals(460, cellMillivolts(couple(Species.Silver), couple(Species.Copper)), "silver-copper")
    }

    // ── The consequences the plan is built on ────────────────────────────────

    /**
     * ⭐ **Water's ceiling, asserted.** The whole scope of the aqueous arc is this one comparison:
     * a metal below hydrogen loses its cathode to water and cannot be won from solution at any
     * voltage, which is why Hall-Héroult uses molten cryolite.
     *
     * If this test ever goes red because somebody added an overpotential, that is the moment
     * `PLAN_electrochemistry.md` §8 becomes real work rather than a note.
     */
    @Test
    fun onlyTheMetalsAboveHydrogenCanBeWonFromWater() {
        val winnable = listOf(Species.Copper, Species.Silver)
        val notWinnable = listOf(
            Species.Sodium, Species.Magnesium, Species.Aluminum,
            Species.Zinc, Species.Iron, Species.Nickel, Species.Tin, Species.Lead,
        )
        for (s in winnable) {
            assertTrue(
                couple(s).standardMillivolts > hydrogen.standardMillivolts,
                "$s should out-compete hydrogen at the cathode",
            )
        }
        for (s in notWinnable) {
            assertTrue(
                couple(s).standardMillivolts < hydrogen.standardMillivolts,
                "$s should lose the cathode to hydrogen — see PLAN_electrochemistry.md §5",
            )
        }
    }

    /**
     * ⭐ **Electrowinning copper is cheaper than splitting water, and the cell prefers it.**
     *
     * Two separate claims from the same number, and both are load-bearing: the copper cell needs
     * less applied voltage *and* wins the competition at the cathode. If only the first were true a
     * player would get hydrogen out of a copper solution.
     */
    @Test
    fun copperIsBothCheaperAndPreferredOverSplittingWater() {
        val copper = couple(Species.Copper)
        assertTrue(
            cellMillivolts(copper, water) > cellMillivolts(hydrogen, water),
            "a copper cell should need less applied voltage than splitting water",
        )
        assertTrue(
            copper.standardMillivolts > hydrogen.standardMillivolts,
            "copper should be reduced in preference to hydrogen",
        )
    }

    /**
     * ⭐ **The anode regenerates acid, and it is the same row read backwards.**
     *
     * Nothing here is a mechanism — the check is that the couple's reduced side is water and its
     * oxidised side carries both the oxygen a cell evolves and the hydrogen that becomes the acid.
     * `PLAN_electrochemistry.md` §2.4's loop is that sentence and nothing else.
     */
    @Test
    fun theWaterAnodeYieldsBothOxygenAndTheAcidBack() {
        assertEquals(listOf(Species.Water to 2), water.reduced)
        assertEquals(setOf(Species.Oxygen, Species.Hydrogen), water.oxidised.map { it.first }.toSet())
        assertEquals(4, water.electrons)
    }
}
