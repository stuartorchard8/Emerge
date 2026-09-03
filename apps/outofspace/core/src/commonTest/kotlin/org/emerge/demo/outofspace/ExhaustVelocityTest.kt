package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.energyAtKelvin
import org.emerge.demo.outofspace.world.machine.Thruster.Companion.exhaustVelocity
import org.emerge.demo.outofspace.world.thermalMassOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What a propellant is worth, in metres per second.**
 *
 * `v_e = √( 2γ/(γ−1) · R·T/M )`, and the whole of the mechanic is `T` over `M`: a hot chamber and a
 * light molecule. This is the file where a wrong answer is cheapest to find, which is why the
 * function is pure and lands a step before anything fires — see `PLAN_fluid_thrusters.md` §6.
 *
 * ⚠️ **The expected values are computed from the physics, not from the code.** Each one is what a
 * textbook gives for that gas at that temperature, to a tolerance wide enough for the three-class γ
 * (§3) and no wider. A test that asserted whatever the implementation happened to print would pass
 * against every unit error in it.
 */
class ExhaustVelocityTest {

    private val kg = Budget.KILOGRAM

    /** A kilogram of one species at [kelvin], which is the shape every row below wants. */
    private fun parcel(species: Species, kelvin: Int, mass: Long = kg): Mixture {
        val cold = Mixture.of(species to mass, energy = 0L)
        return Mixture.of(cold.masses, energyAtKelvin(thermalMassOf(cold), kelvin))
    }

    /** Asserts [actual] is within [tolerance] per cent of [expected]. */
    private fun assertNear(expected: Long, actual: Long, tolerance: Int, what: String) {
        val slack = expected * tolerance / 100L
        assertTrue(
            actual in (expected - slack)..(expected + slack),
            "$what: expected about $expected m/s, got $actual",
        )
    }

    // ── The table ────────────────────────────────────────────────────────────

    @Test
    fun `a cold gas is worth what its molar mass says it is`() {
        // Room temperature, so the only thing separating these is M and the molecule's shape. The
        // spread from CO₂ to hydrogen is a factor of four and it is all M.
        assertNear(669, exhaustVelocity(parcel(Species.CarbonDioxide, 293)), 2, "cold CO₂")
        assertNear(780, exhaustVelocity(parcel(Species.Nitrogen, 293)), 2, "cold N₂")
        assertNear(1744, exhaustVelocity(parcel(Species.Helium, 293)), 2, "cold He")
        assertNear(2920, exhaustVelocity(parcel(Species.Hydrogen, 293)), 2, "cold H₂")
    }

    @Test
    fun `and heating it is the other half of the mechanic`() {
        // The same hydrogen, ten times hotter, for √10 ≈ 3.2× the exhaust velocity. This is the
        // nuclear-thermal end of the scale and the reason a chamber temperature is worth building
        // machinery to raise.
        assertNear(9343, exhaustVelocity(parcel(Species.Hydrogen, 3000)), 2, "hot H₂")
        // Steam at a combustion temperature — what burning hydrogen in oxygen would leave in a
        // chamber, and roughly what a real hydrolox engine gets.
        assertNear(3596, exhaustVelocity(parcel(Species.Water, 3500)), 2, "hot steam")
    }

    @Test
    fun `four times the temperature is twice the speed, exactly`() {
        // The square-root law itself, stated without a single expected value in it. Cheap, and it
        // catches a `kelvin` that went in linearly.
        val warm = exhaustVelocity(parcel(Species.Nitrogen, 300))
        val hot = exhaustVelocity(parcel(Species.Nitrogen, 1200))
        assertNear(2L * warm, hot, 1, "N₂ at 300 K vs 1200 K")
    }

    @Test
    fun `and it does not depend on how much there is`() {
        // v_e is a property of the propellant, not of the parcel. The mole count cancels out of the
        // ratio, and this is the assertion that says so.
        //
        // ⚠️ **Every reading is pinned to the physics, not just to each other.** The first draft
        // compared a milligram against twenty tonnes and passed while *both* were zero — the small
        // one under the millimole floor, the large one overflowing the mole conversion into a
        // negative. Two broken answers agree perfectly. `assertNear` against 2920 is what makes this
        // a test.
        for (mass in listOf(10L * Budget.KILOGRAM, kg, Budget.GRAM, 20L * Budget.TONNE)) {
            assertNear(
                2920, exhaustVelocity(parcel(Species.Hydrogen, 293, mass = mass)), 2,
                "hydrogen at 293 K, $mass of it",
            )
        }
    }

    @Test
    fun `twenty tonnes of hydrogen does not overflow the mole count`() {
        // ⛔ The regression. `mass × millimolesPerKilogram` for a full store of the lightest species
        // is 10¹⁹, which wraps `Long` negative — so the parcel reported *no* velocity rather than a
        // rounded one, and a motor drawing on a full tank would have made no thrust at all.
        // A store is `Storage.CAP`, and this is a shade over it on purpose.
        val full = exhaustVelocity(parcel(Species.Hydrogen, 293, mass = 25L * Budget.TONNE))
        assertTrue(full > 0L, "a full tank of hydrogen was worth nothing")
        assertNear(2920, full, 2, "twenty-five tonnes of hydrogen")
    }

    @Test
    fun `below a millimole there is nothing to price, and it says so`() {
        // The floor of the mole table, stated so a caller knows it is there: hydrogen rounds to zero
        // millimoles under about a milligram. ⚠️ A **zero velocity, not a small one** — so step 4's
        // `ṁ = thrust / v_e` has to guard it rather than divide by it.
        assertEquals(0L, exhaustVelocity(parcel(Species.Hydrogen, 293, mass = Budget.GRAM / 1000L)))
        // And a hair above the floor is an ordinary answer, so the cliff is where it is claimed.
        assertNear(2920, exhaustVelocity(parcel(Species.Hydrogen, 293, mass = Budget.GRAM / 50L)), 2, "20 mg")
    }

    // ── Mixtures ─────────────────────────────────────────────────────────────

    @Test
    fun `a mixture sits between its parts, nearer the one it has more moles of`() {
        val hydrogen = exhaustVelocity(parcel(Species.Hydrogen, 293))
        val nitrogen = exhaustVelocity(parcel(Species.Nitrogen, 293))

        // Equal *masses*, which is fourteen times as many moles of hydrogen as of nitrogen. So the
        // blend should land much nearer the hydrogen — the arithmetic is over moles, and a
        // mass-weighted average would put it near the middle instead.
        val cold = Mixture.of(Species.Hydrogen to kg, Species.Nitrogen to kg, energy = 0L)
        val blend = exhaustVelocity(Mixture.of(cold.masses, energyAtKelvin(thermalMassOf(cold), 293)))

        assertTrue(blend in nitrogen..hydrogen, "a blend outside its own parts: $blend")
        assertTrue(
            blend > (nitrogen + hydrogen) / 2L,
            "equal masses is 14:1 in moles, so the blend should lean hydrogen: $blend",
        )
    }

    @Test
    fun `a gram of rock in the chamber is thrown slowly, and drags the whole jet down`() {
        // ⛔ The case the game got wrong for its whole life: a solid-fed motor was being given
        // hydrogen's exhaust velocity for a chamber full of gravel. Forsterite is 140 g/mol.
        val rock = exhaustVelocity(parcel(Species.Forsterite, 293))
        assertTrue(rock < 400, "rock should be worth almost nothing as propellant: $rock m/s")

        val clean = exhaustVelocity(parcel(Species.Hydrogen, 293))
        val fouled = Mixture.of(Species.Hydrogen to kg, Species.Forsterite to kg, energy = 0L)
        val spoiled = exhaustVelocity(Mixture.of(fouled.masses, energyAtKelvin(thermalMassOf(fouled), 293)))
        assertTrue(spoiled < clean, "rock in the chamber did not cost anything: $spoiled vs $clean")
    }

    // ── The degenerate cases ─────────────────────────────────────────────────

    @Test
    fun `nothing at all is worth zero, not ambient`() {
        // ⚠️ The one that would otherwise be silently wrong. `kelvinOf` answers AMBIENT_KELVIN for a
        // parcel with no capacity, so a naive reading of an empty chamber reports a perfectly
        // healthy 2.9 km/s of hydrogen that is not there.
        assertEquals(0L, exhaustVelocity(Mixture.of(energy = 0L)))
    }

    @Test
    fun `and neither is propellant with no heat in it`() {
        // Absolute zero has no energy to become velocity. Distinct from the empty case above: there
        // is mass here, and it still cannot be thrown.
        assertEquals(0L, exhaustVelocity(Mixture.of(Species.Hydrogen to kg, energy = 0L)))
    }

    @Test
    fun `a parcel at ambient reads the ambient it was seeded at`() {
        // Guards the seam between the two temperature helpers rather than the physics: seeded
        // through `energyAtKelvin`, read back through `kelvinOf`, and the round trip has to land on
        // the same number or every velocity above is measured against the wrong chamber.
        val ambient = exhaustVelocity(parcel(Species.Nitrogen, Temperature.AMBIENT_KELVIN))
        val stated = exhaustVelocity(parcel(Species.Nitrogen, 293))
        assertNear(stated, ambient, 1, "AMBIENT_KELVIN is ${Temperature.AMBIENT_KELVIN}")
    }

    @Test
    fun `every fluid in the game has a velocity and none of them is absurd`() {
        // A sweep rather than a list, so a fluid added later cannot slip through with a zero (which
        // would read as "this motor makes no thrust" and look like a plumbing fault) or with a
        // number no chemical rocket could produce.
        for (fluid in org.emerge.demo.outofspace.chem.Fluid.ALL) {
            val v = exhaustVelocity(parcel(fluid.species, 293))
            assertTrue(v in 100..4000, "${fluid.species} at 293 K gives $v m/s")
        }
    }
}
