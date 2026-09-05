package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.chem.Species

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
        // ⚠️ **Only [MINERALS] now.** This used to union in a `LANTHANIDE_SUITE` map as well,
        // because the rare-earth minerals' formulae named one representative lanthanide each and the
        // other thirteen had no source anywhere else. The suites are the formulae now, so a single
        // walk covers every element and there is one table to keep true.
        val fromMinerals = Species.NATURAL.flatMap { MINERALS[it]?.keys.orEmpty() }.toSet()
        val native = Species.NATURAL.filter { it.isElement }.toSet()
        val obtainable = fromMinerals + native

        val orphans = Species.ALL.filter { it.isElement && it !in obtainable }.map { it.name }
        assertTrue(orphans.isEmpty(), "elements with no source: ${orphans.joinToString()}")
    }

    /**
     * A mineral the player can neither **mine** nor **make** is dead weight in the table.
     *
     * ⚠️ **Two ways to reach a species, not one.** This used to demand a non-zero
     * [Species.relativeAbundance] of every mineral, which was the whole truth for as long as nothing
     * could be manufactured. Lime and periclase are what calcining leaves behind and occur in no
     * rock that has ever met water or carbon dioxide, so their abundance is zero and must stay zero
     * — an abundance invented to satisfy this test would put quicklime in asteroids.
     *
     * Stated against the reaction tables rather than against a list of exceptions, so a product
     * whose reaction is later removed goes back to being dead weight and is reported as such.
     */
    @Test
    fun everyMineralIsMinedOrMade() {
        // ⚠️ **Asked of `REACTIONS`, not of the tables it derives from.** This used to union the
        // decompositions with the oxidations, which was the whole truth while every row reached the
        // sweep through one of those two — and stopped being it the moment a row was written
        // directly in the unified table. Steel and firebrick are both made by such a row, and under
        // the old form both would have been reported as dead weight while the game manufactured them
        // every tick. The unified list is where a reaction *is* now, so it is what this asks.
        val made = REACTIONS.flatMap { r -> r.products.map { it.first } }.toSet()
        val unreachable = MINERALS.keys
            .filter { it.relativeAbundance == 0 && it !in made }
            .map { it.name }
        assertTrue(
            unreachable.isEmpty(),
            "minerals that cannot be mined and cannot be made: ${unreachable.joinToString()}",
        )
    }

    /** And the converse: something made by a reaction has to be a thing the table describes. */
    @Test
    fun everyReactionProductIsAKnownSubstance() {
        for (row in REACTIONS) {
            for ((product, _) in row.products) {
                assertTrue(
                    product.isElement || product in MINERALS,
                    "${row.principal}'s row yields $product, which is neither an element nor in MINERALS",
                )
            }
        }
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

    private val RARE_EARTHS = setOf(
        Species.Yttrium,
        Species.Lanthanum, Species.Cerium, Species.Praseodymium, Species.Neodymium,
        Species.Samarium, Species.Europium, Species.Gadolinium, Species.Terbium,
        Species.Dysprosium, Species.Holmium, Species.Erbium, Species.Thulium,
        Species.Ytterbium, Species.Lutetium,
    )

    /**
     * **Every rare-earth mineral has exactly two hundred lanthanide sites**, which is what makes its
     * formula a faithful statement of the real distribution.
     *
     * ⛔ Two hundred is the smallest cell in which every share of all three suites is a whole number.
     * At a hundred, europium's ten parts per thousand of monazite would be one site and its
     * neighbours would round; at a smaller cell still the rare half of xenotime disappears entirely.
     * A site count that is not 200 means somebody has changed a suite without re-deriving it.
     */
    @Test
    fun everyRareEarthMineralHasTwoHundredLanthanideSites() {
        for (mineral in listOf(Species.Monazite, Species.Bastnasite, Species.Xenotime)) {
            val sites = MINERALS.getValue(mineral).filterKeys { it in RARE_EARTHS }.values.sum()
            assertEquals(200, sites, "${mineral.name}'s lanthanide site")
        }
    }

    /**
     * Nothing outside the rare earths sits on the lanthanide site, and nothing else in the game
     * carries a rare earth.
     *
     * ⚠️ The second half is the one worth having: a rare earth turning up in some unrelated rock
     * would make it obtainable by a route nobody designed, and the whole point of these three
     * minerals is that they are the *only* way to any of the fifteen.
     */
    @Test
    fun rareEarthsOccurOnlyInTheThreeMineralsThatCarryThem() {
        val carriers = MINERALS.filterValues { formula -> formula.keys.any { it in RARE_EARTHS } }.keys
        assertEquals(
            setOf(Species.Monazite, Species.Bastnasite, Species.Xenotime),
            carriers,
            "these minerals carry a rare earth",
        )
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
