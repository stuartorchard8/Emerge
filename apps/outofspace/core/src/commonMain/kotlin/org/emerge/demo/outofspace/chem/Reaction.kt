package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio

/**
 * Matter reacting because of the conditions it is in, rather than because a machine did something
 * to it — increment 1 of `PLAN_ambient_chemistry.md`, and the first reaction in the game.
 *
 * The one reaction here is `C + O₂ → CO₂`: carbon burning in the vessel's air. It is deliberately
 * the *awkward* one rather than the easy one. It is cross-layer — the carbon is on a belt and the
 * oxygen is in the room — cross-phase, since a solid leaves and a gas arrives, and cross-ledger,
 * because those two facts are counted by two different conservation identities that only close if
 * both are told. Every reaction after it is a table row; this one is the shape.
 *
 * ### What is here and what is not
 *
 * ⚠️ **This reaction releases no heat.** Burning carbon is exothermic and a fire that does not warm
 * the room is obviously a half-truth. The enthalpy term belongs to the reaction *table*
 * (increment 4), and adding it here would mean inventing where the heat goes for one reaction and
 * then deciding it again for the table. What the reaction does carry is the thermal energy the
 * carbon *already had*: matter that changes medium takes its heat with it, or a hot lump becomes
 * cold gas and the joules are gone.
 *
 * ⚠️ **Rate is proportional to the whole mass, not to a surface.** Burning is a surface phenomenon
 * and a layer stores bulk. `PLAN_ambient_chemistry.md` says this is not solved and wants it tuned
 * against a real save rather than decided up front; until then, [BASE_RATE] is what stands in for
 * the exposed fraction, and it is a dial rather than a measurement.
 *
 * ### Two consumers, one tile's oxygen — increment 2
 *
 * There are two [Oxidation]s now, [CARBON_BURN] and [IRON_RUST], and the moment a tile holds both
 * reactants they are drinking from the same well. Nothing here resolves that: [demand] asks what a
 * reaction *would* take and [react] takes what it is *allowed*, and the two are separate calls
 * precisely so that the caller can ask everybody first and only then hand anything out. The
 * apportioning itself lives in `AmbientChemistry.kt`, because it is a fact about a tile rather than
 * about a reaction.
 *
 * ⛔ **Never resolve contention by iteration order.** Whoever ran first would get the whole supply,
 * which is a rule nobody can predict and a leftward bias of exactly the kind `stepSolidHeat` and
 * the rigid-body solver are both required to avoid. Jacobi, like everything else here.
 *
 * ⚠️ **Preference is an outcome, not a list.** "The oxygen attacks the carbon first" is true here
 * because carbon's [Oxidation.baseRate] is the larger one at a shared temperature, so its demand is
 * the larger share of an oversubscribed tile. There is no priority order to consult and adding one
 * would make the physics stop explaining itself.
 */

/**
 * How much faster a reaction runs at [kelvin] than it does at its onset temperature, in [SCALE].
 *
 * Arrhenius, sampled. `k ∝ exp(−Tₐ/T)` is the law, and an exponential is a float, so it is a knot
 * table read with fixed-point interpolation — the same construction, for the same reason, as
 * `Saturation.kt`'s dome, and like that one the table is **generated from the law rather than
 * fitted**, so `ReactionTest` re-derives it in floating point and checks these numbers rather than
 * trusting them.
 *
 * Written in **reduced temperature** — `T / onset` — so one table serves every reaction there will
 * ever be, exactly as one saturation curve serves every fluid. A reaction brings its own onset and
 * its own [BASE_RATE]; the shape of the climb is shared.
 *
 * ⚠️ **[ACTIVATION] is a tuning number, not a measurement, and this is the one place that matters.**
 * Real carbon combustion has `Tₐ/T_onset ≈ 27`, which over this range is a factor of `e²⁰`. That is
 * not a curve, it is a step: matter would sit inert and then vanish inside one tick, and every
 * question the player could ask about it ("is this hot enough yet?", "how fast is it going?") would
 * have the same two answers. Six gives a hundredfold climb across the range, which is steep enough
 * that temperature is the thing that matters and shallow enough to be watched. The *shape* is the
 * physics; the stiffness is chosen.
 *
 * Below onset this is not called — see [burn], which rejects cold matter with one compare.
 */
fun rateMultiplier(kelvin: Int, onsetKelvin: Int): Long {
    if (onsetKelvin <= 0 || kelvin <= onsetKelvin) return SCALE
    val reduced = scaledRatio(kelvin.toLong(), onsetKelvin.toLong(), SCALE)
    // Where along the table this sits, as 0..SCALE. The subtraction is what makes the table's own
    // axis start at the onset rather than at absolute zero, which is what lets one table serve
    // reactions whose onsets are hundreds of kelvin apart.
    val along = scaledRatio(reduced - SCALE, (REDUCED_MAX - 1L) * SCALE, SCALE)
    return sample(ARRHENIUS, along)
}

/**
 * A knot table read at `0..SCALE`, interpolating linearly between the two knots either side.
 *
 * ⚠️ **Held, not extrapolated, past the last knot.** The Arrhenius curve is still climbing at the
 * top of the range and a linear run-on would have it climb without limit, which is a reaction that
 * gets arbitrarily fast in a tile that happens to be very hot. Holding says "as fast as this model
 * has anything to say about", and the clamp in [reactionFraction] says the rest.
 *
 * The twin of `Saturation.kt`'s sampler, deliberately down to the arithmetic.
 */
private fun sample(table: LongArray, along: Long): Long {
    if (along <= 0L) return table[0]
    val position = along * (KNOTS - 1)
    val index = (position / SCALE).toInt()
    if (index >= KNOTS - 1) return table[KNOTS - 1]
    val fraction = position % SCALE
    val low = table[index]
    return low + (table[index + 1] - low) * fraction / SCALE
}

/**
 * A reaction's rate at [kelvin] as a fraction of the reactant present, in [SCALE] — [BASE_RATE]
 * climbing along [rateMultiplier], and never more than all of it.
 *
 * The clamp is not a rounding guard. A reaction whose fraction exceeded one would consume more than
 * is there, and the honest rendering of "hot enough to burn faster than it can be supplied" is that
 * it all goes this tick.
 */
fun reactionFraction(kelvin: Int, onsetKelvin: Int, baseRate: Long): Long =
    minOf(scaledRatio(rateMultiplier(kelvin, onsetKelvin), SCALE, baseRate), SCALE)

/**
 * ⛔ **`Oxidation`, `Reacted`, `CARBON_BURN`, `IRON_RUST` and `burn` were here** — deleted by
 * increment 4 of `PLAN_unified_reactions.md`.
 *
 * The class existed because its two reagents came from two different stores: a solid out of a cargo
 * layer and oxygen out of the room. Under the placement rule that is what the pass does for *every*
 * row — a reagent is drawn from wherever it is — so there was nothing left for the class to be. Both
 * rows are in [REACTIONS] with oxygen among their reagents, carrying every number unchanged.
 *
 * ⚠️ **They had to move in the same commit as the fires**, because both tables drank from a tile's
 * oxygen. A well that covered one and not the other is the pass-order bug increment 3 deleted, back
 * again in a smaller room.
 *
 * What survives in this file is the part that was never about a shape: the Arrhenius climb every
 * reaction in the game shares, and the two dials below.
 */

/** The temperature carbon in air starts to burn at. Graphite in air, near enough — see [burn]. */
const val CARBON_IGNITION_KELVIN: Int = 700

/**
 * The temperature iron in air starts to scale at — see [IRON_RUST] for why it is not room
 * temperature.
 */
const val IRON_OXIDATION_KELVIN: Int = 500

/**
 * The share of the carbon present that burns in one pass **at exactly the ignition point**, in
 * [SCALE] — the slowest the reaction ever goes.
 *
 * A quarter of a percent a pass, which at `CHEM_PERIOD` is a lump smouldering away over something
 * like a minute of play and, a few hundred kelvin hotter, over a second or two. It is the dial that
 * stands in for exposed surface area (see this file's header), so it is tuned rather than derived
 * and it is expected to move.
 */
const val BASE_RATE: Long = SCALE / 400L

/**
 * The same dial for [IRON_RUST], and a tenth of [BASE_RATE].
 *
 * ⚠️ **This is what makes "the oxygen attacks the carbon first" true**, and it is the only thing
 * that does: in a tile holding both, carbon asks for the larger share of a scarce supply, so it
 * gets it. Change this number and the preference changes with it, which is the point — it is a
 * consequence of how fast the two reactions go, not a rule anybody wrote down.
 */
const val IRON_BASE_RATE: Long = SCALE / 4000L

/**
 * Knots of [ARRHENIUS], evenly spaced over `T/onset = 1 .. REDUCED_MAX`.
 *
 * ⚠️ **Sized by where the curve is worst, not by where it is typical.** An exponential is at its
 * most convex at the bottom of the range, which is also exactly where the interesting play is — a
 * lump a little over its ignition point. Thirty-three knots put the chord 2% above the law there,
 * and `ReactionTest` re-derives the law and says so; a hundred and twenty-nine put it under 0.16%.
 * The error falls fourfold per doubling, so this is cheap to buy and the table is static data.
 */
private const val KNOTS = 129

/** The top of [ARRHENIUS]. Past it the multiplier is held rather than extrapolated. */
private const val REDUCED_MAX = 4L

/**
 * `exp(ACTIVATION × (1 − onset/T))` at each knot, in [SCALE] — one at the onset, ninety at the top.
 *
 * Generated from the law and checked against it by `ReactionTest`, exactly as `Saturation.kt`'s
 * tables are: a corrupted entry is a test failure rather than a reaction that quietly runs at the
 * wrong speed.
 */
private val ARRHENIUS = longArrayOf(
    100000000L, 114729223L, 130820598L, 148313870L,
    167244347L, 187642924L, 209536136L, 232946257L,
    257891411L, 284385717L, 312439451L, 342059210L,
    373248104L, 406005940L, 440329419L, 476212331L,
    513645748L, 552618225L, 593115983L, 635123098L,
    678621682L, 723592058L, 770012924L, 817861518L,
    867113766L, 917744431L, 969727250L, 1023035061L,
    1077639927L, 1133513254L, 1190625890L, 1248948235L,
    1308450324L, 1369101924L, 1430872606L, 1493731823L,
    1557648978L, 1622593485L, 1688534829L, 1755442619L,
    1823286631L, 1892036857L, 1961663543L, 2032137224L,
    2103428754L, 2175509339L, 2248350558L, 2321924388L,
    2396203225L, 2471159897L, 2546767681L, 2623000317L,
    2699832016L, 2777237473L, 2855191868L, 2933670876L,
    3012650670L, 3092107923L, 3172019811L, 3252364012L,
    3333118704L, 3414262569L, 3495774783L, 3577635020L,
    3659823444L, 3742320706L, 3825107939L, 3908166751L,
    3991479224L, 4075027901L, 4158795786L, 4242766333L,
    4326923440L, 4411251442L, 4495735104L, 4580359613L,
    4665110571L, 4749973984L, 4834936260L, 4919984194L,
    5005104968L, 5090286137L, 5175515621L, 5260781703L,
    5346073015L, 5431378531L, 5516687565L, 5601989754L,
    5687275060L, 5772533754L, 5857756414L, 5942933917L,
    6028057430L, 6113118404L, 6198108565L, 6283019910L,
    6367844701L, 6452575453L, 6537204932L, 6621726149L,
    6706132348L, 6790417009L, 6874573832L, 6958596739L,
    7042479864L, 7126217548L, 7209804334L, 7293234962L,
    7376504362L, 7459607651L, 7542540124L, 7625297256L,
    7707874689L, 7790268234L, 7872473862L, 7954487701L,
    8036306032L, 8117925285L, 8199342033L, 8280552990L,
    8361555005L, 8442345061L, 8522920268L, 8603277861L,
    8683415196L, 8763329747L, 8843019102L, 8922480961L,
    9001713130L,
)

/**
 * The `Tₐ/onset` [ARRHENIUS] was generated at. See [rateMultiplier] for why it is not 27.
 *
 * Stated as an integer because nothing in the simulation may hold a float — it exists so the test
 * can re-derive the table from the same number the table was built with, rather than from a
 * transcription of it.
 */
const val ACTIVATION: Int = 6

/**
 * One kJ/mol of a species whose formula mass is [grams], in [Budget]'s energy unit **per kilogram**
 * of that species.
 *
 * ⚠️ **So that an enthalpy can be written the way it is looked up.** `178L * kJPerMolAt(100)` is
 * still recognisably "178 kJ/mol of something that weighs 100 g/mol", which is a claim a person can
 * check against a table; `178_000_000L` is not, and neither is 1.78 MJ/kg once the division has been
 * done by hand. The tests check each row's divisor against `reactantUnits * molarMass` rather than
 * trusting the call site to have used the right one.
 */
fun kJPerMolAt(grams: Long): Long = 1_000L * Budget.JOULE * 1_000L / grams

/**
 * [perKg] scaled to [mass], **keeping its sign**.
 *
 * ⚠️ **[scaledRatio] returns zero for a negative scale**, by an explicit guard — it is built for
 * fractions of quantities, where a negative scale means a caller has got its arguments the wrong way
 * round. An enthalpy is not one of those: its sign is the whole of what it means, and passing one
 * straight in makes every exothermic reaction release exactly nothing. Silently, and in the
 * direction where the game still looks like it works — a fire that simply never warms the room.
 *
 * So the magnitude goes through the fixed-point path and the sign is put back here, in one place,
 * for both tables.
 */
fun perKilogram(mass: Long, perKg: Long): Long {
    val magnitude = scaledRatio(mass, Budget.KILOGRAM, if (perKg < 0L) -perKg else perKg)
    return if (perKg < 0L) -magnitude else magnitude
}
