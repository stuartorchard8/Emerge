package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * One tick of the fluid simulation: air state, vented mass/energy, vessel impulse, and
 * escaped momentum. [vesselX/Y] and [escapedX/Y] differ because the latter is thrust against
 * the expelled gas, the former is thrust on the hull.
 */
class FluidStep(
    val air: AirField,
    val momentumX: LongArray,
    val momentumY: LongArray,
    val ventedGrams: Long,
    /** Thermal energy that went overboard with the escaping gas — see [advectHeat]. */
    val ventedJoules: Long,
    val vesselX: Long,
    val vesselY: Long,
    val escapedX: Long,
    val escapedY: Long,
    /** Impulse the pressure solve had nowhere to put — fourth place momentum can be. Size measures
     * discretisation error in thrust. */
    val undeliveredX: Long,
    val undeliveredY: Long,
    /** Sub-steps the tick was cut into for CFL safety. One = gas < 1 tile/tick; climbing to MAX =
     * nozzle outrunning the explicit solver. */
    val subSteps: Int,
)

/**
 * Advances the atmosphere one tick: drift → drag/buoyancy/pressure-force → projection → transport.
 *
 * Forces first (gravity acts on initial state), then pressure (elliptic solve), then transport
 * (sub-stepped). Both transport reads use the pre-tick mass snapshot so momentum fluxes are
 * meaningful against the producing density.
 *
 * [gasJoules] nullable = isothermal mode. [volumes] nullable = all cells full tile.
 * [grams], [mx], [my], [gasJoules] edited in place.
 */
fun stepFluid(
    grid: Grid,
    structure: StructureMap,
    grams: LongArray,
    mx: LongArray,
    my: LongArray,
    gravity: Frac2,
    gasJoules: LongArray? = null,
    volumes: VolumeField? = null,
): FluidStep {
    val edges = EdgeGrid(grid)
    return stepFluid(edges, ApertureField.derive(edges, structure), grams, mx, my, gravity, gasJoules, volumes)
}

/**
 * The solver body. Everything from [stepFluid] KDoc applies.
 *
 * Disconnected cells (all faces shut) are naturally idle: [project] couples to nothing,
 * [applyPressureForce] finds no open face, [advectMass] moves nothing across closed apertures.
 */
fun stepFluid(
    edges: EdgeGrid,
    apertures: ApertureField,
    grams: LongArray,
    mx: LongArray,
    my: LongArray,
    gravity: Frac2,
    gasJoules: LongArray? = null,
    volumes: VolumeField? = null,
): FluidStep {
    val grid = edges.grid

    // Sorting first, because it moves mass between tiles: the density and pressure fields everything
    // below reads have to be the ones it leaves behind, not the ones it started from.
    applySpeciesDrift(edges, apertures, grams, gravity)

    val tileGrams = tileMass(grid.size, grams)
    // Temperature is read *after* drift, so a tile that has just gained gas is at the temperature its
    // new capacity implies. Drift moves mass without moving the energy on it, which cools whatever
    // it fills a little and warms whatever it empties. It is a settling term of a few grams a tick,
    // so the effect is well under a kelvin; carrying energy on the drift fluxes too would be the
    // exact version, and is not worth a second transfer pass for that.
    val kelvin = gasJoules?.let { gasKelvin(it, gasCapacity(grid.size, grams)) }
    val pressure = tilePressure(grid.size, grams, kelvin, volumes)

    val rubbed = applyDrag(edges, mx, my)
    val lift = applyBuoyancy(edges, apertures, mx, my, tileGrams, pressure, gravity, volumes)
    val pushed = applyPressureForce(edges, apertures, mx, my, tileGrams, pressure)
    val pressed = project(edges, apertures, mx, my, tileGrams, pressure)

    // ── Transport in sub-steps (CFL safety) ──
    // Forces above ran once; transport is cut into pieces so each step moves < 1 tile.
    val subSteps = subStepsFor(edges, mx, my, tileGrams)
    var vented = 0L
    var ventedJoules = 0L
    var escapedX = 0L
    var escapedY = 0L
    repeat(subSteps) {
        // Fresh density each sub-step: the gas the last piece left behind.
        val nowGrams = tileMass(grid.size, grams)
        val moved =
            advectMass(edges, apertures, MomentumField.of(edges, mx, my), grams, Species.GASES, nowGrams, subSteps)
        val carried = advectMomentum(edges, mx, my, moved.flux, nowGrams)
        // Heat rides the same fluxes as momentum.
        if (gasJoules != null) ventedJoules += advectHeat(edges, gasJoules, moved.flux, nowGrams)
        vented += moved.ventedGrams
        escapedX += carried.x
        escapedY += carried.y
    }

    // Momentum left on empty faces goes to nothing; a vacuum must not store a shove.
    val after = tileMass(grid.size, grams)
    var strandedX = 0L
    var strandedY = 0L
    for (e in 0 until edges.xEdgeCount) {
        if (mx[e] == 0L || xFaceGrams(edges, after, e) > 0L) continue
        strandedX += mx[e]
        mx[e] = 0L
    }
    for (e in 0 until edges.yEdgeCount) {
        if (my[e] == 0L || yFaceGrams(edges, after, e) > 0L) continue
        strandedY += my[e]
        my[e] = 0L
    }

    return FluidStep(
        air = if (gasJoules == null) AirField.of(grams) else AirField.of(grams, gasJoules),
        momentumX = mx.copyOf(),
        momentumY = my.copyOf(),
        ventedGrams = vented,
        ventedJoules = ventedJoules,
        vesselX = pressed.vesselX + pushed.vesselX + lift.vesselX + rubbed.vesselX,
        vesselY = pressed.vesselY + pushed.vesselY + lift.vesselY + rubbed.vesselY,
        escapedX = escapedX + strandedX,
        escapedY = escapedY + strandedY,
        subSteps = subSteps,
        undeliveredX = pressed.undeliveredX,
        undeliveredY = pressed.undeliveredY,
    )
}

/**
 * Sub-steps needed: one per whole tile the fastest face moves.
 *
 * Replaced a hard velocity clamp that broke conservation and still allowed overspeed.
 * [MAX_SUB_STEPS] is a refusal to spend unbounded time on one tick; reaching it means a nozzle
 * outran this scheme.
 */
private fun subStepsFor(edges: EdgeGrid, mx: LongArray, my: LongArray, tileGrams: LongArray): Int {
    var peak = 0L
    val field = MomentumField.of(edges, mx, my)
    for (e in 0 until edges.xEdgeCount) {
        val v = field.velocityX(e, tileGrams).raw
        val a = if (v < 0L) -v else v
        if (a > peak) peak = a
    }
    for (e in 0 until edges.yEdgeCount) {
        val v = field.velocityY(e, tileGrams).raw
        val a = if (v < 0L) -v else v
        if (a > peak) peak = a
    }
    // Round up, so a face at 1.2 tiles gets two pieces and lands at 0.6 of a tile each.
    val needed = (peak + MomentumField.SPEED_LIMIT_RAW - 1) / MomentumField.SPEED_LIMIT_RAW
    return when {
        needed < 1L -> 1
        needed > MAX_SUB_STEPS -> MAX_SUB_STEPS.toInt()
        else -> needed.toInt()
    }
}

/** Bound on sub-steps: a refusal to spend unbounded time on one tick. */
const val MAX_SUB_STEPS = 16L
