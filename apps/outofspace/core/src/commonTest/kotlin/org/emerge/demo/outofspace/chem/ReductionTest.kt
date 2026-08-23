package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.Budget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reduction table, checked the way `DecompositionTest` checks the decomposition table —
 * increment 5 of `PLAN_ambient_chemistry.md`.
 *
 * Same argument, and it applies harder here. A reduction has *two* reagents and three or four
 * products, so there are more places for a formula unit to be wrong and no more chance of anybody
 * noticing by eye: the row would still run, still conserve mass, and quietly yield the wrong amount
 * of titanium for ever. So nothing is asserted against recorded output — every row balances atom by
 * atom against [MINERALS], which is the table [Species.molarMass] is itself derived from.
 */
class ReductionTest {

    /** Atoms in one formula unit, for an element as well as a mineral. `DecompositionTest`'s. */
    private fun atomsOf(species: Species): Map<Species, Int> =
        MINERALS[species] ?: mapOf(species to if (species.molarMass == species.atomicMass) 1 else species.molarMass / species.atomicMass)

    // ── The table balances ───────────────────────────────────────────────────

    @Test
    fun everyReductionBalancesAtomByAtom() {
        for (reaction in REDUCTIONS) {
            val left = mutableMapOf<Species, Int>()
            for ((element, n) in atomsOf(reaction.oxide)) {
                left[element] = (left[element] ?: 0) + n * reaction.oxideUnits
            }
            for ((element, n) in atomsOf(reaction.reductant)) {
                left[element] = (left[element] ?: 0) + n * reaction.reductantUnits
            }

            val right = mutableMapOf<Species, Int>()
            for ((product, units) in reaction.products) {
                for ((element, n) in atomsOf(product)) {
                    right[element] = (right[element] ?: 0) + n * units
                }
            }

            assertEquals(
                left, right,
                "${reaction.oxideUnits} ${reaction.oxide} + ${reaction.reductantUnits} ${reaction.reductant} -> " +
                    reaction.products.joinToString(" + ") { "${it.second} ${it.first}" } +
                    " does not balance",
            )
        }
    }

    @Test
    fun everyReductionWeighsTheSameOnBothSides() {
        // Implied by the atom balance, and stated separately because it is the property the
        // *arithmetic* leans on: `split` hands out shares of what the two reagents weighed between
        // them, so a row whose products weighed more than its inputs would be silently rescaled to
        // fit rather than failing. This is the assertion that notices.
        for (reaction in REDUCTIONS) {
            val left = reaction.oxideUnits.toLong() * reaction.oxide.molarMass +
                reaction.reductantUnits.toLong() * reaction.reductant.molarMass
            val right = reaction.products.sumOf { (product, units) -> units.toLong() * product.molarMass }
            assertEquals(left, right, "${reaction.oxide} + ${reaction.reductant} does not weigh what it becomes")
        }
    }

    @Test
    fun everyEnthalpyIsQuotedAgainstItsOwnOxideFormulaMass() {
        // `DecompositionTest`'s check, with the one difference that matters: that table could assert
        // "positive", because heat-driven decomposition is endothermic without exception. This table
        // has a row that gives energy back, and the whole reason it exists is that it does. So the
        // sign is not asserted here — `magnesiothermicTitaniumIsTheExothermicRow` states it as the
        // specific claim it is, rather than as a table-wide rule that would have to be weakened to
        // let the interesting row through.
        for (reaction in REDUCTIONS) {
            val formulaMass = reaction.oxideUnits.toLong() * reaction.oxide.molarMass
            val kJPerFormulaUnit = reaction.enthalpyPerKg / kJPerMolAt(formulaMass)
            assertTrue(kJPerFormulaUnit != 0L, "${reaction.oxide}'s enthalpy rounds to nothing; is its divisor right?")
            assertEquals(
                reaction.enthalpyPerKg,
                kJPerFormulaUnit * kJPerMolAt(formulaMass),
                "${reaction.oxide}'s enthalpy is not a whole number of kJ/mol over its own formula mass",
            )
        }
    }

    @Test
    fun magnesiothermicTitaniumIsTheExothermicRow() {
        // The claim the chain rests on, asserted as a claim. Magnesium wants oxygen badly enough that
        // taking titania apart with it pays for itself, which is exactly why magnesium is the
        // reductant and carbon is not — and if this row ever came out endothermic, the argument for
        // the two rows that exist only to *make* the magnesium would have quietly evaporated.
        val titanium = REDUCTIONS.single { it.products.any { (species, _) -> species == Species.Titanium } }
        assertTrue(titanium.enthalpyPerKg < 0L, "magnesiothermic reduction of titania should release energy")
        assertTrue(titanium.enthalpy(Budget.KILOGRAM) < 0L, "the sign did not survive `perKilogram`")

        for (reaction in REDUCTIONS) {
            if (reaction === titanium) continue
            assertTrue(
                reaction.enthalpyPerKg > 0L,
                "${reaction.oxide} + ${reaction.reductant} should cost energy; only the titanium row gives it back",
            )
        }
    }

    // ── The chain closes ─────────────────────────────────────────────────────

    @Test
    fun everyReductantIsEitherNativeOrMadeByAnEarlierRow() {
        // The property that makes this a *chain* rather than four rows with a titanium at the end. A
        // reductant nothing produces and no rock contains is a reaction that can never run, which is
        // precisely the state titanium itself was in before this table existed — and the mistake is
        // trivially easy to repeat by adding a row that reduces something with aluminium.
        val produced = REDUCTIONS.flatMap { row -> row.products.map { it.first } }.toSet() +
            DECOMPOSITIONS.flatMap { row -> row.products.map { it.first } }.toSet()

        for (reaction in REDUCTIONS) {
            val native = reaction.reductant.relativeAbundance > 0
            assertTrue(
                native || reaction.reductant in produced,
                "${reaction.reductant} reduces ${reaction.oxide} but nothing can produce it and no rock contains it",
            )
        }
    }

    @Test
    fun titaniumIsReachableFromRocksAndCarbonAlone() {
        // The acceptance test for the whole increment, stated as reachability rather than as a
        // simulation: start from what an extractor can actually dig up, close over both tables, and
        // insist titanium falls out. It is the question this work began with — "is there a pathway to
        // produce all of the species the machines need?" — and it is worth being able to ask it of
        // the tables directly rather than by playing.
        val reachable = Species.NATURAL.toMutableSet()

        // Closure. Bounded by the table sizes, so a fixed number of sweeps reaches the fixed point.
        repeat(REDUCTIONS.size + DECOMPOSITIONS.size) {
            for (row in DECOMPOSITIONS) {
                if (row.reactant in reachable) reachable += row.products.map { it.first }
            }
            for (row in REDUCTIONS) {
                if (row.oxide in reachable && row.reductant in reachable) reachable += row.products.map { it.first }
            }
        }

        assertTrue(Species.Silicon in reachable, "silicon is not reachable")
        assertTrue(Species.Magnesium in reachable, "magnesium is not reachable")
        assertTrue(Species.Titanium in reachable, "TITANIUM is not reachable — the chain is broken")
    }

    @Test
    fun everySpeciesTheVesselIsBuiltFromCanBeMade() {
        // The stronger form, and the one that would have caught the aluminium problem on the day
        // Firebrick was written: every species in every buildable material has to be reachable, or
        // there is a machine in the build menu that the vessel can never replace.
        val reachable = Species.NATURAL.toMutableSet()
        repeat(REDUCTIONS.size + DECOMPOSITIONS.size) {
            for (row in DECOMPOSITIONS) {
                if (row.reactant in reachable) reachable += row.products.map { it.first }
            }
            for (row in REDUCTIONS) {
                if (row.oxide in reachable && row.reductant in reachable) reachable += row.products.map { it.first }
            }
        }

        for (material in org.emerge.demo.outofspace.world.Material.entries) {
            for (species in Species.ALL) {
                if (material.composition[species] <= 0L) continue
                assertTrue(
                    species in reachable,
                    "${material.label} needs $species and nothing in the game can produce it",
                )
            }
        }
    }

    // ── The arithmetic conserves ─────────────────────────────────────────────

    @Test
    fun theProductsAlwaysWeighExactlyWhatWentIn() {
        // Structural conservation, across awkward masses because a rounding rule that closes on round
        // numbers and leaks on odd ones is the failure this construction exists to make impossible.
        for (reaction in REDUCTIONS) {
            for (total in longArrayOf(1L, 7L, 999L, 1_000_003L, Budget.KILOGRAM, 3L * Budget.KILOGRAM + 1L)) {
                assertEquals(
                    total, reaction.split(total).sum(),
                    "${reaction.oxide} + ${reaction.reductant} does not conserve at $total",
                )
            }
        }
    }

    @Test
    fun aStarvedReactionStaysOnTheStoichiometricLine() {
        // The starvation path, which is where a reduction would break the atom balance if it were
        // going to. Give each row far more oxide than its reagent allowance covers and insist the two
        // masses that come back are still in the ratio the formula states — never rich, and never
        // taking reagent for oxide that did not react.
        for (reaction in REDUCTIONS) {
            val hot = reaction.onsetKelvin * 2
            val allowance = 1_000L * Budget.GRAM
            val done = reaction.react(1_000_000L * Budget.KILOGRAM, allowance, hot)

            assertTrue(!done.isNothing, "${reaction.oxide} did not react at all when starved")
            assertTrue(done.reductant <= allowance, "${reaction.oxide} took more reagent than it was allowed")

            // reductant/oxide == reductantNumerator/reductantDenominator, to within one flooring.
            val expected = scaledRatioForTest(reaction.reductantNumerator, reaction.reductantDenominator, done.oxide)
            assertEquals(
                expected, done.reductant,
                "${reaction.oxide} + ${reaction.reductant} ran off the stoichiometric line when starved",
            )
        }
    }

    @Test
    fun nothingHappensBelowTheOnset() {
        for (reaction in REDUCTIONS) {
            val cold = reaction.onsetKelvin - 1
            assertEquals(0L, reaction.demand(1_000L * Budget.KILOGRAM, 0L, cold), "${reaction.oxide} wanted reagent while cold")
            assertTrue(
                reaction.react(1_000L * Budget.KILOGRAM, 1_000L * Budget.KILOGRAM, cold).isNothing,
                "${reaction.oxide} reacted while cold",
            )
        }
    }

    // ── Contention is per reagent, not per tile ──────────────────────────────

    @Test
    fun rowsAfterDifferentReagentsAreNotInTheSameGroup() {
        // The one structural claim the sweep depends on. Quartz and ilmenite are both after the
        // carbon and must share; neither has any claim on the magnesium. Pooling all four against one
        // number would starve rows that were never in competition — and would change the answer
        // whenever an unrelated row was added, which is the kind of coupling nobody would look for.
        assertEquals(
            REDUCTIONS.size, REDUCTION_GROUPS.sumOf { it.rows.size },
            "grouping lost or duplicated a row",
        )
        for (group in REDUCTION_GROUPS) {
            for (row in group.rows) {
                assertEquals(group.reductant, row.reductant, "a row is filed under a reagent it does not eat")
            }
        }
        assertEquals(
            REDUCTIONS.map { it.reductant }.distinct().size, REDUCTION_GROUPS.size,
            "one reagent should be one group",
        )

        val carbon = REDUCTION_GROUPS.single { it.reductant == Species.Carbon }
        assertTrue(carbon.rows.size >= 2, "carbon should be contended; it is what makes the grouping load-bearing")
    }

    /** `scaledRatio`, restated so the assertion above does not simply call the code under test. */
    private fun scaledRatioForTest(numerator: Long, denominator: Long, scale: Long): Long =
        (scale.toDouble() * numerator.toDouble() / denominator.toDouble()).toLong()
}
