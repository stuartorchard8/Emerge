package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.TILE_LITRES

/**
 * Fixed-point unit for the volume sum below. Volumes are ratios, so they need somewhere to keep
 * their fraction.
 *
 * ⚠️ It has to be far larger than it looks like it needs to be, because the divisor is a *tile* of
 * solid — millions of mass — while the dividend is a composition stated in parts per thousand. At
 * a million, `500 g / 6_532_100 g` truncated to 76 parts in a million and put the density of a
 * half-and-half mixture out by a third of a per cent. The division below keeps the remainder
 * explicitly rather than leaning on this being big enough.
 */
private const val VOLUME_UNIT: Long = 1_000_000_000L

/**
 * What a tile of pure [Species] weighs, at its real density.
 *
 * A tile is [TILE_LITRES] of room — a cube a little under a metre on a side — and a tile of iron is
 * what that much iron weighs, six and a half tonnes. Everything solid in the vessel is stated at
 * this scale, which is the scale the *gas* was always at: [Stuff.AMBIENT_AIR] puts a real
 * kilogram of air in a tile. Before that the solids were three orders under, and the visible
 * consequence was a steel ship that weighed about as much as the air inside it.
 *
 * `kg/m³ × litres` is mass because a cubic metre is a thousand litres, so [Budget.GRAM] is the only
 * factor between the textbook density and the integer. Its doc used to read "no scale factor", which
 * was true of the arithmetic and false about the meaning — the twin of `Critical.massPerTile` and
 * the same miss, found the same way. At 10⁶ without it a tile of iron weighed six and a half
 * **mass**, and every rock, hull plate and machine in the vessel with it.
 */
val Species.solidMassPerTile: Long
    get() = solidKgPerCubicMetre.toLong() * TILE_LITRES * Budget.GRAM

/**
 * The densest thing there is, and so the reference every other density is measured against in
 * [massPerTileOf]. Osmium, which is on the species table for exactly this purpose.
 */
private val REFERENCE_DENSITY: Long get() = Species.Osmium.solidMassPerTile

/**
 * What a tile of [mixture] weighs, from what it is made of.
 *
 * Densities do **not** average by mass — two things sharing a tile share its *volume*, so the
 * mixture's density is the harmonic mean over mass fractions (`total / Σ mᵢ/ρᵢ`) and not the
 * arithmetic one. It matters at exactly the compositions the ore field produces: half silica by
 * mass is most of the tile by volume, and an arithmetic mean would have that rock a third too
 * heavy.
 *
 * Empty mixtures weigh nothing, which is the honest answer and keeps callers from dividing by it.
 */
fun massPerTileOf(mixture: Mixture): Long {
    val memo = mixture.massPerTileMemo
    if (memo != Mixture.UNSET) return memo
    return massPerTileUncached(mixture).also { mixture.massPerTileMemo = it }
}

private fun massPerTileUncached(mixture: Mixture): Long {
    val total = mixture.total
    if (total <= 0L) return 0L
    // A pure pile is its species' density exactly. Not an optimisation: the fixed-point round trip
    // below is accurate to a few parts per million, and "a tile of iron weighs what a tile of iron
    // weighs" should not be approximate.
    mixture.dominant?.let { if (mixture[it] == total) return it.solidMassPerTile }
    // ── Everything below is a ratio of two like quantities, so the mass unit cancels ──
    //
    // The obvious loop accumulates `mass / density` directly, and that cannot survive a rescale:
    // `mass` is a *proportion* — a per-mille figure, a few hundred, and it does not move when the
    // unit moves — while `density` is a real tile mass and moves in full. The ratio between them is
    // therefore not scale-free at all; at one microgram per unit it is 6e-11 and floors to nothing
    // however much fixed point is thrown at it. [scaledRatio] cannot rescue it either, since
    // reducing that fraction shifts a three-digit numerator straight to zero.
    //
    // So each density is expressed against [REFERENCE_DENSITY] first. `ρ₀ / ρᵢ` is a ratio of two
    // real densities — unit-free, order one, and exact in fixed point — and the mixture's own
    // proportions are likewise taken as fractions of its total. The mass unit then enters exactly
    // once, at the end, carried by the reference density alone.
    var weighted = 0L
    for (species in Species.ALL) {
        val mass = mixture[species]
        if (mass <= 0L) continue
        val relative = scaledRatio(REFERENCE_DENSITY, species.solidMassPerTile, VOLUME_UNIT)
        weighted += scaledRatio(mass, total, relative)
    }
    // `ρ_mix = ρ₀ / Σ fᵢ·(ρ₀/ρᵢ)`, which is the harmonic mean rearranged so that the only
    // dimensioned term left is the reference density. The normalisation by [total] above is what
    // makes this independent of how the caller chose to state its proportions — per mille, per cent
    // or as a real pile of ore. That used to be an *unstated invariant*: nothing declared that
    // compositions had to sum to about 1000, nothing checked it, and handing this function a real
    // pile — which its signature plainly invites — wrapped it silently.
    return if (weighted <= 0L) 0L else scaledRatio(VOLUME_UNIT, weighted, REFERENCE_DENSITY)
}

/**
 * Millijoules per kelvin for a tile of [mixture] — the same unit every other solid's capacity is in.
 *
 * Heat capacity *does* average by mass, unlike density: warming a tile means warming each gram in
 * it. So this is the mass-weighted specific heat times what the tile weighs, and the two weightings
 * being different is the whole reason they are written out separately here.
 */
fun capacityPerTileOf(mixture: Mixture): Long {
    val memo = mixture.capacityPerTileMemo
    if (memo != Mixture.UNSET) return memo
    return capacityPerTileUncached(mixture).also { mixture.capacityPerTileMemo = it }
}

private fun capacityPerTileUncached(mixture: Mixture): Long {
    if (mixture.total <= 0L) return 0L
    // Split rather than multiplied-then-divided, which is what makes [SPECIFIC_HEAT_SCALE] free.
    //
    // Written the obvious way, `tile × mean / SCALE` peaks at the scaled average — so every digit of
    // precision kept in that average costs an order of headroom, and unlike most products here only
    // ONE side is a mass, so the whole thing is k¹. At a scale of 1000 that measured out at a safe
    // mass unit of 118,000: already too tight for the microgram rebaseline, and a thousand times
    // worse for every further digit.
    //
    // Splitting the average into whole units and remainder bounds the first term by the *unscaled*
    // specific heat (4182, a physical constant) and the second by the tile mass itself. Neither
    // mentions the scale, so the precision of the average and the headroom of the product stop
    // trading against each other and the row goes to a safe unit of 1.2e8 at any scale.
    //
    // [Budget.CAPACITY_DIVISOR] is applied to the *mass* and not folded into the scale, which would
    // be the obvious place for it. Folding it in makes the denominator 1e13, far past the point
    // [scaledRatio] has to start reducing, and the reduction shifts a nine-digit mean specific heat
    // down to three digits — half a per cent of error on every capacity in the game. Dividing the
    // tile mass instead costs the sub-milligram part of a tile, which is nothing.
    return scaledRatio(
        meanSpecificHeatMilli(mixture),
        SPECIFIC_HEAT_SCALE,
        massPerTileOf(mixture) / Budget.CAPACITY_DIVISOR,
    )
}

/**
 * Joules per kelvin for **this actual pile of matter** — not for a full tile of the stuff it is made
 * of, which is what [capacityPerTileOf] answers.
 *
 * The distinction is the whole point of storing a machine's casing as real matter. `capacityPerTileOf`
 * takes a *proportion* and tells you what a solid tile of that material would cost to warm;
 * this takes *masses* and tells you what the matter actually present costs. Once a tile holds real
 * species, the second question is the one every temperature in the game is asking.
 *
 * ⚠️ [org.emerge.demo.outofspace.world.StuffLayer.heatCapacityAt] is the allocation-free twin of
 * this, walking a layer's row instead of a [Mixture]. The two must agree, and `CasingMassTest`
 * checks that they do — a divergence would mean a machine's built temperature and its running
 * temperature came from different physics.
 */
fun heatCapacityOf(mixture: Mixture): Long {
    var sum = 0L
    for (s in Species.ALL) sum += mixture[s] * s.specificHeat
    return sum / Budget.CAPACITY_DIVISOR
}

/** Millijoules per gram per kelvin: what [mixture] costs to warm, averaged by mass. */
fun specificHeatOf(mixture: Mixture): Long = meanSpecificHeatMilli(mixture) / SPECIFIC_HEAT_SCALE

/**
 * Fixed-point unit for [meanSpecificHeatMilli].
 *
 * A millionth, and it costs nothing: [capacityPerTileOf] splits rather than divides, so this does
 * not appear in any overflow bound. It buys the averaging step a truncation of a few parts per
 * billion instead of a few parts per million — which matters only where a rounding accumulates,
 * which is exactly where the energy ledgers live.
 */
internal const val SPECIFIC_HEAT_SCALE: Long = 1_000_000L

/**
 * The mass-averaged specific heat of [mixture], in thousandths of a J/kg/K.
 *
 * **Normalised per species rather than summed and then divided**, which is what makes every caller
 * above total-agnostic. A composition is a set of *proportions* — `Water to 1000` and `Water to 1`
 * are the same recipe and must give the same answer — so this function is only meaningful if it
 * depends on the ratios and not on the units they were stated in. Dividing at the end looks like it
 * achieves that and does not: `Σ mass × specificHeat` overflows on its own at about 2.2e15,
 * before any division gets the chance to cancel the scale. Reducing each term against `total` as it
 * is accumulated means nothing here ever grows with the total at all, and the sum is bounded by the
 * largest specific heat in the table whatever it is handed.
 *
 * The [SPECIFIC_HEAT_SCALE] is why this is not the rounding the old code was right to avoid: an
 * average taken in whole J/kg/K costs 0.04% on a wet rock, which is small until it is the difference
 * between two ledgers. In thousandths the per-species truncation is under 3 parts per million total.
 */
private fun meanSpecificHeatMilli(mixture: Mixture): Long {
    val total = mixture.total
    if (total <= 0L) return 0L
    var mean = 0L
    for (species in Species.ALL) {
        val mass = mixture[species]
        if (mass > 0L) mean += scaledRatio(mass, total, species.specificHeat * SPECIFIC_HEAT_SCALE)
    }
    return mean
}

/**
 * The face of one tile, in **thousandths of a square metre** — the area heat crosses between two
 * neighbouring tiles, and the geometry in [conductanceCentiTicksOf].
 *
 * Derived, not chosen: a tile is [TILE_LITRES] of room, so it is a cube `0.830^(1/3)` = 0.9398 m on
 * a side and its face is `0.830^(2/3)` = 0.8832 m². Stated here as an integer because a cube root is
 * not something to compute in fixed point for a constant, and checked by `MaterialThermalTest`.
 */
private const val TILE_FACE_MILLI_SQUARE_METRES = 883L

/**
 * How many seconds of world time one tick is **worth to the heat solver** — the single calibration
 * that turns real thermal conductivities into the game's time constants.
 *
 * ### Why this number and not another
 *
 * The physics fixes everything except the scale. Two adjacent cubes of side `L` share a face of area
 * `L²` at a centre distance of `L`, so their joint conducts `G = k·L` and each holds `C = ρ·c·L³`;
 * the time constant of the pair is `τ = C/G = ρ·c·L²/k`. That is a number of *seconds*, and
 * A conductance wants a number of *ticks*, so exactly one constant is free.
 *
 * ⛔ **Choosing it is the whole of the re-tune, because the five materials it replaces did not agree
 * on it.** Measured against their own densities and conductivities, the five hand-written
 * time constants implied anywhere from 1,025 s/tick (firebrick) to 13,110 s/tick (copper) — a spread
 * of **12.8×**, which is what it means for those numbers to have been tuned rather than derived.
 * There is no value here that keeps all five where they were; there is only a choice of which way
 * each one moves.
 *
 * **An hour is that choice**, and it is two things at once: it is the round number nearest the
 * *geometric mean* of the five (3,661 s), which is the unique anchor that minimises the largest
 * change any one material sees — no material moves by more than 3.6× — and it is a duration a person
 * can hold in their head. Within 1.7% of the optimum and infinitely easier to reason about.
 *
 * ⚠️ **This is the heat solver's tick and nothing else's, and the game is not consistent about it.**
 * A thruster exhausts at 3 km/s and a rock crossing one tile in a tick is moving at 0.94 m per tick;
 * at an hour a tick that rock is doing a quarter of a millimetre a second. The motion sim and the
 * heat sim disagree about what a tick is by about four orders of magnitude. That disagreement is
 * older than this constant — `PLAN_ambient_chemistry.md` records the same complaint about the
 * furnace element — and naming it here is not fixing it. It is where anyone who goes looking for it
 * should find it written down.
 */
private const val HEAT_SECONDS_PER_TICK = 3_600L

/**
 * How long heat takes to cross a contact of [mixture], in **hundredths of a tick** — the time
 * constant the deleted `Material` enum used to state by hand.
 *
 * `τ = ρ·c·L²/k`, in the units above. ⛔ **Derived rather than stated, which is the point**: a
 * material is a species now, any species can in principle be one, and 170 hand-tuned time constants
 * is not a table anybody could keep honest. The five that were hand-written disagreed with physics
 * by 12.8× amongst themselves — see [HEAT_SECONDS_PER_TICK], which is where that is measured.
 *
 * ⚠️ **A mixture's conductivity is the mass-weighted HARMONIC mean, not the arithmetic one.** Heat
 * crossing a composite passes through all of it, so the poor conductor governs — the same rule
 * [org.emerge.demo.outofspace.world.seriesConductance] applies to a joint between two things, for
 * the same reason, and the same rule [massPerTileOf] applies to density. An arithmetic mean would
 * let one thread of copper make a brick conduct like metal.
 *
 * ⚠️ Every material a thing is built from is a single species today, so the mean is exercised only by
 * `MaterialThermalTest`. It is written out anyway because a rock is a mixture and asking this of one
 * is the obvious next thing somebody does.
 */
fun conductanceCentiTicksOf(mixture: Mixture): Long {
    val total = mixture.total
    if (total <= 0L) return 0L
    val k = conductivityOf(mixture)
    if (k <= 0L) return 0L
    // `100 x rho x c x face` peaks around 3.5e11 for the densest, most heat-hungry species in the
    // table, against `Long`'s 9.2e18 — the two divisors are applied after, together, because each on
    // its own would floor a thin material's time constant toward nothing.
    val density = massPerTileOf(mixture) / (TILE_LITRES * Budget.GRAM)
    val specificHeat = specificHeatOf(mixture)
    return 100L * density * specificHeat * TILE_FACE_MILLI_SQUARE_METRES / (k * HEAT_SECONDS_PER_TICK)
}

/**
 * The thermal conductivity of [mixture] in milliwatts per metre per kelvin — the harmonic mean over
 * mass fractions, for the reason [conductanceCentiTicksOf] gives.
 *
 * Built the way [massPerTileOf] builds its density: each conductivity is expressed against the
 * *largest* in the table first, so the ratio is unit-free and order one, and the physical unit
 * enters exactly once at the end. Stating it as `Σ fᵢ/kᵢ` directly would floor to nothing, since a
 * mass fraction is a few hundred and a conductivity is hundreds of thousands.
 */
fun conductivityOf(mixture: Mixture): Long {
    val total = mixture.total
    if (total <= 0L) return 0L
    mixture.dominant?.let { if (mixture[it] == total) return it.milliWattsPerMetreKelvin.toLong() }
    var weighted = 0L
    for (species in Species.ALL) {
        val mass = mixture[species]
        if (mass <= 0L) continue
        val k = species.milliWattsPerMetreKelvin.toLong()
        if (k <= 0L) return 0L
        val relative = scaledRatio(REFERENCE_CONDUCTIVITY, k, VOLUME_UNIT)
        weighted += scaledRatio(mass, total, relative)
    }
    return if (weighted <= 0L) 0L else scaledRatio(VOLUME_UNIT, weighted, REFERENCE_CONDUCTIVITY)
}

/** The best conductor there is, and so the reference every other is measured against. Silver. */
private val REFERENCE_CONDUCTIVITY: Long get() = Species.Silver.milliWattsPerMetreKelvin.toLong()
