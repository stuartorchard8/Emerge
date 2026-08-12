package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.CRITICAL
import org.emerge.demo.outofspace.chem.SCALE
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AMBIENT_PRESSURE
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.capacityPerTile
import org.emerge.demo.outofspace.world.gramsPerTile
import org.emerge.demo.outofspace.world.solidGramsPerTile
import org.emerge.demo.outofspace.world.thermalTiles
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The overflow budget, as a tripwire.
 *
 * `NUMERIC_LIMITS.md` surveys every place a mass meets a fixed-point scale and works out how much
 * room is left in a `Long`. This is that survey made executable, and it exists for one job: the mass
 * unit is being rebaselined away from "one integer is one gram", and **the whole cost of that change
 * is paid in these intermediates**. Scaling mass by `k` shrinks the headroom of a linear term by `k`
 * and of a quadratic one by `k²`, silently, with no symptom until a pressure or a velocity flips
 * sign somewhere far from the change.
 *
 * ### How to use it during the rescale
 *
 * Raise [targetMassScale] to the unit you are aiming at. Every row that cannot support it fails **by
 * name**, which is the list of expressions that have to be restructured first — in effect a to-do
 * list generated from the game's own constants rather than from anybody's memory of them. Set it
 * back to `1` and the file returns to guarding today's arithmetic.
 *
 * ### What a failure means
 *
 * Not necessarily a bug. A row going red is usually this test doing its job: telling you which
 * expression blocks the next order of magnitude. Read the name, decide whether to fix the expression
 * or lower the ambition, and update `NUMERIC_LIMITS.md` in the same commit.
 *
 * ### What it deliberately does not check
 *
 * The two things already broken at one-gram units — `reducedPressure` at the packing wall, and the
 * five-unit diffusion stranding floor (§6.1, §6.2). Both are real and both are documented, but
 * neither is an overflow this shape of test can express, and pinning them here would mean asserting
 * that a bug is still present — which goes red when somebody fixes it and teaches the next reader to
 * distrust the file.
 */
class NumericLimitsTest {

    /**
     * The mass unit this budget is being checked against: `1` is one integer per gram, `1000` is one
     * per milligram. **This is the knob.** Raise it to find out what blocks that unit; the failures
     * are the answer.
     */
    private val targetMassScale = 1L

    /**
     * How much room every row keeps on top of the target, so that a budget is never merely *just*
     * met. Four rather than ten because two of these rows are already inside a factor of twenty at
     * one gram per unit, and a margin that fails on arrival teaches nothing.
     */
    private val safetyFactor = 4.0

    /**
     * The fastest the ship is expected to go, in tiles per tick.
     *
     * From `OutofspaceHud.NAV_FULL_SCALE_SPEED` — the speed the nav needle is drawn full-scale at,
     * which is the closest thing the game has to a stated design speed. Restated rather than
     * imported because a HUD constant moving for a presentation reason should not silently change
     * what this file believes about flight.
     */
    private val designTopSpeed = 2L

    /**
     * The fewest ticks the ship may take to reach [designTopSpeed] from rest.
     *
     * A design assumption, and the honest way to bound a *per-tick* impulse — which is what
     * `frameAcceleration` divides, unlike the running total `velocityX` divides. The alternative
     * bound is the physical one, "all of the momentum arrives in a single tick", and that is both
     * absurd (the measured peak on a breached hull is six orders below it) and so pessimistic that
     * the row would report a limit nothing can ever hit.
     */
    private val minTicksToTopSpeed = 100L

    /** Hot enough for a smelter, cold enough to be a real state. The top of the thermal range. */
    private val designMaxKelvin = 3000L

    private val gridTiles: Long = OutofspaceConfig().initialGrid.size.toLong()

    /** The heaviest single thing that can stand on a tile, and the most heat it can hold. */
    private val heaviestMachineGrams: Long =
        MachineKind.ALL.maxOf { it.gramsPerTile * it.thermalTiles }
    private val heaviestMachineCapacity: Long =
        MachineKind.ALL.maxOf { it.capacityPerTile * it.thermalTiles }

    /**
     * A ship the game actually flies, rather than the heaviest one that could be drawn.
     *
     * An eighth of the grid in hull, which is roughly what a hull enclosing a usable interior comes
     * to and is within a factor of three of the 110 tonnes measured on the standard bare hull.
     */
    private val referenceShipGrams: Long = MachineKind.Hull.gramsPerTile * gridTiles / 8L

    /** The densest a tile of gas can legitimately get: close packing, three times critical. */
    private val densestPackedLiquid: Long =
        Species.ALL.mapNotNull { CRITICAL[it] }.maxOf { it.gramsPerTile * 3 }

    /** The densest a tile of anything can get. Not in any intermediate — kept as a stated bound. */
    private val densestSolidTile: Long = Species.ALL.maxOf { it.solidGramsPerTile }

    /**
     * `Composition.VOLUME_UNIT`, restated because it is private.
     *
     * ⚠️ If that constant moves and this does not, the `gramsPerTileOf` row goes quietly wrong. It is
     * restated rather than the constant made internal because the row it guards is about an
     * *unstated invariant* (see the row itself), and widening a constant's visibility would be the
     * wrong repair for that.
     */
    private val volumeUnit = 1_000_000_000L

    /** `PressureForce.SOUND_IMPULSE`, likewise private, and derived from ambient rather than fixed. */
    private val soundImpulse: Long = AirField.AMBIENT_AIR.total / 4

    private val rows = mutableListOf<String>()
    private val failures = mutableListOf<String>()

    /**
     * One intermediate product.
     *
     * @param exponent how the worst case grows with the mass unit — 1 for a lone mass, 2 where two
     *   masses are multiplied together. Quadratic rows are the dangerous ones: they look roomy at
     *   one gram per unit and are spent four times as fast.
     */
    private fun budget(name: String, worst: Long, exponent: Int) {
        val required = safetyFactor * when (exponent) {
            2 -> targetMassScale.toDouble() * targetMassScale.toDouble()
            else -> targetMassScale.toDouble()
        }
        if (worst <= 0L) {
            failures += "$name: worst case came out $worst — it has ALREADY overflowed a Long"
            rows += "  %-46s OVERFLOWED".format(name)
            return
        }
        val headroom = Long.MAX_VALUE.toDouble() / worst.toDouble()
        val safeK = if (exponent == 2) kotlin.math.sqrt(headroom) else headroom
        rows += "  %-46s worst=%-20d k^%d  headroom=%.3g  safe k=%.3g".format(
            name, worst, exponent, headroom, safeK,
        )
        if (headroom < required) {
            failures += ("%s: headroom %.3g, needs %.3g for a mass scale of %d — " +
                "restructure it or lower the target (NUMERIC_LIMITS.md §8)")
                .format(name, headroom, required, targetMassScale)
        }
    }

    @Test
    fun `every mass-carrying intermediate keeps its budgeted headroom`() {
        // ── Flight ────────────────────────────────────────────────────────
        // The tightest constraint in the game, and a *velocity* ceiling rather than a mass one:
        // momentum is mass x velocity, and multiplying by PER_TILE before dividing by mass caps the
        // top speed at PER_TILE's headroom over the ship's momentum.
        budget(
            "velocityX: vesselImpulse * PER_TILE",
            referenceShipGrams * designTopSpeed * Flight.PER_TILE, 1,
        )
        // Bounded by a spin-up time rather than by the whole momentum landing at once — see
        // [minTicksToTopSpeed].
        budget(
            "frameAcceleration: netImpulse * FRAC_ONE",
            referenceShipGrams * designTopSpeed / minTicksToTopSpeed * Flight.FRAC_ONE, 1,
        )

        // ── Cargo and mixtures ────────────────────────────────────────────
        // apportion multiplies each weight by the target before dividing by the sum, and BOTH are
        // masses — the tightest quadratic term there is. Reached through Mixture.scaledTo whenever a
        // full Storage is rescaled.
        budget("apportion: weight * target (full Storage)", Storage.CAP * Storage.CAP, 2)
        budget("apportion: weight * target (machine buffer)", MACHINE_BUFFER_CAP * MACHINE_BUFFER_CAP, 2)
        // ⚠️ This row guards an UNSTATED INVARIANT, not a margin. gramsPerTileOf computes
        // `total * VOLUME_UNIT`, which is safe only because both call sites pass per-mille
        // compositions totalling ~1000 (Material.composition; RockSpawner normalises to 1000). Hand
        // it a real pile and it breaks at 9.2e9 g, so this row going red means somebody has widened
        // what reaches that function.
        budget("gramsPerTileOf: total * VOLUME_UNIT", 1_000L * volumeUnit, 1)

        // ── Gas and pressure ──────────────────────────────────────────────
        budget("reducedDensity: packed liquid * SCALE", densestPackedLiquid * SCALE, 1)
        budget(
            "millimolesOf: packed liquid * millimoles/kg",
            densestPackedLiquid * (1_000_000L / Species.Water.molarMass), 1,
        )
        // Measured against ordinary operating pressure rather than the pathological close-packed
        // one: a tile at the packing wall is in the broken regime of §6.1 anyway, so budgeting for
        // it would be budgeting to keep a bug survivable.
        budget("potentialOf: pressure * SOUND_IMPULSE (10 atm)", AMBIENT_PRESSURE * 10L * soundImpulse, 2)
        budget(
            "ambientPressureOf: grams * species share",
            densestPackedLiquid * AirField.AMBIENT_AIR[Species.Nitrogen], 2,
        )

        // ── Heat ──────────────────────────────────────────────────────────
        budget("tile joules: heaviest machine at max kelvin", heaviestMachineCapacity * designMaxKelvin, 1)
        // Scales with grid AREA as well as with the mass unit — the one row where growing the map
        // spends overflow headroom.
        budget(
            "ship joules: that across the whole grid",
            heaviestMachineCapacity * designMaxKelvin * gridTiles, 1,
        )
        budget(
            "atmosphere joules: ambient air across the whole grid",
            AirField.AMBIENT_AIR.total * Species.Water.specificHeat.toLong() *
                Temperature.AMBIENT_KELVIN.toLong() * gridTiles, 1,
        )

        // ── Bulk mass ─────────────────────────────────────────────────────
        budget("solid mass: heaviest machine across the grid", heaviestMachineGrams * gridTiles, 1)
        budget("cargo: Storage.CAP across the grid", Storage.CAP * gridTiles, 1)
        budget("densest single tile (bound, not an intermediate)", densestSolidTile, 1)

        if (failures.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("The overflow budget in NUMERIC_LIMITS.md no longer holds")
                    appendLine("(target mass scale $targetMassScale, safety factor $safetyFactor):")
                    for (f in failures) appendLine("  - $f")
                    appendLine()
                    appendLine("Full table:")
                    for (r in rows) appendLine(r)
                },
            )
        }
    }

    /**
     * The ship the flight model can actually fly, stated as a mass.
     *
     * Separate from the budget table because it is not a margin that might one day be spent — it is
     * a limit that **already binds today**, and the survey originally got it wrong by a factor of a
     * hundred. `velocityX` multiplies momentum by `PER_TILE` before dividing by mass, so any ship
     * heavier than this simply cannot reach [designTopSpeed]: the product wraps and the vessel's
     * velocity flips sign.
     *
     * A grid packed solid with the heaviest machine is **already past it** at one gram per unit, so
     * this asserts the reference ship rather than the worst buildable one, and reports how much of
     * the range the worst buildable one would need. Anybody raising [targetMassScale] should read the
     * printed ratio: it is the first thing the rescale spends.
     */
    @Test
    fun `the flight model can fly the reference ship at design speed`() {
        val flyableGrams = Long.MAX_VALUE / (Flight.PER_TILE * designTopSpeed)
        val heaviestBuildable = heaviestMachineGrams * gridTiles
        println(
            "flyable ship mass %,d g; reference ship %,d g (%.1f%% of budget); ".format(
                flyableGrams, referenceShipGrams, 100.0 * referenceShipGrams / flyableGrams,
            ) + "heaviest buildable %,d g would need %.1fx the budget".format(
                heaviestBuildable, heaviestBuildable.toDouble() / flyableGrams,
            ),
        )
        assertTrue(
            referenceShipGrams * safetyFactor * targetMassScale < flyableGrams,
            "a reference ship of $referenceShipGrams g cannot reach $designTopSpeed tiles/tick at " +
                "mass scale $targetMassScale: the flight model tops out at $flyableGrams g",
        )
    }
}
