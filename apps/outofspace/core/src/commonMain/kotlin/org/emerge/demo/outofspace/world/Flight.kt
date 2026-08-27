package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Thruster
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

    /**
     * **The dial.** How fast gas leaves a hole in the hull, in metres per second.
     *
     * Roughly sonic, which is what escaping gas does: a hole into vacuum chokes, and the exit speed
     * stops depending on how hard you push. Stated in metres per second and converted in one place
     * for the reason [org.emerge.demo.outofspace.world.machine.Thruster.EXHAUST_METRES_PER_SECOND]
     * is — it is the number worth arguing about, and the conversion should not be scattered where
     * the next change of tick rate can miss one.
     *
     * ⚠️ **Not the quarter-tile-per-tick the old pressure solver used.** That figure was pinned by
     * the CFL limit because gas was being *integrated* across the grid at it. Nothing is integrated
     * here: this is a multiplier on a mass to get an impulse, exactly as the thruster's is, so the
     * real number is both available and correct. Using the CFL one would make every breach about
     * twenty times too feeble.
     */
    const val VENT_METRES_PER_SECOND: Long = 340L

    /** [VENT_METRES_PER_SECOND] in the unit momentum is counted in: tiles per tick. */
    fun ventTilesPerTick(ticksPerSecond: Int): Long =
        VENT_METRES_PER_SECOND * 1_000L / (Thruster.TILE_MILLIMETRES * ticksPerSecond)

    /**
     * **The dial.** How long the atmosphere takes to catch up with a hull that has changed speed,
     * in seconds.
     *
     * The vessel and its air are two bodies: the air is not in [VesselState.mass] and never has
     * been, so a ship that accelerates leaves its atmosphere behind and has to drag it along. This
     * is how hard it drags. Small is a stiff coupling — air that might as well be bolted down; large
     * is a ship that swims inside its own gas and keeps drifting after the engines cut.
     *
     * ⚠️ **Not a physical constant, and there is no right answer to look up.** Real air in a real
     * room follows its walls in well under a second, through viscosity and pressure waves this sim
     * does not have. Two seconds is chosen to be *felt* — a ship whose air visibly lags is the point
     * of modelling it at all.
     */
    const val AIR_COUPLING_SECONDS: Long = 2L

    /**
     * What share of the gap between hull and air is closed each time the coupling fires, in permille.
     *
     * Scaled by the firing period so that moving the fluid cadence changes how *often* the air is
     * dragged and not how *fast* it catches up.
     */
    fun airCouplingPermille(ticksPerSecond: Int, period: Int): Int =
        (1000L * period / (AIR_COUPLING_SECONDS * ticksPerSecond)).coerceIn(1L, 1000L).toInt()

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
 * From [Material.massPerTile] and [thermalTiles] — the same two numbers the heat model
 * already uses to work out what a thing costs to warm, because they are the same fact about the same
 * object. A furnace is twenty-five tiles of firebrick whether you are asking what it weighs or what
 * it holds, and deriving both from one place is what stops a vessel that is heavy for heat and light
 * for thrust.
 *
 * ⚠️ These are real masses now: a real density from [Material.composition], at the fraction of a
 * tile the machine actually is ([DeckMachineKind.fillPermille]). The dial that sets how briskly a given
 * thrust moves a given ship is that fill fraction, and it is the only dial left — the densities are
 * measurements.
 */
fun structureMass(grid: Grid, rail: RailLayer, conduits: Conduits, deck: DeckArray, buffers: BufferLayer): Long {
    var sum = 0L
    forEachVesselMass(grid, rail, conduits, deck, buffers) { _, fabric, _ -> sum += fabric }
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
 * ⚠️ **The fabric is booked tile by tile and the cargo at the anchor**, which is the distinction the
 * loop below turns on. Fabric per tile is exact for the centre of mass whatever shape the footprint
 * is, and that matters now that a footprint need not be centred on its anchor at all — a thruster's
 * metal is half in its chamber and half in its bell, and booking the lot at the chamber would put
 * the ship's centre of mass a little astern of where it is. Cargo stays at the anchor because a
 * machine's stores are addressed from there; it is an approximation for the moment of inertia, which
 * loses the load's own spread about the anchor. A five-tile smelter is short by `m·(w²+h²)/12`,
 * about two tile² against the tens to hundreds a real lever arm contributes. Worth knowing, not
 * worth a second walk.
 */
inline fun forEachVesselMass(
    grid: Grid,
    rail: RailLayer,
    conduits: Conduits,
    deck: DeckArray,
    buffers: BufferLayer,
    action: (tile: TileIndex, fabric: Long, cargo: Long) -> Unit,
) {
    // The deck layer weighs the same way the machine list does, tile by tile — a hull plate that
    // moved into `deck` must not stop being part of what a thrust is divided by. Booked at the tile
    // the metal is on rather than at the machine's anchor, which is the same answer for a one-tile
    // machine and the better one for the centre of mass when a bigger one lands here.
    for (t in 0 until deck.size) {
        val m = deck[TileIndex(t)] ?: continue
        // Weighed from the matter stored on each tile rather than from `kind.massPerTile`. The two
        // are equal to the unit — [tileBillOfMaterials] apportions, and it is measured identical for
        // every kind — so this is a change of representation and not of what the ship weighs. It has
        // to move at the same time as the fill in `+=`, or the deck is counted twice or not at all.
        // Fabric tile by tile, cargo once at the centre. ⚠️ The cargo term is not optional now that
        // buffered kinds live here: a warehouse's contents are aboard, and passing 0L for every deck
        // machine made twenty tonnes of ore vanish from `cargoMass` — which reads as the world
        // losing mass rather than as anything to do with storage.
        for (tile in m.tiles(grid)) action(tile, deck.stuff.massAt(tile), 0L)
        action(m.center, 0L, massIn(m, m.center, grid, buffers))
    }
    conduits.all { conduit, tile, _ ->
        // Weighed off the layer for the reason the deck is, and with the same guarantee:
        // [conduitBillOfMaterials] apportions, so a tile of track weighs `conduit.massPerTile` to
        // the unit and this is a change of representation, not of what the ship weighs.
        action(tile, conduits.massAt(conduit, tile), if (conduit == Conduit.Rail) rail.massAt(tile) else 0L)
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
    grid: Grid,
    rail: RailLayer,
    conduits: Conduits,
    deck: DeckArray,
    buffers: BufferLayer,
): Long {
    var sum = 0L
    forEachVesselMass(grid, rail, conduits, deck, buffers) { _, _, cargo -> sum += cargo }
    return sum
}

/** What a thrust is divided by: the fabric plus what it carries. See [Flight] for why not the gas. */
fun vesselMass(
    grid: Grid,
    rail: RailLayer,
    conduits: Conduits,
    deck: DeckArray,
    buffers: BufferLayer,
): Long {
    var sum = 0L
    forEachVesselMass(grid, rail, conduits, deck, buffers) { _, fabric, cargo -> sum += fabric + cargo }
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
  * ⚠️ Thrust→gravity→pressure→thrust loop: self-limiting (gas runs out) and no longer a loop at all — pressure does not push the hull.
  */
fun experiencedGravity(deckGravity: Frac2, netImpulseX: Long, netImpulseY: Long, mass: Long): Frac2 {
    if (mass <= 0L) return deckGravity
    val a = frameAcceleration(netImpulseX, netImpulseY, mass)
    return Frac2(Frac(deckGravity.x.raw - a.x.raw), Frac(deckGravity.y.raw - a.y.raw))
}

/**
 * What the coupling moves between a hull and its own atmosphere in one firing.
 *
 * [carriedX]/[carriedY]/[carriedTorque] left the vessel with vented gas and belong to
 * [VesselState.ventMomentumX]; [dragX]/[dragY]/[dragTorque] moved from the ship to the air and
 * belong to [VesselState.airMomentumX], with the ship taking the negative of each.
 */
data class AirExchange(
    val dragX: Long, val dragY: Long, val dragTorque: Long,
    val carriedX: Long, val carriedY: Long, val carriedTorque: Long,
) {
    companion object { val NONE = AirExchange(0L, 0L, 0L, 0L, 0L, 0L) }
}

/**
 * The ship and its air are two bodies, and this is the whole of what passes between them.
 *
 * A hull that changes speed leaves its gas behind: the atmosphere is not in [VesselState.mass] —
 * [forEachVesselMass] takes no air argument — so it is a body beside the vessel rather than part of
 * it, and dragging it along costs the ship momentum it has to be charged for.
 *
 * **Two things happen here and the order matters.**
 *
 * 1. **Gas that left takes its share with it**, in proportion to the mass that went. This has to
 *    come first, or the store outlives the gas that was carrying it: the coupling then reads a
 *    momentum too large for the mass still aboard, decides the air is running ahead of the hull, and
 *    hands the difference **back to the ship** — a reactionless drive built out of the repair for a
 *    reactionless drive.
 * 2. **The rest is dragged toward the hull's own motion**, by [sharePermille] of the gap. The target
 *    is written as the ship's momentum through the mass ratio rather than as a velocity, so no
 *    intermediate `p/m` is ever formed and rounded: `M_air · v_ship` is exactly
 *    `p_ship · M_air / M_ship`.
 *
 * The angular half is the same statement about a shared ω — `L_air = ω · I_air`, so the target is
 * the ship's angular momentum through the ratio of the two moments, divided gyration-first for the
 * reason [angularVelocity] gives.
 *
 * ⚠️ **A pure function, and deliberately.** This lived inline in the reducer's tick, where the only
 * way to ask it a question was to fly a ship and watch — and what it does is a couple of per cent
 * against a breach reaction a thousand times larger, so a test written that way measured nothing and
 * passed whether the first half was there or not. Two integration-level attempts failed to
 * discriminate it, by 0% and 1.6%. The law is small and exact; it wants asking, not observing.
 */
fun airCoupling(
    airMomentumX: Long,
    airMomentumY: Long,
    airAngImpulse: Long,
    /** The atmosphere's mass before this tick's venting. */
    airMassBefore: Long,
    ventedMass: Long,
    vesselImpulseX: Long,
    vesselImpulseY: Long,
    angImpulse: Long,
    ship: MassDistribution,
    /** The atmosphere's distribution *after* venting, taken about the ship's centre of mass. */
    air: MassDistribution,
    sharePermille: Int,
): AirExchange {
    val carriedX: Long
    val carriedY: Long
    val carriedTorque: Long
    if (airMassBefore > 0L && ventedMass > 0L) {
        val gone = ventedMass.coerceAtMost(airMassBefore)
        carriedX = scaledRatio(airMomentumX, airMassBefore, gone)
        carriedY = scaledRatio(airMomentumY, airMassBefore, gone)
        carriedTorque = scaledRatio(airAngImpulse, airMassBefore, gone)
    } else {
        carriedX = 0L; carriedY = 0L; carriedTorque = 0L
    }
    if (air.mass <= 0L || ship.mass <= 0L) {
        return AirExchange(0L, 0L, 0L, carriedX, carriedY, carriedTorque)
    }

    val heldX = airMomentumX - carriedX
    val heldY = airMomentumY - carriedY
    val heldAng = airAngImpulse - carriedTorque
    val dragTorque =
        if (air.gyrationSq <= 0L || ship.gyrationSq <= 0L) 0L
        else {
            val byGyration = scaledRatio(angImpulse, ship.gyrationSq, air.gyrationSq)
            (scaledRatio(byGyration, ship.mass, air.mass) - heldAng) / 1000L * sharePermille
        }
    return AirExchange(
        dragX = (scaledRatio(vesselImpulseX, ship.mass, air.mass) - heldX) / 1000L * sharePermille,
        dragY = (scaledRatio(vesselImpulseY, ship.mass, air.mass) - heldY) / 1000L * sharePermille,
        dragTorque = dragTorque,
        carriedX = carriedX, carriedY = carriedY, carriedTorque = carriedTorque,
    )
}
