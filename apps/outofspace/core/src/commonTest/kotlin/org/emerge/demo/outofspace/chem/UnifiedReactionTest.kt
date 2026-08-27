package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.Budget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The whole of the game's chemistry, checked once** — increment 4 of
 * `PLAN_unified_reactions.md`.
 *
 * These checks used to exist three times over. `CombustionTest`, `OxidationContentionTest`,
 * `DecompositionTest` and `ReductionTest` each closed their own table atom by atom, each against
 * their own class's field names, and each with its own copy of [atomsOf]. Four tables, four
 * near-identical proofs — and the moment a fifth table arrived it had none, which is exactly how six
 * gas fires reached a live save without the reference knowing they existed.
 *
 * There is one table now, so there is one proof, and it covers every row automatically. A row added
 * to [REACTIONS] is checked on the commit that adds it whether or not anybody remembers this file.
 *
 * ⚠️ **The per-class tests that remain are about their tables as *data*** — the chain [REDUCTIONS]
 * describes, the temperatures [DECOMPOSITIONS] separates a feed by. Those are still worth saying and
 * are not said here.
 */
class UnifiedReactionTest {

    /**
     * A species as its elements. An element is one atom of itself; a compound is its formula.
     *
     * The fallback divides the molar mass by the atomic mass, which is right for a diatomic gas and
     * is why `O₂` reads as two oxygens without [MINERALS] having to carry it.
     */
    private fun atomsOf(species: Species): Map<Species, Int> =
        MINERALS[species] ?: mapOf(species to if (species.molarMass == species.atomicMass) 1 else species.molarMass / species.atomicMass)

    private fun sideAtoms(side: List<Pair<Species, Int>>): Map<Species, Int> {
        val out = mutableMapOf<Species, Int>()
        for ((species, units) in side) {
            for ((element, n) in atomsOf(species)) out[element] = (out[element] ?: 0) + n * units
        }
        return out
    }

    private fun sideMass(side: List<Pair<Species, Int>>): Long {
        var sum = 0L
        for ((species, units) in side) sum += units.toLong() * species.molarMass
        return sum
    }

    private fun Reaction.formula(): String =
        reagents.joinToString(" + ") { "${it.second} ${it.first}" } + " -> " +
            products.joinToString(" + ") { "${it.second} ${it.first}" }

    // ── The table is arithmetic, not prose ───────────────────────────────────

    @Test
    fun everyReactionBalancesAtomByAtom() {
        // ⛔ The check nothing else can make. A row that balances by eye and not by arithmetic yields
        // the wrong amount of the right thing, for ever, and silently — every other test in the
        // package would pass on it.
        for (reaction in REACTIONS) {
            assertEquals(
                sideAtoms(reaction.reagents),
                sideAtoms(reaction.products),
                "${reaction.formula()} does not balance",
            )
        }
    }

    @Test
    fun everyReactionWeighsTheSameOnBothSides() {
        // Implied by the atom balance, and worth stating separately because it is the property the
        // *arithmetic* relies on: `split` hands the reagents' whole mass out across the products, so
        // a row whose sides weighed differently would create or destroy matter at a rate nothing
        // measures.
        //
        // ⚠️ This is what makes the catalyst safe. Photosynthesis carries a hundred units of algae
        // in and a hundred and one out — 18372 g each side — and if it did not close, a bloom would
        // be matter appearing from nowhere every pass.
        for (reaction in REACTIONS) {
            assertEquals(
                sideMass(reaction.reagents),
                sideMass(reaction.products),
                "${reaction.formula()} does not weigh the same on both sides",
            )
        }
    }

    @Test
    fun everyRowConsumesItsOwnPrincipal() {
        // The principal is what the rate is a fraction of, what the enthalpy is per kilogram of, and
        // which store the products land in. A row that did not actually consume it would be quoting
        // all three against a bystander.
        for (reaction in REACTIONS) {
            assertTrue(
                reaction.reagents.any { it.first == reaction.principal },
                "${reaction.formula()} names ${reaction.principal} as principal and does not consume it",
            )
        }
    }

    @Test
    fun everyEnthalpyIsQuotedAgainstItsOwnFormulaMass() {
        // ⚠️ **The one number a reader cannot check by eye is which unit an enthalpy is per.** Every
        // row is written `<kJ> * kJPerMolAt(<grams>)`, and the grams must be the principal's formula
        // mass — `principalUnits * molarMass` — or the row claims a multiple of the energy it
        // should. Photosynthesis was six times out this way once, quoted against one water instead
        // of its six.
        //
        // Recovered by dividing back: if the divisor was right, the result is a whole kJ/mol.
        for (reaction in REACTIONS) {
            if (reaction.enthalpyPerKg == 0L) continue
            // `ReductionTest`'s construction, and it has to be that one: dividing back through
            // `kJPerMolAt` is the only form that recovers the same flooring the row was written
            // with. Recomputing the divisor by hand rounds differently and reads as a broken row.
            val formulaMass = reaction.principalUnits.toLong() * reaction.principal.molarMass
            val kJPerFormulaUnit = reaction.enthalpyPerKg / kJPerMolAt(formulaMass)
            assertTrue(
                kJPerFormulaUnit != 0L,
                "${reaction.formula()} has an enthalpy that rounds to nothing; is its divisor right?",
            )
            assertEquals(
                reaction.enthalpyPerKg,
                kJPerFormulaUnit * kJPerMolAt(formulaMass),
                "${reaction.formula()} is not a whole number of kJ/mol over $formulaMass g",
            )
        }
    }

    @Test
    fun everyOnsetIsAboveAbsoluteZero() {
        // A zero onset makes `rateMultiplier` divide by it, and a negative one is a reaction that
        // runs in a vacuum flask at the bottom of the universe.
        for (reaction in REACTIONS) {
            assertTrue(reaction.onsetKelvin > 0, "${reaction.formula()} has an onset of ${reaction.onsetKelvin}K")
            assertTrue(reaction.baseRate > 0L, "${reaction.formula()} has no rate")
        }
    }

    // ── Rate and starvation ──────────────────────────────────────────────────

    private fun rowFor(principal: Species, other: Species?): Reaction =
        REACTIONS.first { r ->
            r.principal == principal && (other == null || r.reagents.any { it.first == other })
        }

    /** What one pass of [row] takes, given everything it could want. */
    private fun unstarved(row: Reaction, present: Long, kelvin: Int): LongArray {
        val allowed = LongArray(row.reagents.size) { Long.MAX_VALUE / 4 }
        val taken = LongArray(row.reagents.size)
        row.react(present, allowed, kelvin, taken)
        return taken
    }

    @Test
    fun coldMatterDoesNotReact() {
        val burn = rowFor(Species.Carbon, Species.Oxygen)
        assertEquals(0L, unstarved(burn, 100L * Budget.KILOGRAM, CARBON_IGNITION_KELVIN - 1)[0])
        assertTrue(unstarved(burn, 100L * Budget.KILOGRAM, CARBON_IGNITION_KELVIN)[0] > 0L)
    }

    @Test
    fun aStuffyRoomSlowsTheFireRatherThanBreakingIt() {
        // ⛔ Starved of a reagent, the row must stay on the stoichiometric line — react *less*, not
        // react rich. A fire that took the carbon it wanted and only the oxygen it could get would
        // break the atom balance in the direction where it still looks like it is working.
        val burn = rowFor(Species.Carbon, Species.Oxygen)
        val oxygenIndex = burn.reagents.indexOfFirst { it.first == Species.Oxygen }
        val carbonIndex = burn.principalIndex

        val allowed = LongArray(burn.reagents.size) { Long.MAX_VALUE / 4 }
        allowed[oxygenIndex] = 1L * Budget.KILOGRAM
        val taken = LongArray(burn.reagents.size)
        burn.react(1000L * Budget.KILOGRAM, allowed, 2000, taken)

        assertTrue(taken[oxygenIndex] <= 1L * Budget.KILOGRAM, "it took oxygen it was not allowed")
        assertTrue(taken[carbonIndex] > 0L, "it did not react at all")
        // C + O2 -> CO2: 12 g of carbon per 32 g of oxygen, so the carbon is 12/32 of the oxygen.
        val expected = taken[oxygenIndex] * 12L / 32L
        assertTrue(
            taken[carbonIndex] <= expected + 1L && taken[carbonIndex] >= expected - 1L,
            "it ran rich: ${taken[carbonIndex]} carbon against ${taken[oxygenIndex]} oxygen",
        )
    }

    @Test
    fun aFireNeverConsumesMoreThanIsThere() {
        // The rate clamp. At a hundred thousand kelvin the fraction would exceed one, and the honest
        // reading of "hot enough to burn faster than it can be supplied" is that it all goes.
        val burn = rowFor(Species.Carbon, Species.Oxygen)
        val carbon = 1000L * Budget.KILOGRAM
        assertTrue(unstarved(burn, carbon, 100_000)[burn.principalIndex] <= carbon)
    }

    @Test
    fun aTraceReactsNothingRatherThanARoundedGram() {
        // ⚠️ A microgram of reagent must not round *up* into a reaction. See
        // `reference_oos_microgram_deadlock`: a site two micrograms short reads 99% for ever.
        val burn = rowFor(Species.Carbon, Species.Oxygen)
        assertTrue(unstarved(burn, 1L, 800)[burn.principalIndex] <= 1L)
    }

    // ── Contention is an outcome, not a priority list ────────────────────────

    @Test
    fun carbonOutbidsIronForTheSameOxygen() {
        // ⚠️ **"The oxygen attacks the carbon first" is a consequence of two base rates meeting**,
        // not a rule anybody wrote. Carbon's is ten times iron's, so at a shared temperature it asks
        // for the larger share of a scarce supply and gets it. There is no priority order to
        // consult, and adding one would make the physics stop explaining itself.
        val burn = rowFor(Species.Carbon, Species.Oxygen)
        val rust = rowFor(Species.Iron, Species.Oxygen)
        val hot = 900
        val mass = 100L * Budget.KILOGRAM

        val carbonWants = burn.reagentFor(
            burn.reagents.indexOfFirst { it.first == Species.Oxygen },
            burn.consumed(mass, hot),
        )
        val ironWants = rust.reagentFor(
            rust.reagents.indexOfFirst { it.first == Species.Oxygen },
            rust.consumed(mass, hot),
        )
        assertTrue(carbonWants > ironWants, "iron asked for as much oxygen as carbon: $ironWants vs $carbonWants")
    }

    @Test
    fun coldIronDoesNotScale() {
        val rust = rowFor(Species.Iron, Species.Oxygen)
        assertEquals(0L, rust.consumed(100L * Budget.KILOGRAM, IRON_OXIDATION_KELVIN - 1))
        assertTrue(rust.consumed(100L * Budget.KILOGRAM, IRON_OXIDATION_KELVIN) > 0L)
    }
}
