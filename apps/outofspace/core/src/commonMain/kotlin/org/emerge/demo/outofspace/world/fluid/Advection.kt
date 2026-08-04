package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.apportion

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
 * Moves gas along the velocity field, in flux form, conserving every gram by construction.
 *
 * ### Why not the usual scheme
 *
 * The textbook step here — and Lague's — is semi-Lagrangian: for each cell trace backwards along the
 * velocity, bilinearly sample the field, and take what you find. It is unconditionally stable and it
 * is the reason most fluid sims can take large steps. It is also not conservative in any sense: the
 * amount arriving somewhere has no arithmetic relationship to the amount leaving anywhere, so mass
 * drifts, and the drift is invisible until you go looking for it. This project's whole position is
 * that "where did the mass go" must be answerable exactly, so the scheme has to be one where a gram
 * arriving *is* the gram that left.
 *
 * So: **donor-cell upwind flux**. Each face computes how much crosses it, one cell gives that up and
 * the other receives it, and it is the same number. Conservation is then structural, exactly as
 * [org.emerge.demo.outofspace.chem.Mixture.minus] makes it structural for splitting a pile. The price
 * is that upwind advection is diffusive — a sharp front smears over a few tiles as it travels — and
 * that is a price worth paying, because a slightly soft plume that conserves mass is a better
 * foundation than a crisp one that does not.
 *
 * ### Not taking more than is there
 *
 * A cell has four faces, and the CFL condition only says that *each* of them moves less than a tile
 * per tick. A cell with fluid leaving in all four directions at once can therefore be asked for
 * several times what it holds. Every flux is computed against the same snapshot and applied together
 * — the order-independence the rest of the sim keeps — so this cannot be papered over by draining
 * cells in a lucky order.
 *
 * The fix is to ask first and pay afterwards: total each cell's requested outflow, and where it
 * exceeds what the cell has, [apportion] the cell's actual contents across its outgoing faces. That
 * scales the requests down in proportion, sums to exactly what was available, and — being the same
 * largest-remainder split used everywhere else — cannot lose a gram to rounding. A cell that is
 * over-subscribed simply empties, which is the physically right answer.
 *
 * ### Boundaries
 *
 * Space has no gas in it, so a face on the rim of the grid never carries anything inward. Outward it
 * carries whatever the flux says, and that mass leaves the world and is reported as [
 * AdvectionResult.ventedGrams] so the vessel's air ledger still closes. Increment D turns that same
 * number into thrust; here it is only bookkeeping.
 *
 * ### A fraction of a tick
 *
 * [subSteps] says how many of these this tick is being cut into, and every flux is that much smaller.
 * One — the default — is the whole tick and the arithmetic below is unchanged.
 *
 * It exists because the CFL condition this pass rests on is a statement about *distance per step*, not
 * per tick: a face moving at three tiles a tick is fine if the step is a third of a tick. The scaling
 * belongs here rather than on the velocity field because the velocity is real — the gas genuinely is
 * going that fast — and what has to shrink is how long it is allowed to go on doing it before the
 * density it is moving through is recomputed. See [stepFluid] for how the count is chosen.
 *
 * [grams] is `tiles × Species.COUNT` and is **edited in place**, matching [
 * org.emerge.demo.outofspace.world.stepAir]'s convention. Mass moved across a face is a proportional
 * sample of the donor over [apportion], so a draught carries the room's actual mix rather than
 * skimming one gas off the top.
 */
fun advectMass(
    edges: EdgeGrid,
    apertures: ApertureField,
    momentum: MomentumField,
    grams: LongArray,
    species: List<Species> = Species.GASES,
    tileGrams: LongArray = tileMass(edges.grid.size, grams, species),
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
        vented += moveGas(grams, donor, acceptor, if (amount > 0L) amount else -amount, species)
    }
    for (e in 0 until edges.yEdgeCount) {
        val amount = fy[e]
        if (amount == 0L) continue
        val donor = if (amount > 0L) edges.yEdgeBefore(e) else edges.yEdgeAfter(e)
        val acceptor = if (amount > 0L) edges.yEdgeAfter(e) else edges.yEdgeBefore(e)
        vented += moveGas(grams, donor, acceptor, if (amount > 0L) amount else -amount, species)
    }

    return AdvectionResult(MassFlux(fx, fy), vented)
}

/**
 * Grams crossing a face in one tick: the donor's density, times how fast it is going, times how much
 * of the face is open.
 *
 * A tile is one unit of area and a tick is one unit of time — divided by [subSteps] where the tick is
 * being taken in pieces — which is what lets this be a product rather than an integration: the choice
 * to make the tick the unit paying off again. [speedRaw] is
 * a [org.emerge.sim.core.physics.primitives.Frac] raw value, so dividing by [
 * MomentumField.SPEED_LIMIT_RAW] converts it to a fraction of a tile per tick.
 *
 * The intermediate product is bounded by `grams × 2^31`, so a tile would have to hold four billion
 * grams to overflow a `Long`. A tile holds about a kilogram.
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
 * Scales each cell's outgoing fluxes down to what the cell actually holds.
 *
 * Only cells that are over-subscribed are touched, and a cell's shares are recomputed from its four
 * faces rather than tracked as the fluxes are built, so this stays a pure function of the snapshot.
 * Every face has exactly one donor, so no face is adjusted twice and the result does not depend on
 * the order tiles are visited.
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
 * Moves [amount] grams from one tile to another as a proportional sample of the donor's mix.
 *
 * An [acceptor] of -1 is space: the mass leaves the donor and is returned as vented rather than
 * arriving anywhere. That is the only path by which gas legitimately stops existing.
 */
private fun moveGas(
    grams: LongArray,
    donor: Int,
    acceptor: Int,
    amount: Long,
    species: List<Species>,
): Long {
    val donorBase = donor * Species.COUNT
    val weights = LongArray(Species.COUNT)
    for (s in species) weights[s.ordinal] = grams[donorBase + s.ordinal]

    val share = apportion(weights, amount)
    var vented = 0L
    for (s in species) {
        val moved = minOf(share[s.ordinal], grams[donorBase + s.ordinal])
        if (moved <= 0L) continue
        grams[donorBase + s.ordinal] -= moved
        if (acceptor < 0) vented += moved else grams[acceptor * Species.COUNT + s.ordinal] += moved
    }
    return vented
}
