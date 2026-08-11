package org.emerge.demo.fluidlab.world.fluid

import org.emerge.demo.fluidlab.chem.Species
import org.emerge.demo.fluidlab.chem.apportion

/**
 * How much mass crossed each face this tick, signed toward +x and +y.
 *
 * Kept rather than discarded because two later passes need exactly this and would otherwise
 * recompute it from a field that has already moved on: momentum rides on the mass fluxes (increment
 * B2), and thrust is the flux through the rim faces (increment D). It is also the honest debugging
 * artefact — "which way did the gas actually go" is a question about faces, not tiles.
 */
class MassFlux(val x: LongArray, val y: LongArray) {

    /** Grams crossing a face, positive toward +x. */
    fun xAt(edge: Int): Long = x[edge]

    /** Grams crossing a face, positive toward +y — which is downward. See [EdgeGrid]. */
    fun yAt(edge: Int): Long = y[edge]

    companion object {
        fun none(edges: EdgeGrid): MassFlux =
            MassFlux(LongArray(edges.xEdgeCount), LongArray(edges.yEdgeCount))
    }
}

/** What one advection pass moved: the per-face fluxes, and what left the grid entirely. */
class AdvectionResult(val flux: MassFlux, val ventedGrams: Long)

/**
 * Moves gas along the velocity field using donor-cell upwind flux, conserving every gram by
 * construction. One cell gives, the other receives the same number.
 *
 * Asks first (computes all fluxes against one snapshot), pays after (applies in a single pass).
 * Over-subscribed cells are apportioned fairly. Space is a sink; vented mass is reported.
 *
 * [subSteps] cuts each flux proportionally for CFL safety. [grams] edited in place.
 */
fun advectMass(
    edges: EdgeGrid,
    apertures: ApertureField,
    momentum: MomentumField,
    grams: LongArray,
    tileGrams: LongArray = tileMass(edges.grid.size, grams),
    subSteps: Int = 1,
): AdvectionResult {
    val grid = edges.grid
    val fx = LongArray(edges.xEdgeCount)
    val fy = LongArray(edges.yEdgeCount)

    // ── Ask: what each face would carry, from the snapshot, before anyone has paid ──
    for (e in 0 until edges.xEdgeCount) {
        val aperture = apertures.xAt(e)
        if (aperture == ApertureField.CLOSED) continue
        val raw = momentum.velocityX(e, tileGrams).raw
        if (raw == 0L) continue
        val donor = if (raw > 0L) edges.xEdgeBefore(e) else edges.xEdgeAfter(e)
        if (donor < 0) continue // Space is empty; nothing blows in from outside.
        val amount = fluxAcross(tileGrams[donor], raw, aperture, subSteps)
        fx[e] = if (raw > 0L) amount else -amount
    }
    for (e in 0 until edges.yEdgeCount) {
        val aperture = apertures.yAt(e)
        if (aperture == ApertureField.CLOSED) continue
        val raw = momentum.velocityY(e, tileGrams).raw
        if (raw == 0L) continue
        val donor = if (raw > 0L) edges.yEdgeBefore(e) else edges.yEdgeAfter(e)
        if (donor < 0) continue
        val amount = fluxAcross(tileGrams[donor], raw, aperture, subSteps)
        fy[e] = if (raw > 0L) amount else -amount
    }

    limitToWhatIsThere(edges, tileGrams, fx, fy)

    // ── Pay: one debit and one matching credit per face ──
    var vented = 0L
    for (e in 0 until edges.xEdgeCount) {
        val amount = fx[e]
        if (amount == 0L) continue
        val donor = if (amount > 0L) edges.xEdgeBefore(e) else edges.xEdgeAfter(e)
        val acceptor = if (amount > 0L) edges.xEdgeAfter(e) else edges.xEdgeBefore(e)
        vented += moveGas(grams, donor, acceptor, if (amount > 0L) amount else -amount)
    }
    for (e in 0 until edges.yEdgeCount) {
        val amount = fy[e]
        if (amount == 0L) continue
        val donor = if (amount > 0L) edges.yEdgeBefore(e) else edges.yEdgeAfter(e)
        val acceptor = if (amount > 0L) edges.yEdgeAfter(e) else edges.yEdgeBefore(e)
        vented += moveGas(grams, donor, acceptor, if (amount > 0L) amount else -amount)
    }

    return AdvectionResult(MassFlux(fx, fy), vented)
}

/**
 * Grams across a face: density × speed × aperture / subSteps. Speed in Frac raw units.
 * Intermediate bounded by grams × 2^31 (tile holds ~kilogram, far from overflow).
 */
private fun fluxAcross(donorGrams: Long, speedRaw: Long, aperture: Int, subSteps: Int): Long {
    if (donorGrams <= 0L) return 0L
    val speed = if (speedRaw < 0L) -speedRaw else speedRaw
    // Divided once, at the end of the product, rather than by scaling the speed first: a face moving
    // at a hundredth of a tile per tick still has to carry something when the tick is cut in six, and
    // dividing the speed would round most of those to nothing.
    val full = donorGrams * speed / (MomentumField.SPEED_LIMIT_RAW * subSteps)
    return full * aperture / ApertureField.OPEN
}

/**
 * Scales over-subscribed cells' outgoing fluxes down to what they hold. Pure function of the
 * snapshot; result independent of visit order.
 */
private fun limitToWhatIsThere(
    edges: EdgeGrid,
    tileGrams: LongArray,
    fx: LongArray,
    fy: LongArray,
) {
    val grid = edges.grid
    val requested = LongArray(grid.size)

    for (e in 0 until edges.xEdgeCount) {
        val amount = fx[e]
        if (amount == 0L) continue
        val donor = if (amount > 0L) edges.xEdgeBefore(e) else edges.xEdgeAfter(e)
        requested[donor] += if (amount > 0L) amount else -amount
    }
    for (e in 0 until edges.yEdgeCount) {
        val amount = fy[e]
        if (amount == 0L) continue
        val donor = if (amount > 0L) edges.yEdgeBefore(e) else edges.yEdgeAfter(e)
        requested[donor] += if (amount > 0L) amount else -amount
    }

    val weights = LongArray(4)
    for (tile in 0 until grid.size) {
        val available = tileGrams[tile]
        if (requested[tile] <= available) continue

        val left = edges.leftEdgeOf(tile)
        val right = edges.rightEdgeOf(tile)
        val up = edges.upEdgeOf(tile)
        val down = edges.downEdgeOf(tile)

        // A face is this tile's outflow when it points away from the tile.
        weights[0] = if (fx[left] < 0L) -fx[left] else 0L
        weights[1] = if (fx[right] > 0L) fx[right] else 0L
        weights[2] = if (fy[up] < 0L) -fy[up] else 0L
        weights[3] = if (fy[down] > 0L) fy[down] else 0L

        val share = apportion(weights, available)
        if (weights[0] > 0L) fx[left] = -share[0]
        if (weights[1] > 0L) fx[right] = share[1]
        if (weights[2] > 0L) fy[up] = -share[2]
        if (weights[3] > 0L) fy[down] = share[3]
    }
}

/**
 * Moves [amount] grams as a proportional sample of the donor's mix. Acceptor -1 = space (vented).
 */
private fun moveGas(
    grams: LongArray,
    donor: Int,
    acceptor: Int,
    amount: Long,
): Long {
    val donorBase = donor * Species.COUNT
    val weights = LongArray(Species.COUNT)
    for (s in Species.ALL) weights[s.ordinal] = grams[donorBase + s.ordinal]

    val share = apportion(weights, amount)
    var vented = 0L
    for (s in Species.ALL) {
        val moved = minOf(share[s.ordinal], grams[donorBase + s.ordinal])
        if (moved <= 0L) continue
        grams[donorBase + s.ordinal] -= moved
        if (acceptor < 0) vented += moved else grams[acceptor * Species.COUNT + s.ordinal] += moved
    }
    return vented
}
