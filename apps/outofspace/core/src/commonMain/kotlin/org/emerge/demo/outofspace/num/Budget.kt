package org.emerge.demo.outofspace.num

/**
 * **What one integer means.** The single place the simulation states its units.
 *
 * Every mass- and energy-dimensioned constant in the game derives from here. That is the whole
 * point: `PLAN_unit_rescale.md` moves the mass unit from one gram to one microgram, and a rescale is
 * only tractable if there is *one* number to change rather than thirty literals scattered across
 * twenty files, each of which happens to be right for a reason nobody wrote down.
 *
 * ### The rule
 *
 * A constant that means a quantity of stuff is written as a multiple of [GRAM], [KILOGRAM] or
 * [TONNE] — never as a bare literal. If it can instead be expressed as a **fraction of something
 * physical**, that is better still, and it should be: `Negligible` is a per-mille of ambient
 * pressure, `Material.RADIANCE` is a fraction of a hull plate's capacity, `Edit.WATER_INJECT_GRAMS`
 * is a 64th of what a tile of saturated liquid weighs. Those three need no rescaling at all, because
 * they are ratios and a ratio has no unit. Everything derived that way is one less thing that can
 * silently go wrong at step 8.
 *
 * ### Why this lives in `num` and not in `world`
 *
 * It was in `world` until step 8's audit of `chem`, and being there is what let that audit's misses
 * happen. `chem` deliberately does not depend on `world` — `StateEquation` restates
 * `Temperature.AMBIENT_KELVIN` rather than import it — so a mass constant in `chem` **could not**
 * have been written against [GRAM] even if somebody had thought to. `Critical.gramsPerTile` was
 * therefore a bare literal not because it was overlooked but because the layering forbade the
 * alternative. A statement of what one integer means has to sit below everything that counts in
 * integers, which is both packages.
 *
 * ### What is deliberately NOT here
 *
 * Dimensionless things, which a rescale must not touch:
 * - `VolumeField.FULL` and `ApertureField.OPEN` (1024) — fractions of a tile.
 * - `SignalField.FULL` (1000) — a percentage.
 * - `Material.fillPermille` and every `Mixture` composition — parts per thousand.
 * - `Plumbing.MILLIMOLES_PER_TICK` — **molar**, a particle count. Moles are not grams and do not
 *   move with [GRAM]. Worth stating because it is the one quantity in the game that looks
 *   mass-dimensioned in the source and is not.
 * - `Frac` — belongs to the engine, and bounds accelerations rather than masses.
 *
 * ### The relation between mass and energy WAS not free, and now it is
 *
 * Specific heat is quoted per **kilogram**, so a heat capacity is `mass × specificHeat × Kₑ/(1000·Kₘ)`.
 * That factor used to be pinned at exactly **1** by holding the energy unit equal to the mass unit,
 * which is why `gasCapacityAt` could read `grams * specificHeat` with no conversion constant.
 *
 * ⚠️ **That lock is what stopped the rescale at step 8, and it is gone.** Locked together, a
 * millionfold finer gram made a millionfold finer joule — one integer of energy became a
 * *nanojoule* — and a rock's thermal energy stopped fitting in a `Long`. Measured: every mass row in
 * `NumericLimitsTest` cleared 10⁶ with room to spare, and every row that failed was an energy row.
 * The two dimensions want different units and pretending otherwise cost the whole target.
 *
 * So there are now **two knobs**, and the factor between them is [CAPACITY_DIVISOR] — one named
 * constant, in one place, exactly as the single lock was. Its being 1 today is what makes this
 * change bit-for-bit invisible at the current units.
 */
object Budget {

    /**
     * **The mass knob.** One integer of mass, in micrograms.
     *
     * **`1` — one microgram per integer, set at step 8 on 2026-08-12.** It was `1_000_000`, one gram
     * per integer, for the whole of the game's life before that.
     *
     * The six orders bought at the bottom of the range are what turn "negligible" into something
     * genuinely negligible. Diffusion cannot move fewer than `SLOTS` integers of a species, so the
     * stranding floor is five *integers* no matter what an integer means; at a gram it was five grams,
     * which is the same size as the floor below which the readouts stop drawing anything at all. The
     * two coincided, and `Negligible` was therefore hiding a quantisation artefact rather than
     * describing a physical threshold — see `NUMERIC_LIMITS.md` §6.2 and
     * `NumericLimitsTest :: the stranding floor falls away from the floor a player can see`. At a
     * microgram the stranding floor is a millionfold below anything a player can see.
     *
     * Stated as micrograms-per-unit rather than as a multiplier so that it only ever goes *down*, and
     * so the unit is a real physical one rather than an anonymous factor.
     */
    const val MICROGRAMS_PER_UNIT: Long = 1L

    /**
     * **The energy knob.** One integer of energy, in nanojoules.
     *
     * **`10_000_000` — one centijoule per integer, set at step 8 on 2026-08-12.** It was `1_000_000`,
     * a millijoule, and that is where the whole game's thermal behaviour had always lived. This is
     * ten times *coarser*, the one move in this plan that goes that way.
     *
     * Coarser deserves its own justification, since every other move in this plan is toward finer.
     * The floor the rescale exists for is a **mass** floor — diffusion stranding, `Negligible`, a
     * trace of gas in a tile. Nothing wants a finer joule; the millijoule has never been the limiting
     * quantity in anything. What energy needs is *range*, and it is short of it: with bodies storing
     * their joules per tile, a rock tile supports a mass scale of 966,000 against a target of 10⁶.
     * Ten times coarser turns that into 9.66e6 and takes `atmosphere joules` out of the red with it.
     *
     * ⚠️ Coarser was **margin, not the fix**. The wall it was chosen to clear was measured with a
     * `NumericLimitsTest` that extrapolated every row by the *mass* scale — correct only while the
     * two units were locked, which is precisely what this change undoes. An energy quantity is a
     * number of joules divided by the energy unit, so its integer count does not depend on the mass
     * unit at all, and simply decoupling the two knobs cleared every energy row on its own. The
     * centijoule is kept because nothing wants a finer joule and the headroom is worth having, but
     * it was not required, and the table that said otherwise was wrong.
     *
     * ⚠️ Must stay a whole multiple of [MICROGRAMS_PER_UNIT], since [CAPACITY_DIVISOR] divides them.
     */
    const val NANOJOULES_PER_UNIT: Long = 10_000_000L

    /** One gram, in whatever the current unit is. The base every mass constant is written against. */
    const val GRAM: Long = 1_000_000L / MICROGRAMS_PER_UNIT

    /** A thousand grams — a tile of air at one atmosphere, near enough. */
    const val KILOGRAM: Long = 1_000L * GRAM

    /** A thousand kilograms. The natural unit for solids: a tile of steel is six and a half of them. */
    const val TONNE: Long = 1_000L * KILOGRAM

    /**
     * One joule, in whatever the current energy unit is. The base every energy constant is written
     * against.
     *
     * The joule rather than the millijoule, because the millijoule stops being representable the
     * moment the energy unit is coarser than one — which step 8 makes it. An energy constant that
     * cannot be stated in whole joules is below the resolution of the field it would be stored in,
     * so this is the right place for the floor to sit.
     */
    const val JOULE: Long = 1_000_000_000L / NANOJOULES_PER_UNIT

    /**
     * What a `mass × specificHeat` product must be divided by to become a heat capacity.
     *
     * The one number that carries the relation between the two knobs, and the only thing standing
     * where the old `MILLIJOULE == GRAM` lock used to. Specific heat is J/kg/K, so
     * `capacity = mass_units × c × u_mass / (1000 × u_energy)`, and with the units expressed in
     * micrograms and nanojoules the 1000s and the 1e-6s cancel to exactly this ratio.
     *
     * **It is 1 today**, which is why `grams * specificHeat` has always read correctly with no
     * conversion constant anywhere, and why introducing it changes nothing until a knob moves.
     */
    const val CAPACITY_DIVISOR: Long = NANOJOULES_PER_UNIT / MICROGRAMS_PER_UNIT
}
