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
 * second, transport third** — and that way round matters. The projection's whole job is to work out
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
 * ### Venting
 *
 * Gas leaves in two ways, and both are counted. It flows out through the rim faces of the grid under
 * its own pressure — the honest way, carrying momentum with it — and anything still sitting in the
 * outermost ring of tiles at the end of the tick is written off, because that ring is the edge of
 * the world rather than a place. Momentum on a face that has been left with no gas on it goes too:
 * it went with the gas, and leaving it behind would let a vacuum accumulate a push.
 *
 * [grams], [mx] and [my] are the tick's working arrays, **edited in place** — the same arrays the
 * edit pass has already written to, so a hull put down this tick has moved its air out of the way
 * before any of this runs.
 */
fun stepFluid(
    grid: Grid,
    structure: StructureMap,
    grams: LongArray,
    mx: LongArray,
    my: LongArray,
    gravity: Frac2,
): FluidStep {
    val edges = EdgeGrid(grid)
    val apertures = ApertureField.derive(edges, structure)

    // Sorting first, because it moves mass between tiles: the density and pressure fields everything
    // below reads have to be the ones it leaves behind, not the ones it started from.
    applySpeciesDrift(edges, apertures, grams, gravity)

    val tileGrams = tileMass(grid.size, grams)
    val pressure = tilePressure(grid.size, grams)

    val rubbed = applyDrag(edges, mx, my)
    val lift = applyBuoyancy(edges, apertures, mx, my, tileGrams, pressure, gravity)
    val pressed = project(edges, apertures, mx, my, tileGrams, pressure)

    val moved = advectMass(edges, apertures, MomentumField.of(edges, mx, my), grams, Species.GASES, tileGrams)
    val carried = advectMomentum(edges, mx, my, moved.flux, tileGrams)

    var vented = moved.ventedGrams
    for (tile in 0 until grid.size) {
        if (!grid.isEdge(tile)) continue
        val base = tile * Species.COUNT
        for (s in Species.GASES) {
            vented += grams[base + s.ordinal]
            grams[base + s.ordinal] = 0L
        }
    }

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
        air = AirField.of(grams),
        momentumX = mx.copyOf(),
        momentumY = my.copyOf(),
        ventedGrams = vented,
        vesselX = pressed.vesselX + lift.vesselX + rubbed.vesselX,
        vesselY = pressed.vesselY + lift.vesselY + rubbed.vesselY,
        escapedX = carried.x + strandedX,
        escapedY = carried.y + strandedY,
    )
}
