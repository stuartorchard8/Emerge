package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.FORMER_MATERIALS
import org.emerge.demo.outofspace.num.Budget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Whether the table is a chain** — can a vessel actually get from a rock to the things it is built
 * out of, and does heat still sort a mixed feed?
 *
 * ⛔ **The successor to `DecompositionTest` and `ReductionTest`, both deleted with the classes they
 * tested.** Everything they proved about arithmetic — atom closure, mass conservation, the split
 * following the formula, nothing reacting below its onset — is proved for *every* row by
 * `UnifiedReactionTest`, and everything they proved about energy is now
 * [FormationTest]'s. What was left is the part that was never about a table's shape: reachability,
 * and the handful of relationships between rows that make the chemistry a game rather than a list.
 *
 * ⚠️ **Everything here closes over [REACTIONS] and must keep doing so.** The old forms walked
 * `DECOMPOSITIONS` and `REDUCTIONS`, which covered every row for exactly as long as every row
 * arrived through one of them — steel and firebrick were written straight into the unified table and
 * were invisible to the reachability sweep that was supposed to guarantee them.
 */
class ReactionChainTest {

    /**
     * Every species that any reaction can make, starting from what an extractor can dig up.
     *
     * A row fires when all of its reagents are reachable, which subsumes the two old cases: a
     * decomposition has one reagent and a reduction has two. Bounded by the table size, so a fixed
     * number of sweeps reaches the fixed point.
     */
    private fun reachableSpecies(): Set<Species> {
        val reachable = Species.NATURAL.toMutableSet()
        repeat(REACTIONS.size) {
            for (row in REACTIONS) {
                if (row.reagents.all { it.first in reachable }) reachable += row.products.map { it.first }
            }
        }
        return reachable
    }

    // ── The chain closes ─────────────────────────────────────────────────────

    @Test
    fun everyReagentIsEitherNativeOrMadeByAnotherRow() {
        // The property that makes this a *chain* rather than a pile of rows with a titanium at the
        // end. A reagent nothing produces and no rock contains is a reaction that can never run,
        // which is precisely the state titanium was in before the reduction rows existed — and the
        // mistake is trivially easy to repeat by adding a row that reduces something with aluminium.
        val produced = REACTIONS.flatMap { row -> row.products.map { it.first } }.toSet()
        for (reaction in REACTIONS) {
            for ((species, _) in reaction.reagents) {
                assertTrue(
                    species.relativeAbundance > 0 || species in produced,
                    "$species is a reagent of ${reaction.principal}'s row, but no rock contains it " +
                        "and nothing in the game can make it",
                )
            }
        }
    }

    @Test
    fun titaniumIsReachableFromRocksAndCarbonAlone() {
        // The acceptance test for the reduction chain, stated as reachability rather than as a
        // simulation: start from what an extractor can actually dig up, close over the table, and
        // insist titanium falls out. It is the question that work began with — "is there a pathway to
        // produce all of the species the machines need?" — and it is worth being able to ask it of
        // the table directly rather than by playing.
        val reachable = reachableSpecies()
        assertTrue(Species.Silicon in reachable, "silicon is not reachable")
        assertTrue(Species.Magnesium in reachable, "magnesium is not reachable")
        assertTrue(Species.Titanium in reachable, "TITANIUM is not reachable — the chain is broken")
    }

    @Test
    fun everySpeciesTheVesselIsBuiltFromCanBeMade() {
        // The stronger form, and the one that would have caught the aluminium problem on the day
        // Firebrick was written: every species in every buildable material has to be reachable, or
        // there is a machine in the build menu that the vessel can never replace.
        val reachable = reachableSpecies()
        for (species in FORMER_MATERIALS) {
            assertTrue(
                species in reachable,
                "$species is something a ship is built from and nothing in the game can produce it",
            )
        }
    }

    /**
     * **Every element the vessel can manufacture**, named — the headline number for the whole
     * "grow the table" effort, and the test each new batch of rows has to come here and update.
     *
     * ⛔ **Asserted as a set rather than checked one metal at a time**, so it fails in both
     * directions: a row that stops working drops a metal out and a batch that lands adds one, and
     * neither can happen quietly. Losing titanium to an edit elsewhere is exactly the sort of thing
     * that would otherwise be noticed months later.
     *
     * ⚠️ Elements that a rock already contains are not interesting here — an extractor digs up iron
     * and carbon and sulfur, and always could. This is the list of things that **did not exist**
     * until some reaction made them.
     *
     * ⛔ **So silver is absent, and the argentite roast is not a counterexample.** Native silver has
     * an abundance of 5, so a vessel could always find some; what roasting argentite (abundance 23)
     * buys is five times as much of it from a commoner rock. That is a route, not a reachability
     * change, and this test is deliberately blind to it — see `everySpeciesTheVesselIsBuiltFromCanBeMade`
     * for the question that is about being *able* to build something.
     */
    @Test
    fun theElementsTheGameCanManufactureAreTheOnesNamedHere() {
        val reachable = reachableSpecies()
        val manufactured = Species.ALL
            .filter { it.isElement && it.relativeAbundance == 0 && it in reachable }
            .toSet()
        assertEquals(
            setOf(
                // The chain that reaches titanium, and the reason `Reduction` was written.
                Species.Silicon, Species.Magnesium, Species.Titanium,
                // Oxygen, which a hot bed of hematite gives up.
                Species.Oxygen,
                // The oxide ores, which were mineable and irreducible until 2026-09-05.
                Species.Tin, Species.Manganese, Species.Chromium,
                // The sulfides that give up their metal without an oxide.
                Species.Lead, Species.Antimony, Species.Bismuth,
            ),
            manufactured,
            "the set of elements the game can make has changed",
        )
    }

    // ── Heat is the separator ────────────────────────────────────────────────

    @Test
    fun aMagnesiteFeedCracksAtATemperatureCalciteIgnores() {
        // The property that makes a setpoint a *decision* rather than a formality: the two
        // carbonates come apart hundreds of kelvin apart, so heat is a separator. If these onsets are
        // ever brought together, the decomposer goes back to being a box that does one thing.
        val magnesite = REACTIONS.first { it.principal == Species.Magnesite }
        val calcite = REACTIONS.first { it.principal == Species.Calcite }
        assertTrue(
            magnesite.onsetKelvin < calcite.onsetKelvin - 200,
            "magnesite at ${magnesite.onsetKelvin}K and calcite at ${calcite.onsetKelvin}K " +
                "are too close to sort by heat",
        )

        val between = (magnesite.onsetKelvin + calcite.onsetKelvin) / 2
        val plenty = 100L * Budget.KILOGRAM
        assertTrue(magnesite.consumed(plenty, between) > 0L, "magnesite did not crack")
        assertEquals(0L, calcite.consumed(plenty, between), "calcite cracked as well")
    }

    // ── The two inequalities the gameplay loop rests on ──────────────────────

    @Test
    fun calciningCostsMoreThanTheRockHoldsAtItsOwnCalciningTemperature() {
        // The reason a decomposer's element has to keep working rather than reaching a setpoint and
        // stopping. A kilogram of limestone at 1170 K holds about 1.05 MJ of heat and coming apart
        // costs it 1.78 MJ — so a charge cannot possibly calcine itself on stored heat alone. It
        // cools, drops under its onset, and waits. That *is* the gameplay loop, and it stops being
        // one the moment this inequality flips.
        val calcite = REACTIONS.first { it.principal == Species.Calcite }
        val kilogram = Budget.KILOGRAM

        val heldAtOnset =
            kilogram * Species.Calcite.specificHeat / Budget.CAPACITY_DIVISOR * calcite.onsetKelvin
        val costToDecompose = calcite.enthalpy(kilogram)

        assertTrue(costToDecompose > 0L, "calcining is not endothermic")
        assertTrue(
            costToDecompose > heldAtOnset,
            "a limestone charge can calcine itself on its own heat: " +
                "costs $costToDecompose, holds $heldAtOnset",
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

    @Test
    fun magnesiothermicTitaniumIsTheOneReductionThatGivesEnergyBack() {
        // Magnesium wants oxygen badly enough that reducing titania with it pays for itself once lit,
        // which is exactly the property that makes magnesium the reductant and carbon not. ⚠️ Stated
        // against the *other* reductions rather than as a bare sign check, because "it is exothermic"
        // is only interesting next to the rows that are not.
        val titanium = REACTIONS.first { it.principal == Species.Rutile }
        assertTrue(titanium.enthalpyPerKg < 0L, "magnesiothermic titanium should give energy back")

        // Every other row that reduces an oxide with a solid reductant costs energy.
        //
        // ⚠️ **Named by oxide *and* reductant, because the principal alone is not a key.** Periclase
        // is the principal of two rows — the Pidgeon reduction and the firebrick firing — and picking
        // the first match got the firebrick one, which is quoted at zero and fails a test about
        // reductions for a reason that has nothing to do with reduction.
        val otherReductions = listOf(
            Species.Quartz to Species.Carbon,
            Species.Periclase to Species.Silicon,
            Species.Forsterite to Species.Carbon,
            Species.Enstatite to Species.Carbon,
            Species.Fayalite to Species.Carbon,
            Species.Ferrosilite to Species.Carbon,
            Species.Ilmenite to Species.Carbon,
        )
        for ((oxide, reductant) in otherReductions) {
            val row = REACTIONS.first { r ->
                r.principal == oxide && r.reagents.any { it.first == reductant }
            }
            assertTrue(
                row.enthalpyPerKg > 0L,
                "$oxide + $reductant should cost energy; only the titanium row gives it back",
            )
        }
    }

    // ── Conservation across awkward masses ───────────────────────────────────

    @Test
    fun theProductsAlwaysWeighExactlyWhatWentIn() {
        // Structural conservation, across awkward masses because a rounding rule that closes on round
        // numbers and leaks on odd ones is the failure this construction exists to make impossible.
        val masses = longArrayOf(
            1L, 7L, 999L, 1_000_003L, Budget.KILOGRAM, 3L * Budget.KILOGRAM + 1L,
        )
        for (reaction in REACTIONS) {
            for (total in masses) {
                assertEquals(
                    total, reaction.split(total).sum(),
                    "${reaction.principal}'s row does not conserve at $total",
                )
            }
        }
    }

    @Test
    fun theSplitFollowsTheFormulaAndNotAGuess() {
        // Calcite is the one everybody can check by hand: CaCO3 is 100, CaO is 56, CO2 is 44. So a
        // hundred kilograms of limestone gives fifty-six of quicklime and forty-four of gas, and if
        // it ever gives anything else the formula and the arithmetic have parted company.
        val calcite = REACTIONS.first { it.principal == Species.Calcite }
        val parts = calcite.split(100L * Budget.KILOGRAM)
        assertEquals(56L * Budget.KILOGRAM, parts[0], "the lime")
        assertEquals(44L * Budget.KILOGRAM, parts[1], "the carbon dioxide")
    }
}
