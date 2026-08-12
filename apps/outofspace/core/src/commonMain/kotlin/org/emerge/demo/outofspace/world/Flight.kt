package org.emerge.demo.outofspace.world

import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * Vessel flight: hull + structures (not gas). Momentum = vesselImpulse, mass = vesselMassGrams, v = p/m.
 * Two systems (ship + gas), two momenta, one ledger (ThrustBalanceTest). Position = only true new state.
 * ⚠️ Spending propellant doesn't lighten ship (gas not in vesselMassGrams; fuel tank arrival = I).
 * Thrust felt before travel: sloshing air → hull recoil (bounded, sealed ship jitters not departs).
 */
object Flight {

    /** Fixed-point: 1e9 units/tile. Overflow threshold ~9.2e9 gram·tiles/tick (40M ticks on bare hull). */
    const val PER_TILE: Long = 1_000_000_000L

    /** [Frac]'s unit: 1 tile/tick² = one whole Frac. Shared to prevent copies. */
    const val FRAC_ONE: Long = Int.MAX_VALUE.toLong()
}

/**
 * `numerator × scale / denominator`, computed so that the **mass unit cannot overflow it**.
 *
 * Step 4 of `PLAN_unit_rescale.md`. Every velocity and acceleration in the game is a momentum over a
 * mass, put into fixed point — and written the obvious way, `impulse * PER_TILE / mass`, that product
 * is the tightest expression in the codebase. `NumericLimitsTest` measures it at a safe mass scale of
 * **16.7**, against the 10⁶ the rescale is aiming at.
 *
 * ### Why the usual fix does not work here
 *
 * The standard repair is to split whole part and remainder, as `gramsPerTileOf` does. It is worth
 * almost nothing at this site, and the reason is worth stating because it is not obvious: splitting
 * turns the worst intermediate from `impulse × scale` into `mass × scale`, and `impulse = mass ×
 * velocity`, so the whole gain is a factor of the top speed — **two**. Both terms scale with the mass
 * unit together, so no amount of rearranging a product of them buys an order of magnitude.
 *
 * ### What actually works: reduce the fraction first
 *
 * A velocity is a *ratio*, and a ratio does not care what unit its two halves are in as long as they
 * are in the same one. So this shifts both down together until the denominator is small enough that
 * the scaling cannot overflow, and only then does the arithmetic. The result is **scale-invariant**:
 * it costs nothing at one gram per unit, and it holds at a microgram, and at any unit after that,
 * without anybody having to come back and re-derive a bound.
 *
 * The precision given up is not real. The denominator keeps at least 33 bits after the reduction, so
 * the ratio is good to about one part in 10¹⁰ — two orders finer than the 10⁻⁹ of a tile
 * [Flight.PER_TILE] can express in the first place. Shifting rather than dividing by an arbitrary
 * factor keeps it exact for the (common) case where no reduction is needed at all.
 *
 * ⚠️ **The whole part is still a plain multiply.** `n / d * scale` overflows if the ratio itself is
 * enormous — a gram of hull carrying a ship's momentum. That was true of the old form too and is not
 * a regression, but it is the one case this does not cover: it bounds the *unit*, not the *physics*.
 */
fun scaledRatio(numerator: Long, denominator: Long, scale: Long): Long {
    if (denominator <= 0L || numerator == 0L) return 0L
    var n = numerator
    var d = denominator
    // Below this, `remainder × scale` cannot overflow, because the remainder is smaller than `d`.
    val ceiling = Long.MAX_VALUE / scale
    while (d > ceiling) {
        n = n shr 1
        d = d shr 1
    }
    // Exact for the reduced pair, and for both signs: Kotlin truncates toward zero and `%` takes the
    // dividend's sign, so the whole part and the remainder always agree about which way they lean.
    return n / d * scale + n % d * scale / d
}

/**
 * Frame acceleration (Frac units): deck gravity minus vessel acceleration = what gas/rocks feel.
 * Two consumers: experiencedGravity (subtracts from plating), feltBy (hands to rock without plating).
 */
fun frameAcceleration(netImpulseX: Long, netImpulseY: Long, massGrams: Long): Frac2 =
    if (massGrams <= 0L) Frac2(Frac(0L), Frac(0L))
    else Frac2(
        Frac(scaledRatio(netImpulseX, massGrams, Flight.FRAC_ONE)),
        Frac(scaledRatio(netImpulseY, massGrams, Flight.FRAC_ONE)),
    )

/**
 * Everything the hull is made of, in grams.
 *
 * From [Material.gramsPerTile] and [MachineKind.thermalTiles] — the same two numbers the heat model
 * already uses to work out what a thing costs to warm, because they are the same fact about the same
 * object. A furnace is twenty-five tiles of firebrick whether you are asking what it weighs or what
 * it holds, and deriving both from one place is what stops a vessel that is heavy for heat and light
 * for thrust.
 *
 * ⚠️ These are real masses now: a real density from [Material.composition], at the fraction of a
 * tile the machine actually is ([MachineKind.fillPermille]). The dial that sets how briskly a given
 * thrust moves a given ship is that fill fraction, and it is the only dial left — the densities are
 * measurements.
 */
fun structureMassGrams(machines: List<Machine?>, conduits: Conduits, bridges: List<Machine?>): Long {
    var sum = 0L
    for (m in machines) {
        if (m == null) continue
        sum += m.kind.gramsPerTile * m.kind.thermalTiles
    }
    conduits.all { conduit, _, _ -> sum += conduit.gramsPerTile }
    for (b in bridges) {
        if (b == null) continue
        sum += b.kind.gramsPerTile * MachineKind.Bridge.thermalTiles
    }
    return sum
}

/**
 * Every gram the vessel is carrying rather than made of: in machine buffers, on the track, on the
 * spans, and lying loose on the deck.
 *
 * The body of [VesselState.inTransitGrams], lifted out so that the mass a thrust is divided by and
 * the mass the conservation check compares are provably the same walk. Two implementations of "what
 * is aboard" would disagree the first time a buffer was added to one of them, and the disagreement
 * would look like the ship getting heavier for no reason.
 */
fun cargoGrams(
    machines: List<Machine?>,
    conduits: Conduits,
    bridges: List<Machine?>,
): Long {
    var sum = 0L
    for (m in machines) sum += massIn(m)
    for (r in conduits[Conduit.Rail]) sum += r?.held?.mass ?: 0L
    for (b in bridges) sum += massIn(b)
    return sum
}

/** What a thrust is divided by: the fabric plus what it carries. See [Flight] for why not the gas. */
fun vesselMassGrams(
    machines: List<Machine?>,
    conduits: Conduits,
    bridges: List<Machine?>,
): Long = structureMassGrams(machines, conduits, bridges) + cargoGrams(machines, conduits, bridges)

/**
 * What the inside of an accelerating vessel feels: the plating's own gravity, less the acceleration.
 *
 * A ship under thrust is a lift going up. Nothing aboard is *pulled* sternward; the deck is pushed
 * forward into it, and in the vessel's own frame — which is the frame the whole grid is written in —
 * that is indistinguishable from a gravity pointing the other way. So the sign is a minus, and the
 * result is what [org.emerge.demo.outofspace.world.applyBuoyancy],
 * [org.emerge.demo.outofspace.world.applySpeciesDrift] and [downDirection] are handed instead
 * of the plating's constant. Convection under acceleration and a heavy gas pooling against the
 * direction of travel all fall out of passes that were written before there was anything to fall out of.
 *
 * [netImpulseX] is one tick's change in the ship's momentum — not the running total — because what is
 * felt is a force and not a history. Divided by the ship's mass it is an acceleration in tiles per
 * tick per tick, which is exactly the unit [VesselState.PLATING_ONE_G] is already stated in.
 *
 * ### What moving gravity off exactly one g cost, because it was not nothing
 *
 * Both components are applied, unprojected. An earlier version of this dropped the cross-axis one, on
 * the theory that a breach venting straight up still books a little sideways impulse from the grid's
 * row-major asymmetry, and that feeding that back would amplify noise into a lean. `BreachSymmetryTest`
 * did indeed go from even to a **nine per cent lean**, so the theory had evidence — and it was the
 * wrong theory, which is worth recording because the right one was two layers down and would not have
  * ⚠️ **Gravity must not truncate**: q*g/SPEED_LIMIT at non-1g killed thin plumes asymmetrically.
  * Fix: scaleByGravity rounds instead (was identity at g=1, truncation at all other values).
  * [downDirection] rounds off-axis gravity to lean axis (prevents frozen piles under diagonal plating).
  * ⚠️ Thrust→gravity→pressure→thrust loop: self-limiting (gas runs out), converges (undelivered flat at −163).
  */
fun experiencedGravity(deckGravity: Frac2, netImpulseX: Long, netImpulseY: Long, massGrams: Long): Frac2 {
    if (massGrams <= 0L) return deckGravity
    val a = frameAcceleration(netImpulseX, netImpulseY, massGrams)
    return Frac2(Frac(deckGravity.x.raw - a.x.raw), Frac(deckGravity.y.raw - a.y.raw))
}
