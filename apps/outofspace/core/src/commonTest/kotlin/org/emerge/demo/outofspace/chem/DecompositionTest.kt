package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.Budget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.chem.Species

/**
 * The reaction table, checked the way `MineralTest` checks the mineral table — increment 4 of
 * `PLAN_ambient_chemistry.md`.
 *
 * A reaction table has no oracle. "Calcite yields lime" is easy to write and easy to get right, and
 * *how much* lime is a number nobody can eyeball: get it wrong and the reaction still runs, still
 * conserves mass, and quietly produces the wrong amount for ever. So the check is not against
 * recorded output but against the **formulae** — every row must balance atom by atom against
 * [MINERALS], which is the same table [Species.molarMass] is derived from.
 *
 * That is what makes the whole thing safe to extend: a new row with a typo in its formula units is a
 * test failure here rather than a mineral that weighs the wrong amount.
 */
class DecompositionTest {

    /** Atoms in one formula unit, for an element as well as a mineral. */
    private fun atomsOf(species: Species): Map<Species, Int> =
        MINERALS[species] ?: mapOf(species to if (species.molarMass == species.atomicMass) 1 else species.molarMass / species.atomicMass)

    // ── The table balances ───────────────────────────────────────────────────

    @Test
    fun everyReactionBalancesAtomByAtom() {
        for (reaction in DECOMPOSITIONS) {
            val left = mutableMapOf<Species, Int>()
            for ((element, n) in atomsOf(reaction.reactant)) {
                left[element] = (left[element] ?: 0) + n * reaction.reactantUnits
            }

            val right = mutableMapOf<Species, Int>()
            for ((product, units) in reaction.products) {
                for ((element, n) in atomsOf(product)) {
                    right[element] = (right[element] ?: 0) + n * units
                }
            }

            assertEquals(
                left, right,
                "${reaction.reactantUnits} ${reaction.reactant} -> " +
                    reaction.products.joinToString(" + ") { "${it.second} ${it.first}" } +
                    " does not balance",
            )
        }
    }

    @Test
    fun everyReactionWeighsTheSameOnBothSides() {
        // Implied by the atom balance, and worth stating separately because it is the property the
        // *arithmetic* relies on: `split` hands out shares of the reactant's mass, so a row whose
        // products weighed more than its reactant would silently be rescaled to fit rather than
        // failing. This is the assertion that notices.
        for (reaction in DECOMPOSITIONS) {
            val left = reaction.reactantUnits.toLong() * reaction.reactant.molarMass
            val right = reaction.products.sumOf { (product, units) -> units.toLong() * product.molarMass }
            assertEquals(left, right, "${reaction.reactant} does not weigh what it comes apart into")
        }
    }

    @Test
    fun everyEnthalpyIsQuotedAgainstItsOwnFormulaMass() {
        // The enthalpies are written `178L * kJPerMolAt(100)` so they can be checked against a
        // textbook — but nothing stops a row using another row's divisor, which would be wrong by
        // whatever ratio the two formula masses happen to be in and would look entirely plausible.
        // So: recover the divisor from the value and insist it is this reaction's own formula mass.
        for (reaction in DECOMPOSITIONS) {
            val formulaMass = reaction.reactantUnits.toLong() * reaction.reactant.molarMass
            // enthalpyPerKg = kJ * (1e8 / formulaMass), so this recovers kJ per formula unit.
            val kJPerFormulaUnit = reaction.enthalpyPerKg / kJPerMolAt(formulaMass)
            assertTrue(kJPerFormulaUnit > 0L, "${reaction.reactant} is not endothermic; is its divisor right?")
            assertEquals(
                reaction.enthalpyPerKg,
                kJPerFormulaUnit * kJPerMolAt(formulaMass),
                "${reaction.reactant}'s enthalpy is not a whole number of kJ/mol over its own formula mass",
            )
        }
    }

    // ── The arithmetic conserves ─────────────────────────────────────────────

    @Test
    fun theProductsAlwaysWeighExactlyWhatCameApart() {
        // Structural conservation: `split` apportions the reactant's own mass, so the sum is the
        // total by construction and no rounding can leak. Checked across awkward masses because a
        // telescoping sum is exactly the construction that a "fix" would replace with per-share
        // rounding and a leftover unit.
        for (reaction in DECOMPOSITIONS) {
            for (mass in listOf(1L, 7L, 999L, Budget.GRAM, Budget.KILOGRAM + 1L, 4L * Budget.TONNE - 3L)) {
                val parts = reaction.split(mass)
                assertEquals(mass, parts.sum(), "${reaction.reactant} split $mass into ${parts.toList()}")
                assertTrue(parts.all { it >= 0L }, "${reaction.reactant} produced a negative mass")
            }
        }
    }

    @Test
    fun theSplitFollowsTheFormulaAndNotAGuess() {
        // Calcite is the one everybody can check by hand: CaCO3 is 100, CaO is 56, CO2 is 44. So a
        // hundred kilograms of limestone gives fifty-six of quicklime and forty-four of gas, and if
        // it ever gives anything else the formula and the arithmetic have parted company.
        val calcite = DECOMPOSITIONS.first { it.reactant == Species.Calcite }
        val parts = calcite.split(100L * Budget.KILOGRAM)
        assertEquals(56L * Budget.KILOGRAM, parts[0], "the lime")
        assertEquals(44L * Budget.KILOGRAM, parts[1], "the carbon dioxide")
    }

    // ── Conditions ───────────────────────────────────────────────────────────

    @Test
    fun nothingDecomposesBelowItsOnset() {
        for (reaction in DECOMPOSITIONS) {
            val plenty = 100L * Budget.KILOGRAM
            assertEquals(0L, reaction.decomposed(plenty, reaction.onsetKelvin - 1), "${reaction.reactant} decomposed cold")
            assertTrue(reaction.decomposed(plenty, reaction.onsetKelvin) > 0L, "${reaction.reactant} never starts")
        }
    }

    @Test
    fun nothingDecomposesMoreThanIsThere() {
        // The clamp in `reactionFraction`, seen from here: at an absurd temperature the fraction is
        // all of it, and what it must never be is more — a reactant mass larger than the tile's is a
        // negative mass one line later.
        for (reaction in DECOMPOSITIONS) {
            val present = 5L * Budget.KILOGRAM
            assertTrue(
                reaction.decomposed(present, 100_000) <= present,
                "${reaction.reactant} decomposed more than was there",
            )
        }
    }

    @Test
    fun aMagnesiteFeedCracksAtATemperatureCalciteIgnores() {
        // The property that makes a setpoint a *decision* rather than a formality: the two
        // carbonates come apart hundreds of kelvin apart, so heat is a separator. If these onsets
        // are ever brought together, the decomposer goes back to being a box that does one thing.
        val magnesite = DECOMPOSITIONS.first { it.reactant == Species.Magnesite }
        val calcite = DECOMPOSITIONS.first { it.reactant == Species.Calcite }
        assertTrue(
            magnesite.onsetKelvin < calcite.onsetKelvin - 200,
            "magnesite at ${magnesite.onsetKelvin}K and calcite at ${calcite.onsetKelvin}K are too close to sort by heat",
        )

        val between = (magnesite.onsetKelvin + calcite.onsetKelvin) / 2
        assertTrue(magnesite.decomposed(100L * Budget.KILOGRAM, between) > 0L, "magnesite did not crack")
        assertEquals(0L, calcite.decomposed(100L * Budget.KILOGRAM, between), "calcite cracked as well")
    }

    // ── Enthalpy ─────────────────────────────────────────────────────────────

    @Test
    fun calciningCostsMoreThanTheRockHoldsAtItsOwnCalciningTemperature() {
        // The reason a decomposer's element has to keep working rather than reaching a setpoint and
        // stopping. A kilogram of limestone at 1170 K holds about 1.05 MJ of heat and coming apart
        // costs it 1.78 MJ — so a charge cannot possibly calcine itself on stored heat alone. It
        // cools, drops under its onset, and waits. That *is* the gameplay loop, and it stops being
        // one the moment this inequality flips.
        val calcite = DECOMPOSITIONS.first { it.reactant == Species.Calcite }
        val kilogram = Budget.KILOGRAM

        val heldAtOnset = kilogram * Species.Calcite.specificHeat / Budget.CAPACITY_DIVISOR * calcite.onsetKelvin
        val costToDecompose = calcite.enthalpy(kilogram)

        assertTrue(costToDecompose > 0L, "calcining is not endothermic")
        assertTrue(
            costToDecompose > heldAtOnset,
            "a limestone charge can calcine itself on its own heat: costs $costToDecompose, holds $heldAtOnset",
        )
    }

    @Test
    fun burningCarbonGivesBackFarMoreThanHoldingItCosts() {
        // The mirror image, and the reason a fire sustains itself. A kilogram of carbon at its
        // ignition point holds about half a megajoule; burning it releases some thirty-three. A fire
        // that released less than it took to reach the ignition point would not be a fire.
        val kilogram = Budget.KILOGRAM
        val heldAtIgnition =
            kilogram * Species.Carbon.specificHeat / Budget.CAPACITY_DIVISOR * CARBON_IGNITION_KELVIN
        // The carbon-burning row, which used to be `CARBON_BURN` and is now an ordinary entry in
        // [REACTIONS] with oxygen among its reagents.
        val carbonBurn = REACTIONS.first { r ->
            r.principal == Species.Carbon && r.reagents.any { it.first == Species.Oxygen }
        }
        val released = -carbonBurn.enthalpy(kilogram)

        assertTrue(released > 0L, "burning carbon is not exothermic")
        assertTrue(
            released > heldAtIgnition * 10L,
            "a fire barely pays for itself: releases $released, needs $heldAtIgnition to light",
        )
    }
}
