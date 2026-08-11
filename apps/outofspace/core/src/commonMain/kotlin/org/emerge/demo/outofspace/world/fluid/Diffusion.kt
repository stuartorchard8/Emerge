package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * Rapid diffusion: the replacement for the momentum solver.
 *
 * Every cell dumps very nearly all of its contents into its neighbours each tick, expecting them to
 * do the same. There is no velocity, no pressure solve and no sub-stepping — what emerges is a
 * fast-moving equilibrium, which is the only thing the vessel ever wanted from the atmosphere. The
 * shape is lifted from cyto's welded-cell exchange, and the three properties worth copying exactly
 * are:
 *
 * **Two passes, gather not scatter.** Every tile computes its own net delta from the pre-tick
 * snapshot, writing only its own slot; a second pass applies it. Disjoint by index, so this is
 * trivially parallel later and — the part that matters now — order-independent, with no arrival-order
 * bug of the kind [org.emerge.demo.outofspace.world.Segment] had to learn about the hard way.
 *
 * **Conservation is by construction, not by correction.** Each face is evaluated twice, once from
 * each side, through the same [quantum] of the same pre-tick count with the same aperture. What one
 * tile sheds is therefore exactly what its neighbour takes, to the gram, and there is no ledger
 * fix-up pass to get wrong.
 *
 * **A fixed divisor, never a per-degree one.** Dividing by a tile's own degree looks fairer and
 * biases high-degree tiles into piling up about twice their neighbours; a fixed divisor is
 * edge-symmetric, so the steady state is uniform and the divisor sets only the speed, never the
 * bias. See [DENOM].
 */
class DiffusionStep(
    val air: AirField,
    val ventedGrams: Long,
    val ventedJoules: Long,
)

/**
 * The share of a cell's contents that crosses one fully-open face in one tick.
 *
 * It must stay at or above the lattice's maximum degree — 4 on a square grid — because that is what
 * makes the integer floor guarantee `out × degree ≤ count`, i.e. that no tile can shed more than it
 * holds and go negative. Five is therefore the tightest value available, and tightest is what is
 * wanted: it is the "practically all of it" the model is named for, with no convergence transient to
 * sit through.
 */
const val DENOM = 5L

/**
 * How much of [count] crosses a face of width [aperture] in one tick.
 *
 * Multiplication before division, so a narrow face still passes something rather than flooring its
 * way to nothing — the integer lesson [org.emerge.demo.outofspace.world.AirField] paid for once
 * already, where a flux of `rate × gap / ticks` froze every gradient under ten grams.
 */
private fun quantum(count: Long, aperture: Int): Long =
    count * aperture / (ApertureField.OPEN.toLong() * DENOM)

/** Convenience overload deriving the face connectivity from [structure]. */
fun diffuseFluid(
    grid: Grid,
    structure: StructureMap,
    grams: LongArray,
    joules: LongArray? = null,
): DiffusionStep {
    val edges = EdgeGrid(grid)
    return diffuseFluid(edges, ApertureField.derive(edges, structure), grams, joules)
}

/**
 * One tick of diffusion over [grams] (tiles × species) and, if it is being tracked, [joules] (per
 * tile). Both are edited in place.
 *
 * Joules ride the mass rather than diffusing on their own account: what leaves a face is the same
 * fraction of the tile's energy as of its mass, so temperature travels with the gas that carries it
 * and an emptied tile keeps no ghost of heat that nothing is left to hold. Diffusing energy
 * separately would put joules in cells with no capacity to have them, where they read as ambient and
 * are silently lost to every gauge in the vessel.
 *
 * **Venting to the rim stays.** A face with no tile on the far side sheds its quantum into space and
 * books it, which is what keeps breaches and the vent ledger working. It no longer produces thrust —
 * that is [applyPressureForce]'s job, wired up separately.
 */
fun diffuseFluid(
    edges: EdgeGrid,
    apertures: ApertureField,
    grams: LongArray,
    joules: LongArray? = null,
): DiffusionStep {
    val grid = edges.grid
    val tiles = grid.size
    val species = Species.COUNT

    val mass = tileMass(tiles, grams)
    val deltaGrams = LongArray(grams.size)
    val deltaJoules = if (joules == null) null else LongArray(tiles)

    var ventedGrams = 0L
    var ventedJoules = 0L

    for (tile in 0 until tiles) {
        val base = tile * species

        // The tile's own four faces, each as the aperture across it and the tile on the other side
        // (-1 = the rim, i.e. space). Walked in a fixed order, though nothing depends on the order:
        // every term below is a read of the pre-tick snapshot plus an add into this tile's own slot.
        for (face in 0 until 4) {
            val aperture: Int
            val neighbour: Int
            when (face) {
                0 -> {
                    val e = edges.leftEdgeOf(tile)
                    aperture = apertures.xAt(e); neighbour = edges.xEdgeBefore(e)
                }
                1 -> {
                    val e = edges.rightEdgeOf(tile)
                    aperture = apertures.xAt(e); neighbour = edges.xEdgeAfter(e)
                }
                2 -> {
                    val e = edges.upEdgeOf(tile)
                    aperture = apertures.yAt(e); neighbour = edges.yEdgeBefore(e)
                }
                else -> {
                    val e = edges.downEdgeOf(tile)
                    aperture = apertures.yAt(e); neighbour = edges.yEdgeAfter(e)
                }
            }
            if (aperture <= 0) continue

            // ── What this tile sheds across the face ──
            var out = 0L
            for (s in 0 until species) {
                val q = quantum(grams[base + s], aperture)
                if (q == 0L) continue
                deltaGrams[base + s] -= q
                out += q
            }
            val ownMass = mass[tile]
            val outJoules =
                if (joules == null || ownMass <= 0L || out == 0L) 0L else joules[tile] * out / ownMass
            if (deltaJoules != null) deltaJoules[tile] -= outJoules

            if (neighbour < 0) {
                // The rim: gone, and booked. Only the one tile sees this face, so it is booked once.
                ventedGrams += out
                ventedJoules += outJoules
                continue
            }

            // ── What comes back the other way ──
            //
            // Recomputed here from the neighbour's own pre-tick counts and the *same* aperture, which
            // is exactly the figure the neighbour will subtract from itself when its turn comes. That
            // identity is the conservation argument; it is the reason this is not a ledger.
            val far = neighbour * species
            var incoming = 0L
            for (s in 0 until species) {
                val q = quantum(grams[far + s], aperture)
                if (q == 0L) continue
                deltaGrams[base + s] += q
                incoming += q
            }
            if (deltaJoules != null && joules != null) {
                val farMass = mass[neighbour]
                if (farMass > 0L && incoming > 0L) deltaJoules[tile] += joules[neighbour] * incoming / farMass
            }
        }
    }

    for (i in grams.indices) grams[i] += deltaGrams[i]
    if (joules != null && deltaJoules != null) for (t in 0 until tiles) joules[t] += deltaJoules[t]

    return DiffusionStep(
        air = if (joules == null) AirField.of(grams) else AirField.of(grams, joules),
        ventedGrams = ventedGrams,
        ventedJoules = ventedJoules,
    )
}

/**
 * The same tick, shaped so it can be dropped into [stepFluid]'s call sites unchanged.
 *
 * [mx], [my], [gravity] and [volumes] are accepted and ignored. The momentum fields are left exactly
 * as they were found — nothing here writes them, and nothing here reads them — so the overlay, the
 * save format and the ledgers all keep working while thrust is rewired onto blocked flux separately.
 * Gravity has no term in a diffusion model: without a hydrostatic gradient a sealed room at uniform
 * pressure is uniform, which is why the impulse this reports is exactly zero rather than nearly so.
 */
@Suppress("UNUSED_PARAMETER")
fun diffuseAsFluidStep(
    edges: EdgeGrid,
    apertures: ApertureField,
    grams: LongArray,
    mx: LongArray,
    my: LongArray,
    gravity: Frac2,
    gasJoules: LongArray? = null,
    volumes: VolumeField? = null,
): FluidStep {
    val step = diffuseFluid(edges, apertures, grams, gasJoules)
    return FluidStep(
        air = step.air,
        momentumX = mx.copyOf(),
        momentumY = my.copyOf(),
        ventedGrams = step.ventedGrams,
        ventedJoules = step.ventedJoules,
        vesselX = 0L,
        vesselY = 0L,
        escapedX = 0L,
        escapedY = 0L,
        undeliveredX = 0L,
        undeliveredY = 0L,
        subSteps = 1,
    )
}
