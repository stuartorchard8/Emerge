package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.CRITICAL
import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.cohesionOf
import org.emerge.demo.outofspace.world.gasKelvin
import org.emerge.demo.outofspace.world.heatCapacityAt
import org.emerge.demo.outofspace.world.settleCohesion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.thermalMassAt
import org.emerge.demo.outofspace.world.energyAtKelvin

/**
 * **Condensing gives back what boiling took.**
 *
 * `offGas` charges the latent heat when matter evaporates, because that is an event. Condensing is
 * not one — phase is derived, so a tile of vapour that cools *becomes* frost with no code executing
 * — which left the heat uncredited and an evaporate-condense cycle a net energy sink of 2.26 MJ per
 * kilogram of water. Harmless only because nothing closes the matter loop; a free refrigerator the
 * moment anything does.
 *
 * So the cohesion is carried as a statement about the field rather than as a transaction, and only
 * its change moves. These are the two things that has to buy: a **plateau** in the cooling curve,
 * which is what a latent heat *is*, and a **cycle that costs nothing**.
 */
class LatentHeatTest {

    private val tiles = 1
    private val tile = TileIndex(0)
    private val kg = Budget.KILOGRAM

    private fun water(kelvin: Int): Triple<MassArray, EnergyArray, EnergyArray> {
        val masses = MassArray(tiles)
        masses.add(tile, Fluid.Water, 1L * kg)
        val energies = EnergyArray(tiles)
        energies[tile] = energyAtKelvin(thermalMassAt(masses, tile), kelvin)
        val cohesion = cohesionOf(masses, gasKelvin(energies, masses))
        return Triple(masses, energies, cohesion)
    }

    /** Well clear of the clamp at absolute zero, so a floored tile cannot masquerade as a plateau. */
    private val FLOOR_KELVIN = 60

    private fun kelvinOf(masses: MassArray, energies: EnergyArray): Int =
        gasKelvin(energies, masses)[0]

    @Test
    fun `the cooling curve has a plateau where the water condenses`() {
        // A kilogram of steam at 500 K, cooled by taking a fixed bite of thermal energy at a time.
        // Without a latent heat the temperature falls in a straight line. With one it stalls, and it
        // stalls for a long time: condensing water gives up about 2.3 MJ/kg against a sensible heat
        // of 4.2 kJ/kg/K, so the plateau is worth some hundreds of degrees of cooling.
        val (masses, energies, cohesion) = water(500)
        val bite = heatCapacityAt(masses, tile) * 2   // two kelvin's worth of sensible heat

        // ⚠️ **Stalls are only counted well above absolute zero.** An earlier version of this
        // counted any step that removed heat without cooling, and passed against a completely
        // unstable implementation — because a tile clamped at 0 K stalls on every step. A plateau
        // that is really the floor is not a plateau.
        var previous = kelvinOf(masses, energies)
        var biggestDrop = 0
        var stalledSteps = 0
        var stalledAt = 0
        repeat(400) {
            energies[tile] -= bite
            if (energies[tile] < 0L) energies[tile] = 0L
            settleCohesion(masses, energies, cohesion)
            val now = kelvinOf(masses, energies)
            val drop = previous - now
            if (drop > biggestDrop) biggestDrop = drop
            if (drop == 0 && now > FLOOR_KELVIN) {
                stalledSteps++
                stalledAt = now
            }
            previous = now
        }

        assertTrue(biggestDrop >= 2, "the water never cooled at all; biggest step was ${biggestDrop}K")
        assertTrue(
            stalledSteps > 50,
            "there was no plateau: only $stalledSteps of 400 steps removed heat without cooling it " +
                "above ${FLOOR_KELVIN}K. A latent heat that does not stall the curve is not one.",
        )
        assertTrue(
            stalledAt in 300..450,
            "the plateau was at ${stalledAt}K, which is not where a kilogram of water in a tile " +
                "condenses — a stall somewhere else is an artefact, not a phase change",
        )
    }

    @Test
    fun `it costs far more to cool steam through condensing than the sensible heat alone`() {
        // The quantitative form of the same statement, and the one with a number in it: the excess
        // over the sensible heat *is* the latent heat, so it has to be the larger of the two.
        val (masses, energies, cohesion) = water(500)
        val capacity = heatCapacityAt(masses, tile)
        val bite = capacity / 4
        val target = 300

        var removed = 0L
        var steps = 0
        while (kelvinOf(masses, energies) > target && steps < 200_000) {
            energies[tile] -= bite
            if (energies[tile] < 0L) energies[tile] = 0L
            settleCohesion(masses, energies, cohesion)
            removed += bite
            steps++
        }

        assertTrue(kelvinOf(masses, energies) <= target, "never reached ${target}K in $steps steps")
        val sensible = capacity * (500 - target)
        assertTrue(
            removed > sensible * 2,
            "cooling a kilogram of steam from 500K to ${target}K took $removed against a sensible " +
                "heat of $sensible — the latent heat is missing or tiny",
        )
    }

    @Test
    fun `boiling it back off gives the energy straight back`() {
        // The property the whole thing is for. Cool it until it condenses, then put exactly the same
        // energy back, and it must land where it started — otherwise the cycle is a pump.
        val (masses, energies, cohesion) = water(500)
        val start = energies[tile]
        val startKelvin = kelvinOf(masses, energies)
        val bite = heatCapacityAt(masses, tile)

        var out = 0L
        repeat(300) {
            energies[tile] -= bite
            if (energies[tile] < 0L) energies[tile] = 0L
            out += bite
        }
        settleCohesion(masses, energies, cohesion)
        assertTrue(kelvinOf(masses, energies) < startKelvin, "it did not cool")

        var backIn = 0L
        while (backIn < out) {
            energies[tile] += bite
            settleCohesion(masses, energies, cohesion)
            backIn += bite
        }

        val ended = kelvinOf(masses, energies)
        assertTrue(
            kotlin.math.abs(ended - startKelvin) <= 3,
            "a cool-then-reheat cycle ended at ${ended}K having started at ${startKelvin}K — " +
                "the same energy in and out does not return it to the same state",
        )
        assertTrue(
            kotlin.math.abs(energies[tile] - start) < start / 100,
            "the thermal energy did not come back: ${energies[tile]} against $start",
        )
    }

    @Test
    fun `a cycle books as much energy out as it books in`() {
        // Stated on the ledger rather than on the thermometer, because it is the ledger that a free
        // refrigerator would show up in: settleCohesion reports what crossed between bonds and heat,
        // and over a closed cycle that has to sum to nothing.
        val (masses, energies, cohesion) = water(500)
        val bite = heatCapacityAt(masses, tile)

        var booked = 0L
        repeat(300) {
            energies[tile] -= bite
            if (energies[tile] < 0L) energies[tile] = 0L
            booked += settleCohesion(masses, energies, cohesion)
        }
        assertTrue(booked > 0L, "nothing condensed, so nothing was proved")

        repeat(300) {
            energies[tile] += bite
            booked += settleCohesion(masses, energies, cohesion)
        }

        assertTrue(
            kotlin.math.abs(booked) < bite,
            "a closed cycle booked $booked of net energy out of nowhere",
        )
    }

    /**
     * ⛔ **The precondition [settleCohesion] rests on, asserted per fluid and per kelvin.**
     *
     * It solves `f(K) = capacity·K + cohesion(K) = total` by bisection, and the whole correctness
     * argument is the one word in `LatentHeat.kt`: *strictly increasing*. Nothing checked it, and
     * for a long time it was false. A tile holding 20 kg of water, `cohesionOf`-consistent at
     * 200 K, settled to **171 K** and booked 245 MJ across the bond boundary for having been asked
     * a question — the bisection walks past the true root and stops at the first place the
     * predicate happens to hold. A solver's precondition that nothing checks is one that drifts.
     *
     * ⚠️ **Two fluids are named exceptions rather than skipped**, and the set is asserted to be
     * exactly those two, so the gap can neither grow unnoticed nor be closed without this test
     * saying so. Both fail for the same pre-existing reason, and it is not in the reading of the
     * table: [vaporisationHeat] extracts `L` from Clausius-Clapeyron assuming the vapour is ideal
     * and `Δv ≈ v_gas`. That holds well below the critical point and fails approaching it, where
     * `Δv → 0` while `dP/dT` stays finite — so `L` turns back upward toward `Tc` instead of falling
     * to zero. Zinc returns to 200 818 at 3169 K against a critical point of 3170 K. Every fluid
     * does it; it only defeats a settlement where the climb outruns one kelvin of sensible heat,
     * which is Fluorine at 52–54 K and Cadmium at 563 K. Closing it means a compressibility
     * correction on the latent heat, and `PhaseRealityTest` is the only test that can see whether
     * the replacement law is right.
     */
    @Test
    fun `the function a settlement bisects is strictly increasing, per fluid and per kelvin`() {
        val nonMonotone = mutableSetOf<Fluid>()
        for (fluid in Fluid.entries) {
            // Helium has no dome — nothing condenses, so there is no `f` to be monotone.
            val critical = CRITICAL[fluid.species] ?: continue
            for (kilograms in listOf(1L, 20L)) {
                val masses = MassArray(tiles)
                masses.add(tile, fluid, kilograms * kg)
                val capacity = heatCapacityAt(masses, tile)

                // Up to the critical point, where the dome closes and cohesion is zero above.
                var previous = Long.MIN_VALUE
                for (kelvin in 1..critical.kelvin) {
                    val bound = cohesionOf(masses, IntArray(tiles) { kelvin })[tile]
                    val f = capacity * kelvin + bound
                    if (previous != Long.MIN_VALUE && f <= previous) {
                        nonMonotone += fluid
                        break
                    }
                    previous = f
                }
            }
        }

        assertEquals(
            setOf(Fluid.Fluorine, Fluid.Cadmium),
            nonMonotone,
            "the set of fluids whose settlement function is not strictly increasing has changed. " +
                "Gained one and a settlement can silently land tens of kelvin from the root for it; " +
                "lost one and the near-critical latent heat has been corrected, so drop it from the " +
                "expected set and delete the paragraph in this test's doc that explains it.",
        )
    }
}
