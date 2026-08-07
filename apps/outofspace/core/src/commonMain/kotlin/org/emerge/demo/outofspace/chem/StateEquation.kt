package org.emerge.demo.outofspace.chem

/**
 * The equation of state: what pressure a fluid pushes back with, given how much of it is packed
 * into a cell and how hot it is.
 *
 * The atmosphere solver has always used the ideal gas law — `P = nRT/V`, which is
 * [org.emerge.demo.outofspace.world.fluid.tilePressure]. An ideal gas has no liquid phase and
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
 * quantised out of existence — at a million, thirteen grams of carbon dioxide in a tile of air came
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
 * infinity — `ρr = 3`, three times critical density, the point where the cell is all molecule and
 * no gap. Van der Waals is undefined at and beyond it, so callers are held below it.
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
const val CLOSE_PACKED: Long = 3 * SCALE

/**
 * Where a species stops being able to tell liquid from gas.
 *
 * Two measured numbers, both published constants of the substance rather than tuning knobs: the
 * temperature at the critical point and the density there. Above [kelvin] there is no liquid phase
 * at any pressure at all — the distinction genuinely ceases to exist — and that fact arrives as a
 * consequence of the reduced equation rather than as a branch anybody wrote.
 *
 * ### Why the critical *pressure* is derived and not given
 *
 * It is tempting to supply all three, since all three are tabulated. They cannot all be honoured.
 * Van der Waals fixes the ratio `Pc·vc/(R·Tc)` at exactly `3/8` — that is forced by the shape of
 * the equation, not chosen — whereas real fluids measure around `0.29`. The three constants are
 * therefore over-determined: pick any two and the third follows.
 *
 * Taking [kelvin] and [gramsPerTile] as the inputs is the choice that matters, because those two
 * are what place the phase transition in the state space the solver actually moves through — how
 * hot a cell has to get, and how dense it has to be. [pressure] is then whatever the equation says
 * it is. Supplying a measured critical pressure alongside them would not make the model more
 * accurate; it would make it inconsistent, and the inconsistency would surface as a fluid whose
 * boiling curve quietly disagrees with its own density.
 *
 * The cost is that critical pressures come out roughly 30% high. That is the well-known error of
 * van der Waals and the honest price of a two-constant equation of state.
 *
 * @param kelvin critical temperature, K.
 * @param gramsPerTile critical density, expressed as the grams of this species that a full tile
 *   holds when it is exactly at critical density. Tile-relative for the same reason
 *   [org.emerge.demo.outofspace.world.fluid.VolumeField] is: the solver never asks how big a metre
 *   is, and this keeps it from having to start.
 */
class Critical(val kelvin: Int, val gramsPerTile: Long, private val species: Species) {

    /**
     * Critical pressure, in the units [org.emerge.demo.outofspace.world.fluid.tilePressure]
     * reports — the conversion that carries a reduced pressure back into the solver's scale.
     *
     * `Pc = (3/8)·n_c·Tc / T_ambient`, which is the `3/8` ratio above rearranged, with the
     * millimoles-scaled-by-temperature units the existing pressure field already speaks. The
     * consequence worth knowing: at ordinary atmospheric density this reproduces the ideal gas law
     * the solver used before to within a fraction of a percent, because that is what van der Waals
     * *does* when the molecules are far apart. The old behaviour is the sparse limit of the new one.
     */
    val pressure: Long = 3 * millimolesIn(gramsPerTile, species) * kelvin / (8 * REFERENCE_KELVIN)
}

/**
 * Millimoles of [species] in [grams] of it — the same conversion
 * [org.emerge.demo.outofspace.world.fluid.millimolesOf] performs, kept here so the critical point
 * can be expressed in the same currency as everything downstream of it.
 */
private fun millimolesIn(grams: Long, species: Species): Long = grams * (MILLI * MILLI / species.molarMass) / MILLI

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
fun vanDerWaalsPressure(densityR: Long, temperatureR: Long): Long {
    require(densityR in 0 until CLOSE_PACKED) {
        "density must be inside the close-packing limit; got $densityR of $CLOSE_PACKED"
    }
    // Numerator carries SCALE², denominator SCALE, so the quotient lands back on SCALE. Both
    // multiplications are done before either division to keep a dense cell from rounding its way
    // onto a different branch of the curve than the one it is really on.
    val thermal = 8L * temperatureR * densityR / (CLOSE_PACKED - densityR)
    val attraction = 3L * densityR * densityR / SCALE
    return thermal - attraction
}

/**
 * The pressure a cell actually reports: [vanDerWaalsPressure] outside the saturation dome, and the
 * flat coexistence pressure inside it.
 *
 * **This is the one the solver must use, and the difference is not cosmetic.** The raw equation's
 * falling stretch has `dP/dρ < 0`, which is an imaginary speed of sound and an instability no
 * timestep can outrun — see [saturationPressure] for why, and why replacing it with a flat line is
 * the equation's own prediction rather than a patch over it. Inside the dome the cell is holding
 * two phases at once, every density across the band coexists at the same pressure, and the slope
 * is zero.
 *
 * Outside the dome this is exactly [vanDerWaalsPressure], so nothing that was never near
 * condensing sees any change at all — which is what let this land without moving a single existing
 * pressure in the game. `SaturationTest` pins that.
 */
fun reducedPressure(densityR: Long, temperatureR: Long): Long {
    val raw = vanDerWaalsPressure(densityR, temperatureR)
    val vapour = saturatedVapourDensity(temperatureR) ?: return raw
    val liquid = saturatedLiquidDensity(temperatureR) ?: return raw
    val saturation = saturationPressure(temperatureR)!!
    // The clamps are what keep the seam continuous. Physically they say nothing new — a vapour
    // below its saturation density is below its saturation pressure, and a liquid above saturation
    // density is above it, both by definition of the dome. They are here because the three tables
    // are interpolated independently and so disagree slightly between knots, and that disagreement
    // would otherwise show up as a small pressure *step* at the edge of the dome, which is exactly
    // the kind of discontinuity an explicit solver turns into a standing oscillation.
    return when {
        densityR <= vapour -> minOf(raw, saturation)
        densityR >= liquid -> maxOf(raw, saturation)
        else -> saturation
    }
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
 * Analytically this is `24·Tr/(3 − ρr)² − 6·ρr`, which at critical density comes out as exactly
 * `6·(Tr − 1)` — zero at the critical point, negative below it, positive above. The transition
 * appears and disappears at precisely the right temperature without being told to.
 *
 * It is nonetheless *measured* here, as a finite difference across [step], rather than evaluated
 * from that formula. The analytic version overflows as `ρr` approaches [CLOSE_PACKED] and the
 * denominator collapses, and — more to the point — what the solver will actually have to stay
 * upright on is the integer curve [reducedPressure] really produces, rounding and all, not the
 * real-numbered one it approximates. So that is the curve this reports the slope of.
 */
fun reducedStiffness(densityR: Long, temperatureR: Long, step: Long = SCALE / 1000L): Long {
    require(step > 0) { "step must be positive; got $step" }
    val low = (densityR - step).coerceAtLeast(0L)
    val high = (densityR + step).coerceAtMost(CLOSE_PACKED - 1)
    require(high > low) { "no room to measure a slope at $densityR" }
    return (reducedPressure(high, temperatureR) - reducedPressure(low, temperatureR)) * SCALE / (high - low)
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
    Species.Water to critical(kelvin = 647, kgPerCubicMetre = 322, species = Species.Water),
    Species.Nitrogen to critical(kelvin = 126, kgPerCubicMetre = 313, species = Species.Nitrogen),
    Species.Oxygen to critical(kelvin = 155, kgPerCubicMetre = 436, species = Species.Oxygen),
    Species.CarbonDioxide to critical(kelvin = 304, kgPerCubicMetre = 468, species = Species.CarbonDioxide),
)

/**
 * How many litres of room a tile is, worked out from what the world already believes rather than
 * declared.
 *
 * [org.emerge.demo.outofspace.world.AirField.AMBIENT_AIR] puts one kilogram of air in a full tile
 * and calls that one atmosphere at room temperature. A kilogram of air is about 34.5 moles, and
 * 34.5 moles at 293 K and one atmosphere occupies about 830 litres. So a tile is a cube a little
 * under a metre on a side — which nothing in the simulation had to know until a critical density
 * arrived quoted in `kg/m³` and needed somewhere to land.
 *
 * This is the single place SI units touch the vessel, and it exists only to convert the table
 * above. If the meaning of a tile ever changes, this is the one number that moves.
 */
const val TILE_LITRES: Long = 830

private fun critical(kelvin: Int, kgPerCubicMetre: Int, species: Species): Critical =
    Critical(kelvin = kelvin, gramsPerTile = kgPerCubicMetre * TILE_LITRES, species = species)

/**
 * What a cell's fluid is *behaving* as. Read, never stored.
 *
 * This is deliberately not a field on [Species] and deliberately not written down anywhere: it is
 * computed from density and temperature every time it is asked for, the same way a tile's kelvin is
 * computed from its joules and its capacity rather than kept alongside them. A species does not
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
}

/**
 * Which branch a cell's fluid sits on, from reduced density and temperature alone.
 *
 * Above the critical temperature there is nothing to decide. Below it, the saturation dome does the
 * deciding: sparser than [saturatedVapourDensity] is vapour, denser than [saturatedLiquidDensity]
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
fun phaseAt(densityR: Long, temperatureR: Long): FluidPhase = when {
    temperatureR >= SCALE -> FluidPhase.Supercritical
    // Inclusive at both edges, to agree with [liquidFraction], which reads a cell at exactly the
    // saturated liquid density as wholly liquid and one at exactly saturated vapour as wholly
    // vapour. A pool sitting in equilibrium lands precisely on that boundary, so the two functions
    // disagreeing about it is not an edge case but the ordinary situation.
    densityR <= saturatedVapourDensity(temperatureR)!! -> FluidPhase.Vapour
    densityR >= saturatedLiquidDensity(temperatureR)!! -> FluidPhase.Liquid
    else -> FluidPhase.Separating
}

/**
 * Reduced density of [grams] of [species] in a cell holding [volume] out of
 * [org.emerge.demo.outofspace.world.fluid.VolumeField.FULL] — how packed it is, as a multiple of
 * its own critical density.
 *
 * Returns null for a species with no entry in [CRITICAL], which is the caller's signal to treat it
 * as an ideal gas.
 */
fun reducedDensity(grams: Long, species: Species, volume: Int, full: Int): Long? {
    val c = CRITICAL[species] ?: return null
    if (grams <= 0L) return 0L
    // Divided against the critical density before being scaled by the cell's fullness, so that a
    // large mass in a small cell cannot run the intermediate past a Long on its way to a number
    // that was always going to be less than CLOSE_PACKED.
    return grams * SCALE / c.gramsPerTile * full / volume
}

/** Reduced temperature — [kelvin] as a multiple of the species' critical temperature. */
fun reducedTemperature(kelvin: Int, species: Species): Long? {
    val c = CRITICAL[species] ?: return null
    return kelvin.toLong() * SCALE / c.kelvin
}

/**
 * The pressure [grams] of [species] contributes on its own, in the units
 * [org.emerge.demo.outofspace.world.fluid.tilePressure] reports.
 *
 * Partial pressures, which is what makes the nitrogen-holds-the-water-down case work without
 * anybody arranging it: the solver sums these across species exactly as
 * [org.emerge.demo.outofspace.world.fluid.millimolesOf] already sums moles, and a water cell
 * therefore has to push against everything else in the room to expand, not just against itself.
 *
 * Returns null for a species with no critical point on file — an ideal gas, whose pressure the
 * caller should compute the old way.
 */
fun partialPressure(grams: Long, species: Species, kelvin: Int, volume: Int, full: Int): Long? {
    val c = CRITICAL[species] ?: return null
    val densityR = reducedDensity(grams, species, volume, full) ?: return null
    val temperatureR = reducedTemperature(kelvin, species) ?: return null
    return c.pressure * reducedPressure(densityR, temperatureR) / SCALE
}

/**
 * The energy a cell's fluid holds by virtue of its molecules attracting each other, in the
 * millijoules the rest of the thermal bookkeeping uses. Always negative or zero: a cohering fluid
 * is in a bound state, and pulling it apart costs energy.
 *
 * **This is the latent heat**, and it is not a separate mechanism — it is the same `3·ρr²`
 * attraction term from [reducedPressure], multiplied by the cell's volume. When a cell boils, its
 * density falls, this number rises toward zero, and the energy to pay for that has to come out of
 * the thermal pot, which is why a boiling liquid cools itself and why condensation gives the heat
 * back. Nothing anywhere states a latent heat of vaporisation; it is a consequence of the same two
 * constants that produced the phase transition in the first place.
 *
 * ⚠️ **Inside the saturation dome this is not yet right, and knowingly so.** A cell there is not
 * uniform — it is [liquidFraction] of its volume at [saturatedLiquidDensity] and the rest at
 * [saturatedVapourDensity] — and because the term is quadratic, the attraction of that mixture is
 * not the attraction of its mean density. The lever-rule version is what makes latent heat come out
 * *linear in the fraction boiled*, i.e. a constant joules-per-gram, which is what a latent heat is.
 * Applying it needs a temperature, which neither this nor [org.emerge.demo.outofspace.world.fluid.cohesionField]
 * currently takes, so it is left for whoever turns latent heat on — it belongs with teaching the
 * ledger its third term, not before, since nothing consumes the difference until then.
 */
fun cohesionJoules(densityR: Long, species: Species, volume: Int, full: Int): Long {
    val c = CRITICAL[species] ?: return 0L
    val attraction = 3L * densityR * densityR / SCALE
    return -(c.pressure * attraction / SCALE) * volume / full * CAPACITY_SCALE
}

/**
 * Millijoules per unit of the pressure scale. The pressure field is millimoles scaled by
 * temperature, so a pressure times a volume is already an energy up to this factor, which is the
 * same thousand that [Species.specificHeat] carries.
 */
private const val CAPACITY_SCALE = 1000L
