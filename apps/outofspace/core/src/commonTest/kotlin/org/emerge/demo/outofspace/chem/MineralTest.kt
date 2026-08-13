package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The species table's internal consistency, checked rather than trusted.
 *
 * A hand-authored table of ~150 physical constants will contain typos, and the failure mode is
 * silent: a mineral with the wrong molar mass does not crash, it just weighs wrong forever. So the
 * numbers are arranged to have an **oracle** — [Species.molarMass] for a compound is derivable from
 * its formula, and this asserts the two agree. Nothing here pins a literal; every expected value is
 * computed from something else in the table.
 */
class MineralTest {

    /**
     * Declared molar mass equals the sum of the formula's atoms.
     *
     * This is the load-bearing one. It catches a wrong atom count, a wrong element, and a mistyped
     * molar mass, and it does so without anyone having to state the right answer twice.
     */
    @Test
    fun everyMineralWeighsWhatItsFormulaSays() {
        val wrong = MINERALS.keys
            .filter { it.molarMass != derivedMolarMass(it) }
            .map { "${it.name}: declared ${it.molarMass}, formula gives ${derivedMolarMass(it)}" }
        assertTrue(wrong.isEmpty(), "molar mass disagrees with formula:\n  " + wrong.joinToString("\n  "))
    }

    /** A formula naming a compound as an ingredient would mean decomposition never terminates. */
    @Test
    fun formulaeContainOnlyElements() {
        val nested = MINERALS.entries.flatMap { (mineral, formula) ->
            formula.keys.filter { it in COMPOUNDS }.map { "${mineral.name} contains compound ${it.name}" }
        }
        assertTrue(nested.isEmpty(), nested.joinToString("\n  "))
    }

    /** No formula may name a species zero or a negative number of times. */
    @Test
    fun atomCountsArePositive() {
        for ((mineral, formula) in MINERALS) {
            for ((element, atoms) in formula) {
                assertTrue(atoms > 0, "${mineral.name} has $atoms of ${element.name}")
            }
        }
    }

    /**
     * **Every naturally-occurring element is obtainable.**
     *
     * The claim the table exists to support: for each element, either it occurs native (a non-zero
     * [Species.relativeAbundance]) or some mineral that occurs in rock decomposes into it. An
     * element failing this is one the player can name but never hold.
     */
    @Test
    fun everyElementHasASource() {
        val fromMinerals = Species.NATURAL.flatMap { MINERALS[it]?.keys.orEmpty() }.toSet()
        val fromSuites = Species.NATURAL.flatMap { LANTHANIDE_SUITE[it]?.keys.orEmpty() }.toSet()
        val native = Species.NATURAL.filter { it.isElement }.toSet()
        val obtainable = fromMinerals + fromSuites + native

        val orphans = Species.ALL.filter { it.isElement && it !in obtainable }.map { it.name }
        assertTrue(orphans.isEmpty(), "elements with no source: ${orphans.joinToString()}")
    }

    /** A mineral no rock contains is a mineral the player can never mine — dead weight in the table. */
    @Test
    fun everyMineralOccursInRock() {
        val unreachable = MINERALS.keys.filter { it.relativeAbundance == 0 }.map { it.name }
        assertTrue(unreachable.isEmpty(), "minerals with no abundance: ${unreachable.joinToString()}")
    }

    /** Only compounds, ices and genuinely native elements belong in a rock. */
    @Test
    fun nativeElementsAreOnlyTheOnesThatOccurNative() {
        val expected = setOf(
            Species.Iron, Species.Nickel, Species.Copper, Species.Carbon, Species.Sulfur,
            Species.Gold, Species.Silver, Species.Platinum, Species.Palladium,
            Species.Iridium, Species.Osmium,
            Species.Nitrogen, Species.Hydrogen, Species.Argon,
            Species.Helium, Species.Neon, Species.Krypton, Species.Xenon,
        )
        assertEquals(expected, Species.NATURAL.filter { it.isElement }.toSet())
    }

    /**
     * The two iron oxides, which are the reason the model changed.
     *
     * Iron(II) oxide is 1:1 and iron(III) is 2:3, so the same tonne of rock yields materially
     * different amounts of iron depending on which one it is. Both expectations are derived from
     * atomic masses here rather than pinned, so this documents the arithmetic instead of freezing it.
     */
    @Test
    fun theTwoIronOxidesYieldDifferently() {
        val wustite = massPartsPerThousand(Species.Wustite)
        val hematite = massPartsPerThousand(Species.Hematite)

        val ironAtom = Species.Iron.atomicMass
        val oxygenAtom = Species.Oxygen.atomicMass

        assertEquals(ironAtom * 1000 / (ironAtom + oxygenAtom), wustite[Species.Iron])
        assertEquals(2 * ironAtom * 1000 / (2 * ironAtom + 3 * oxygenAtom), hematite[Species.Iron])

        assertTrue(
            wustite.getValue(Species.Iron) > hematite.getValue(Species.Iron),
            "the 1:1 oxide must be the richer iron ore",
        )
    }

    /** A suite that summed to 998 would lose two parts per thousand of every rare earth in the game. */
    @Test
    fun lanthanideSuitesAreWhole() {
        for ((mineral, suite) in LANTHANIDE_SUITE) {
            assertEquals(1000, suite.values.sum(), "${mineral.name} suite")
        }
    }

    /** A suite describes the rare-earth site, so everything in one must be a rare earth. */
    @Test
    fun lanthanideSuitesHoldOnlyRareEarths() {
        val rareEarths = setOf(
            Species.Yttrium,
            Species.Lanthanum, Species.Cerium, Species.Praseodymium, Species.Neodymium,
            Species.Samarium, Species.Europium, Species.Gadolinium, Species.Terbium,
            Species.Dysprosium, Species.Holmium, Species.Erbium, Species.Thulium,
            Species.Ytterbium, Species.Lutetium,
        )
        for ((mineral, suite) in LANTHANIDE_SUITE) {
            val strays = suite.keys.filterNot { it in rareEarths }.map { it.name }
            assertTrue(strays.isEmpty(), "${mineral.name} suite holds ${strays.joinToString()}")
        }
    }

    /** Mass parts describe a split, so they cannot exceed the whole. */
    @Test
    fun massPartsNeverExceedTheWhole() {
        for (mineral in MINERALS.keys) {
            val sum = massPartsPerThousand(mineral).values.sum()
            assertTrue(sum in 990..1000, "${mineral.name} splits into $sum parts per thousand")
        }
    }

    /** Every species needs a density and a heat capacity, or the physics divides by zero. */
    @Test
    fun everySpeciesHasPhysicalConstants() {
        for (s in Species.ALL) {
            assertTrue(s.molarMass > 0, "${s.name} molarMass")
            assertTrue(s.specificHeat > 0, "${s.name} specificHeat")
            assertTrue(s.solidKgPerCubicMetre > 0, "${s.name} density")
        }
    }

    /**
     * Nothing is denser than osmium — the anchor `NUMERIC_LIMITS.md` §3 rests on.
     *
     * A compound necessarily dilutes with something lighter, so no mineral can breach it either.
     * This is what keeps the world's mass ceiling a physical fact rather than a side-effect of
     * whatever happens to be top of the table today.
     */
    @Test
    fun osmiumRemainsTheDensestThing() {
        val denser = Species.ALL
            .filter { it.solidKgPerCubicMetre > Species.Osmium.solidKgPerCubicMetre }
            .map { it.name }
        assertTrue(denser.isEmpty(), "denser than osmium: ${denser.joinToString()}")
    }
}
