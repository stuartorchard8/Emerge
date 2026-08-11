package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.StructureMap

/**
 * Rapid diffusion: the replacement for the momentum solver.
 *
 * Every cell splits its contents [SLOTS] ways each tick — one share for each of its four faces and
 * one that stays put — and hands the face shares to its neighbours, expecting them to do the same.
 * There is no velocity, no pressure solve and no sub-stepping; what emerges is a fast-moving
 * equilibrium, which is the only thing the vessel ever wanted from the atmosphere. Three properties
 * are load-bearing:
 *
 * **The remainder stays home.** Each face takes `count / SLOTS`, and whatever will not divide is
 * simply left where it is.
 *
 * It was briefly handed round the faces instead, in a rotation that turned with the tick, so that a
 * cell holding fewer than [SLOTS] grams of a species could still shed them — otherwise a breached
 * room drains to a few grams a tile and stops. **Reverted 2026-08-12 (Stu), because it flew the
 * ship.** The rotation moved a gram or two per tile per tick, and since every tile shared one offset
 * it moved them *in step*: a grid-wide ripple in the pressure field, on a five-tick cycle, with no
 * reason to average to nothing on a hull that is not symmetric. [applyPressureForce] reads that
 * field, so the ripple became a small standing shove and a sealed vessel slowly departed.
 *
 * What is given up is stated plainly, because it is a real effect: a trace below the divisor **stays
 * put**, so a vented room keeps a few grams a tile. Nothing is lost — rounding down strands mass
 * here, it never destroys it — and the cheap fix, if the residue ever matters, is a rule for rim
 * faces alone, which empties the room without perturbing a single interior pressure.
 *
 * **Conservation is by construction, not by correction.** Every gram subtracted from one tile is
 * added to a named neighbour or booked as vented in the same statement, so there is no ledger to
 * reconcile and no fix-up pass to get wrong.
 *
 * **A fixed divisor, never a per-degree one.** Dividing by a tile's own degree looks fairer and
 * biases high-degree tiles into piling up about twice their neighbours; a fixed divisor is
 * edge-symmetric, so the steady state is uniform and the divisor sets only the speed, never the
 * bias. See [SLOTS].
 *
 * Deltas are accumulated into a scratch field and applied at the end, so a tile's neighbours are read
 * as they were at the start of the tick and never mid-move. Within that, this scatters — a tile pushes
 * into its neighbours' deltas rather than each tile gathering its own. Integer addition commutes, so
 * the result does not depend on visit order either way; what scattering gives up is being trivially
 * parallel by index, and what it buys is not having to replay a neighbour's entire remainder rotation
 * and aperture arithmetic to learn one number.
 */
class DiffusionStep(
    val air: AirField,
    val ventedGrams: Long,
    val ventedJoules: Long,
    /**
     * What actually crossed each face this pass, and the mass it came out of — the raw material for
     * [flow], kept separate so a caller that never asks the question never pays for the answer.
     */
    private val edges: EdgeGrid,
    private val fluxX: LongArray,
    private val fluxY: LongArray,
    private val startingMass: LongArray,
    private val endingMass: LongArray,
) {

    /**
     * Where the fluid went, as a picture — see [FlowField].
     *
     * Lazy because the pipes run the same step and never ask, and because the room only asks when
     * the flow overlay is up.
     */
    val flow: FlowField by lazy { FlowField.derive(edges, fluxX, fluxY, startingMass, endingMass) }
}

/**
 * The ways a cell's contents are divided each tick: its four faces, plus itself.
 *
 * The share that stays home is what keeps this stable rather than a cell emptying itself completely
 * into a ring that empties straight back. The count must also stay at or above the lattice's maximum
 * degree — 4 on a square grid — so that a cell can never promise its faces more than it holds; five
 * is therefore the tightest value available, and tightest is wanted, since it is the "practically all
 * of it" the model is named for with no convergence transient to sit through.
 */
const val SLOTS = 5

/**
 * A tile's four faces, in the order they are walked: up, down, left, right. The fifth share of
 * [SLOTS] is the one that stays home, which is why there is no slot for it here.
 */
private const val FACES = 4

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
 * Joules ride the mass rather than diffusing on their own account: what leaves across a face is the
 * same fraction of the tile's energy as of its mass, so temperature travels with the gas carrying it.
 * Diffusing energy separately would put joules in cells with no capacity to have them, where they
 * read as ambient and are silently lost to every gauge in the vessel. The split is *telescoped* —
 * each face is given the difference between two running totals rather than its own rounded-down
 * fraction — so a tile whose mass leaves entirely has no energy left behind either. Ghost heat in an
 * evacuated cell would be exactly the stranding problem again, in the other ledger.
 *
 * **Venting to the rim stays.** A face with no tile on the far side sheds its share into space and
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

    // Net grams across each face, signed toward +x / +y. Both ends of a face add into the same slot,
    // so gas crossing in both directions cancels and what is left is the net movement — which is the
    // only thing a flow picture should claim.
    val fluxX = LongArray(edges.xEdgeCount)
    val fluxY = LongArray(edges.yEdgeCount)

    // Reused across tiles: what actually crossed each of the four faces, and who was on the far side.
    val faceAperture = IntArray(FACES)
    val faceNeighbour = IntArray(FACES)
    val faceOut = LongArray(FACES)
    val faceEdge = IntArray(FACES)

    for (tile in 0 until tiles) {
        val ownMass = mass[tile]
        if (ownMass <= 0L) continue
        val base = tile * species

        val left = edges.leftEdgeOf(tile)
        val right = edges.rightEdgeOf(tile)
        val up = edges.upEdgeOf(tile)
        val down = edges.downEdgeOf(tile)
        faceAperture[0] = apertures.yAt(up); faceNeighbour[0] = edges.yEdgeBefore(up)
        faceAperture[1] = apertures.yAt(down); faceNeighbour[1] = edges.yEdgeAfter(down)
        faceAperture[2] = apertures.xAt(left); faceNeighbour[2] = edges.xEdgeBefore(left)
        faceAperture[3] = apertures.xAt(right); faceNeighbour[3] = edges.xEdgeAfter(right)
        faceEdge[0] = up; faceEdge[1] = down; faceEdge[2] = left; faceEdge[3] = right
        faceOut.fill(0L)

        var outMass = 0L
        for (s in 0 until species) {
            val count = grams[base + s]
            if (count <= 0L) continue
            val share = count / SLOTS
            if (share <= 0L) continue

            for (f in 0 until FACES) {
                val aperture = faceAperture[f]
                if (aperture <= 0) continue

                // A partly-open face passes its share in proportion to how open it is; what will not
                // fit stays home, which is the same thing a shut face does. Full openness is the
                // overwhelmingly common case and is exact — the rounding here is confined to valves
                // and doors mid-cycle, where a stranded gram is a gram behind a nearly-shut door.
                val out =
                    if (aperture >= ApertureField.OPEN) share
                    else share * aperture / ApertureField.OPEN
                if (out <= 0L) continue

                deltaGrams[base + s] -= out
                val neighbour = faceNeighbour[f]
                if (neighbour < 0) ventedGrams += out else deltaGrams[neighbour * species + s] += out
                faceOut[f] += out
                outMass += out
            }
        }

        // ── Which way that gas went ──
        //
        // Signed toward +x and +y, so the neighbour on the other side of the face will subtract what
        // it sends back through the same slot. Gas shed into space over the rim counts too: a breach
        // is the clearest flow in the vessel and the arrows should say so.
        fluxY[faceEdge[0]] -= faceOut[0]
        fluxY[faceEdge[1]] += faceOut[1]
        fluxX[faceEdge[2]] -= faceOut[2]
        fluxX[faceEdge[3]] += faceOut[3]

        // ── The energy on the gas that just left ──
        //
        // Telescoped: each face is handed the difference between two running totals of
        // `joules × massSoFar / ownMass`, so the shares sum to exactly `joules × outMass / ownMass`
        // and, when everything leaves, to exactly `joules`. Giving each face its own floored fraction
        // instead would leave a few joules behind every time, and behind in an empty cell they are
        // heat with nothing to hold it.
        if (joules != null && deltaJoules != null && outMass > 0L) {
            val energy = joules[tile]
            var carried = 0L
            var assigned = 0L
            for (f in 0 until FACES) {
                if (faceOut[f] <= 0L) continue
                carried += faceOut[f]
                val upTo = energy * carried / ownMass
                val out = upTo - assigned
                assigned = upTo
                if (out == 0L) continue
                deltaJoules[tile] -= out
                val neighbour = faceNeighbour[f]
                if (neighbour < 0) ventedJoules += out else deltaJoules[neighbour] += out
            }
        }
    }

    for (i in grams.indices) grams[i] += deltaGrams[i]
    if (joules != null && deltaJoules != null) for (t in 0 until tiles) joules[t] += deltaJoules[t]

    // Snapshotted rather than folded on demand: [grams] belongs to the caller, which goes on editing
    // it after the pass, and a mass read later would not be the mass this flux came out of.
    val endingMass = tileMass(tiles, grams)

    return DiffusionStep(
        air = if (joules == null) AirField.of(grams) else AirField.of(grams, joules),
        ventedGrams = ventedGrams,
        ventedJoules = ventedJoules,
        edges = edges,
        fluxX = fluxX,
        fluxY = fluxY,
        startingMass = mass,
        endingMass = endingMass,
    )
}
