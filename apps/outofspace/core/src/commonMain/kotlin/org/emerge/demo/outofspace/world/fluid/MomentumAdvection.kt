package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.apportion

/** Momentum that left the grid this tick, by axis. Equal and opposite, it is thrust. */
class MomentumEscape(val x: Long, val y: Long)

/**
 * Carries momentum along with the mass that is moving, conserving it exactly.
 *
 * ### The awkward bit, stated plainly
 *
 * Mass lives in tiles and momentum lives on faces, so the two cannot be advected by the same sweep.
 * A tile's mass has four faces to leave by and that is the end of it; a face's momentum has to be
 * moved through a control volume that is *offset half a tile* from the grid everything else is
 * written against. This is the standard price of a staggered grid and it is the reason most game
 * fluid sims quietly do not conserve momentum: the easy scheme is to interpolate face momenta to
 * tile centres, advect them there, and interpolate back. That round trip is a low-pass filter
 * applied once per tick. It conserves the total, so it looks correct in a ledger, and it smears a
 * jet into a warm smudge within a few tiles — which for a rocket exhaust destroys the only thing
 * anybody cares about while passing every test that only checks the sum.
 *
 * So the control volumes are built properly. Each x-face is a cell of the *dual* grid, and the faces
 * of that dual cell fall in two families:
 *
 * - **Along x:** the dual face sits at a tile centre and separates that tile's left and right faces.
 *   Mass crosses it at the mean of the two primal fluxes either side. There is one per column of
 *   tiles *plus one at each end*, because a dual cell centred on a rim face overhangs the grid by
 *   half a tile and that overhang has an outer wall — which is the wall momentum leaves through.
 *   Omitting the two of them does not leak momentum, it strands it: the rim accumulates everything
 *   that reaches it and never lets go, which reads as a vessel that cannot be pushed.
 * - **Along y:** the dual face sits at a tile corner and separates one x-face from the x-face above
 *   it. Mass crosses it at the mean of the two primal y-fluxes either side.
 *
 * The y-momentum field gets the mirror image of both.
 *
 * ### Why it conserves
 *
 * The pass iterates over **dual faces, not dual cells**, and every transfer is one subtraction and
 * one matching addition. So conservation does not depend on two neighbouring cells agreeing about a
 * shared face — there is only ever one number, applied twice with opposite signs. That is the same
 * discipline the mass pass keeps, and the reason both can be asserted with exact equalities.
 *
 * Momentum crossing a dual face is the donor's momentum scaled by the *fraction of the donor's mass*
 * that moved: mass carries momentum, so if a tenth of the mass on a face leaves, a tenth of its
 * momentum goes with it. That framing is what keeps the whole thing in integers, and it makes the
 * bound obvious — a face cannot give away more momentum than it has, because it cannot give away
 * more mass than it has.
 *
 * A dual cell can still be drained by all four of its faces at once, exactly as a tile can, so the
 * same ask-first-pay-afterwards clamp applies, using the same [apportion].
 *
 * ### Off the grid
 *
 * A dual face on the rim has a donor but no acceptor. That momentum leaves the world, and it is
 * returned as [MomentumEscape] rather than being dropped, because it is not waste — it is the
 * reaction the vessel gets. Increment D turns it into thrust and torque. Nothing comes back the
 * other way: space is empty and has no momentum to give.
 *
 * [mx] and [my] are the working momentum arrays, **edited in place**. [tileGrams] and [flux] must
 * both be from the same snapshot as each other — the mass field *before* [advectMass] moved it, and
 * the fluxes that pass computed.
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

    // Along x: a dual face at each column centre, between the x-faces either side of it.
    //
    // The column index runs one *past* the grid at both ends. A dual cell is centred on a face, so
    // the one belonging to a rim face sticks half a tile out into space, and that overhanging half
    // has an outer wall which is where momentum leaves the world. Iterating only over real tiles
    // silently omits those two walls, and the symptom is not a leak but the opposite — momentum
    // arrives at the rim and piles up there forever, because it has been given no way out.
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
 *
 * The two-pass shape is the same as the mass pass's, and for the same reason: every transfer is
 * computed against one snapshot, so a dual cell can be over-subscribed and there is no lucky
 * visiting order that avoids it. Requests are gathered, each donor's are scaled to what it actually
 * holds, and only then is anything moved.
 */
private class Transfers(capacity: Int, faceCount: Int) {
    val donor = IntArray(capacity)
    val acceptor = IntArray(capacity)
    val amount = LongArray(capacity)
    var count = 0

    /**
     * Which transfers each face is the donor of, four slots per face.
     *
     * Four is not a guess: a dual cell is bounded by exactly four dual faces — one at the tile centre
     * either side of it, and one at the corner above and below — so a face can be asked to give
     * momentum away at most four times. Fixing the stride turns the clamp below from a scan of every
     * transfer per over-subscribed face into a constant-time lookup.
     */
    private val donatedBy = IntArray(faceCount * SLOTS) { -1 }
    private val donations = IntArray(faceCount)

    /**
     * Records the momentum that [crossing] grams of mass would carry from one face to the other.
     *
     * The donor is the upwind face — the one the mass is coming *from*. Momentum moves in whichever
     * direction the mass does, regardless of which way the momentum itself points: a face moving
     * leftward still hands its leftward momentum to the face downwind of the mass flow.
     */
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
