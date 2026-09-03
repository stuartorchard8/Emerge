package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **How many atoms a molecule has, and the `2γ/(γ−1)` that follows from it.**
 *
 * [Species.atomsPerMolecule] is derived from [MINERALS] and [ATOMIC_MASS] rather than stated, so
 * what is worth testing is not the arithmetic — it is that the derivation lands on the *chemistry*
 * for every fluid the game can put in a chamber. A wrong class here is silent: it does not crash, it
 * makes a rocket a fifth too strong or too weak for ever.
 *
 * The hand-checked list below is therefore the oracle and is deliberately written out by formula, so
 * a reader can check it against a periodic table rather than against the code that produced it.
 *
 * See `PLAN_fluid_thrusters.md` §3.
 */
class AtomicityTest {

    /** Every fluid the game has, with the formula the atom count is claiming for it. */
    private val expected: List<Triple<Species, Int, String>> = listOf(
        // ── Monatomic: the nobles, and the metals that leave a roasting bed as vapour ──
        Triple(Species.Helium, 1, "He"),
        Triple(Species.Neon, 1, "Ne"),
        Triple(Species.Argon, 1, "Ar"),
        Triple(Species.Krypton, 1, "Kr"),
        Triple(Species.Xenon, 1, "Xe"),
        Triple(Species.Mercury, 1, "Hg"),
        Triple(Species.Zinc, 1, "Zn"),
        Triple(Species.Cadmium, 1, "Cd"),
        // ⚠️ The known divergence, and it is self-consistent rather than right — real sulfur vapour
        // is S₈ or S₂. See [Species.atomsPerMolecule]; the game's sulfur weighs one atom, and the
        // pressure model reads that same 32.
        Triple(Species.Sulfur, 1, "S (game); really S₈/S₂"),

        // ── Diatomic ──
        Triple(Species.Hydrogen, 2, "H₂"),
        Triple(Species.Nitrogen, 2, "N₂"),
        Triple(Species.Oxygen, 2, "O₂"),
        Triple(Species.Fluorine, 2, "F₂"),
        Triple(Species.Chlorine, 2, "Cl₂"),
        Triple(Species.Bromine, 2, "Br₂"),
        Triple(Species.Iodine, 2, "I₂"),
        // A compound, and still diatomic — the one case a "compounds are polyatomic" shortcut would
        // get wrong, which is why the rule counts atoms instead of asking whether there is a formula.
        Triple(Species.CarbonMonoxide, 2, "CO"),

        // ── Polyatomic ──
        Triple(Species.Water, 3, "H₂O"),
        Triple(Species.CarbonDioxide, 3, "CO₂"),
        Triple(Species.HydrogenSulfide, 3, "H₂S"),
        Triple(Species.SulfurDioxide, 3, "SO₂"),
        Triple(Species.Ammonia, 4, "NH₃"),
        Triple(Species.Methane, 5, "CH₄"),
    )

    @Test
    fun `every fluid counts the atoms its formula has`() {
        for ((species, atoms, formula) in expected) {
            assertEquals(
                atoms, species.atomsPerMolecule,
                "$species is $formula, so it has $atoms atom(s) per molecule",
            )
        }
    }

    /**
     * ⛔ The reason the list above is an oracle and not a sample.
     *
     * A fluid added to [Fluid] and not to this file would otherwise get whatever the derivation
     * happened to produce, unchecked — and the derivation's fallback for an unknown species is
     * "monatomic", which is the answer that looks most plausible and is wrong most often.
     */
    @Test
    fun `and every fluid in the game is on that list`() {
        val listed = expected.map { it.first }.toSet()
        val missing = Fluid.ALL.map { it.species }.filter { it !in listed }
        assertTrue(missing.isEmpty(), "fluids with no hand-checked atom count: $missing")
    }

    @Test
    fun `the three shapes give the three whole numbers`() {
        // The property the integer arithmetic rests on: 2γ/(γ−1) for γ = 5/3, 7/5 and 4/3 is exactly
        // 4, 7 and 8. If this ever needed a fourth value it would need a fraction with it.
        for ((species, atoms, formula) in expected) {
            val k = when (atoms) {
                1 -> 4
                2 -> 7
                else -> 8
            }
            assertEquals(k, species.adiabaticK, "$formula has $atoms atom(s), so 2γ/(γ−1) is $k")
        }
    }

    @Test
    fun `a solid mineral is polyatomic too, since the rule is about the formula and not the phase`() {
        // Not a fluid, and it still answers — nothing about this asks whether a species can be a gas.
        // Worth pinning because the caller that wants it (a thruster chamber) will one day be handed
        // a mixture that has something condensed in it.
        assertEquals(7, Species.Forsterite.atomsPerMolecule, "Mg₂SiO₄ is 2 + 1 + 4")
        assertEquals(8, Species.Forsterite.adiabaticK)
    }

    @Test
    fun `an element with no formula is one atom rather than none`() {
        // The fallback path: `molarMass / atomicMass` where the two coincide. A zero here would make
        // an exhaust velocity divide by zero rather than merely be wrong.
        for (species in Species.ALL) {
            assertTrue(
                species.atomsPerMolecule >= 1,
                "$species has ${species.atomsPerMolecule} atoms per molecule",
            )
        }
    }
}
