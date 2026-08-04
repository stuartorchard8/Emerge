package org.emerge.demo.outofspace.world.fluid

/**
 * What pressure did this tick: what the gas gained, and what the ship gained.
 *
 * The two are computed **independently** — the fluid's from the gradient across every open face, the
 * vessel's from the gas leaning on every bulkhead — and they must come out exactly equal and
 * opposite wherever the gas is contiguous. Defining one as the negative of the other would make
 * Newton's third law true by fiat and test nothing; computing both and comparing them is a real
 * check that the discretisation is sound, and it is the check the rocket rests on.
 *
 * They part company only at a vacuum boundary, and legitimately so: there is no wall there to take
 * the reaction, because the momentum has left with the gas. That escape is counted by
 * [advectMomentum], not here.
 */
class ProjectionResult(
    val vesselX: Long,
    val vesselY: Long,
    val fluidX: Long,
    val fluidY: Long,
)

/**
 * Pressure: makes the velocity field consistent with what the gas is actually trying to do.
 *
 * ### Why solve, rather than just push down the gradient
 *
 * The cheap version of pressure is to read it off the equation of state and accelerate each face by
 * the difference across it. That works, and it is *local*: a pressure change travels one tile per
 * tick. On a vessel a hundred tiles across, a door opening at one end is not felt at the other for a
 * hundred ticks, and a room equalises as a slow visible ripple rather than as a draught. That is the
 * same defect the relaxation in `stepAir` has, and no tuning removes it, because it is a property of
 * the scheme and not of the numbers in it.
 *
 * Solving makes pressure **elliptic** — every cell couples to every other within the single tick.
 * That is what a pressure field physically is: not a substance that propagates, but whatever field
 * simultaneously satisfies the constraint everywhere at once.
 *
 * ### Compressible, via a target divergence
 *
 * The textbook projection drives divergence to **zero**, which is the statement that the fluid cannot
 * be compressed — right for a liquid, wrong for the gas in a spacecraft, where a room at two
 * atmospheres decompressing into one at one atmosphere is the entire subject. So it solves toward a
 * *target* divergence instead, taken from how far a cell's pressure sits above its neighbours'. A
 * cell in a uniform room asks for nothing and the solve reduces to the incompressible one, so a
 * still room stays still. A cell beside vacuum asks for a great deal, and gets a blowout.
 *
 * Written as a parameter rather than a special case because **a liquid is the same solve with the
 * target fixed at zero.** Increment E adds a free surface, not a second solver.
 *
 * ### Density-weighted, which is not a detail
 *
 * The Laplacian carries `1/ρ` coefficients rather than constant ones. The tempting simplification is
 * to solve for `p/ρ` with uniform coefficients — it is the same equation when density is uniform,
 * and the integer arithmetic is tidier. It also quietly destroys the thrust ledger. With constant
 * coefficients the quantity that telescopes along a row is `p/ρ`, so the *momentum* impulses do not
 * cancel where density varies, and a sealed vessel full of gas of uneven density slowly accelerates
 * itself. Density is never uniform in the situations this exists for — a breach, a hot exhaust — so
 * the tidier version fails precisely where it is being relied on.
 *
 * With `1/ρ` weights the solved field is in **momentum units**, the impulse across a face is simply
 * the pressure difference, and those differences telescope exactly along every row and column. That
 * exactness survives the integer division in the solve: an imprecise pressure field makes the
 * divergence correction approximate, but conservation holds regardless, because it is a property of
 * the shape of the sum rather than of the values in it.
 *
 * ### Jacobi
 *
 * Each sweep reads the previous field and writes a new one, so no cell sees a neighbour that has
 * already moved. Gauss-Seidel — Lague's choice — updates in place and converges in fewer sweeps, at
 * the price of the answer depending on visiting order. A poor trade twice over: it breaks the
 * snapshot-then-apply discipline the rest of the sim keeps, and it cannot be handed to more than one
 * thread. Sweeps are cheap; ordering guarantees are not.
 *
 * ### Walls, and where thrust comes from
 *
 * A closed face never moves — gas does not flow through a bulkhead — so its momentum is pinned at
 * zero rather than given a pressure force. The force is still real: it goes into the wall, and the
 * wall is the ship. A sealed vessel's wall terms cancel and it goes nowhere. Put a hole in one end
 * and one of those terms is replaced by vacuum, so they no longer cancel, and the vessel is pushed.
 * Thrust arrives here as a consequence of the arithmetic rather than as a feature anyone added.
 *
 * [mx] and [my] are edited in place. [pressure] is the equation-of-state field from [tilePressure]
 * and [tileGrams] the density field; they are separate arguments because they are separate physics —
 * see [tilePressure] for why conflating them is what forced `stratifyColumns` to exist.
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

    // A bulkhead carries no flow. Enforced rather than assumed, because a face that has just been
    // built over would otherwise keep whatever momentum it had while it was still open.
    for (e in 0 until edges.xEdgeCount) if (!apertures.isXOpen(e)) mx[e] = 0L
    for (e in 0 until edges.yEdgeCount) if (!apertures.isYOpen(e)) my[e] = 0L

    // ── How strongly each face couples the cells either side: aperture over density ──
    //
    // A face with no gas on it couples nothing, whatever its aperture. That is not a special case
    // being handled, it is the physics: pressure is transmitted by the fluid, and there is none.
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

            // Minus the residual: a cell that wants to expand has a positive target, hence a
            // negative residual, hence a pressure *above* its neighbours — which is what pushes gas
            // out of it. Getting this sign wrong makes a high-pressure cell suck inward, which looks
            // plausible for about one frame.
            var sum = -residual[tile]
            sum += wx[left] * beyond(p, edges.xEdgeBefore(left))
            sum += wx[right] * beyond(p, edges.xEdgeAfter(right))
            sum += wy[up] * beyond(p, edges.yEdgeBefore(up))
            sum += wy[down] * beyond(p, edges.yEdgeAfter(down))
            next[tile] = sum / coupled[tile]
        }
        val swap = p; p = next; next = swap
    }

    // ── Push the fluid down the gradient ──
    var fluidX = 0L
    var fluidY = 0L
    for (e in 0 until edges.xEdgeCount) {
        if (wx[e] == 0L) continue
        val impulse = beyond(p, edges.xEdgeBefore(e)) - beyond(p, edges.xEdgeAfter(e))
        mx[e] += impulse
        fluidX += impulse
    }
    for (e in 0 until edges.yEdgeCount) {
        if (wy[e] == 0L) continue
        val impulse = beyond(p, edges.yEdgeBefore(e)) - beyond(p, edges.yEdgeAfter(e))
        my[e] += impulse
        fluidY += impulse
    }

    // ── And let the bulkheads take the reaction ──
    //
    // Worked out from the walls themselves rather than from what the fluid gained, so that the two
    // can be compared. Only faces shut by an *aperture* count: a face uncoupled merely because there
    // is no gas on it is not a wall, it is empty space, and empty space cannot be pushed against.
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

    return ProjectionResult(vesselX, vesselY, fluidX, fluidY)
}

/**
 * How much this cell wants to expand, from how far its pressure sits above its neighbours'.
 *
 * Relative to the neighbourhood rather than to a fixed reference, so it needs no notion of what
 * "normal" is and behaves correctly at a breach without anybody special-casing one: the neighbour is
 * vacuum, the difference is a whole atmosphere, and the cell tries to empty itself. A cell in a
 * uniform room differs from its neighbours by nothing and asks for nothing, which is the rest state
 * — and the rest state has to be exactly right, or a still vessel hums.
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
    return (pressure[tile] - sum / count) * EXPANSION / AMBIENT_PRESSURE
}

/**
 * A face's coupling strength: its open area divided by the mass on it.
 *
 * This is the `1/ρ` in the density-weighted Laplacian, pre-multiplied by [
 * MomentumField.SPEED_LIMIT_RAW] so that the solved pressure lands in momentum units and the impulse
 * across a face is the bare pressure difference. For ordinary air on a fully open face it comes to
 * about two million, which leaves plenty of headroom below a `Long` once multiplied by a pressure.
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

/**
 * How many Jacobi sweeps a tick gets.
 *
 * Jacobi spreads influence about one tile per sweep, so this does not fully converge a hundred-tile
 * vessel within a tick — but a partly-solved pressure field is a *smoothed* one rather than a wrong
 * one, and the next tick resumes from a better guess. What matters is that pressure reaches twenty
 * tiles per tick instead of one.
 */
const val JACOBI_ITERATIONS = 20

/**
 * The divergence a cell asks for when it sits one whole atmosphere above its neighbours: an eighth
 * of a tile per tick.
 *
 * Pinned by the extreme case on purpose, because the extreme case is the one anybody will look at. A
 * sealed room opened to vacuum is exactly this situation, and an eighth empties it over something
 * like ten ticks — fast enough to read as explosive, slow enough to watch happen.
 */
private val EXPANSION: Long = MomentumField.SPEED_LIMIT_RAW / 8
