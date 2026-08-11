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
 * **The split is exact, remainders included.** Five shares that sum to precisely what the cell held,
 * with the leftover units handed out in a rotation that turns with the tick (see [shareOf]). The
 * plain `count / 5` this replaced was conservative but *immobilising*: a tile holding three grams
 * computed a zero share across every face and sheds nothing, for ever — next to vacuum, a room that
 * never finishes emptying. Rounding down does not lose mass here; it strands it.
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
 * The four face slots, in the order the remainder rotation visits them: up, down, left, right.
 * Slot `0` is the share that stays home, which is why it is absent here and why a leftover unit
 * landing on it is a unit that does not move this tick.
 */
private val FACE_SLOTS = intArrayOf(1, 2, 3, 4)

/**
 * How much of [count] this slot takes when the cell is divided [SLOTS] ways.
 *
 * The quotient goes to everyone; the `count % SLOTS` leftover units go one each to consecutive slots,
 * starting from [offset]. Summed over every slot this is exactly [count], which is the whole point —
 * nothing is left over to strand.
 *
 * [offset] turns with the tick so that the direction the leftovers lean is not a permanent property
 * of the geometry. It also turns with the species, or every gas in a mixed tile would lean the same
 * way on the same tick and the mixture would drift as a lump rather than spreading.
 */
private fun shareOf(count: Long, slot: Int, offset: Int, remainder: Int): Long {
    val quotient = count / SLOTS
    val place = (slot - offset + SLOTS) % SLOTS
    return if (place < remainder) quotient + 1 else quotient
}

/** Convenience overload deriving the face connectivity from [structure]. */
fun diffuseFluid(
    grid: Grid,
    structure: StructureMap,
    grams: LongArray,
    joules: LongArray? = null,
    tick: Long = 0L,
): DiffusionStep {
    val edges = EdgeGrid(grid)
    return diffuseFluid(edges, ApertureField.derive(edges, structure), grams, joules, tick)
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
 * [tick] drives the remainder rotation and so is part of the model, not a debugging aid: the same
 * field diffuses differently on consecutive ticks, deterministically. Callers must pass the real tick
 * or replay will diverge.
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
    tick: Long = 0L,
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
    val faceAperture = IntArray(FACE_SLOTS.size)
    val faceNeighbour = IntArray(FACE_SLOTS.size)
    val faceOut = LongArray(FACE_SLOTS.size)
    val faceEdge = IntArray(FACE_SLOTS.size)

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
            val remainder = (count % SLOTS).toInt()
            val offset = ((tick + s) % SLOTS).toInt()

            for (f in FACE_SLOTS.indices) {
                val aperture = faceAperture[f]
                if (aperture <= 0) continue
                val share = shareOf(count, FACE_SLOTS[f], offset, remainder)
                if (share <= 0L) continue

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
            for (f in FACE_SLOTS.indices) {
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
