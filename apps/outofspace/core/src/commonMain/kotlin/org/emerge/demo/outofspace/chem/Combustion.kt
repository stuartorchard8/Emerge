package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * Fuel and air burning **in the air** — the fourth reaction shape, and the one that makes a room
 * dangerous.
 *
 * ### Why it is a shape and not a row
 *
 * The other three each earned a class by the *source of their reagents*, which is what decides how
 * contention works and what has to be told about it:
 *
 *  - [Decomposition] is heat and nothing else, so nothing can compete for anything.
 *  - [Oxidation] takes a solid out of a layer and oxygen out of the air, so every row drinks from
 *    one shared well and the contention is one apportionment per tile.
 *  - [Reduction] takes two solids out of the same layer, so contention is per *reagent species*.
 *
 * This one takes **both reagents out of the air and puts every product back into it**. That has two
 * consequences worth a class of its own. Contention is per tile again, as in [Oxidation], because
 * there is one oxygen. And **nothing crosses a ledger**: the cargo identity is untouched, the air
 * identity is untouched, and the only thing a pass of this has to report is the energy it made. A
 * fire in a room is not matter changing medium, it is matter changing partners.
 *
 * It also needs more than one product, which [Oxidation] cannot express — methane gives carbon
 * dioxide *and* water — so it takes [Decomposition]'s product list and [apportion] with it.
 *
 * ### Why it did not exist until now
 *
 * Nothing gaseous was ever worth burning. Fuel arrived as carbon on a belt, and the air was there
 * to supply oxygen and carry exhaust away. Then `offGas` taught ore lumps to shed their volatiles,
 * and on a live save ammonia, methane and carbon monoxide became a quarter of the atmosphere within
 * two thousand ticks. A vessel that can fill its own corridors with fuel and cannot set fire to them
 * is modelling the interesting half and refusing the consequence.
 *
 * ⚠️ **There is no flame front here and there does not need to be one.** Each tile burns on its own
 * conditions, exactly as every other ambient reaction does. Propagation is what the heat does next:
 * a burning tile gets hotter, diffusion carries that heat into its neighbours, and they reach their
 * own ignition point or they do not. That is a front, and nobody wrote it.
 */
class Combustion(
    val fuel: Species,
    val fuelUnits: Int,
    val oxygenUnits: Int,
    val products: List<Pair<Species, Int>>,
    /** Autoignition temperature — where the mixture goes off on its own, with no spark. */
    val onsetKelvin: Int,
    /** Positive is **endothermic**, as in every other table. Every row here is negative. */
    val enthalpyPerKg: Long,
    val baseRate: Long = COMBUSTION_BASE_RATE,
) {
    /** Mass of O₂ per mass of fuel, as the exact ratio of formula-unit masses. */
    internal val oxygenNumerator: Long = oxygenUnits.toLong() * Species.Oxygen.molarMass
    internal val oxygenDenominator: Long = fuelUnits.toLong() * fuel.molarMass

    /**
     * The mass of each of [products], in order, from [totalMass] of **fuel and oxygen together**.
     *
     * [Reduction.split]'s construction, for [Reduction.split]'s reason: both reagents are consumed
     * and the products account for every atom of both. Apportioning only the fuel would lose the
     * oxygen's mass silently, and since that oxygen came out of the same array the products go into,
     * the room would quietly get lighter every time anything caught fire.
     */
    fun split(totalMass: Long): LongArray = apportion(weights, totalMass)

    /** The energy [mass] of this fuel releases — negative, because burning is exothermic. */
    fun enthalpy(mass: Long): Long = perKilogram(mass, enthalpyPerKg)

    /**
     * How much oxygen this reaction **wants** at [kelvin] with [fuelMass] present.
     *
     * Half of the Jacobi rule, and the same half [Oxidation.demand] is: asked of every row against
     * one snapshot, before any oxygen has been taken, so no row's answer depends on when it was
     * asked. ⛔ Reacting each in turn against a dwindling supply would hand the whole tile to
     * whichever entry of [COMBUSTIONS] came first — a rule no player can predict.
     */
    fun demand(fuelMass: Long, kelvin: Int): Long {
        if (fuelMass <= 0L || kelvin < onsetKelvin) return 0L
        val fraction = reactionFraction(kelvin, onsetKelvin, baseRate)
        val consumed = scaledRatio(fraction, SCALE, fuelMass)
        if (consumed <= 0L) return 0L
        return scaledRatio(oxygenNumerator, oxygenDenominator, consumed)
    }

    /**
     * What one pass consumes, given the fuel present, how hot it is, and **how much oxygen this row
     * is allowed** — which in a contended tile is less than [demand] asked for.
     *
     * The starvation path is [Reduction.react]'s, down to the double flooring: when the allowance
     * binds, the fuel is re-derived from the oxygen and then the oxygen re-derived from *that* fuel,
     * so the pair sits exactly on the stoichiometric line. A fire running rich would break the atom
     * balance in the direction where it still looks like it is working.
     */
    fun react(fuelMass: Long, oxygenAllowed: Long, kelvin: Int): Burned {
        if (fuelMass <= 0L || oxygenAllowed <= 0L || kelvin < onsetKelvin) return NOTHING

        val fraction = reactionFraction(kelvin, onsetKelvin, baseRate)
        var consumed = scaledRatio(fraction, SCALE, fuelMass)
        if (consumed <= 0L) return NOTHING

        var oxygen = scaledRatio(oxygenNumerator, oxygenDenominator, consumed)
        if (oxygen > oxygenAllowed) {
            oxygen = oxygenAllowed
            consumed = scaledRatio(oxygenDenominator, oxygenNumerator, oxygen)
            if (consumed <= 0L) return NOTHING
            oxygen = scaledRatio(oxygenNumerator, oxygenDenominator, consumed)
            if (oxygen <= 0L) return NOTHING
        }

        return Burned(consumed, oxygen)
    }

    /** Mass each product's formula units account for — the weights [split] apportions by. */
    private val weights: LongArray =
        LongArray(products.size) { products[it].second.toLong() * products[it].first.molarMass }

    companion object {
        private val NOTHING = Burned(0L, 0L)
    }
}

/** What one pass of a [Combustion] took out of the air. Both go into the products. */
class Burned(val fuel: Long, val oxygen: Long) {
    val total: Long get() = fuel + oxygen
    val isNothing: Boolean get() = fuel == 0L && oxygen == 0L
}

/**
 * How fast a gas burns, as a fraction of the fuel present at the onset temperature.
 *
 * ⚠️ **Eight times [BASE_RATE], and the factor is the one thing here that is a choice.** A solid
 * burns at its *surface* — that is what [BASE_RATE] stands in for, and why a lump's rate is a
 * property of how finely divided it is rather than of how much of it there is. A gas has no surface:
 * fuel and oxidiser are mixed at the molecular level and the whole volume reacts at once. Eight is
 * the smallest round number that makes that difference visible rather than a rounding, and it is
 * the dial to turn if gas fires read as too sluggish or too sudden.
 */
val COMBUSTION_BASE_RATE: Long = SCALE / 50L

/**
 * Every gas-phase fire in the game, in a fixed order.
 *
 * ⚠️ **Order is for reproducibility, not for priority** — contention is settled by demand before
 * anything is taken, so which row comes first changes only the rounding.
 *
 * ⚠️ **The onsets are real autoignition temperatures**, which is what makes them a *design*: hydrogen
 * sulfide goes off at 260 °C and ammonia needs 651 °C, so a hold full of comet volatiles has a
 * temperature at which it becomes a problem and a different one at which it becomes a bomb.
 *
 * Every row closes atom-for-atom against [Species]'s molar masses — `CombustionTest` checks it the
 * way `DecompositionTest` checks its own table, because a row that balances by eye and not by
 * arithmetic would yield the wrong amount of the right thing, for ever, silently.
 */
val COMBUSTIONS: List<Combustion> = listOf(
    // CH4 + 2 O2 -> CO2 + 2 H2O. The marquee one: methane is what a comet gives up first and what
    // `offGas` now puts into every room the vessel has.
    Combustion(
        fuel = Species.Methane, fuelUnits = 1, oxygenUnits = 2,
        products = listOf(Species.CarbonDioxide to 1, Species.Water to 2),
        onsetKelvin = 810,
        enthalpyPerKg = -802L * kJPerMolAt(16),
    ),
    // 2 H2 + O2 -> 2 H2O. The cleanest and the most eager: nothing else here lights at 773 K and
    // leaves only water behind.
    Combustion(
        fuel = Species.Hydrogen, fuelUnits = 2, oxygenUnits = 1,
        products = listOf(Species.Water to 2),
        onsetKelvin = 773,
        enthalpyPerKg = -242L * kJPerMolAt(4),
    ),
    // 2 CO + O2 -> 2 CO2. Carbon monoxide is what a starved fire makes, so this is the second half
    // of a fire that did not get enough air the first time -- and what the Boudouard reaction has
    // been quietly filling the rooms with since 14306ded.
    Combustion(
        fuel = Species.CarbonMonoxide, fuelUnits = 2, oxygenUnits = 1,
        products = listOf(Species.CarbonDioxide to 2),
        onsetKelvin = 882,
        enthalpyPerKg = -566L * kJPerMolAt(56),
    ),
    // 2 H2S + 3 O2 -> 2 SO2 + 2 H2O. The lowest onset in the table by a wide margin, and the reason
    // a sour hold is the one to worry about first.
    Combustion(
        fuel = Species.HydrogenSulfide, fuelUnits = 2, oxygenUnits = 3,
        products = listOf(Species.SulfurDioxide to 2, Species.Water to 2),
        onsetKelvin = 533,
        enthalpyPerKg = -1036L * kJPerMolAt(68),
    ),
    // 4 NH3 + 3 O2 -> 2 N2 + 6 H2O. Ammonia is hard to light and worth the trouble: it burns back
    // to breathable nitrogen and water, so a vessel that can get a hold hot enough has a way of
    // turning a comet's worst volatile into two things it wants.
    Combustion(
        fuel = Species.Ammonia, fuelUnits = 4, oxygenUnits = 3,
        products = listOf(Species.Nitrogen to 2, Species.Water to 6),
        onsetKelvin = 924,
        enthalpyPerKg = -1267L * kJPerMolAt(68),
    ),
    // S + O2 -> SO2. Sulfur vapour, which a pyrite roast puts into the air by the kilogram.
    Combustion(
        fuel = Species.Sulfur, fuelUnits = 1, oxygenUnits = 1,
        products = listOf(Species.SulfurDioxide to 1),
        onsetKelvin = 505,
        enthalpyPerKg = -297L * kJPerMolAt(32),
    ),
)

/** The coldest any gas fire starts at, so a cool tile is rejected without asking each row. */
val LOWEST_COMBUSTION_ONSET: Int = COMBUSTIONS.minOf { it.onsetKelvin }

/** The widest [COMBUSTIONS] gets, for the scratch arrays a sweep hoists once. */
val COMBUSTION_COUNT: Int = COMBUSTIONS.size
