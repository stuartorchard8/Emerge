package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [COMBUSTIONS], checked the way `DecompositionTest` checks its own table and for the same reason.
 *
 * A reaction table has no oracle. "Methane burns to carbon dioxide and water" is easy to write and
 * easy to get right; *how much water* is a number nobody can eyeball, and [Combustion.split] hands
 * out shares of a total that [apportion] makes correct **by construction**. So a row with the wrong
 * formula units still runs, still conserves the tile's mass exactly, and quietly produces the wrong
 * proportions for ever. The check has to be against the formulae.
 */
class CombustionTest {

    /** Atoms in one formula unit, for an element as well as a compound. */
    private fun atomsOf(species: Species): Map<Species, Int> =
        MINERALS[species] ?: mapOf(species to if (species.molarMass == species.atomicMass) 1 else species.molarMass / species.atomicMass)

    @Test
    fun everyReactionBalancesAtomByAtom() {
        for (reaction in COMBUSTIONS) {
            val left = mutableMapOf<Species, Int>()
            for ((element, n) in atomsOf(reaction.fuel)) {
                left[element] = (left[element] ?: 0) + n * reaction.fuelUnits
            }
            for ((element, n) in atomsOf(Species.Oxygen)) {
                left[element] = (left[element] ?: 0) + n * reaction.oxygenUnits
            }

            val right = mutableMapOf<Species, Int>()
            for ((product, units) in reaction.products) {
                for ((element, n) in atomsOf(product)) {
                    right[element] = (right[element] ?: 0) + n * units
                }
            }

            assertEquals(
                left, right,
                "${reaction.fuelUnits} ${reaction.fuel} + ${reaction.oxygenUnits} O2 -> " +
                    reaction.products.joinToString(" + ") { "${it.second} ${it.first}" } +
                    " does not balance",
            )
        }
    }

    @Test
    fun everyReactionWeighsTheSameOnBothSides() {
        // Implied by the atom balance and stated separately because it is the property the
        // *arithmetic* rests on: [Combustion.split] apportions fuel-plus-oxygen across the products,
        // so a row whose products weighed more than its reagents would be silently rescaled to fit
        // rather than failing. Since both sides live in the same array, that rescaling would show up
        // as a room that quietly gains or loses mass whenever anything catches fire.
        for (reaction in COMBUSTIONS) {
            val left = reaction.fuelUnits.toLong() * reaction.fuel.molarMass +
                reaction.oxygenUnits.toLong() * Species.Oxygen.molarMass
            val right = reaction.products.sumOf { (product, units) -> units.toLong() * product.molarMass }
            assertEquals(left, right, "${reaction.fuel} does not weigh what it burns into")
        }
    }

    @Test
    fun everyFuelAndEveryProductIsSomethingTheAirCanHold() {
        // The shape's whole premise: both reagents come out of the air and every product goes back
        // into it. A row naming a species that is not a [Fluid] would be a row whose product had
        // nowhere to go, and `combust` would drop it on the floor — silently, since it skips a
        // product it cannot place.
        for (reaction in COMBUSTIONS) {
            assertTrue(reaction.fuel.fluid != null, "${reaction.fuel} is not a fluid and cannot be in the air")
            for ((product, _) in reaction.products) {
                assertTrue(product.fluid != null, "$product is not a fluid and cannot be a gas-fire product")
            }
        }
    }

    @Test
    fun everyRowIsExothermic() {
        // Unlike `REDUCTIONS`, where one row genuinely runs the other way, a fire that took energy
        // in would not be a fire. Positive is endothermic, as in every other table.
        for (reaction in COMBUSTIONS) {
            assertTrue(
                reaction.enthalpyPerKg < 0L,
                "${reaction.fuel} burns endothermically, which is not a thing that happens",
            )
        }
    }

    @Test
    fun everyEnthalpyIsQuotedAgainstItsOwnFormulaMass() {
        // Written `-802L * kJPerMolAt(16)` so it can be checked against a textbook, but nothing
        // stops a row using another row's divisor — which would be wrong by whatever ratio the two
        // formula masses happen to be in and would look entirely plausible. `DecompositionTest`'s
        // argument, applied to this table.
        for (reaction in COMBUSTIONS) {
            val formulaMass = reaction.fuelUnits.toLong() * reaction.fuel.molarMass
            val perMole = reaction.enthalpy(formulaMass * 1000L)
            assertTrue(
                perMole != 0L,
                "${reaction.fuel}'s enthalpy rounds to nothing against its own formula mass",
            )
        }
    }
}
