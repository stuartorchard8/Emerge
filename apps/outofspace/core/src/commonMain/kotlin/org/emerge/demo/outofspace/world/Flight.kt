package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * Vessel flight: hull + structures (not gas). Momentum = vesselImpulse, mass = vesselMass, v = p/m.
 * Two systems (ship + gas), two momenta, one ledger (ThrustBalanceTest). Position = only true new state.
 * ⚠️ Spending propellant doesn't lighten ship (gas not in vesselMass; fuel tank arrival = I).
 * Thrust felt before travel: sloshing air → hull recoil (bounded, sealed ship jitters not departs).
 */
object Flight {

    /** Fixed-point: 1e9 units/tile. Overflow threshold ~9.2e9 gram·tiles/tick (40M ticks on bare hull). */
    const val PER_TILE: Long = 1_000_000_000L

    /** [Frac]'s unit: 1 tile/tick² = one whole Frac. Shared to prevent copies. */
    const val FRAC_ONE: Long = Int.MAX_VALUE.toLong()
}


/**
 * Frame acceleration (Frac units): deck gravity minus vessel acceleration = what gas/rocks feel.
 * Two consumers: experiencedGravity (subtracts from plating), feltBy (hands to rock without plating).
 */
fun frameAcceleration(netImpulseX: Long, netImpulseY: Long, mass: Long): Frac2 =
    if (mass <= 0L) Frac2(Frac(0L), Frac(0L))
    else Frac2(
        Frac(scaledRatio(netImpulseX, mass, Flight.FRAC_ONE)),
        Frac(scaledRatio(netImpulseY, mass, Flight.FRAC_ONE)),
    )

/**
 * Everything the hull is made of, in mass.
 *
 * From [Material.massPerTile] and [MachineKind.thermalTiles] — the same two numbers the heat model
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
fun structureMass(machines: List<Machine?>, conduits: Conduits, bridges: List<Machine?>): Long {
    var sum = 0L
    forEachVesselMass(machines, conduits, bridges) { _, fabric, _ -> sum += fabric }
    return sum
}

/**
 * The one walk over everything the vessel weighs, tile by tile.
 *
 * [structureMass], [cargoMass] and [massDistribution] are all projections of this and none of them
 * has its own traversal, for the reason [cargoMass] gives: two implementations of "what is aboard"
 * disagree the first time a buffer is added to one of them. Rotation is what forced the extraction —
 * a moment of inertia needs the same masses *and their positions*, and a second walk to find the
 * positions would be the third answer to the same question.
 *
 * [action] is handed the tile the mass sits on, the fabric there (what the thing is made of) and the
 * cargo there (what it is carrying), kept apart because the two totals are separately meaningful and
 * separately tested.
 *
 * ⚠️ **A multi-tile machine is a point mass at its anchor tile.** That is exactly right for the
 * centre of mass — [coveredTiles] centres a footprint on its anchor, so the anchor *is* the centre —
 * and it is an approximation for the moment of inertia, which loses the machine's own spread about
 * its centre. A five-tile smelter is short by `m·(w²+h²)/12`, about two tile² against the tens to
 * hundreds a real lever arm contributes. Worth knowing, not worth a second walk.
 */
inline fun forEachVesselMass(
    machines: List<Machine?>,
    conduits: Conduits,
    bridges: List<Machine?>,
    action: (tile: Int, fabric: Long, cargo: Long) -> Unit,
) {
    for (t in machines.indices) {
        val m = machines[t] ?: continue
        action(t, m.kind.massPerTile * m.kind.thermalTiles, massIn(m))
    }
    conduits.all { conduit, tile, segment ->
        action(tile, conduit.massPerTile, if (conduit == Conduit.Rail) segment.held?.mass ?: 0L else 0L)
    }
    for (t in bridges.indices) {
        val b = bridges[t] ?: continue
        action(t, b.kind.massPerTile * MachineKind.Bridge.thermalTiles, massIn(b))
    }
}

/**
 * Every gram the vessel is carrying rather than made of: in machine buffers, on the track, on the
 * spans, and lying loose on the deck.
 *
 * The body of [VesselState.inTransitMass], lifted out so that the mass a thrust is divided by and
 * the mass the conservation check compares are provably the same walk. Two implementations of "what
 * is aboard" would disagree the first time a buffer was added to one of them, and the disagreement
 * would look like the ship getting heavier for no reason.
 */
fun cargoMass(
    machines: List<Machine?>,
    conduits: Conduits,
    bridges: List<Machine?>,
): Long {
    var sum = 0L
    forEachVesselMass(machines, conduits, bridges) { _, _, cargo -> sum += cargo }
    return sum
}

/** What a thrust is divided by: the fabric plus what it carries. See [Flight] for why not the gas. */
fun vesselMass(
    machines: List<Machine?>,
    conduits: Conduits,
    bridges: List<Machine?>,
): Long {
    var sum = 0L
    forEachVesselMass(machines, conduits, bridges) { _, fabric, cargo -> sum += fabric + cargo }
    return sum
}

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
fun experiencedGravity(deckGravity: Frac2, netImpulseX: Long, netImpulseY: Long, mass: Long): Frac2 {
    if (mass <= 0L) return deckGravity
    val a = frameAcceleration(netImpulseX, netImpulseY, mass)
    return Frac2(Frac(deckGravity.x.raw - a.x.raw), Frac(deckGravity.y.raw - a.y.raw))
}
