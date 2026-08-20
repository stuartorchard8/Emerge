package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.Budget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two reactions, one tile's oxygen — increment 2 of `PLAN_ambient_chemistry.md`, as arithmetic.
 *
 * The arithmetic half is here; the sweep that actually hands the oxygen out is tested against a
 * running vessel in `AmbientChemistryTest`. What this file is for is the two properties that make
 * contention *safe* rather than merely present: every reaction closes atom by atom against the
 * molar masses `Species` already holds, and no reaction can take more of a shared reagent than it
 * was allowed.
 */
class OxidationContentionTest {

    // ── Stoichiometry ────────────────────────────────────────────────────────

    @Test
    fun everyReactionClosesAtomByAtom() {
        // The `MineralTest` argument applied to reactions: the formula units are what is written
        // down, so the mass on each side is *derived*, and a typo in the units is a test failure
        // here rather than a reaction that quietly runs at the wrong ratio forever. Nothing in the
        // simulation may hold a hand-written mass fraction.
        for (reaction in OXIDATIONS) {
            val reactants =
                reaction.reactantUnits.toLong() * reaction.reactant.molarMass +
                    reaction.oxygenUnits.toLong() * Species.Oxygen.molarMass
            val products = reaction.productUnits.toLong() * reaction.product.molarMass
            assertEquals(
                reactants,
                products,
                "${reaction.reactant} + O₂ → ${reaction.product} does not balance",
            )
        }
    }

    @Test
    fun aProductThatIsAFluidIsTheOnlyKindThatLeaves() {
        // Phase is asked of `Species.fluid` rather than stated on the reaction, so there is one
        // place for it. If that ever stops being true, CO₂ stays on the belt and iron scale is
        // pumped into the room, and both are silent.
        assertTrue(CARBON_BURN.productIsGas, "carbon dioxide did not leave the solid")
        assertTrue(!IRON_RUST.productIsGas, "iron scale was put into the atmosphere")
    }

    // ── The allowance is a hard bound ────────────────────────────────────────

    @Test
    fun aReactionNeverTakesMoreOxygenThanItWasAllowed() {
        // The property the whole demand pass rests on. If `react` could exceed its allowance, the
        // apportionment above it would be decoration and the tile would go oxygen-negative — which
        // the air ledger reports as a leak somewhere else entirely.
        val hot = 2000
        for (reaction in OXIDATIONS) {
            val plenty = 1000L * Budget.KILOGRAM
            val wanted = reaction.demand(plenty, hot)
            assertTrue(wanted > 0L, "${reaction.reactant} wanted no oxygen at ${hot}K")

            val allowance = wanted / 3L
            val reacted = reaction.react(plenty, allowance, hot)
            assertTrue(
                reacted.oxygen <= allowance,
                "${reaction.reactant} took ${reacted.oxygen} of an allowance of $allowance",
            )
            assertTrue(reacted.reactant > 0L, "a third of what it wanted reacted nothing at all")
            assertEquals(
                reacted.reactant + reacted.oxygen,
                reacted.product,
                "the product does not weigh its own parts",
            )
        }
    }

    @Test
    fun whatARationedReactionAsksForIsWhatItTakesWhenNothingIsScarce() {
        // Demand and reaction have to agree, or the apportionment is dividing up a number that has
        // nothing to do with what the reactions then take: too high and oxygen sits unused in a
        // tile that is starving, too low and the shares do not add up to the supply.
        val hot = 1200
        for (reaction in OXIDATIONS) {
            val present = 500L * Budget.KILOGRAM
            val wanted = reaction.demand(present, hot)
            val reacted = reaction.react(present, wanted, hot)
            assertEquals(
                wanted,
                reacted.oxygen,
                "${reaction.reactant} asked for $wanted and took ${reacted.oxygen}",
            )
        }
    }

    // ── Preference is a consequence ──────────────────────────────────────────

    @Test
    fun carbonOutbidsIronForTheSameOxygen() {
        // Decision 2's flavour — "the oxygen attacks the carbon first" — must fall out of the rates
        // rather than out of a priority list. Equal masses, one temperature, both well above their
        // onsets: carbon simply wants more, so an apportionment gives it more. Nothing anywhere
        // consults the order of `OXIDATIONS`, and this is the assertion that would notice if it did.
        val equal = 100L * Budget.KILOGRAM
        val hot = 1400

        val carbonWants = CARBON_BURN.demand(equal, hot)
        val ironWants = IRON_RUST.demand(equal, hot)
        assertTrue(carbonWants > 0L && ironWants > 0L, "one of them was not running at ${hot}K")
        assertTrue(carbonWants > ironWants, "iron outbid carbon: $ironWants vs $carbonWants")

        // And the apportionment of a scarce tile follows the demands, in that order.
        val scarce = (carbonWants + ironWants) / 4L
        val shares = apportion(longArrayOf(carbonWants, ironWants), scarce)
        assertEquals(scarce, shares[0] + shares[1], "the shares do not add up to the oxygen there was")
        assertTrue(shares[0] > shares[1], "the larger demand got the smaller share")
    }

    @Test
    fun coldIronDoesNotScale() {
        // A room-temperature vessel must not quietly turn into ore. The onset is the whole guard —
        // see `IRON_RUST` for why dry oxidation is modelled and wet corrosion is not.
        assertEquals(0L, IRON_RUST.demand(100L * Budget.KILOGRAM, 293))
        assertEquals(0L, IRON_RUST.demand(100L * Budget.KILOGRAM, IRON_OXIDATION_KELVIN - 1))
        assertTrue(IRON_RUST.demand(100L * Budget.KILOGRAM, IRON_OXIDATION_KELVIN) > 0L)
    }
}
