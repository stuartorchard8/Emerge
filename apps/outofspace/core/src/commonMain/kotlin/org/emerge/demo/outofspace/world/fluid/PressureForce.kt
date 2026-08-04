package org.emerge.demo.outofspace.world.fluid

/** The reaction the vessel takes from its own air pressing on its bulkheads, by axis. */
class PressureForceResult(val vesselX: Long, val vesselY: Long)

/**
 * The gas pushes itself down its own pressure gradient — which is what sound is.
 *
 * ### The term this restores, and what its absence cost
 *
 * [project] solves an elliptic problem, and that is genuinely the right tool for the *constraint*
 * part of pressure: every cell coupled to every other within one tick, so a room does not equalise
 * as a visible ripple. But a solve is a correction, not a force, and until this existed the
 * equation-of-state field from [tilePressure] reached the momentum field through exactly one path —
 * [wantedDivergence] — which is deliberately zero wherever a cell matches its neighbours.
 *
 * That is correct as a rest state and it is why a still vessel does not hum. It is also, on its own,
 * a sim with **no speed of sound**. Nothing carries "there is a hole at the far end of this deck"
 * across a uniform room, because a uniform room is precisely the case that asks for nothing. What
 * propagated instead was a diffusive front, one tile of accumulated pressure-difference at a time,
 * throttled further by [EXPANSION] capping a whole atmosphere at an eighth of a tile per tick — so
 * the one-percent differences a few tiles inside a breached room got about a thousandth of that. A
 * measured breach left most of the vessel sitting at exactly full ambient two hundred ticks in, and
 * took the better part of a thousand to half empty. That was not a tuning failure. It was arithmetic
 * doing what it was asked, and no constant in the projection could have fixed it.
 *
 * So the missing piece was the plainest one in fluid dynamics: `a = -∇p/ρ`. A cell beside a lower
 * one gets pushed toward it, every tick, whether or not anything else in the room agrees. Pressure
 * differences then travel as a **wave** at a speed this function sets, the projection goes back to
 * being the correction it is documented as, and a breach is felt vessel-wide in tens of ticks.
 *
 * ### Why the impulse is the bare pressure difference
 *
 * Momentum on a face is `grams × tiles/tick`, and the acceleration a gradient produces goes as
 * `1/ρ` — so the momentum it produces goes as `ρ × (1/ρ)`, and the density cancels. The impulse is
 * the pressure difference and nothing else. This is not a coincidence or a simplification; it is the
 * same cancellation that makes [project]'s density-weighted Laplacian hand back impulses in bare
 * pressure units, and the two passes agree about it for that reason. The *velocity* a light face
 * picks up is still larger than a heavy one's, because velocity is momentum over mass and the mass
 * is what differs.
 *
 * It also means the internal terms telescope exactly along every row and column: a sealed vessel of
 * gas at any distribution of pressures pushes on itself and goes precisely nowhere. That has to be
 * exact rather than approximate, or a stationary ship drifts, and integer arithmetic gives it for
 * free here because the same difference is added to one face and subtracted from the next.
 *
 * ### A face with nothing on it is not accelerated
 *
 * Not a guard against a division — there is no division — but against inventing momentum. Momentum
 * on an empty face has no gas to belong to, and [stepFluid] strips it at the end of the tick as
 * stranded, which would quietly book it as exhaust. Vacuum has no gradient force because vacuum has
 * nothing to push.
 *
 * ### The reaction, and where thrust actually comes from
 *
 * A closed face gets no impulse — gas does not flow through a bulkhead — but the push is real and
 * goes into the hull. Summed over a sealed vessel those terms cancel, so a pressurised ship sitting
 * in vacuum stays put. Breach one end and the term that would have cancelled is replaced by a
 * vacuum, and the vessel is pushed. That is the whole rocket, and it arrives here rather than in
 * [project] because this is where the pressure the gas *actually has* is applied; the projection
 * only ever knew about the correction on top.
 *
 * [mx] and [my] are edited in place.
 */
fun applyPressureForce(
    edges: EdgeGrid,
    apertures: ApertureField,
    mx: LongArray,
    my: LongArray,
    tileGrams: LongArray,
    pressure: LongArray,
): PressureForceResult {
    var vesselX = 0L
    var vesselY = 0L

    // Converted to impulse units once per *tile*, before any difference is taken — see [potentialOf].
    val potential = potentialOf(pressure)

    for (e in 0 until edges.xEdgeCount) {
        val drop = beyond(potential, edges.xEdgeBefore(e)) - beyond(potential, edges.xEdgeAfter(e))
        if (drop == 0L) continue
        if (apertures.isXOpen(e)) {
            val faceGrams = xFaceGrams(edges, tileGrams, e)
            if (faceGrams <= 0L) continue
            val toGas = drop * apertures.xAt(e) / ApertureField.OPEN
            mx[e] = capped(mx[e] + toGas, faceGrams)
            // What the solid part of a restriction took. Zero for a fully open face.
            vesselX += drop - toGas
        } else {
            vesselX += drop
        }
    }
    for (e in 0 until edges.yEdgeCount) {
        val drop = beyond(potential, edges.yEdgeBefore(e)) - beyond(potential, edges.yEdgeAfter(e))
        if (drop == 0L) continue
        if (apertures.isYOpen(e)) {
            val faceGrams = yFaceGrams(edges, tileGrams, e)
            if (faceGrams <= 0L) continue
            val toGas = drop * apertures.yAt(e) / ApertureField.OPEN
            my[e] = capped(my[e] + toGas, faceGrams)
            vesselY += drop - toGas
        } else {
            vesselY += drop
        }
    }

    return PressureForceResult(vesselX, vesselY)
}

/**
 * A face's momentum, held to a speed the transport scheme can actually integrate.
 *
 * ### Why a bound is needed at all
 *
 * The impulse being density-independent is right, and the velocity it implies is unbounded, and both
 * are consequences of the same cancellation. A face carrying a hundredth of ambient mass gets the
 * full push and therefore a hundred times the speed. Physically that is even roughly true — gas
 * expanding into vacuum really does accelerate hard — but an explicit scheme cannot integrate it.
 * Measured without this, a breach plume reached **eleven tiles per tick** against a CFL limit of
 * one, which means advection stepping over ten tiles it should have interacted with. Mass stayed
 * conserved throughout, which is exactly what makes it dangerous: the ledger looks perfect while the
 * transport underneath is nonsense.
 *
 * ### It has to bound the total, not the increment
 *
 * The first attempt capped the per-tick impulse, on the reasoning that clamping a *force* is more
 * conservative than clamping stored state — it cannot eat exhaust that the projection or advection
 * legitimately produced. That reasoning is wrong, and measurement said so: capping the increment
 * changed the peak speed from eleven tiles per tick to eleven tiles per tick. Momentum accumulates.
 * A face taking a bounded push every tick with only [applyDrag]'s thirty-second to bleed it off
 * still runs away over a few dozen ticks, and it runs away *faster* as the gas drains, because the
 * mass in the denominator is falling while the momentum is not. So the bound is on the resulting
 * velocity, which is the quantity that actually has to stay under one.
 *
 * ### What it costs, said plainly
 *
 * Capping breaks the exact telescoping that guarantees a sealed vessel cannot push itself. That
 * guarantee is the one this file leans on hardest, so it matters that the cap **only ever binds on
 * a face with far less than ambient gas on it** — which is to say, in a plume outside the hull or in
 * a room already most of the way to vacuum. Every face of a sealed pressurised vessel sits near
 * ambient mass and moves at a small fraction of a tile per tick, so inside an intact ship the cap is
 * unreachable and the cancellation is exact. A ship whose interior faces are light enough to trip it
 * has lost its atmosphere and has nothing left to drift on.
 *
 * The honest fix is sub-stepping the fluid when [MomentumField.isCflSafe] fails, which is what that
 * function exists to make observable and what a fast exhaust will force anyway. This is the cheap
 * version of the same statement.
 */
private fun capped(momentum: Long, faceGrams: Long): Long {
    val limit = faceGrams / CAP_DENOMINATOR
    return if (momentum > limit) limit else if (momentum < -limit) -limit else momentum
}

/** Half a tile per tick: the fastest this force will leave a face going, with CFL headroom to spare. */
private const val CAP_DENOMINATOR = 2L

/** A tile's potential, or zero off the grid — space pushes back with nothing. */
private fun beyond(potential: LongArray, tile: Int): Long =
    if (tile < 0) 0L else potential[tile]

/**
 * Every tile's pressure in the impulse units the faces work in, converted **before** any difference
 * is taken.
 *
 * This is the whole of what makes the telescoping above exact, and it was worth an array. Converting
 * per face instead — `drop × SOUND_IMPULSE / AMBIENT_PRESSURE`, as this did — truncates each
 * difference separately, and a truncated sum of differences is not the difference of the sums. The
 * error is a fraction of a unit per face, it does not cancel, and along a column under gravity the
 * drops all lean the same way so it accumulates in one direction: a sealed motionless vessel booked
 * itself a fifth of its own impulse ledger in twelve ticks, all of it on the gravity axis, all of it
 * rounding. The x-axis looked perfect throughout, because there the drops alternate sign and the
 * truncation averages out — which is exactly how a bug like this hides.
 *
 * Differencing values that are each already whole numbers of impulse units telescopes exactly, in
 * integers, with no rounding left to accumulate. That is what the doc above always claimed and what
 * it now does.
 */
private fun potentialOf(pressure: LongArray): LongArray =
    LongArray(pressure.size) { pressure[it] * SOUND_IMPULSE / AMBIENT_PRESSURE }

/**
 * The momentum one whole atmosphere of difference puts on a face of ordinary air: a quarter of a
 * tile per tick, per tick.
 *
 * This is the speed of sound, expressed in the only units the grid has. Pinned by the CFL limit
 * rather than by the real figure — sound in air is about 340 m/s and a tile is a metre or so, which
 * would be hundreds of tiles per tick and is not a thing an explicit scheme can integrate. A quarter
 * is the largest value that leaves a breach comfortably inside [MomentumField.isCflSafe] once
 * buoyancy and the projection have also had their say, and it puts the far end of a hundred-tile
 * deck four hundred ticks away by wave *and* immediately by the elliptic solve, which between them
 * is the behaviour wanted: a bang that arrives, and a room that then drains coherently.
 *
 * Sub-stepping is the honest way past this and is the same answer [MomentumField.isCflSafe] already
 * points at for a fast exhaust. Until then the gas is slow and the ordering of events is right,
 * which is the trade every explicit fluid sim makes.
 */
private val SOUND_IMPULSE: Long = AMBIENT_TILE_GRAMS / 4
