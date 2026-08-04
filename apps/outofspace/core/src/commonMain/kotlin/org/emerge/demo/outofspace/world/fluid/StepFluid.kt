package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * One tick of the fluid: what the air became, what left, and what the vessel felt.
 *
 * [vesselX] and [vesselY] are the impulse delivered to the ship — gas leaning on bulkheads, and the
 * deck holding its air up. [escapedX] and [escapedY] are the momentum that went overboard with
 * escaping gas. The two are not the same number and neither is redundant: the first is what pushes
 * the vessel *now*, the second is the exhaust it pushed against, and in steady flight they should
 * mirror each other.
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
)

/**
 * Advances the atmosphere one tick: sorting, then forces, then pressure, then transport.
 *
 * ### The order, and why it is this one
 *
 * Forces first, because gravity acts on the gas as it is at the start of the tick. **Pressure
 * second, transport third** — and that way round matters.
 *
 * "Forces" now includes [applyPressureForce], and it belongs with gravity rather than with the
 * projection even though both are about pressure. The distinction is that this one is a force the
 * gas exerts on itself from the pressure it actually has, and the projection is a *correction* that
 * makes the resulting field consistent. Both read the same equation-of-state field; only the first
 * gives the gas a speed of sound. Leaving it out was why a breached vessel vented *nothing at all* —
 * see [applyPressureForce] for the arithmetic of that failure.
 *
 * Pressure second, transport third. The projection's whole job is to work out
 * the velocity field that satisfies what the gas is trying to do; advecting before solving would
 * move mass according to a field that does not yet satisfy anything, piling it up in places the
 * pressure was about to forbid. Solve, then move what the solution says to move.
 *
 * Both transport passes read the mass field *as it was before either of them ran*, which is why
 * [tileGrams] is computed once and handed to both. Momentum rides on the mass fluxes, and a flux is
 * only meaningful against the density that produced it.
 *
 * ### What replaced what
 *
 * This is the successor to `stepAir`, and it is worth being precise about what it retires. The
 * fifteen relaxation passes are gone: they equalised pressure without ever producing motion, because
 * diffusion has no momentum — air could arrive somewhere but never be *going* anywhere. And
 * `stratifyColumns` is gone, replaced between [applySpeciesDrift] and [applyBuoyancy]; with it goes
 * the last piece of the sim that had to be told which way was down.
 *
 * It took two passes to replace, not one, and that split is the useful thing this increment
 * learned. Buoyancy sinks a heavy *parcel* through lighter surroundings, which needs somewhere to
 * circulate; drift separates a *mixture* that is already sharing a tile, which bulk flow provably
 * cannot do — one velocity field carries every species the same way at the same speed, so it can
 * never change the ratio between them. A sealed one-tile-wide column needs the second and gets
 * nothing at all from the first.
 *
 * ### Venting, and why there is only one way to do it
 *
 * Gas leaves by flowing out through the rim faces under its own pressure, carrying its momentum with
 * it, and [advectMass] counts what crosses. That is the whole mechanism.
 *
 * There used to be a second one: anything still sitting in the outermost ring of tiles was written
 * off at the end of every tick, on the grounds that the ring is the edge of the world rather than a
 * place. That is defensible bookkeeping and a disastrous boundary condition, because a ring that is
 * emptied every tick is a **permanent hard vacuum**. Every cell beside it read a neighbour at zero
 * pressure, so [wantedDivergence] granted it full expansion and [applyPressureForce] gave it a
 * full-atmosphere shove, every tick, forever, whatever the gas was doing. The rim stopped being an
 * exit and became a pump — and one with infinite suction sitting a fixed distance from anything
 * built near it.
 *
 * The cost was that **where a vessel sat in its grid changed how it vented**. The same hull with a
 * centred breach leaned 28% one tile from the rim and 0% forty tiles away. It was mistaken for a
 * fluid-model bug twice, and it was neither: it was a boundary quietly rewriting the problem.
 *
 * Deleting the ring deletion fixes it at the source. The rim tiles are ordinary tiles that fill and
 * push back like any others, the outermost *faces* open onto nothing, and gas crosses them when the
 * pressure behind it says so — which is what an opening onto space is. Nothing has to be told where
 * the edge of the world is.
 *
 * Momentum on a face left with no gas on it still goes: it went with the gas, and leaving it behind
 * would let a vacuum accumulate a push.
 *
 * ### Temperature
 *
 * [gasJoules] is the atmosphere's own thermal energy — see [Thermal.kt][advectHeat] for why it is
 * the gas's and not the tile's. Passing it turns two things on at once: pressure becomes `n × T`
 * rather than `n` alone (see [tilePressure]), and heat travels with the gas holding it (see
 * [advectHeat]). Convection is the two of them together and is implemented nowhere — a warmed tile
 * reads as higher pressure, so [applyBuoyancy] finds it light for what it is pushing and lifts it,
 * and [advectHeat] means the warmth goes up with the parcel instead of staying behind to be lifted
 * again.
 *
 * It is nullable, and null means "run the isothermal sim". Every fluid test with no opinion about
 * heat gets exactly the behaviour it had before, which is what makes this increment's effect on
 * venting and thrust measurable rather than tangled up with re-baselined expectations.
 *
 * ### Volume
 *
 * [volumes] says how much room each cell's gas has, and null means every cell is a whole tile —
 * which is what every cell was until pipes needed to be smaller than one. See [VolumeField].
 *
 * It reaches exactly two of the passes below, and that is the useful thing to know about it rather
 * than an implementation detail: [tilePressure], because the same gas in less room pushes harder, and
 * [applyBuoyancy], because that is the one pass comparing a parcel against what its surroundings
 * weigh. Every other use of [tileGrams] in this file is a **mass** — the fraction of it that crosses
 * a face, the mass a momentum is divided by to get a velocity, the mass a velocity cap is measured
 * against — and none of those change because a box got smaller. Conflating the two would make a
 * narrow pipe advect and accelerate wrongly while looking superficially more physical.
 *
 * ### Connectivity is an argument, not a derivation
 *
 * There are two entry points and the difference between them is the whole of what a pipe will be.
 *
 * This one is the vessel's own atmosphere: hand it a [StructureMap] and it works out which faces are
 * open from what has been built, which is the only sensible reading of "the air in the ship". The
 * other takes an [ApertureField] already made up, and exists because the pipe layer's connectivity is
 * not derivable from the structure at all — a pipe conducts where the player **drew a link**, which
 * is a fact about `Segment.links` rather than about which tiles are solid.
 *
 * Splitting them costs one delegating call and buys the thing §5b promised the aperture decision
 * would buy: a second body of fluid is the same solver pointed at a different adjacency, not a second
 * solver. Nothing below this line knows which layer it is running on.
 *
 * [grams], [mx], [my] and [gasJoules] are the tick's working arrays, **edited in place** — the same
 * arrays the edit pass has already written to, so a hull put down this tick has moved its air out of
 * the way before any of this runs.
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
 * The solver proper, over whatever [apertures] say is connected to what.
 *
 * Everything [stepFluid]'s own documentation says about ordering, venting, temperature and volume
 * applies here unchanged — this is the body that used to be inline in it, and the only thing that
 * moved is where the aperture field comes from.
 *
 * The one thing worth adding is what a *disconnected* cell does, because the pipe layer is mostly
 * disconnected and the vessel's atmosphere never was. A cell with every face shut couples to nothing,
 * so [project] leaves it alone, [applyPressureForce] finds no open face to push through, and
 * [advectMass] moves nothing across a closed aperture. It simply sits there holding what it holds.
 * That falls out of the existing passes rather than needing a guard, which is the payment for having
 * made the aperture an area from the start.
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

    val moved = advectMass(edges, apertures, MomentumField.of(edges, mx, my), grams, Species.GASES, tileGrams)
    val carried = advectMomentum(edges, mx, my, moved.flux, tileGrams)
    // Heat rides the same fluxes as momentum, off the same pre-advection snapshot, for the same
    // reason: a fraction of the mass leaving takes that fraction of what the mass was carrying.
    val ventedJoules =
        if (gasJoules == null) 0L else advectHeat(edges, gasJoules, moved.flux, tileGrams)

    val vented = moved.ventedGrams

    // Momentum cannot outlive the gas carrying it. Anything on a face that has just been emptied
    // left with what was on it; keeping it would let a vacuum quietly store a shove.
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
        escapedX = carried.x + strandedX,
        escapedY = carried.y + strandedY,
    )
}
