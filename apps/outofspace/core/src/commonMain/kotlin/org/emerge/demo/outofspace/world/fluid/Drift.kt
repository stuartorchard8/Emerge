package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.apportion
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * Species drift: settling under gravity (heavier sinks) and mixing down concentration gradients
 * (Fick's law). Mass-neutral by construction; not pressure-neutral.
 *
 * Two terms: settling (g × (M − M̄) / M) and mixing (down concentration share gradient).
 * Exchange balanced per face via [apportion]; no momentum attached.
 */
fun applySpeciesDrift(
    edges: EdgeGrid,
    apertures: ApertureField,
    grams: LongArray,
    gravity: Frac2,
    species: List<Species> = Species.GASES,
) {
    val snapshot = grams.copyOf()
    val total = tileMass(edges.grid.size, snapshot, species)
    val planned = LongArray((edges.xEdgeCount + edges.yEdgeCount) * Species.COUNT)

    // ── Plan every face against the same snapshot ──
    eachOpenFace(edges, apertures) { slot, before, after, aperture, alongX ->
        exchange(
            snapshot, total, before, after,
            if (alongX) gravity.x.raw else gravity.y.raw,
            aperture, species, planned, slot * Species.COUNT,
        )
    }

    // ── How much each tile has been asked to give up, across all four of its faces ──
    val demand = LongArray(snapshot.size)
    eachOpenFace(edges, apertures) { slot, before, after, _, _ ->
        val base = slot * Species.COUNT
        for (s in species) {
            val amount = planned[base + s.ordinal]
            if (amount > 0L) demand[before * Species.COUNT + s.ordinal] += amount
            else if (amount < 0L) demand[after * Species.COUNT + s.ordinal] -= amount
        }
    }

    // ── Apply, scaled so that nobody is drained past what they actually had ──
    eachOpenFace(edges, apertures) { slot, before, after, _, _ ->
        val base = slot * Species.COUNT

        // One factor for the whole face rather than one per species, because [exchange] balanced the
        // two directions against each other and scaling them unevenly would break that. Drift would
        // start shifting net weight about, which is the one thing it must never do.
        var factor = FACTOR_SCALE
        for (s in species) {
            val amount = planned[base + s.ordinal]
            if (amount == 0L) continue
            val at = (if (amount > 0L) before else after) * Species.COUNT + s.ordinal
            val asked = demand[at]
            if (asked <= snapshot[at]) continue
            val allowed = snapshot[at] * FACTOR_SCALE / asked
            if (allowed < factor) factor = allowed
        }
        if (factor <= 0L) return@eachOpenFace

        for (s in species) {
            val amount = planned[base + s.ordinal] * factor / FACTOR_SCALE
            if (amount > 0L) move(grams, before, after, s.ordinal, amount)
            else if (amount < 0L) move(grams, after, before, s.ordinal, -amount)
        }
    }
}

/**
 * Every open face, x then y. One iterator for all three passes to keep them in sync.
 */
private inline fun eachOpenFace(
    edges: EdgeGrid,
    apertures: ApertureField,
    body: (slot: Int, before: Int, after: Int, aperture: Int, alongX: Boolean) -> Unit,
) {
    for (e in 0 until edges.xEdgeCount) {
        if (!apertures.isXOpen(e)) continue
        val before = edges.xEdgeBefore(e)
        val after = edges.xEdgeAfter(e)
        if (before < 0 || after < 0) continue
        body(e, before, after, apertures.xAt(e), true)
    }
    for (e in 0 until edges.yEdgeCount) {
        if (!apertures.isYOpen(e)) continue
        val before = edges.yEdgeBefore(e)
        val after = edges.yEdgeAfter(e)
        if (before < 0 || after < 0) continue
        body(edges.xEdgeCount + e, before, after, apertures.yAt(e), false)
    }
}

/** Fixed-point denominator for the over-draw limiter. A power of two, so the division is cheap. */
private const val FACTOR_SCALE = 1L shl 20

/**
 * Per-species settling + mixing across one face, balanced mass-neutral.
 *
 * Plans rather than applies: all faces computed against one snapshot, applied after.
 * [planned] gets signed per-species amounts (positive = before → after).
 */
private fun exchange(
    grams: LongArray,
    total: LongArray,
    before: Int,
    after: Int,
    gravityRaw: Long,
    aperture: Int,
    species: List<Species>,
    planned: LongArray,
    at: Int,
) {
    val mass = total[before] + total[after]
    if (mass <= 0L) return

    val moles = millimolesOf(grams, before, species) + millimolesOf(grams, after, species)
    if (moles <= 0L) return
    val average = mass * MILLI / moles          // grams per mole, averaged over the face
    val faceGrams = mass / 2L

    val down = LongArray(Species.COUNT)         // net movement from `before` to `after`
    val up = LongArray(Species.COUNT)
    var downTotal = 0L
    var upTotal = 0L

    for (s in species) {
        var net = settling(grams, s, before, after, average, gravityRaw) +
            mixing(grams, total, s, before, after, faceGrams)
        if (net == 0L) continue

        net = net * aperture / ApertureField.OPEN
        // Never more than the side it would come from actually holds.
        val donor = if (net > 0L) before else after
        val available = grams[donor * Species.COUNT + s.ordinal]
        if (available <= 0L) continue
        if (net > available) net = available
        if (net < -available) net = -available
        if (net == 0L) continue

        if (net > 0L) { down[s.ordinal] = net; downTotal += net }
        else { up[s.ordinal] = -net; upTotal += -net }
    }

    // As much one way as the other, so this sorts and stirs without shifting any weight about.
    val traded = minOf(downTotal, upTotal)
    if (traded <= 0L) return
    val goingDown = apportion(down, traded)
    val goingUp = apportion(up, traded)

    for (s in species) {
        planned[at + s.ordinal] = goingDown[s.ordinal] - goingUp[s.ordinal]
    }
}

/**
 * Settling: `g × (M − M̄) / M`, applied to the upwind side. Positive = before → after.
 */
private fun settling(
    grams: LongArray,
    s: Species,
    before: Int,
    after: Int,
    average: Long,
    gravityRaw: Long,
): Long {
    if (gravityRaw == 0L) return 0L
    val heaviness = s.molarMass.toLong() - average
    if (heaviness == 0L) return 0L

    // Toward +n when a heavy species is pulled that way, or a light one pushed the other way.
    val along = heaviness * gravityRaw > 0L
    val donor = if (along) before else after
    val available = grams[donor * Species.COUNT + s.ordinal]
    if (available <= 0L) return 0L

    val magnitude = if (heaviness > 0L) heaviness else -heaviness
    val speed = if (gravityRaw > 0L) gravityRaw else -gravityRaw
    var moving = available * magnitude / s.molarMass.toLong()
    // One rounded operation, gravity and settling together — see [scaleByGravity]. Splitting the
    // two put a truncating divide immediately after a rounded multiply, which is a truncating chain.
    moving = scaleByGravity(moving, speed, SETTLING_NUMERATOR, SETTLING_DENOMINATOR)
    return if (along) moving else -moving
}

/**
 * Mixing: flows from higher to lower share of mixture (Fick's law). Driven by composition,
 * not pressure.
 */
private fun mixing(
    grams: LongArray,
    total: LongArray,
    s: Species,
    before: Int,
    after: Int,
    faceGrams: Long,
): Long {
    val here = share(grams, total, before, s)
    val there = share(grams, total, after, s)
    val gap = here - there
    if (gap == 0L) return 0L
    return faceGrams * gap / SHARE_SCALE * MIXING_NUMERATOR / MIXING_DENOMINATOR
}

/** Species' fraction of a tile's gas, numerator over [SHARE_SCALE]. */
private fun share(grams: LongArray, total: LongArray, tile: Int, s: Species): Long {
    val all = total[tile]
    if (all <= 0L) return 0L
    return grams[tile * Species.COUNT + s.ordinal] * SHARE_SCALE / all
}

private fun move(grams: LongArray, from: Int, to: Int, species: Int, amount: Long) {
    if (amount <= 0L) return
    val taken = minOf(amount, grams[from * Species.COUNT + species])
    grams[from * Species.COUNT + species] -= taken
    grams[to * Species.COUNT + species] += taken
}

private const val MILLI = 1000L

/** Fixed-point denominator for a mixture share. A power of two, so the division is exact-ish. */
private const val SHARE_SCALE = 1L shl 20

/** Settling/mixing rates. Game-fidelity dials; ratio decides whether rooms stratify or stay stirred.
 * Settling > mixing makes heavy gas pool; equal rates keep things stirred. */
private const val SETTLING_NUMERATOR = 1L
private const val SETTLING_DENOMINATOR = 8L
private const val MIXING_NUMERATOR = 1L
private const val MIXING_DENOMINATOR = 16L
