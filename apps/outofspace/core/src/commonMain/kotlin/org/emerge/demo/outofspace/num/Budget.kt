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
 * ### The relation between mass and energy is not free
 *
 * Specific heat is quoted per **kilogram**, so a heat capacity is `mass × specificHeat × Kₑ/(1000·Kₘ)`.
 * Holding [ENERGY_PER_MASS] at 1000 makes that factor exactly **1**, which is why `gasCapacityAt`
 * can read `grams * specificHeat` today with no conversion constant anywhere. Break the relation and
 * a lossy divisor has to be carried through every capacity in the game. See the plan's §3.
 */
object Budget {

    /**
     * **The knob.** One integer of mass, in micrograms.
     *
     * `1_000_000` is one gram per integer — today's unit, and the value this sits at while steps 2
     * through 7 prepare the ground. Step 8 lowers it to `1`, making one integer one microgram and
     * buying six orders of magnitude at the bottom of the range, which is what turns "negligible"
     * into something genuinely negligible rather than half a percent of a tile.
     *
     * Stated as micrograms-per-unit rather than as a multiplier so that it only ever goes *down*,
     * and so the target is a real physical unit rather than an anonymous factor.
     */
    const val MICROGRAMS_PER_UNIT: Long = 1_000_000L

    /** One gram, in whatever the current unit is. The base every mass constant is written against. */
    const val GRAM: Long = 1_000_000L / MICROGRAMS_PER_UNIT

    /** A thousand grams — a tile of air at one atmosphere, near enough. */
    const val KILOGRAM: Long = 1_000L * GRAM

    /** A thousand kilograms. The natural unit for solids: a tile of steel is six and a half of them. */
    const val TONNE: Long = 1_000L * KILOGRAM

    /**
     * How many energy units there are per mass unit, and it is **not** a free choice.
     *
     * Forced to 1000 by specific heat being quoted per kilogram — see the class note. Energy is
     * carried in millijoules today, so this being 1000 is exactly the statement that a millijoule
     * per gram is the pairing the capacity expressions already assume.
     */
    const val ENERGY_PER_MASS: Long = 1_000L

    /** One millijoule, the unit every `joules` field and every capacity is actually counted in. */
    const val MILLIJOULE: Long = GRAM * ENERGY_PER_MASS / 1_000L

    /** A thousand millijoules. */
    const val JOULE: Long = 1_000L * MILLIJOULE
}
