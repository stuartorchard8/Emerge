package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.HeatField

/**
 * How hot the gas is: thermal energy that belongs to the atmosphere, travels with it, and sets its
 * pressure.
 *
 * ### Why this is not [HeatField]
 *
 * The first attempt put the gas's energy into the existing per-tile joules and made a tile's heat
 * capacity include its air. One field, one temperature per room, air and fittings in equilibrium —
 * which is defensible physics at this grain, and it broke immediately for a reason worth writing
 * down.
 *
 * When capacity depends on the air and **energy is the stored quantity with temperature derived**,
 * changing the air silently changes the temperature. `state.copy(air = …)` — a save load, a test
 * fixture, a scenario — would leave the joules alone and reinterpret them against a new capacity. A
 * room given ten kilograms of oxygen read as 57K and stopped behaving like a gas at all. There is no
 * way to make that safe while the two are separate fields on the same object, because `copy` is
 * exactly the operation that lets them disagree.
 *
 * So the gas's energy lives with the gas. It is impossible to move air without moving its heat,
 * because the same pass does both, and the invariant is structural rather than remembered.
 *
 * The cost, stated plainly: there are two temperatures in a tile — the fabric's, in [HeatField], and
 * the air's, here. They are not yet coupled, so a smelter warms the deck plating and not the room.
 * The coupling is one conduction term between the two fields and it belongs with heat's own step
 * coming back on, which is the next piece of work rather than this one. Building it now would mean
 * tuning a coefficient against convection that does not exist yet.
 *
 * ### Why energy is the thing that moves
 *
 * Advecting temperature — Lague's scheme, where temperature is a channel of the smoke texture
 * sampled along with everything else — is right for incompressible passive dye and wrong here, for
 * the same reason storing velocity instead of momentum was wrong: an intensive quantity averaged
 * between unlike cells creates and destroys the extensive one behind it. Two tiles at 300K holding
 * different amounts of gas hold different energies, and any scheme that mixes their temperatures has
 * to invent or lose joules to do it.
 *
 * So joules move, in flux form, as the fraction of the donor's gas that left — the identical rule
 * [advectMomentum] uses, for the identical reason. A tenth of the air leaves, a tenth of the air's
 * heat goes with it, and the arithmetic is one subtraction and one matching addition.
 */

/**
 * Heat capacity of the gas in each tile, in **milli**joules per kelvin. Zero where there is no gas.
 *
 * Milli, and not the obvious joules-per-kelvin, because [Species.specificHeat] is per *kilogram* and
 * the mass is in grams — so the honest figure needs a division by a thousand, and doing it here
 * quantises the capacity to whole joules per kelvin. That sounds harmless and is not: a tile holding
 * less than a gram floors to zero capacity and reads as ambient, while two grams floors to one and
 * reads its entire energy as a single kelvin's worth. The step between them is a cliff, it lands
 * exactly in the thin outer edge of a venting plume, and it showed up as `BreachSymmetryTest`
 * finding trace species leaning by a fifth.
 *
 * So the scale is not divided out at all. The gas's *energy* is carried in millijoules to match, and
 * temperature is then a plain division of like by like — exact at ambient, which is what lets
 * [stepFluid] promise that a vessel at room temperature runs identically to the isothermal sim. A
 * tile of ordinary air is about a million of these units and holds a third of a billion millijoules,
 * nowhere near troubling a `Long`.
 */
fun gasCapacity(tileCount: Int, grams: LongArray, species: List<Species> = Species.GASES): LongArray =
    LongArray(tileCount) { gasCapacityAt(grams, it, species) }

/** Millijoules per kelvin held by the gas in one tile — see [gasCapacity] for the units. */
fun gasCapacityAt(grams: LongArray, tile: Int, species: List<Species> = Species.GASES): Long {
    val base = tile * Species.COUNT
    var sum = 0L
    for (s in species) sum += grams[base + s.ordinal] * s.specificHeat
    return sum
}

/**
 * What both the gas's heat capacity and its energy are scaled by, so the two divide cleanly.
 *
 * [Species.specificHeat] is per kilogram and mass is in grams, so a factor of a thousand has to go
 * somewhere. Putting it in the *unit* rather than in a division keeps `joules / capacity` exact.
 */
const val CAPACITY_SCALE = 1000L

/**
 * Temperature of the gas in each tile, in kelvin.
 *
 * A tile with no gas has no gas temperature, and reads as [HeatField.AMBIENT_KELVIN] rather than as
 * space. That is not a dodge around dividing by zero: [tilePressure] multiplies by this, and nothing
 * times a temperature is still nothing, so the value is only ever a placeholder for an absent
 * quantity. Ambient is the placeholder that makes an empty tile behave identically to how it did
 * before temperature existed, which is what [stepFluid] guarantees for a vessel at room temperature.
 */
fun gasKelvin(gasJoules: LongArray, capacity: LongArray): IntArray =
    IntArray(gasJoules.size) {
        if (capacity[it] <= 0L) HeatField.AMBIENT_KELVIN else (gasJoules[it] / capacity[it]).toInt()
    }

/**
 * The energy a tile's gas holds at room temperature, in millijoules — what a filled vessel starts
 * with, and exactly divisible by its capacity so the temperature reads as ambient on the nose.
 */
fun ambientGasJoules(tileCount: Int, grams: LongArray): LongArray {
    val capacity = gasCapacity(tileCount, grams)
    return LongArray(tileCount) { capacity[it] * HeatField.AMBIENT_KELVIN }
}

/**
 * Moves the gas's heat along with the gas, conserving joules exactly.
 *
 * ### Boundaries
 *
 * Energy leaving on the rim leaves the world, and is returned so the vessel's energy ledger still
 * closes. Venting hot gas is a genuine way for a ship to lose energy — it is what a rocket does —
 * and it must be counted on its way out rather than quietly vanishing, for exactly the reasons
 * [advectMass] counts grams.
 *
 * Nothing arrives from outside: space is empty, and empty is not cold, it is *absent*.
 *
 * [gasJoules] is edited in place. [flux] and [tileGrams] must both be from the same snapshot as each
 * other and as the [advectMass] pass that produced them — the mass field as it was *before* it moved
 * anything, which is why [tileGrams] is passed rather than recomputed.
 *
 * @return the joules that left the grid entirely.
 */
fun advectHeat(
    edges: EdgeGrid,
    gasJoules: LongArray,
    flux: MassFlux,
    tileGrams: LongArray,
): Long {
    // Snapshotted before anything moves, so every transfer is measured against the same field and
    // the result cannot depend on the order faces are visited.
    val before = gasJoules.copyOf()

    val moves = HeatTransfers(edges.xEdgeCount + edges.yEdgeCount, gasJoules.size)
    for (e in 0 until edges.xEdgeCount) {
        val crossing = flux.x[e]
        if (crossing == 0L) continue
        moves.request(crossing, edges.xEdgeBefore(e), edges.xEdgeAfter(e), before, tileGrams)
    }
    for (e in 0 until edges.yEdgeCount) {
        val crossing = flux.y[e]
        if (crossing == 0L) continue
        moves.request(crossing, edges.yEdgeBefore(e), edges.yEdgeAfter(e), before, tileGrams)
    }
    return moves.settle(gasJoules)
}

/**
 * Ask-first-pay-afterwards for the heat the gas is carrying.
 *
 * The same discipline as the mass and momentum passes and for the same reason: a tile can be drained
 * by all four of its faces at once, every flux is computed against one snapshot, so the requests have
 * to be totalled and clamped before any of them is honoured.
 */
private class HeatTransfers(capacity: Int, tileCount: Int) {
    private val amount = LongArray(capacity)
    private val from = IntArray(capacity)
    private val to = IntArray(capacity)
    private var count = 0
    private val available = LongArray(tileCount)
    private val requested = LongArray(tileCount)

    fun request(crossing: Long, before: Int, after: Int, gasJoules: LongArray, tileGrams: LongArray) {
        val donor = if (crossing > 0L) before else after
        val acceptor = if (crossing > 0L) after else before
        if (donor < 0) return // Space has no heat to give.
        val donorGrams = tileGrams[donor]
        if (donorGrams <= 0L) return
        val moving = if (crossing > 0L) crossing else -crossing
        val carried = gasJoules[donor] * moving / donorGrams
        if (carried <= 0L) return

        amount[count] = carried
        from[count] = donor
        to[count] = acceptor
        count++
        available[donor] = gasJoules[donor]
        requested[donor] += carried
    }

    /**
     * Applies every transfer, scaled down where a tile was asked for more than it had.
     *
     * Every clamp is against the **snapshot**, never against the live array. A further guard of
     * `minOf(moving, gasJoules[donor])` looks like free safety and is in fact a Gauss-Seidel sweep:
     * the first face to drain a tile would see its full energy and the second a reduced one, so the
     * answer would depend on the order faces are visited — which on a row-major grid is a leftward
     * bias. That is the exact fault [BreachSymmetryTest] was written for after `applySpeciesDrift`
     * had it, and it caught this one within a tick of it being written.
     *
     * It is also unnecessary. The scaled shares sum to at most `available`, which is what the tile
     * had, so a tile cannot be overdrawn.
     */
    fun settle(gasJoules: LongArray): Long {
        var escaped = 0L
        for (i in 0 until count) {
            val donor = from[i]
            var moving = amount[i]
            val asked = requested[donor]
            if (asked > available[donor]) moving = moving * available[donor] / asked
            if (moving <= 0L) continue
            gasJoules[donor] -= moving
            if (to[i] < 0) escaped += moving else gasJoules[to[i]] += moving
        }
        return escaped
    }
}
