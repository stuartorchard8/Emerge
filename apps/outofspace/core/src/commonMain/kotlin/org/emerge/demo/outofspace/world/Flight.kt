package org.emerge.demo.outofspace.world

import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * How the vessel moves, and what moving does to the people and the gas inside it.
 *
 * ### What "the vessel" is
 *
 * The **hull and everything bolted to or carried by it**: the deck, the conduit layers, the spans,
 * what the machines hold, and the debris on the floor. Not the atmosphere.
 *
 * That boundary is not arbitrary and it is the whole reason this is short. The gas already has its
 * own momentum, on the faces, in [org.emerge.demo.outofspace.world.fluid.MomentumField]; the ship's
 * momentum is [VesselState.vesselImpulseX], which is the running total of everything the gas has
 * handed to a wall, an elbow or a pump intake. Two systems, two momenta, one ledger between them —
 * see `ThrustBalanceTest`. Counting the gas's *mass* against the ship's *momentum* would mix the two
 * halves of that ledger and produce a velocity belonging to neither.
 *
 * So: `p = vesselImpulse`, `m = ` [vesselMassGrams], `v = p / m`. Nothing is integrated and nothing
 * accumulates error, because momentum is the stored quantity and velocity is derived — the same
 * inversion [org.emerge.demo.outofspace.world.fluid.MomentumField] and
 * [org.emerge.demo.outofspace.world.AirField] both make, for the same reason. Only [VesselState.positionX]
 * is genuinely new state, because a position is a history and cannot be derived from anything.
 *
 * ### What this does not model yet
 *
 * **Spending propellant does not lighten the ship.** The gas is not in [vesselMassGrams], so venting
 * a roomful of it changes the thrust and not the mass, and there is no rocket equation here. That is
 * honest for what exists today — the propellant is the atmosphere, and the atmosphere is scenery —
 * and it stops being honest the moment fuel is cargo in a tank, which is increment I. At that point
 * the fuel is already in [cargoGrams] and the mass loss arrives on its own.
 *
 * ### Thrust is felt before it is travelled
 *
 * A sealed vessel whose air is sloshing has a non-zero [VesselState.vesselImpulseX] and therefore a
 * non-zero velocity: the hull genuinely recoils from air moving inside it, and the centre of mass of
 * ship-plus-air stays exactly where it was. It is **bounded** — the ledger says the ship's momentum
 * is minus the gas's, and settled gas has none — so a sealed ship jitters and does not depart. It is
 * a firing one that departs, because what left through the rim never comes back.
 */
object Flight {

    /**
     * Fixed-point units per tile, for velocity and position.
     *
     * A billion, which is coarse for neither. A bare breached hull accelerates at about 2.6e-4 tiles
     * per tick per tick, so a resolution of 1e-9 tiles has four decent digits under the smallest
     * thing worth seeing, and a `Long` of them still spans nine billion tiles of position.
     *
     * The one bound worth stating: [VesselState.velocityX] multiplies the impulse by this before
     * dividing, so it overflows once the accumulated impulse passes ~9.2e9 gram·tiles/tick. On the
     * bare hull that is forty million ticks of continuous thrust. If a real engine ever gets within
     * sight of it, the fix is to divide first and carry the remainder, not to coarsen the unit.
     */
    const val PER_TILE: Long = 1_000_000_000L

    /**
     * [Frac]'s unit, spelled out: one tile per tick per tick is one whole [Frac].
     *
     * Public because three files now turn an impulse into an acceleration and one of them is about
     * rocks — see [Rock]. A second copy of this number is a second chance to get it wrong.
     */
    const val FRAC_ONE: Long = Int.MAX_VALUE.toLong()
}

/**
 * The ship's own acceleration, in [Frac]'s units — the term that makes the vessel's frame a
 * non-inertial one.
 *
 * Named and shared rather than open-coded twice, because it has two consumers that need it for
 * opposite reasons: [experiencedGravity] subtracts it from the plating to get what the gas feels,
 * and [feltBy] hands it to a rock *without* the plating, since a deck's artificial gravity does not
 * reach a rock a hundred tiles astern and the frame's acceleration reaches everything.
 */
fun frameAcceleration(netImpulseX: Long, netImpulseY: Long, massGrams: Long): Frac2 =
    if (massGrams <= 0L) Frac2(Frac(0L), Frac(0L))
    else Frac2(Frac(netImpulseX * Flight.FRAC_ONE / massGrams), Frac(netImpulseY * Flight.FRAC_ONE / massGrams))

/**
 * Everything the hull is made of, in grams.
 *
 * From [Material.gramsPerTile] and [MachineKind.thermalTiles] — the same two numbers the heat model
 * already uses to work out what a thing costs to warm, because they are the same fact about the same
 * object. A furnace is twenty-five tiles of firebrick whether you are asking what it weighs or what
 * it holds, and deriving both from one place is what stops a vessel that is heavy for heat and light
 * for thrust.
 *
 * ⚠️ These masses are tuned two orders of magnitude below real ones — see [Material]'s note. What is
 * right is the *ratio* between materials; the absolute scale is a dial, and here it is the dial that
 * sets how briskly a given thrust moves a given ship.
 */
fun structureMassGrams(machines: List<Machine?>, conduits: Conduits, bridges: List<Machine?>): Long {
    var sum = 0L
    for (m in machines) {
        if (m == null) continue
        sum += m.kind.material.gramsPerTile * m.kind.thermalTiles
    }
    conduits.all { conduit, _, _ -> sum += conduit.material.gramsPerTile }
    for (b in bridges) {
        if (b == null) continue
        sum += b.kind.material.gramsPerTile * MachineKind.Bridge.thermalTiles
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
    debris: Debris,
): Long {
    var sum = debris.totalGrams
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
    debris: Debris,
): Long = structureMassGrams(machines, conduits, bridges) + cargoGrams(machines, conduits, bridges, debris)

/**
 * What the inside of an accelerating vessel feels: the plating's own gravity, less the acceleration.
 *
 * A ship under thrust is a lift going up. Nothing aboard is *pulled* sternward; the deck is pushed
 * forward into it, and in the vessel's own frame — which is the frame the whole grid is written in —
 * that is indistinguishable from a gravity pointing the other way. So the sign is a minus, and the
 * result is what [org.emerge.demo.outofspace.world.fluid.applyBuoyancy],
 * [org.emerge.demo.outofspace.world.fluid.applySpeciesDrift] and [downDirection] are handed instead
 * of the plating's constant. Convection under acceleration, debris settling toward the stern and a
 * heavy gas pooling against the direction of travel all fall out of passes that were written before
 * there was anything to fall out of.
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
 * been found by anyone who had accepted the first.
 *
 * The lean was not the cross-axis term and it was not a feedback loop at all. It was that this sim had
 * spent its entire life at `gravity == Frac(1, 1)`, and several passes scale a quantity by
 * `q * g / SPEED_LIMIT_RAW`, which at exactly one g is the identity and at **anything else** truncates
 * — killing the ones and twos a thin plume is made of, and killing them asymmetrically, because
 * integer division rounds toward zero. The tell was that the lean was bit-identical at 0.9999 g and at
 * 0.99 g: not a sensitivity to how much gravity there is, but a cliff at the one value that had ever
 * been used. See [org.emerge.demo.outofspace.world.fluid.scaleByGravity], which rounds instead, and
 * with which the plume is even under every gravity tried.
 *
 * So there is no projection here. The lesson is the general one and it is the increment's real
 * finding: **anything only ever exercised at one value has not been exercised.** Increment G's whole
 * job is to make gravity a variable, and the first thing it found was a constant that had been quietly
 * load-bearing.
 *
 * [downDirection] rounds an off-axis gravity to the axis it leans toward, for a related reason — a
 * lateral engine under active plating gives a permanently diagonal pull, and the old rule answered
 * "nowhere" to it and froze every pile aboard.
 *
 * ⚠️ **This closes a loop that has never been closed before**: thrust becomes gravity, gravity piles
 * the atmosphere toward the breach, a deeper pile is a higher pressure at the hole, and a higher
 * pressure is more thrust. It is self-limiting only because the gas runs out. The two instruments to
 * watch it with are [org.emerge.demo.outofspace.world.fluid.ProjectionResult.undeliveredX] and
 * [org.emerge.demo.outofspace.world.fluid.FluidStep.subSteps]. Measured on a bare breached hull over
 * three hundred ticks, `undelivered` sits flat at −163 and the acceleration tops out around a six
 * hundredth of a g, so the loop converges — but it converges because the hole is small.
 */
fun experiencedGravity(deckGravity: Frac2, netImpulseX: Long, netImpulseY: Long, massGrams: Long): Frac2 {
    if (massGrams <= 0L) return deckGravity
    val a = frameAcceleration(netImpulseX, netImpulseY, massGrams)
    return Frac2(Frac(deckGravity.x.raw - a.x.raw), Frac(deckGravity.y.raw - a.y.raw))
}
