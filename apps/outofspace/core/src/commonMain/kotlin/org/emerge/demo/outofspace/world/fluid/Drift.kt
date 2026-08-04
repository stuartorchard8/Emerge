package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.apportion
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * How each gas moves *relative to the mixture it is in* — settling out under gravity, and mixing
 * back together down its own concentration gradient.
 *
 * ### Why bulk flow cannot do this
 *
 * There is one velocity field, shared by every species, and advection moves a tile's contents as a
 * **proportional sample** of it — deliberately, since that is what stops a draught skimming the
 * oxygen off the top of a room. But a rule that always moves gases in the ratio they are already in
 * can never change that ratio. **Bulk flow cannot unmix, and it cannot mix either.** Seal carbon
 * dioxide in the top half of a one-tile-wide column and nitrogen in the bottom and nothing will ever
 * swap them; put two gases side by side in weightlessness and they will sit there forever.
 *
 * So species get a second, much smaller motion of their own, on top of the bulk:
 *
 * ```
 * flux  =  concentration × bulk velocity      (unchanged, everything together)
 *        + concentration × drift velocity     (this file, each gas its own way)
 * ```
 *
 * The drift velocity is worked out algebraically rather than integrated, and that is the whole trick.
 * The full version of this model gives every species its own momentum field and lets them push each
 * other about; the coupling between them turns out to be extremely stiff, because molecules collide
 * far more often than once a tick, so the honest reduction is to assume each species reaches its
 * terminal relative velocity immediately and simply compute what that is. One extra flux pass, no
 * extra momentum fields, no stiff coupling to sub-step around.
 *
 * ### The two terms
 *
 * Both come from each species having its own partial pressure and its own weight; setting the drift
 * to zero recovers the textbook result that each gas settles to its own scale height, heavier gases
 * hugging the floor more closely than lighter ones.
 *
 * - **Settling** goes as `g × (M − M̄) / M`. Note the division by the species' *own* molar mass, not
 *   by a fixed reference: a light gas has to move further to carry the same momentum, so it drifts
 *   further for the same imbalance. Heavier than the local mixture means sinking, lighter means
 *   rising, and what counts as heavy is judged against the face's own average — carbon dioxide sinks
 *   through air and would rise through something denser, with nothing having to be told which.
 * - **Mixing** goes down the gradient of a species' share of the mixture. This is Fick's law, and it
 *   is what makes two gases in contact eventually become one gas. It opposes settling, which is why
 *   a real atmosphere is not sorted into neat layers, and why the balance between the two constants
 *   below is what decides whether a room stratifies or stays stirred.
 *
 * Writing them as two explicit terms rather than deriving both from the total pressure gradient is
 * deliberate. The tidy derivation reads settling out of the *hydrostatic* gradient — and
 * [applyBuoyancy] suppresses exactly that gradient on purpose, since applying gravity to the full
 * weight of the air would pile a vessel's whole atmosphere on the floor. Taken literally the tidy
 * version would therefore find no gradient to work from and nothing would ever settle. Same physics
 * and the same equilibrium; it just cannot borrow a quantity that has been deliberately removed.
 *
 * ### Mass-neutral by construction
 *
 * The exchange across a face is balanced: as many grams go one way as the other, which is what
 * "relative to the mixture" means. Each direction is scaled to the smaller of the two totals by
 * [apportion], so the balance is exact and every species is conserved to the gram.
 *
 * It is *not* pressure-neutral, and should not be. Equal masses of gases with different molar masses
 * are different numbers of moles, so the pressure field genuinely changes and the next tick's
 * projection responds to it. The old `stratifyColumns` appeared to conserve pressure only because
 * pressure was mass back then.
 *
 * No momentum is attached to any of it: this is molecular, not bulk motion, and giving it momentum
 * would let a sealed column push its own ship around.
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
 * Every face gas can cross, x then y, handed its slot in the planning arrays.
 *
 * Exists so the three passes above walk the same faces in the same order with the same skips.
 * Written out three times they would drift apart the first time anybody changed what counts as open,
 * and the symptom would be one face's planned exchange being applied to another.
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
 * Works out what gas should trade across one face: every species' settling and mixing added
 * together, then balanced so that as much mass comes back as goes.
 *
 * A face with the world on one side does nothing — there is no mixture out there to sort against,
 * and gas leaving for space is [advectMass]'s business.
 *
 * ### It plans rather than applies, and that is the whole point
 *
 * This used to edit `grams` in place as the sweep visited each face, which made the answer depend on
 * the order faces were visited in — the very trade [project] refuses to make when it picks Jacobi
 * over Gauss-Seidel. x-edges are numbered `y × stride + x`, so a tile's left face was always
 * processed before its right one, and gas moved in from the left could move on again in the same
 * sweep while gas moved in from the right could not.
 *
 * That is a left-to-right bias, and because the amount traded is scaled by a species' molar mass it
 * is a bias that acts on *composition*. It was found by mirroring a breach about its own column: the
 * bulk density came out even to within one percent, while oxygen — the heavy minority — sat at three
 * grams on one side against six on the other. Mass was conserved perfectly throughout, so nothing
 * else could have caught it.
 *
 * So every face is now planned against one snapshot and applied afterwards. [planned] receives the
 * signed per-species amounts at [at], positive meaning `before → after`.
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
 * Settling: `g × (M − M̄) / M`, applied to whatever is upwind of the drift.
 *
 * Positive means "from `before` toward `after`", which is along +x or +y — and +y is down, so under
 * ordinary gravity a heavy gas gives a positive number on a horizontal face.
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
    moving = moving * speed / MomentumField.SPEED_LIMIT_RAW
    moving = moving * SETTLING_NUMERATOR / SETTLING_DENOMINATOR
    return if (along) moving else -moving
}

/**
 * Mixing: a species flows from wherever it is a larger share of the mixture toward wherever it is a
 * smaller one, which is Fick's law and is what makes two gases in contact become one gas.
 *
 * Shares rather than raw amounts, so this is driven by *composition* and not by pressure — a dense
 * room next to a thin one does not diffuse if both hold the same mixture, which is right, because
 * evening out the pressure is the projection's job and not this one's.
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

/** A species' fraction of a tile's gas, as a numerator over [SHARE_SCALE]. */
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

/**
 * How briskly gases settle out, and how briskly they stir back together.
 *
 * Both are game-fidelity dials rather than physical constants, and the *ratio* between them is the
 * interesting one: it decides whether a still room ends up in neat layers or stays mixed. Real
 * barodiffusion is minuscule — Earth's atmosphere is not sorted by species at human scale, because
 * stirring beats settling by orders of magnitude — so honest numbers here would mean nothing ever
 * visibly stratifies. These are set so that heavy gas pools in the low corners of a quiet room over
 * tens of ticks while a fresh interface still blurs, because both of those are things worth being
 * able to see.
 */
private const val SETTLING_NUMERATOR = 1L
private const val SETTLING_DENOMINATOR = 8L
private const val MIXING_NUMERATOR = 1L
private const val MIXING_DENOMINATOR = 16L
