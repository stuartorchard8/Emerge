package org.emerge.demo.outofspace.world.fluid

/**
 * What pressure did this tick: fluid impulse and vessel impulse, computed independently
 * so the ledger can check conservation. They sum to zero wherever the gas is contiguous.
 *
 * At a vacuum boundary the terms diverge; [undeliveredX/Y] captures that shortfall.
 * [vesselX] and [vesselY] include both the lean on bulkheads and the momentum walls stop.
 */
class ProjectionResult(
    val vesselX: Long,
    val vesselY: Long,
    val fluidX: Long,
    val fluidY: Long,
    /**
     * Impulse the solve worked out and had nowhere to put.
     *
     * An open face with no gas on it has a pressure difference but nothing to act on.
     * It occurs at plume fronts where gas-adjacent vacuum tiles get a solved pressure.
     * Named rather than fixed: pinning p=0 on massless tiles nearly closes blowout but
     * breaks vent gradients. Reported size measures discretisation error in thrust.
     */
    val undeliveredX: Long,
    val undeliveredY: Long,
)

/**
 * Pressure projection: makes the velocity field consistent with the pressure gradient.
 *
 * Solves toward a target divergence (not zero) to handle compressible gas: cells beside vacuum
 * expand, uniform rooms stay still. Uses `1/ρ` density weighting so the solved field is in
 * momentum units and impulses telescope exactly along rows/columns (conservation holds regardless
 * of density variation).
 *
 * Damped Jacobi iteration: undamped oscillates forever on small gas/vacuum domains. Each sweep
 * moves 2/3 toward the plain Jacobi answer.
 *
 * Closed faces pin momentum at zero; the reaction goes into the vessel. [mx] and [my] edited in place.
 */
fun project(
    edges: EdgeGrid,
    apertures: ApertureField,
    mx: LongArray,
    my: LongArray,
    tileGrams: LongArray,
    pressure: LongArray,
    iterations: Int = JACOBI_ITERATIONS,
): ProjectionResult {
    val grid = edges.grid

    // ── A bulkhead carries no flow; momentum it stopped goes to the hull ──
    //
    // Gas flowing toward a wall hands its momentum to the closed face. Zeroing the face is right,
    // but the momentum goes into the hull, not into nothing. Summing what was stopped before
    // zeroing so the hull ledger gets it.
    var stoppedX = 0L
    var stoppedY = 0L
    for (e in 0 until edges.xEdgeCount) if (!apertures.isXOpen(e)) { stoppedX += mx[e]; mx[e] = 0L }
    for (e in 0 until edges.yEdgeCount) if (!apertures.isYOpen(e)) { stoppedY += my[e]; my[e] = 0L }

    // ── Coupling strength per face: aperture over density ──
    // A face with no gas couples nothing.
    val wx = LongArray(edges.xEdgeCount) { coupling(apertures.xAt(it), xFaceGrams(edges, tileGrams, it)) }
    val wy = LongArray(edges.yEdgeCount) { coupling(apertures.yAt(it), yFaceGrams(edges, tileGrams, it)) }

    val xGrams = LongArray(edges.xEdgeCount) { xFaceGrams(edges, tileGrams, it) }
    val yGrams = LongArray(edges.yEdgeCount) { yFaceGrams(edges, tileGrams, it) }

    // ── What the field is doing now, against what the gas wants it to be doing ──
    val coupled = LongArray(grid.size)
    val residual = LongArray(grid.size)
    for (tile in 0 until grid.size) {
        val left = edges.leftEdgeOf(tile)
        val right = edges.rightEdgeOf(tile)
        val up = edges.upEdgeOf(tile)
        val down = edges.downEdgeOf(tile)

        coupled[tile] = wx[left] + wx[right] + wy[up] + wy[down]
        if (coupled[tile] == 0L) continue

        val divergence =
            velocityRaw(mx[right], xGrams[right]) - velocityRaw(mx[left], xGrams[left]) +
                velocityRaw(my[down], yGrams[down]) - velocityRaw(my[up], yGrams[up])
        residual[tile] = divergence - wantedDivergence(edges, wx, wy, pressure, tile)
    }

    // ── Jacobi ──
    var p = LongArray(grid.size)
    var next = LongArray(grid.size)
    repeat(iterations) {
        for (tile in 0 until grid.size) {
            if (coupled[tile] == 0L) { next[tile] = 0L; continue }
            val left = edges.leftEdgeOf(tile)
            val right = edges.rightEdgeOf(tile)
            val up = edges.upEdgeOf(tile)
            val down = edges.downEdgeOf(tile)

        // Negative residual: positive target → pressure above neighbours → pushes gas out.
            var sum = -residual[tile]
            sum += wx[left] * beyond(p, edges.xEdgeBefore(left))
            sum += wx[right] * beyond(p, edges.xEdgeAfter(right))
            sum += wy[up] * beyond(p, edges.yEdgeBefore(up))
            sum += wy[down] * beyond(p, edges.yEdgeAfter(down))
            // Damped by 2/3 each sweep — undamped oscillates.
            val jacobi = sum / coupled[tile]
            next[tile] = p[tile] + (jacobi - p[tile]) * DAMPING_NUMERATOR / DAMPING_DENOMINATOR
        }
        val swap = p; p = next; next = swap
    }

    // ── Push fluid down the pressure gradient ──
    var fluidX = 0L
    var fluidY = 0L
    // An open face with no gas on it is the one case where the impulse goes nowhere. It is measured
    // on the way past rather than silently skipped — see [ProjectionResult.undeliveredX].
    var undeliveredX = 0L
    var undeliveredY = 0L
    for (e in 0 until edges.xEdgeCount) {
        val impulse = beyond(p, edges.xEdgeBefore(e)) - beyond(p, edges.xEdgeAfter(e))
        if (impulse == 0L) continue
        if (wx[e] == 0L) {
            if (apertures.isXOpen(e)) undeliveredX += impulse
            continue
        }
        mx[e] += impulse
        fluidX += impulse
    }
    for (e in 0 until edges.yEdgeCount) {
        val impulse = beyond(p, edges.yEdgeBefore(e)) - beyond(p, edges.yEdgeAfter(e))
        if (impulse == 0L) continue
        if (wy[e] == 0L) {
            if (apertures.isYOpen(e)) undeliveredY += impulse
            continue
        }
        my[e] += impulse
        fluidY += impulse
    }


    // ── Bulkheads take the reaction ──
    //
    // Only faces shut by an aperture count; a face uncoupled because there's no gas is empty space.
    var vesselX = 0L
    var vesselY = 0L
    for (e in 0 until edges.xEdgeCount) {
        if (apertures.isXOpen(e)) continue
        val before = edges.xEdgeBefore(e)
        val after = edges.xEdgeAfter(e)
        if (before >= 0 && coupled[before] > 0L) vesselX += p[before]
        if (after >= 0 && coupled[after] > 0L) vesselX -= p[after]
    }
    for (e in 0 until edges.yEdgeCount) {
        if (apertures.isYOpen(e)) continue
        val before = edges.yEdgeBefore(e)
        val after = edges.yEdgeAfter(e)
        if (before >= 0 && coupled[before] > 0L) vesselY += p[before]
        if (after >= 0 && coupled[after] > 0L) vesselY -= p[after]
    }

    return ProjectionResult(
        vesselX + stoppedX, vesselY + stoppedY, fluidX, fluidY, undeliveredX, undeliveredY,
    )
}

/**
 * How much this cell wants to expand: pressure above neighbours as a fraction of the local
 * pressure scale.
 *
 * Relative to neighbours, not an absolute reference. A cell beside vacuum asks for full expansion;
 * a uniform room asks for nothing. Dividing by local pressure (not ambient) makes the target
 * scale-free so thin gas expands as hard as dense gas.
 */
private fun wantedDivergence(
    edges: EdgeGrid,
    wx: LongArray,
    wy: LongArray,
    pressure: LongArray,
    tile: Int,
): Long {
    var sum = 0L
    var count = 0

    val left = edges.leftEdgeOf(tile)
    if (wx[left] > 0L) edges.xEdgeBefore(left).let { if (it >= 0) { sum += pressure[it]; count++ } }
    val right = edges.rightEdgeOf(tile)
    if (wx[right] > 0L) edges.xEdgeAfter(right).let { if (it >= 0) { sum += pressure[it]; count++ } }
    val up = edges.upEdgeOf(tile)
    if (wy[up] > 0L) edges.yEdgeBefore(up).let { if (it >= 0) { sum += pressure[it]; count++ } }
    val down = edges.downEdgeOf(tile)
    if (wy[down] > 0L) edges.yEdgeAfter(down).let { if (it >= 0) { sum += pressure[it]; count++ } }

    if (count == 0) return 0L
    val mean = sum / count
    // The larger of the two, so the fraction is bounded by one and a cell beside vacuum asks for
    // exactly [EXPANSION] rather than for a division by nothing.
    val scale = if (pressure[tile] > mean) pressure[tile] else mean
    if (scale <= 0L) return 0L
    return (pressure[tile] - mean) * EXPANSION / scale
}

/**
 * A face's coupling: open area divided by mass, scaled so solved pressure lands in
 * momentum units.
 */
private fun coupling(aperture: Int, faceGrams: Long): Long {
    if (aperture == 0 || faceGrams <= 0L) return 0L
    return aperture.toLong() * MomentumField.SPEED_LIMIT_RAW / (ApertureField.OPEN.toLong() * faceGrams)
}

/** Pressure beyond a face, or zero — space and solid alike push back with nothing. */
private fun beyond(p: LongArray, tile: Int): Long = if (tile < 0) 0L else p[tile]

/** Velocity across a face as a raw [org.emerge.sim.core.physics.primitives.Frac]. */
private fun velocityRaw(momentum: Long, faceGrams: Long): Long =
    if (faceGrams <= 0L) 0L else momentum * MomentumField.SPEED_LIMIT_RAW / faceGrams

/** Jacobi sweeps per tick. Spreads influence ~1 tile per sweep; a partly-solved field is a smoothed one
 * that the next tick resumes from. */
const val JACOBI_ITERATIONS = 20

/** Damping per Jacobi sweep: 2/3. Undamped (weight=1) oscillates forever on small domains. */
private const val DAMPING_NUMERATOR = 2L
private const val DAMPING_DENOMINATOR = 3L

/** Divergence target when neighbours are vacuum: an eighth of a tile per tick. A ratio, not absolute,
 * so thin plumes expand as hard as pressurised decks. */
private val EXPANSION: Long = MomentumField.SPEED_LIMIT_RAW / 8
