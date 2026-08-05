package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.apportion

/** Momentum that left the grid this tick, by axis. Equal and opposite, it is thrust. */
class MomentumEscape(val x: Long, val y: Long)

/**
 * Carries momentum along with advecting mass, conserving exactly.
 *
 * Uses a staggered/dual grid: each face's momentum lives in a dual cell bounded by four dual faces.
 * Iterates over dual faces (not cells), so every transfer is one subtraction + one matching addition.
 * Momentum per face = donor momentum × fraction of donor mass that moved.
 *
 * [mx] and [my] edited in place. [tileGrams] and [flux] must be from the same snapshot.
 */
fun advectMomentum(
    edges: EdgeGrid,
    mx: LongArray,
    my: LongArray,
    flux: MassFlux,
    tileGrams: LongArray,
): MomentumEscape {
    val grid = edges.grid
    // Computed once: every dual face asks about the mass on the faces it separates, and most faces
    // are asked about four times.
    val xGrams = LongArray(edges.xEdgeCount) { xFaceGrams(edges, tileGrams, it) }
    val yGrams = LongArray(edges.yEdgeCount) { yFaceGrams(edges, tileGrams, it) }

    // ── x-momentum ──
    val xMoves = Transfers(
        (grid.width + 2) * grid.height + (grid.width + 1) * (grid.height + 1),
        edges.xEdgeCount,
    )

    // Along x: a dual face at each column centre, between the x-faces either side.
    // Column index runs one past the grid at both ends for rim momentum escape.
    for (y in 0 until grid.height) {
        for (column in -1..grid.width) {
            val left = if (column < 0) -1 else edges.xEdge(column, y)
            val right = if (column >= grid.width) -1 else edges.xEdge(column + 1, y)
            val crossing = meanPresent(
                if (left < 0) NONE else flux.x[left],
                if (right < 0) NONE else flux.x[right],
            )
            xMoves.request(
                crossing, left, right, mx,
                if (left < 0) 0L else xGrams[left],
                if (right < 0) 0L else xGrams[right],
            )
        }
    }

    // Along y: one dual face per tile corner, between an x-face and the x-face above it.
    for (y in 0..grid.height) {
        for (x in 0..grid.width) {
            val above = if (y == 0) -1 else edges.xEdge(x, y - 1)
            val below = if (y == grid.height) -1 else edges.xEdge(x, y)
            // The two y-fluxes either side of this corner, in x.
            val leftFlux = if (x == 0) NONE else flux.y[edges.yEdge(x - 1, y)]
            val rightFlux = if (x == grid.width) NONE else flux.y[edges.yEdge(x, y)]
            val crossing = meanPresent(leftFlux, rightFlux)
            xMoves.request(
                crossing, above, below, mx,
                if (above < 0) 0L else xGrams[above],
                if (below < 0) 0L else xGrams[below],
            )
        }
    }

    // ── y-momentum: the mirror image ──
    val yMoves = Transfers(
        (grid.height + 2) * grid.width + (grid.width + 1) * (grid.height + 1),
        edges.yEdgeCount,
    )

    // Along y: a dual face at each row centre, running one past the grid at both ends for the same
    // reason the column loop above does.
    for (x in 0 until grid.width) {
        for (row in -1..grid.height) {
            val up = if (row < 0) -1 else edges.yEdge(x, row)
            val down = if (row >= grid.height) -1 else edges.yEdge(x, row + 1)
            val crossing = meanPresent(
                if (up < 0) NONE else flux.y[up],
                if (down < 0) NONE else flux.y[down],
            )
            yMoves.request(
                crossing, up, down, my,
                if (up < 0) 0L else yGrams[up],
                if (down < 0) 0L else yGrams[down],
            )
        }
    }

    // Along x: one dual face per tile corner, between a y-face and the y-face to its left.
    for (y in 0..grid.height) {
        for (x in 0..grid.width) {
            val before = if (x == 0) -1 else edges.yEdge(x - 1, y)
            val after = if (x == grid.width) -1 else edges.yEdge(x, y)
            val upFlux = if (y == 0) NONE else flux.x[edges.xEdge(x, y - 1)]
            val downFlux = if (y == grid.height) NONE else flux.x[edges.xEdge(x, y)]
            val crossing = meanPresent(upFlux, downFlux)
            yMoves.request(
                crossing, before, after, my,
                if (before < 0) 0L else yGrams[before],
                if (after < 0) 0L else yGrams[after],
            )
        }
    }

    return MomentumEscape(
        xMoves.settle(mx),
        yMoves.settle(my),
    )
}

/** Stands in for "this flux is off the grid", which is not the same as a flux of zero. */
private const val NONE = Long.MIN_VALUE

/** Mean of whichever of the two exist; a lone value stands for itself rather than being halved. */
private fun meanPresent(a: Long, b: Long): Long = when {
    a == NONE && b == NONE -> 0L
    a == NONE -> b
    b == NONE -> a
    else -> (a + b) / 2
}

/**
 * Momentum transfers collected across dual faces, then clamped per donor and applied.
 * Two-pass: gather requests against one snapshot, clamp, apply.
 */
private class Transfers(capacity: Int, faceCount: Int) {
    val donor = IntArray(capacity)
    val acceptor = IntArray(capacity)
    val amount = LongArray(capacity)
    var count = 0

    /** Which transfers each face donates to: four slots per face (one per dual face of dual cell). */
    private val donatedBy = IntArray(faceCount * SLOTS) { -1 }
    private val donations = IntArray(faceCount)

    /** Records momentum that [crossing] grams of mass carries from one face to the other.
     * Donor = upwind face. Momentum follows mass direction regardless of its own direction. */
    fun request(crossing: Long, before: Int, after: Int, momentum: LongArray, gramsBefore: Long, gramsAfter: Long) {
        if (crossing == 0L) return
        val from = if (crossing > 0L) before else after
        if (from < 0) return // Space has no momentum to hand over.
        val to = if (crossing > 0L) after else before
        val fromGrams = if (crossing > 0L) gramsBefore else gramsAfter
        if (fromGrams <= 0L) return

        val moving = if (crossing > 0L) crossing else -crossing
        // The share of this face's mass that is leaving takes the same share of its momentum.
        val carried = momentum[from] * minOf(moving, fromGrams) / fromGrams
        if (carried == 0L) return

        // Overrunning the stride would write into the next face's slots and quietly break
        // conservation rather than failing, which is the one outcome worth paying a branch to avoid.
        require(donations[from] < SLOTS) { "face $from donates more than $SLOTS times" }

        donor[count] = from
        acceptor[count] = to
        amount[count] = carried
        donatedBy[from * SLOTS + donations[from]] = count
        donations[from]++
        count++
    }

    /** Clamps each donor to what it holds, applies every transfer, and returns what left the grid. */
    fun settle(momentum: LongArray): Long {
        val weights = LongArray(SLOTS)

        // Only over-subscribed donors are rescaled, and each is rescaled from its own requests
        // alone, so the result does not depend on the order transfers were collected in.
        for (face in donations.indices) {
            val n = donations[face]
            if (n == 0) continue

            var total = 0L
            for (k in 0 until n) total += abs(amount[donatedBy[face * SLOTS + k]])
            val available = abs(momentum[face])
            if (total <= available) continue

            for (k in 0 until n) weights[k] = abs(amount[donatedBy[face * SLOTS + k]])
            for (k in n until SLOTS) weights[k] = 0L
            val share = apportion(weights, available)
            val sign = if (momentum[face] < 0L) -1L else 1L
            for (k in 0 until n) amount[donatedBy[face * SLOTS + k]] = sign * share[k]
        }

        var escaped = 0L
        for (i in 0 until count) {
            val moved = amount[i]
            if (moved == 0L) continue
            momentum[donor[i]] -= moved
            if (acceptor[i] < 0) escaped += moved else momentum[acceptor[i]] += moved
        }
        return escaped
    }

    private fun abs(v: Long): Long = if (v < 0L) -v else v

    companion object {
        /** A dual cell has four dual faces. See [donatedBy]. */
        const val SLOTS = 4
    }
}
