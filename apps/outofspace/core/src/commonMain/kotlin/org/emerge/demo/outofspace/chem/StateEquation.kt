package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio

/**
 * The equation of state: what pressure a fluid pushes back with, given how much of it is packed
 * into a cell and how hot it is.
 *
 * The atmosphere solver has always used the ideal gas law — `P = nRT/V`, which is
 * [org.emerge.demo.outofspace.world.tilePressure]. An ideal gas has no liquid phase and
 * cannot have one: its molecules have no size and do not attract each other, so there is nothing
 * to condense and nothing to condense *into*. Squeeze it forever and it just gets denser.
 *
 * Van der Waals adds the two things that were missing, and only those two:
 *
 *  - molecules take up room, so a cell cannot be packed past a hard limit ([CLOSE_PACKED]);
 *  - molecules pull on each other, so a dense clump holds itself together.
 *
 * That is the whole model. Everything a phase transition *is* — a boiling curve, a latent heat, a
 * critical point above which liquid and gas stop being different things — is a consequence of
 * those two terms fighting, and none of it is written down anywhere in this file. There is no
 * boiling point here, and deliberately so: a boiling point is an *answer*, and the answer depends
 * on pressure, which depends on what else is in the room.
 *
 * ### Why this is in reduced units
 *
 * Written the usual way, van der Waals carries two fitted constants per species — `a` for the
 * attraction, `b` for the excluded volume — and both are hostile to this codebase. They are in SI
 * units, they are tiny, and the `a·n²/V²` term squares a mole count that is already in the
 * billions once a tile holds liquid. That product overflows a `Long` before it means anything.
 *
 * So this uses the *reduced* form instead. Measure each quantity as a multiple of that species'
 * own critical point — density as a fraction of critical density, temperature as a fraction of
 * critical temperature — and the constants cancel out entirely, leaving:
 *
 * ```
 *     Pr = 8·Tr·ρr / (3 − ρr)  −  3·ρr²
 * ```
 *
 * This is the law of corresponding states, and the thing to notice about it is that **there are no
 * species in it**. Water and nitrogen obey the same curve. They differ only in where their critical
 * point sits, which is three measured numbers apiece ([Critical]) rather than a hand-drawn phase
 * diagram. It is also, conveniently, all small numbers: `ρr` lives in `[0, 3)` and `Tr` around 1,
 * so the arithmetic fits in fixed point with room to spare.
 */

/**
 * Fixed-point scale for every reduced quantity here: `SCALE` means 1.0.
 *
 * The size of this is not free choice in either direction. Too coarse and a trace species is
 * quantised out of existence — at a million, thirteen mass of carbon dioxide in a tile of air came
 * out as a reduced density of `33`, two significant figures, and something rarer would have rounded
 * to zero and stopped exerting any pressure at all. Too fine and [reducedPressure] overflows, since
 * it multiplies a temperature by a density and both carry this scale.
 *
 * A hundred million is the largest round value that leaves `8 · Tr · ρr` inside a `Long` at the
 * extremes of both ([CLOSE_PACKED] density, a few times critical temperature), and it puts the
 * quantisation error on a trace gas comfortably below a tenth of a percent.
 */
const val SCALE: Long = 100_000_000L

/**
 * The reduced density at which the fluid is packed shoulder to shoulder and pressure runs away to
 * infinity — `ρr = Zc/Ωb = 3.9514`, the point where the cell is all molecule and no gap.
 * Peng-Robinson is undefined at and beyond it, so callers are held below it.
 *
 * This is not a fudge factor: it is the excluded-volume term saying that matter cannot be
 * compressed past the size of its own molecules.
 *
 * ### The ceiling this puts on a liquid
 *
 * Three times critical density is a real bound on how dense any fluid here can get, and it is
 * tighter than reality. Liquid water at room temperature is about 998 kg/m³ against a critical
 * density of 322 — that is `ρr = 3.1`, which this model cannot represent at all. What it can
 * represent tops out at 966 kg/m³, some 3% light, and the stable liquid branch at room temperature
 * begins around `ρr = 2.1` rather than filling the range.
 *
 * So liquid water lives in a narrow window here, roughly `2.1` to `3.0`, and a caller that tries to
 * pack a cell denser than that is asking for a state van der Waals does not have. That is the
 * two-constant equation showing its age, and it is the main thing to remember before trusting a
 * liquid density out of this model quantitatively. Qualitatively — that a liquid holds together,
 * resists spreading, and boils when it is heated — it is sound.
 */
const val CLOSE_PACKED: Long = 395_137_292L

/**
 * `1/Zc`, in [SCALE] — the coefficient on Peng-Robinson's repulsive term once it is written in
 * reduced density.
 *
 * The reduced isotherm is
 *
 *     Pr = (Tr·ρr)/Zc·(1 − b·ρr)  −  A·α(Tr)·ρr² / (1 + 2b·ρr − b²ρr²)
 *
 * with `b = Ωb/Zc` ([COVOLUME]) and `A = Ωa/Zc²` ([ATTRACTION]). All three fall out of the two
 * universal Peng-Robinson constants `Ωa = 0.45724`, `Ωb = 0.07780` and its critical compressibility
 * `Zc = 0.30740`; none of them is a choice.
 *
 * ⚠️ Worth one check by eye: `INV_ZC − COVOLUME` is exactly 3, because `(1 − Ωb)/Zc = 3`. If either
 * constant is ever retyped, that identity is the cheapest way to catch it.
 */
const val INV_ZC: Long = 325_307_668L

/** Peng-Robinson's covolume as a fraction of critical volume, `Ωb/Zc`, in [SCALE]. See [INV_ZC]. */
const val COVOLUME: Long = 25_307_659L

/** The attraction coefficient `Ωa/Zc²`, in [SCALE]. See [INV_ZC]. */
const val ATTRACTION: Long = 483_869_859L

/**
 * The margin [MAX_REDUCED_DENSITY] holds back from the wall, as a fraction of one reduced unit.
 *
 * A thirty-second — 0.03125 — chosen to clear the sweeps that examine the curve's *shape*:
 * `StateEquationTest` walks the isotherm to `3 − 0.05` looking for the falling stretch, and a clamp
 * biting inside that sweep would be flattening the very defect those tests exist to pin.
 * (`SaturationTest` goes further, to `3 − 0.01`, but only asserts the curve never falls — which a
 * flat clamp satisfies by construction.)
 *
 * It also bounds `ρr / (3 − ρr)` at **96**, which is what makes the thermal term's arithmetic safe
 * at any temperature rather than up to an assumed one.
 */
private const val PACKING_MARGIN: Long = SCALE / 32

/**
 * The densest a fluid is allowed to actually *be evaluated at* — a margin short of [CLOSE_PACKED].
 *
 * [CLOSE_PACKED] is the physical wall and stays the domain limit: past it there is no pressure and
 * [vanDerWaalsPressure] refuses. This is a narrower, arithmetic limit inside that domain, and the
 * distinction is the whole of `NUMERIC_LIMITS.md` §6.1: the equation was being *asked* for values
 * it could not represent while still technically inside its domain, because `leastRoomFor` drives
 * density to `CLOSE_PACKED − 1` and a denominator of 1 sends `8·Tr·ρr/(3 − ρr)` to about 1e17.
 *
 * ### What it costs, stated plainly
 *
 * `ρr` tops out at **2.96875** instead of 2.99999999. Against a liquid branch that already only
 * runs from about 2.1 to 3.0 at room temperature — and which [CLOSE_PACKED] notes is 3% light
 * against real water anyway — that is the top ~1% of the representable liquid range, traded for
 * a pressure curve that no longer changes sign. Everything below it is bit-for-bit unchanged.
 */
const val MAX_REDUCED_DENSITY: Long = CLOSE_PACKED - PACKING_MARGIN

/**
 * Where a species stops being able to tell liquid from gas.
 *
 * Two measured numbers, both published constants of the substance rather than tuning knobs: the
 * temperature at the critical point and the density there. Above [kelvin] there is no liquid phase
 * at any pressure at all — the distinction genuinely ceases to exist — and that fact arrives as a
 * consequence of the reduced equation rather than as a branch anybody wrote.
 *
 * ### Why the critical *density* is derived and not given
 *
 * All three constants are tabulated and they cannot all be honoured. A cubic equation of state
 * fixes the ratio `Pc·vc/(R·Tc)` — that is forced by the shape of the equation, not chosen — at
 * `3/8` for van der Waals and at **0.30740** for Peng-Robinson, whereas real fluids measure around
 * 0.27. Pick any two of the three and the third follows.
 *
 * ⚠️ **This file used to pick the other two, and the swap is deliberate.** It took [kelvin] and a
 * *measured* critical density and let the pressure fall out, on the reasoning that temperature and
 * density are what place the transition in the state space the solver moves through. That is true
 * and it is not what the vessel actually asks. What the vessel asks is "is this cell above its
 * saturation pressure" — boiling, off-gassing, and every comparison [reducedPressure] feeds — and
 * getting that right means getting *pressure* right. So the measured pair is now the critical
 * temperature and the critical **pressure**, and the density is whatever the equation says.
 *
 * The cost is stated rather than hidden: Peng-Robinson's critical compressibility is 0.307 against
 * a real ~0.27 for these fluids, so a derived critical density lands around 25% low, and every
 * liquid density downstream is low by about as much. Liquid water at room temperature comes out
 * near 850 kg/m³ against a real 998. That is the well-known density weakness of Peng-Robinson, the
 * price of getting its excellent vapour pressures, and it is fixable later by a Péneloux volume
 * shift — a fourth constant that moves densities without touching the pressure curve at all.
 *
 * @param kelvin critical temperature, K.
 * @param bar critical pressure, bar.
 * @param acentric the acentric factor, which is the third constant and the only thing in the whole
 *   equation of state that tells one substance from another. See [kappa].
 * @param triplePointKelvin the temperature below which this species has no liquid phase at any
 *   pressure. **Nothing reads it yet** — it is here so the shape of the table is settled before a
 *   solid phase arrives, rather than being retrofitted through five call sites afterwards.
 */
class Critical(
    val kelvin: Int,
    private val bar: Double,
    private val acentric: Double,
    val triplePointKelvin: Int,
    private val species: Species,
) {

    /**
     * Peng-Robinson's `κ`, in [SCALE] — the acentric factor as the equation actually consumes it.
     *
     * `κ = 0.37464 + 1.54226ω − 0.26992ω²`, derived once here so that [alphaAt] is two multiplies
     * and a square root rather than a polynomial per call. It is the *entire* footprint of ω in the
     * model: two fluids with equal κ have identical reduced behaviour, which is why the saturation
     * dome is a one-parameter family and not a curve per substance.
     */
    val kappa: Long = ((0.37464 + 1.54226 * acentric - 0.26992 * acentric * acentric) * SCALE).toLong()

    /**
     * Critical density, expressed as the mass of this species a full tile holds at exactly that
     * density. Tile-relative for the same reason
     * [org.emerge.demo.outofspace.world.VolumeField] is: the solver never asks how big a metre is,
     * and this keeps it from having to start.
     *
     * Derived from [kelvin] and [bar] through Peng-Robinson's own `Zc`, not measured — see the note
     * above on which two constants are the inputs. Computed in floating point because it happens
     * once per species at class-init and never again; only `*` and `/` are involved, which are
     * exactly specified by IEEE-754, so it is as deterministic across machines as the integers are.
     */
    val massPerTile: Long = run {
        val molesPerCubicMetre = bar * 1e5 / (PENG_ROBINSON_ZC * GAS_CONSTANT * kelvin)
        val kgPerCubicMetre = molesPerCubicMetre * species.molarMass / 1000.0
        (kgPerCubicMetre * TILE_LITRES).toLong() * Budget.GRAM
    }

    /**
     * Critical pressure, in the units [org.emerge.demo.outofspace.world.tilePressure]
     * reports — the conversion that carries a reduced pressure back into the solver's scale.
     *
     * `Pc = Zc·n_c·Tc / T_ambient`, which is the compressibility ratio above rearranged, with the
     * millimoles-scaled-by-temperature units the existing pressure field already speaks. The
     * consequence worth knowing: at ordinary atmospheric density this reproduces the ideal gas law
     * the solver used before to within a fraction of a percent, because that is what a cubic
     * equation of state *does* when the molecules are far apart. The old behaviour is the sparse
     * limit of the new one.
     */
    /**
     * The triple point as a reduced temperature, for the one comparison that needs it every time
     * [phaseAt] is asked a question.
     */
    val triplePointR: Long = triplePointKelvin.toLong() * SCALE / kelvin

    /**
     * Reduced density of this species as a **solid** — the condensed branch below [triplePointR].
     *
     * ⛔ **Measured, not derived, and it has to be.** A cubic equation of state describes fluids: it
     * has a vapour branch and a liquid branch and no third one, so there is nothing in
     * Peng-Robinson that could produce this. [Species.solidKgPerCubicMetre] is a laboratory number
     * and it is already on file for all 165 species.
     *
     * ⚠️ **Clamped to [MAX_REDUCED_DENSITY], and that clamp is now a mechanism rather than a
     * guard.** It was a guard while five fluids had domes and all of them landed between 3.19 and
     * 3.82 against a wall at 3.95. Four of the twenty-two now reach it: ammonia at 4.40, sulfur
     * dioxide at 4.19, chlorine at 3.95 and sulfur at 10.50 — the last because [Species.Sulfur] is
     * atomic where its critical constants describe S8.
     *
     * What that costs is a solid that reads *less dense than it is*, so a tile packed with frozen
     * ammonia answers [FluidPhase.Separating] where it should answer [FluidPhase.Solid]. It costs
     * nothing at all to `scavengeFrost`, which asks the triple point rather than the density. The
     * real fix is a Péneloux volume shift, which moves densities without touching the pressure
     * curve and would pull all three back inside the wall.
     */
    val solidDensityR: Long = scaledRatio(
        species.solidKgPerCubicMetre.toLong() * TILE_LITRES * Budget.GRAM, massPerTile, SCALE,
    ).coerceAtMost(MAX_REDUCED_DENSITY)

    val pressure: Long =
        millimolesIn(massPerTile, species) * kelvin * PENG_ROBINSON_ZC_NUMERATOR /
            (PENG_ROBINSON_ZC_DENOMINATOR * REFERENCE_KELVIN)
}

/** Peng-Robinson's critical compressibility — a property of the equation, not of any substance. */
private const val PENG_ROBINSON_ZC = 0.3074013

/** The same as an exact rational, for [Critical.pressure], which may not touch floating point. */
private const val PENG_ROBINSON_ZC_NUMERATOR = 3_074_013L
private const val PENG_ROBINSON_ZC_DENOMINATOR = 10_000_000L

/** The molar gas constant, J/(mol·K). */
private const val GAS_CONSTANT = 8.314462618

/**
 * Millimoles of [species] in [mass] of it — the same conversion
 * [org.emerge.demo.outofspace.world.millimolesOf] performs, kept here so the critical point
 * can be expressed in the same currency as everything downstream of it.
 *
 * ⚠️ **This is the mass unit's only exit from the mass system**, here and in its twin. A mole is a
 * particle count: it does not move when [Budget.GRAM] moves, and everything built on it — the whole
 * pressure scale, [Critical.pressure], [MAX_REDUCED_PRESSURE], `Negligible.MILLIMOLES` — is
 * supposed to read the same number at every mass unit. So [Budget.GRAM] has to be divided *out*
 * here, and it is the one place in the game where that is true.
 *
 * Folded into the existing [MILLI] divide rather than applied as a separate step, so that at one
 * gram per unit the arithmetic is bit-for-bit what it has always been and no pressure anywhere
 * moves. Left as its own division and the truncation would land differently.
 */
private fun millimolesIn(mass: Long, species: Species): Long =
    mass * (MILLI * MILLI / species.molarMass) / (MILLI * Budget.GRAM)

private const val MILLI = 1000L

/**
 * The temperature the pressure scale is quoted against, matching
 * `org.emerge.demo.outofspace.world.Temperature.AMBIENT_KELVIN`. Restated rather than imported so
 * that `chem` does not have to depend on `world`, which depends on it.
 */
private const val REFERENCE_KELVIN = 293

/**
 * Reduced pressure `Pr` from reduced density [densityR] and reduced temperature [temperatureR],
 * all three in [SCALE] fixed point.
 *
 * The two terms are the two ideas, in order: `8·Tr·ρr/(3 − ρr)` is thermal push, diverging as the
 * cell fills up, and `3·ρr²` is the attraction pulling back. Below the critical temperature the
 * second wins over a band of densities in the middle, and the curve *falls* as the fluid is
 * compressed. That falling stretch is the phase transition — it is mechanically unstable, so a
 * fluid put there will not stay, and separates into the stable dense and sparse states on either
 * side of it. Liquid and vapour, arrived at without either word appearing.
 *
 * The return value can be negative, and that is meaningful rather than a fault: a liquid under
 * tension pulls inward.
 *
 * @throws IllegalArgumentException if [densityR] is at or past [CLOSE_PACKED], which has no
 *   pressure to report — see that constant.
 */
fun pengRobinsonPressure(densityR: Long, temperatureR: Long, species: Species): Long {
    require(densityR in 0 until CLOSE_PACKED) {
        "density must be inside the close-packing limit; got $densityR of $CLOSE_PACKED"
    }
    // Held a margin short of the wall. Being *inside* the domain is not the same as being
    // representable in it: at `densityR = CLOSE_PACKED - 1` the gap below is literally 1, the
    // thermal term reaches ~1e17, and multiplying that by a critical pressure in [partialPressure]
    // wrapped a Long for three species out of four. See NUMERIC_LIMITS.md §6.1.
    val rho = densityR.coerceAtMost(MAX_REDUCED_DENSITY)

    // ── Repulsion: `Tr·ρr / (Zc·(1 − b·ρr))` ────────────────────────────────
    //
    // Taken as the ratio `ρr / gap` first, so that no temperature, however absurd, can overflow the
    // numerator on its way to a quotient that was always going to be small. `gap` is the distance
    // left to the covolume wall in the same units the numerator is in, which is what makes the
    // whole term one `scaledRatio` rather than three multiplies looking for somewhere to divide.
    val gap = SCALE - COVOLUME * rho / SCALE
    val thermal = scaledRatio(rho, gap, INV_ZC * temperatureR / SCALE)

    // ── Attraction: `A·α(Tr)·ρr² / (1 + 2b·ρr − b²ρr²)` ─────────────────────
    //
    // The denominator is Peng-Robinson's whole departure from van der Waals — a quadratic in ρr
    // rather than the bare `1`, which is what lets one equation carry a realistic liquid branch and
    // a realistic vapour pressure at the same time. Written in terms of `bRho` so that `b²ρr²`
    // arrives as `(b·ρr)²` and is never a product of two [SCALE] constants looking for a divide.
    //
    // ⚠️ It cannot vanish inside the domain: its positive root is at `ρr = (1 + √2)/b = 9.54`, well
    // past [CLOSE_PACKED], so there is no second singularity to guard against.
    val bRho = COVOLUME * rho / SCALE
    val cohesion = SCALE + 2L * bRho - bRho * bRho / SCALE
    val alpha = alphaAt(temperatureR, species)
    val attraction = scaledRatio(ATTRACTION * alpha / SCALE * rho / SCALE * rho / SCALE, cohesion, SCALE)

    // The last guard, and the one that does not rest on an assumed maximum temperature: whatever
    // comes out, [partialPressure] is going to multiply it by a critical pressure. Clamping the
    // *result* rather than the thermal term alone is what keeps the curve monotonic — clamp the
    // numerator and the attraction term would go on growing underneath it, and a pressure that
    // FALLS as a fluid is compressed is the exact instability `reducedPressure` exists to remove.
    return (thermal - attraction).coerceAtMost(MAX_REDUCED_PRESSURE)
}

/**
 * The pressure a cell actually reports: [pengRobinsonPressure] outside the saturation dome, and the
 * flat coexistence pressure inside it.
 *
 * **This is the one the solver must use, and the difference is not cosmetic.** The raw equation's
 * falling stretch has `dP/dρ < 0`, which is an imaginary speed of sound and an instability no
 * timestep can outrun — see [saturationPressure] for why, and why replacing it with a flat line is
 * the equation's own prediction rather than a patch over it. Inside the dome the cell is holding
 * two phases at once, every density across the band coexists at the same pressure, and the slope
 * is zero.
 *
 * Outside the dome this is exactly [pengRobinsonPressure], so nothing that was never near
 * condensing sees any change from the flattening. `SaturationTest` pins that.
 */
fun reducedPressure(densityR: Long, temperatureR: Long, species: Species): Long {
    val raw = pengRobinsonPressure(densityR, temperatureR, species)
    val vapour = saturatedVapourDensity(temperatureR, species) ?: return raw
    val liquid = condensedDensity(temperatureR, species) ?: return raw
    return flattened(raw, densityR, vapour, liquid, saturationPressure(temperatureR, species)!!)
}

/**
 * [reducedPressure] for a caller holding a whole [kelvin] — the dome read off
 * [org.emerge.demo.outofspace.chem.DomeTable] rather than interpolated. [temperatureR] is still
 * wanted because [pengRobinsonPressure] is a curve in reduced temperature and is not tabled; only
 * the three saturation curves are.
 */
internal fun reducedPressureAt(densityR: Long, temperatureR: Long, kelvin: Int, species: Species): Long {
    val raw = pengRobinsonPressure(densityR, temperatureR, species)
    val vapour = saturatedVapourDensityAt(kelvin, species) ?: return raw
    val liquid = condensedDensityAt(kelvin, species) ?: return raw
    return flattened(raw, densityR, vapour, liquid, saturationPressureAt(kelvin, species)!!)
}

/**
 * The Maxwell flattening itself, given the dome — the arithmetic both readings above share, stated
 * once so there is one rule and not two.
 *
 * The clamps are what keep the seam continuous. Physically they say nothing new — a vapour below its
 * saturation density is below its saturation pressure, and a liquid above saturation density is
 * above it, both by definition of the dome. They are here because the three tables are interpolated
 * independently and so disagree slightly between knots, and that disagreement would otherwise show
 * up as a small pressure *step* at the edge of the dome, which is exactly the kind of discontinuity
 * an explicit solver turns into a standing oscillation.
 */
private fun flattened(raw: Long, densityR: Long, vapour: Long, liquid: Long, saturation: Long): Long =
    when {
        densityR <= vapour -> minOf(raw, saturation)
        densityR >= liquid -> maxOf(raw, saturation)
        else -> saturation
    }

/**
 * How the reduced pressure responds to being compressed a little further: `dPr/dρr`, in [SCALE].
 *
 * Its **sign is the whole phase story**, and since [reducedPressure] took on the Maxwell
 * construction there are only two signs left. Positive is an ordinary fluid on one of the stable
 * branches — push on it and it pushes back harder. **Zero** is the saturation dome: push on it and
 * it neither resists nor gives way, it converts, some vapour becoming liquid at unchanged
 * pressure.
 *
 * Negative is what this used to return across the whole dome, off the raw
 * [vanDerWaalsPressure] curve, and it is why a pool could not be simulated at all: a negative
 * slope is an imaginary speed of sound, so any patch slightly denser than its neighbours kept
 * getting denser, without bound and faster on a finer grid. Measured against [reducedPressure] it
 * can no longer happen below the critical temperature, which `SaturationTest` asserts directly
 * because it is the property the solver's stability rests on.
 *
 * Analytically this is the derivative of [pengRobinsonPressure], which is zero at the critical
 * point, negative below it and positive above — the transition appears and disappears at precisely
 * the right temperature without being told to.
 *
 * It is nonetheless *measured* here, as a finite difference across [step], rather than evaluated
 * from that formula. The analytic version overflows as `ρr` approaches [CLOSE_PACKED] and the
 * denominator collapses, and — more to the point — what the solver will actually have to stay
 * upright on is the integer curve [reducedPressure] really produces, rounding and all, not the
 * real-numbered one it approximates. So that is the curve this reports the slope of.
 */
fun reducedStiffness(
    densityR: Long,
    temperatureR: Long,
    species: Species,
    step: Long = SCALE / 1000L,
): Long {
    require(step > 0) { "step must be positive; got $step" }
    val low = (densityR - step).coerceAtLeast(0L)
    val high = (densityR + step).coerceAtMost(CLOSE_PACKED - 1)
    require(high > low) { "no room to measure a slope at $densityR" }
    return (reducedPressure(high, temperatureR, species) - reducedPressure(low, temperatureR, species)) *
        SCALE / (high - low)
}

/**
 * The critical points of the fluids the vessel carries.
 *
 * Measured properties of real substances, not choices — the critical temperatures are the
 * textbook ones and the densities are the textbook `kg/m³` put into the world's units by
 * [TILE_LITRES]. Nothing here is a knob, and in particular nothing here says which species is a
 * liquid: [Species.Water] and [Species.Nitrogen] differ only in how hot their critical point is,
 * and *that alone* is why one of them pools on the deck at room temperature and the other does
 * not.
 *
 * A species absent from this map has no phase behaviour and falls back to the ideal gas law, which
 * is the correct treatment for anything the vessel never gets near condensing.
 */
val CRITICAL: Map<Species, Critical> = mapOf(
    // ── The vessel's own atmosphere ───────────────────────────────────────────
    Species.Water to critical(647, 220.64, 0.3443, 273, Species.Water),
    Species.Nitrogen to critical(126, 33.958, 0.0372, 63, Species.Nitrogen),
    Species.Oxygen to critical(155, 50.43, 0.0222, 54, Species.Oxygen),
    Species.CarbonDioxide to critical(304, 73.773, 0.2276, 217, Species.CarbonDioxide),
    // Argon condenses at 151 K, between nitrogen and oxygen, which is where a noble gas belongs on
    // this curve: the law of corresponding states does not care that it is monatomic. Its acentric
    // factor is zero by definition — argon is the reference the whole correction is measured
    // against — so it is the row that shows the change here was never only about that constant.
    Species.Argon to critical(151, 48.63, 0.0000, 84, Species.Argon),

    // ── What a comet gives up, and what a roast puts in the air ───────────────
    //
    // ⛔ **These seven were absent, and absent here means "an ideal gas at every temperature".** On
    // a live save at 50 K they were 59% of the atmosphere between them and not one of them could
    // condense, freeze, stop diffusing or be picked up off the floor — because a fluid with no
    // critical point has no dome, and a fluid with no dome has no phase behaviour at all. Ammonia
    // was 28% of a room at forty-four kelvin and the model called it a gas.
    //
    // Every one of them lands within a kelvin of its measured boiling point except sulfur; see
    // `PhaseRealityTest`, which is what says so.
    Species.Ammonia to critical(405, 113.30, 0.2526, 195, Species.Ammonia),
    Species.Methane to critical(191, 45.99, 0.0115, 91, Species.Methane),
    Species.CarbonMonoxide to critical(133, 34.94, 0.0497, 68, Species.CarbonMonoxide),
    Species.HydrogenSulfide to critical(373, 89.63, 0.0942, 188, Species.HydrogenSulfide),
    Species.SulfurDioxide to critical(431, 78.84, 0.2454, 198, Species.SulfurDioxide),
    // ⚠️ **Hydrogen is a quantum fluid and a cubic equation of state has no business with it.** Its
    // acentric factor is *negative* — the only one here that is — which is the correlation reporting
    // that hydrogen does not behave like the substances it was fitted to. It comes out at 21 K
    // against a measured 20.3, which is better than it deserves, and the critical density lands at
    // 31.0 kg/m³ against a measured 31.3, which is luck. Treat a hydrogen liquid as indicative.
    Species.Hydrogen to critical(33, 13.13, -0.216, 14, Species.Hydrogen),
    // ⛔ **Sulfur is the one entry here that is genuinely approximate, and it is worth knowing why.**
    // Sulfur vapour is not sulfur atoms: near its boiling point it is mostly S8 rings, with S6 and
    // S2 as it gets hotter. [Species.Sulfur] is atomic — 32 g/mol — because that is what every
    // mineral formula in the game needs it to be, so the molar mass the reduction uses is a factor
    // of eight from the molecule the critical constants describe. The boiling point comes out ten
    // kelvin high and the critical density about a third of the measured 563 kg/m³. It is here
    // because a sulfur that condenses ten kelvin late is a great deal closer than a sulfur that is
    // an ideal gas at thirteen hundred kelvin, which is what it was.
    Species.Sulfur to critical(1314, 207.0, 0.207, 388, Species.Sulfur),

    // ── The noble gases and the halogens ──────────────────────────────────────
    //
    // Cheap to add and all of them land within a kelvin: these are the substances the law of
    // corresponding states was *fitted to*, and their acentric factors sit within a few hundredths
    // of zero. The halogens are stored diatomic ([Species.Fluorine] is 38 g/mol, which is F2), so
    // unlike sulfur their molar mass is the molecule the critical constants describe.
    Species.Neon to critical(44, 26.79, -0.0387, 25, Species.Neon),
    Species.Krypton to critical(209, 55.00, -0.002, 116, Species.Krypton),
    Species.Xenon to critical(290, 58.40, 0.002, 161, Species.Xenon),
    Species.Fluorine to critical(144, 51.72, 0.0530, 53, Species.Fluorine),
    Species.Chlorine to critical(417, 77.10, 0.0688, 172, Species.Chlorine),
    Species.Bromine to critical(584, 103.40, 0.1286, 266, Species.Bromine),
    Species.Iodine to critical(819, 117.00, 0.1115, 387, Species.Iodine),

    // ── The volatile metals, which are the ones to be careful about ───────────
    //
    // ⚠️ **Mercury's critical point is measured; zinc's and cadmium's are extrapolations.** A metal
    // critical point sits at thousands of kelvin and thousands of bar, and only mercury's is
    // experimentally reachable — the other two are model extrapolations carrying perhaps twenty per
    // cent. Their acentric factors are not tabulated at all, so they are derived here by the
    // **Edmister correlation from their measured normal boiling points**, which is a derivation from
    // a measurement rather than a number somebody liked.
    //
    // What that buys is worth the caveat. These three are in [Fluid] because a roasting bed loses
    // them as vapour; without a dome that vapour was an ideal gas for ever and could never come back
    // out of the air, so a roaster leaked zinc into the atmosphere permanently. `PhaseRealityTest`
    // pins each of them to its *boiling point*, which is measured, and that is the number the game
    // actually feels.
    Species.Mercury to critical(1750, 1720.0, -0.167, 234, Species.Mercury),
    Species.Zinc to critical(3170, 2900.0, -0.1216, 693, Species.Zinc),
    Species.Cadmium to critical(2570, 2000.0, -0.0400, 594, Species.Cadmium),
)

/**
 * [CRITICAL] by ordinal, because this is read inside the pressure sweep — the same reason [DOMES]
 * has a `DOME_OF` beside it, and the same fix.
 *
 * ⚠️ **A `Map` lookup is not free at this frequency.** `reducedTemperature` is two arithmetic
 * operations wrapped around one of these, and `tilePressure` reaches it six times per species per
 * tile: once the 128-bit divide came off the hot path, `HashMap.getNode` was **31% of every
 * execution sample in the game** and `reducedTemperature` 23% inclusive, for a function whose own
 * body is a multiply and a divide.
 *
 * The map stays because it is the readable statement of the table and what the tests iterate; this
 * is the same data reached the way a sweep wants to reach it.
 */
private val CRITICAL_OF: Array<Critical?> = arrayOfNulls<Critical>(Species.COUNT).also {
    for ((species, critical) in CRITICAL) it[species.ordinal] = critical
}

/** [CRITICAL] for [species], by ordinal. Prefer this to `criticalOf(species)` anywhere in a loop. */
internal fun criticalOf(species: Species): Critical? = CRITICAL_OF[species.ordinal]


// ⛔ **Helium is deliberately absent.** Its critical temperature is 5.2 K — colder than anything a
// vessel can reach, and colder than deep space — so it can never be inside its own dome and "ideal
// gas at every temperature" is the *correct* treatment rather than a missing row. Its acentric
// factor is −0.39, which would give the α function a negative κ and send attraction the wrong way
// with temperature; there is nothing to gain and a pathology to import.
//
// It is the only absentee. Every other fluid the vessel can hold has a dome.

/**
 * The largest reduced pressure [vanDerWaalsPressure] will report.
 *
 * Derived from the table above rather than picked, and that is the point: whatever the equation
 * returns, [partialPressure] multiplies it by some `c.pressure`, so the ceiling is set by the
 * *largest critical pressure any species has* (water, by four times) with a factor of four to
 * spare. Any species added later moves this number automatically instead of quietly eating the
 * margin.
 *
 * ⚠️ It is deliberately **not** derived from a maximum temperature. The obvious alternative — pick
 * a design maximum kelvin and size the margin for it — is precisely the mistake `NUMERIC_LIMITS.md`
 * §6.4 records: a budget derived from a design intention read green over a live overflow for
 * months, because the expression was also fed by inputs nobody had counted. A ceiling that depends
 * only on the consumer cannot be wrong about its inputs.
 *
 * The physical reading is honest enough: pressure at close packing is infinite, so *every* finite
 * representation of it is a clamp. This one states where it is.
 */
val MAX_REDUCED_PRESSURE: Long = Long.MAX_VALUE / 4 / CRITICAL.values.maxOf { it.pressure }

/**
 * How many litres of room a tile is, worked out from what the world already believes rather than
 * declared.
 *
 * [org.emerge.demo.outofspace.world.Stuff.AMBIENT_AIR] puts one kilogram of air in a full tile
 * and calls that one atmosphere at room temperature. A kilogram of air is about 34.5 moles, and
 * 34.5 moles at 293 K and one atmosphere occupies about 830 litres. So a tile is a cube a little
 * under a metre on a side — which nothing in the simulation had to know until a critical density
 * arrived quoted in `kg/m³` and needed somewhere to land.
 *
 * This is the single place SI units touch the vessel, and it exists only to convert the table
 * above. If the meaning of a tile ever changes, this is the one number that moves.
 */
const val TILE_LITRES: Long = 830

/**
 * `kg/m³ × litres` is mass, because a cubic metre is a thousand litres — so the textbook density
 * and [TILE_LITRES] give the answer in mass with no factor in between. [Budget.GRAM] is then what
 * turns those mass into integers, and it is the whole of this function's participation in the mass
 * unit. Before step 8's audit it was absent, and a tile of critical water weighed 267 milligrams.
 */
private fun critical(
    kelvin: Int,
    bar: Double,
    acentric: Double,
    triplePointKelvin: Int,
    species: Species,
): Critical = Critical(kelvin, bar, acentric, triplePointKelvin, species)

/**
 * What a cell's fluid is *behaving* as. Read, never stored.
 *
 * This is deliberately not a field on [Species] and deliberately not written down anywhere: it is
 * computed from density and temperature every time it is asked for, the same way a tile's kelvin is
 * computed from its energy and its capacity rather than kept alongside them. A species does not
 * have a phase; a cell does, and only for as long as its conditions hold.
 */
enum class FluidPhase {
    /** Sparse and stable — the dilute branch. Ordinary air is here, and so is steam. */
    Vapour,

    /** Dense and stable — the cohering branch. Holds its own density against being spread out. */
    Liquid,

    /**
     * Inside the falling stretch of the isotherm, where compressing the fluid *lowers* its
     * pressure. Mechanically unstable: a cell here is mid-transition and will not stay, but is
     * separating into the two branches either side. Boiling and condensing are both passages
     * through this state, which is exactly why it needs a name and cannot be an error.
     */
    Separating,

    /**
     * Hotter than the critical point, where there is no liquid branch to be on and the distinction
     * has no meaning. Nitrogen in a warm room is here — not "a gas" by declaration, but by being
     * more than twice its own critical temperature.
     */
    Supercritical,

    /**
     * Colder than the triple point and dense enough to be condensed: frost, ice, dry ice.
     *
     * A separate name from [Liquid] because the difference is not descriptive — a solid does not
     * flow, and [org.emerge.demo.outofspace.world.diffuseFluid] has to be able to tell. The
     * boundary is [Critical.triplePointKelvin], which is a measured property and the temperature
     * below which a substance has no liquid phase **at any pressure at all**.
     */
    Solid,
}

/**
 * Which branch a cell's fluid sits on, from reduced density and temperature alone.
 *
 * Above the critical temperature there is nothing to decide. Below it, the saturation dome does the
 * deciding: sparser than [saturatedVapourDensity] is vapour, denser than [condensedDensity]
 * is liquid, and between them the cell is holding both at once.
 *
 * This reads the dome rather than the sign of [reducedStiffness], which is both cheaper — two
 * table lookups against two pressure evaluations — and more correct. The stiffness test finds the
 * *spinodal*, where a fluid tears itself apart with no provocation; the dome is the *binodal*,
 * where it separates given anywhere to start. Between the two lies the metastable region, which
 * the old test called stable and which really is not: it is superheated liquid and supercooled
 * vapour, both of which do separate in a cell that has a wall or a neighbour to nucleate against,
 * and every cell here has six.
 */
fun phaseAt(densityR: Long, temperatureR: Long, species: Species): FluidPhase = when {
    temperatureR >= SCALE -> FluidPhase.Supercritical
    // Inclusive at both edges, to agree with [condensedFraction], which reads a cell at exactly the
    // saturated liquid density as wholly liquid and one at exactly saturated vapour as wholly
    // vapour. A pool sitting in equilibrium lands precisely on that boundary, so the two functions
    // disagreeing about it is not an edge case but the ordinary situation.
    densityR <= saturatedVapourDensity(temperatureR, species)!! -> FluidPhase.Vapour
    densityR >= condensedDensity(temperatureR, species)!! ->
        if (temperatureR < (criticalOf(species)?.triplePointR ?: 0L)) FluidPhase.Solid else FluidPhase.Liquid
    else -> FluidPhase.Separating
}

/**
 * Reduced density of [mass] of [species] in a cell holding [volume] out of
 * [org.emerge.demo.outofspace.world.VolumeField.FULL] — how packed it is, as a multiple of
 * its own critical density.
 *
 * Returns null for a species with no entry in [CRITICAL], which is the caller's signal to treat it
 * as an ideal gas.
 */
fun reducedDensity(mass: Long, species: Species, volume: Int, full: Int): Long? {
    val c = criticalOf(species) ?: return null
    if (mass <= 0L) return 0L
    // `mass / c.massPerTile` is a ratio of two masses, so the mass unit cancels out of it and
    // what comes back — a multiple of critical density — does not depend on what a unit means.
    // Taking that ratio first is what makes this scale-invariant; written as `mass * SCALE` it was
    // linear in the mass unit and the last non-ledger row still red at 10⁶ (safe k 69,100). The
    // division against critical density already came first for the same reason at a smaller scale;
    // this only finishes the thought. See [scaledRatio] and step 4b of PLAN_unit_rescale.md.
    return scaledRatio(mass, c.massPerTile, SCALE) * full / volume
}

/**
 * The inverse of [reducedDensity]: how much mass of [species] a cell of [volume] out of [full]
 * holds when it sits at reduced density [densityR].
 *
 * ### Why this is a function and not four expressions
 *
 * It was four expressions, and every one of them was `densityR * c.massPerTile / SCALE` — the same
 * multiply, arrived at independently in `Edit.WATER_INJECT_MASS`, `vapourMass`,
 * `closePackedAirMass` and a `PhaseEmergenceTest` helper. That product is a reduced fraction (up to
 * 1e8) times a mass, so it is linear in the mass unit and reaches 3.9e19 for critical carbon dioxide
 * at one microgram per unit. Four sites, one defect, and step 8's audit found them one at a time
 * over two passes — the test copy last, by way of an `ArrayIndexOutOfBoundsException` in `ValveTest`
 * that had nothing visibly to do with density.
 *
 * So the arithmetic lives here, [scaledRatio] takes the mass unit out of the fraction, and the tests
 * call the same function the simulation does. **A test that recomputes what it is testing against
 * inherits its bugs**, and this one inherited a wrap that turned a mass negative and sent a negative
 * temperature into `Saturation.sample`.
 *
 * Returns null for a species with no critical point on file, matching [reducedDensity].
 */
fun massAtReducedDensity(densityR: Long, species: Species, volume: Int, full: Int): Long? {
    val c = criticalOf(species) ?: return null
    if (densityR <= 0L) return 0L
    return scaledRatio(densityR, SCALE, c.massPerTile) * volume / full
}

/** Reduced temperature — [kelvin] as a multiple of the species' critical temperature. */
fun reducedTemperature(kelvin: Int, species: Species): Long? {
    val c = criticalOf(species) ?: return null
    return kelvin.toLong() * SCALE / c.kelvin
}

/**
 * The pressure [mass] of [species] contributes on its own, in the units
 * [org.emerge.demo.outofspace.world.tilePressure] reports.
 *
 * Partial pressures, which is what makes the nitrogen-holds-the-water-down case work without
 * anybody arranging it: the solver sums these across species exactly as
 * [org.emerge.demo.outofspace.world.millimolesOf] already sums moles, and a water cell
 * therefore has to push against everything else in the room to expand, not just against itself.
 *
 * Returns null for a species with no critical point on file — an ideal gas, whose pressure the
 * caller should compute the old way.
 */
fun partialPressure(mass: Long, species: Species, kelvin: Int, volume: Int, full: Int): Long? {
    val c = criticalOf(species) ?: return null
    // Clamped, so that adding mass to a cell can never *throw*. [reducedPressure] keeps its strict
    // domain — past close packing there is genuinely no pressure to report — but that contract
    // belongs between the equation and its own internals, not between the equation and a caller
    // holding mass. Before step 5 the only thing standing between "pour water into a tile" and an
    // exception mid-tick was `leastRoomFor` computing exactly the right volume at every call site,
    // which is the coupling NUMERIC_LIMITS.md §6.1 is really about. Now it is belt and braces.
    val densityR = (reducedDensity(mass, species, volume, full) ?: return null)
        .coerceAtMost(MAX_REDUCED_DENSITY)
    val temperatureR = reducedTemperature(kelvin, species) ?: return null
    return c.pressure * reducedPressureAt(densityR, temperatureR, kelvin, species) / SCALE
}

/**
 * The energy a cell's fluid holds by virtue of its molecules attracting each other, in the
 * millijoules the rest of the thermal bookkeeping uses. Always negative or zero: a cohering fluid
 * is in a bound state, and pulling it apart costs energy.
 *
 * **This is the latent heat**, and it is not a separate mechanism — it is the same attraction term
 * from [pengRobinsonPressure], multiplied by the cell's volume. When a cell boils, its
 * density falls, this number rises toward zero, and the energy to pay for that has to come out of
 * the thermal pot, which is why a boiling liquid cools itself and why condensation gives the heat
 * back. Nothing anywhere states a latent heat of vaporisation; it is a consequence of the same two
 * constants that produced the phase transition in the first place.
 *
 * ⚠️ **Inside the saturation dome this is not yet right, and knowingly so.** A cell there is not
 * uniform — it is [condensedFraction] of its volume at [condensedDensity] and the rest at
 * [saturatedVapourDensity] — and because the term is quadratic, the attraction of that mixture is
 * not the attraction of its mean density. The lever-rule version is what makes latent heat come out
 * *linear in the fraction boiled*, i.e. a constant energy-per-gram, which is what a latent heat is.
 * Applying it needs a temperature, which neither this nor [org.emerge.demo.outofspace.world.cohesionField]
 * currently takes, so it is left for whoever turns latent heat on — it belongs with teaching the
 * ledger its third term, not before, since nothing consumes the difference until then.
 */
fun cohesionEnergy(densityR: Long, temperatureR: Long, species: Species, volume: Int, full: Int): Long {
    val c = criticalOf(species) ?: return 0L
    // The same attraction term [pengRobinsonPressure] subtracts, which is the whole point: a latent
    // heat is not a separate quantity, it is what that term does as density falls.
    val bRho = COVOLUME * densityR / SCALE
    val cohesion = SCALE + 2L * bRho - bRho * bRho / SCALE
    val alpha = alphaAt(temperatureR, species)
    val attraction = scaledRatio(
        ATTRACTION * alpha / SCALE * densityR / SCALE * densityR / SCALE, cohesion, SCALE,
    )
    return -(c.pressure * attraction / SCALE) * volume / full * Budget.JOULE
}

// The pressure field is millimoles scaled by temperature, so a pressure times a volume is already an
// energy in energy up to the reference temperature — hence [Budget.JOULE] above, which was a bare
// 1000 while the energy unit happened to be the millijoule.

