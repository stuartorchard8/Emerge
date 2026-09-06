package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * **What a charge does when a voltage is put across it** — the competition that replaced
 * `electrolyse`.
 *
 * Increment 1 of `PLAN_electrochemistry.md`. There is no row anywhere saying that water splits into
 * hydrogen and oxygen. There is a table of couples ([HALF_REACTIONS]), and a rule:
 *
 *  - **the cathode** runs the couple with the **highest** E° whose oxidised side the charge can
 *    supply;
 *  - **the anode** runs the couple with the **lowest** E° whose reduced side the charge can supply,
 *    backwards;
 *  - the cell needs `E°(anode) − E°(cathode)` and does nothing at all below it.
 *
 * Water splitting is what that rule *does* to a charge of water, because water is the only thing
 * there. ⭐ Put copper in the same charge and it plates instead, on the strength of +340 being above
 * 0 — and nothing in this file knows the difference between those two situations.
 *
 * ### ⛔ Why this is not a `Reaction`
 *
 * A [Reaction] gates on temperature and is swept over the world. This gates on **applied potential**
 * and happens only inside a machine that is applying one. They are the two condition axes of the
 * same idea and they deliberately do not share a type: a `Reaction` with a null onset and a voltage
 * would be a row that the ambient sweep has to remember to skip.
 *
 * ### ⚠️ What is not modelled yet, and where it lands
 *
 * **Solution conductivity.** A real cell of pure water does nothing, because pure water carries
 * almost no current — 5.5e-6 S/m against 80 for molar acid. There is no current here to be resistive
 * (the voltage is a dial), so pure water splits. `PLAN_power_network.md` increment 2 is where the
 * cell becomes a load on a real circuit and that stops being true. See the plan's §5.
 *
 * **Concentration.** Every potential here is the standard one. A cell plates at full rate down to
 * the last gram rather than stalling as it depletes; the Nernst equation is `PLAN_electrochemistry.md`
 * §8.
 */
class CellAction(
    val cathodeCouple: HalfReaction,
    val anodeCouple: HalfReaction,
    /** Electrons passed — the least common multiple of the two halves, so both run whole. */
    val electrons: Int,
    /** What must be applied for this to run at all, in millivolts. Always positive for a driven cell. */
    val requiredMillivolts: Int,
    /** Species drawn out of the charge, as formula units at [electrons]. */
    val consumes: List<Pair<Species, Int>>,
    /** What appears at the cathode, as formula units at [electrons]. */
    val cathodeProducts: List<Pair<Species, Int>>,
    /** What appears at the anode, as formula units at [electrons]. */
    val anodeProducts: List<Pair<Species, Int>>,
    /**
     * Protons left over once the two halves are added — ⭐ **the acid a cell regenerates.**
     *
     * Zero when splitting water, because the cathode eats what the anode makes. Four per four
     * electrons when plating copper, because the cathode eats none. ⚠️ Nothing consumes this yet:
     * it needs somewhere to put an anion, which is `PLAN_electrochemistry.md` §6's open question.
     * It is computed and asserted so that the arithmetic is right before anything depends on it.
     */
    val surplusProtons: Int,
) {
    /** Mass of one whole pass at [electrons], as the charge sees it leaving. */
    val consumedMass: Long = consumes.sumOf { it.second.toLong() * it.first.molarMass }
}

/** Smallest whole number of electrons at which both halves run a whole number of times. */
private fun lcm(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) { val t = x % y; x = y; y = t }
    return a / x * b
}

/**
 * Fold a species list into another, summing units — so `2 H₂O` consumed twice is `4 H₂O` and not
 * two entries a caller has to remember to add up.
 */
private fun MutableList<Pair<Species, Int>>.add(side: List<Pair<Species, Int>>, times: Int) {
    for ((species, units) in side) {
        val at = indexOfFirst { it.first == species }
        if (at >= 0) this[at] = species to (this[at].second + units * times) else add(species to units * times)
    }
}

/**
 * What the cell would do to [charge] if [appliedMillivolts] were put across it, or null if nothing.
 *
 * Null has three causes and they are deliberately indistinguishable to a caller: nothing reducible,
 * nothing oxidisable, or not enough voltage. A machine that cannot run does not care why.
 */
fun cellAction(charge: Mixture, appliedMillivolts: Int): CellAction? {
    // The solvent is what supplies protons — see the autoionization note in the plan's §5. A couple
    // that eats protons is only available if there is water for them to have come from.
    val hasWater = charge[Species.Water] > 0L

    fun canSupply(side: List<Pair<Species, Int>>, protons: Int): Boolean =
        (protons == 0 || hasWater) && side.all { charge[it.first] > 0L }

    // ⚠️ The cathode reduces its OXIDISED side, so that is what the charge must hold; the anode runs
    // backwards and consumes its REDUCED side. Getting these the wrong way round yields a cell that
    // looks plausible and electroplates in reverse.
    val cathode = HALF_REACTIONS.filter { canSupply(it.oxidised, it.protons) }.maxByOrNull { it.standardMillivolts }
        ?: return null
    // ⛔ **A metal couple cannot be the anode yet, and the reason is a missing distinction rather
    // than a missing row.** An anode that dissolves its own metal is *electrorefining*, and to run
    // one the cell would have to tell deposited copper from dissolved copper — which is the same
    // species here, differing only by a charge this game does not store. That is `dissolvedFraction`
    // and it is increment 3's. Until then the anode is the solvent's.
    //
    // ⚠️ Without this the competition is not merely incomplete, it *stalls*: a charge holding copper
    // offers copper at both electrodes, the highest and the lowest available couple are the same
    // one, and the cell does nothing at all.
    val anode = HALF_REACTIONS.filter { !it.isMetalCouple && canSupply(it.reduced, 0) }
        .minByOrNull { it.standardMillivolts }
        ?: return null
    if (cathode === anode) return null

    val required = -cellMillivolts(cathode, anode)
    if (required <= 0 || appliedMillivolts < required) return null

    val electrons = lcm(cathode.electrons, anode.electrons)
    val ca = electrons / cathode.electrons
    val an = electrons / anode.electrons

    val consumes = mutableListOf<Pair<Species, Int>>()
    consumes.add(cathode.oxidised, ca)
    consumes.add(anode.reduced, an)
    if (consumes.isEmpty()) return null

    return CellAction(
        cathodeCouple = cathode,
        anodeCouple = anode,
        electrons = electrons,
        requiredMillivolts = required,
        consumes = consumes,
        cathodeProducts = mutableListOf<Pair<Species, Int>>().also { it.add(cathode.reduced, ca) },
        anodeProducts = mutableListOf<Pair<Species, Int>>().also { it.add(anode.oxidised, an) },
        surplusProtons = anode.protons * an - cathode.protons * ca,
    )
}

/**
 * Run [action] over as much of [charge] as [limit] allows, splitting the result between the two
 * electrodes.
 *
 * ⚠️ **Exactness is the whole job here**, and it is inherited rather than reinvented: the draw is
 * [Mixture.take], the only exact draw in the game, and the products are [apportion]ed over their
 * formula-unit weights so the telescoping sum cannot lose or invent a gram. That is the property
 * `electrolyse` had — *"the hydrogen is computed and the oxygen is the remainder"* — generalised to
 * a product list of any length.
 *
 * ⚠️ **Thermal energy rides along in proportion and no enthalpy is charged**, exactly as before. See
 * `PLAN_chemical_rockets.md` §1 for why the energy is free and `PLAN_power_network.md` for what will
 * eventually bill it.
 */
class Electrolysed(val cathode: Mixture, val anode: Mixture, val consumed: Mixture)

fun electrolyse(charge: Mixture, action: CellAction, limit: Long): Electrolysed? {
    if (limit <= 0L) return null

    // ⛔ **A pass with protons left over cannot run yet, and this is `PLAN_electrochemistry.md` §6
    // arriving early.** Those protons are the regenerated acid, they weigh a gram apiece, and there
    // is nowhere to put them: an acid needs an anion, and whether the game grows a `Sulfate` species
    // for that is the plan's one open question.
    //
    // ⚠️ **Refusing is not fastidiousness — the arithmetic is wrong without it.** Winning copper
    // consumes 2 Cu + 2 H₂O = 164 and yields 2 Cu + O₂ = 160, and the missing four are the protons.
    // [apportion] would spread the 164 over the products' 128:32 weights and hand back copper and
    // oxygen that each weigh slightly too much. Silent, and exactly the class of fault this package
    // is built to make impossible.
    if (action.surplusProtons != 0) return null

    // How many whole passes fit, capped by the limit and by the scarcest reagent the pass needs.
    // ⚠️ Whole passes, never a fraction: a part-pass would have to round its stoichiometry and the
    // rounding is exactly where a gram gets invented.
    var passes = limit / action.consumedMass
    for ((species, units) in action.consumes) {
        passes = minOf(passes, charge[species] / (units.toLong() * species.molarMass))
    }
    if (passes <= 0L) return null

    // The draw is stoichiometric, not proportional — [Mixture.take] spreads across whatever is
    // present, which is the right answer for a shovelful and the wrong one for a reagent list.
    val drawn = LongArray(Species.COUNT)
    for ((species, units) in action.consumes) {
        drawn[species.ordinal] += passes * units.toLong() * species.molarMass
    }
    var consumedTotal = 0L
    for (m in drawn) consumedTotal += m
    val consumed = Mixture.of(drawn, energy = scaledRatio(consumedTotal, charge.total, charge.energy))

    // Products apportioned over their formula-unit weights, so the telescoping sum cannot lose or
    // invent a gram. This is `electrolyse`'s old "the hydrogen is computed and the oxygen is the
    // remainder", generalised to a product list of any length.
    val products = action.cathodeProducts + action.anodeProducts
    val weights = LongArray(products.size) { products[it].second.toLong() * products[it].first.molarMass }
    val masses = apportion(weights, consumedTotal)
    val energies = apportion(weights, consumed.energy)

    var cathode = Mixture.EMPTY
    var anode = Mixture.EMPTY
    for (i in products.indices) {
        val part = Mixture.of(products[i].first to masses[i], energy = energies[i])
        if (i < action.cathodeProducts.size) cathode += part else anode += part
    }
    return Electrolysed(cathode, anode, consumed)
}
