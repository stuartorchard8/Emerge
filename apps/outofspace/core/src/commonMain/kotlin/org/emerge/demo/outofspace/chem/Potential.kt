package org.emerge.demo.outofspace.chem

/**
 * **What it costs to take an electron off something, and therefore every cell voltage in the game.**
 *
 * The sibling of [FORMATION_ENTHALPY], built for the same reason and read the same way. That table
 * states one number per species and Hess's law derives every reaction's energy; this one states one
 * number per *couple* and subtraction derives every cell's voltage. See `PLAN_electrochemistry.md`.
 *
 * ⛔ **Stated, not derived.** A standard potential needs free energy and entropy, and
 * [FORMATION_ENTHALPY] carries neither — so unlike a reaction's enthalpy this cannot be computed
 * from something the game already knows. It is an oracle, and `HalfReactionTest` is what holds it to
 * a textbook.
 *
 * ### Why couples rather than whole cell reactions
 *
 * A cell reaction is a *pair*. Writing the pairs down would be the n×m table this whole arc exists
 * to avoid — and worse, it would make water's ceiling a row somebody remembered to write instead of
 * a consequence. Stating the halves means [cellMillivolts] derives the rest, and the two facts that
 * matter both fall out of the ordering rather than out of a rule:
 *
 *  - **Copper plates and hydrogen does not**, because +340 is above 0.
 *  - **Aluminium cannot be won from water at all**, because −1660 is below 0 and the cathode takes
 *    the hydrogen every time. That is why the real process uses molten cryolite, and the game gets
 *    the reason rather than the prohibition.
 *
 * ### The ion is the species, and the charge is arithmetic
 *
 * ⛔ **No ionic species exist and none will.** `Cu²⁺` is [Species.Copper]; what makes it an ion is
 * that it is dissolved, and what makes it 2+ is that its couple carries two [electrons] for one
 * formula unit. So [HalfReaction.chargeOn] is *derived*, exactly as [Species.molarMass] is derived
 * from [MINERALS], and a mistyped electron count is a test failure rather than an ion that quietly
 * carries the wrong charge.
 *
 * ⚠️ **Both sides count formula units, [Species.molarMass]'s way**, which is why `2 H⁺` is written as
 * one unit of [Species.Hydrogen] — the game's hydrogen is H₂ at 2 g/mol, and two protons weigh the
 * same two grams. The masses balance across a couple for the same reason they balance across a
 * reaction, and `HalfReactionTest` checks every one.
 */
class HalfReaction(
    /** The side that gains electrons — what is present *before* reduction. */
    val oxidised: List<Pair<Species, Int>>,
    /** What it becomes. */
    val reduced: List<Pair<Species, Int>>,
    /** Electrons transferred per the formula units written above. */
    val electrons: Int,
    /** E° against the standard hydrogen electrode. Positive is easier to reduce. */
    val standardMillivolts: Int,
) {
    /** The species this couple is *about*: the one that changes oxidation state. */
    val principal: Species = oxidised.first().first

    /** Mass of the oxidised side as written — the denominator of every ratio here. */
    val oxidisedMass: Long = oxidised.sumOf { it.second.toLong() * it.first.molarMass }

    /** Mass of the reduced side as written. Equal to [oxidisedMass], and a test says so. */
    val reducedMass: Long = reduced.sumOf { it.second.toLong() * it.first.molarMass }

    /**
     * The charge on one formula unit of [principal] in its oxidised form — **derived**.
     *
     * Two electrons shared over one unit of copper is Cu²⁺; three over one aluminium is Al³⁺. ⚠️ Only
     * meaningful for the couples whose oxidised side is a single dissolved species, which is every
     * metal here; the water couples spread their electrons over a molecule and answer nonsense.
     */
    val chargeOn: Int get() = electrons / oxidised.first().second

    fun formula(): String =
        oxidised.joinToString(" + ") { "${it.second} ${it.first}" } +
            " + $electrons e- -> " +
            reduced.joinToString(" + ") { "${it.second} ${it.first}" }
}

/**
 * **What a cell of these two halves is worth**, in millivolts.
 *
 * Negative means the cell must be *driven* and the magnitude is what has to be applied; positive
 * means it would run on its own and is a battery. ⚠️ That sign is the only difference between
 * electrolysis and a battery in this game, and it is why `PLAN_electrochemistry.md` can defer
 * batteries without deferring anything structural.
 */
fun cellMillivolts(cathode: HalfReaction, anode: HalfReaction): Int =
    cathode.standardMillivolts - anode.standardMillivolts

/**
 * Every couple the game knows, ordered as the electrochemical series — **most negative first**, so
 * that reading down the list is reading the order a cathode picks them in.
 *
 * ⚠️ **A couple being here does not make its metal winnable.** Aluminium, magnesium and sodium are
 * listed and are all unreachable from water; zinc, iron, nickel, tin and lead are listed and all
 * lose to hydrogen on E° alone. That is the point — they are here so the competition has something
 * to reject, and so `HalfReactionTest` can assert the ordering that does the rejecting. See
 * `PLAN_electrochemistry.md` §8 for overpotential, which is the honest lever that would widen this.
 */
val HALF_REACTIONS: List<HalfReaction> = listOf(
    // ══ BELOW HYDROGEN: everything here loses the cathode to water ════════════════════════════
    //
    // Na+ + e- -> Na. The floor, and the reason molten-salt electrolysis exists at all.
    HalfReaction(listOf(Species.Sodium to 1), listOf(Species.Sodium to 1), 1, -2710),
    // Mg2+ + 2e- -> Mg
    HalfReaction(listOf(Species.Magnesium to 1), listOf(Species.Magnesium to 1), 2, -2370),
    // Al3+ + 3e- -> Al. ⛔ The one that makes the scope cut physical rather than chosen.
    HalfReaction(listOf(Species.Aluminum to 1), listOf(Species.Aluminum to 1), 3, -1660),
    // Zn2+ + 2e- -> Zn. Industrially won from sulfate anyway, on an overpotential this does not model.
    HalfReaction(listOf(Species.Zinc to 1), listOf(Species.Zinc to 1), 2, -760),
    // Fe2+ + 2e- -> Fe. Iron(II); the game's other iron is a mineral, not an ion.
    HalfReaction(listOf(Species.Iron to 1), listOf(Species.Iron to 1), 2, -440),
    // Ni2+ + 2e- -> Ni
    HalfReaction(listOf(Species.Nickel to 1), listOf(Species.Nickel to 1), 2, -250),
    // Sn2+ + 2e- -> Sn
    HalfReaction(listOf(Species.Tin to 1), listOf(Species.Tin to 1), 2, -140),
    // Pb2+ + 2e- -> Pb
    HalfReaction(listOf(Species.Lead to 1), listOf(Species.Lead to 1), 2, -130),

    // ══ THE DEFINITION ════════════════════════════════════════════════════════════════════════
    //
    // 2 H+ + 2e- -> H2. Zero by definition — every number on this page is measured against it, and
    // it is the line every metal above is on the wrong side of.
    HalfReaction(listOf(Species.Hydrogen to 1), listOf(Species.Hydrogen to 1), 2, 0),

    // ══ ABOVE HYDROGEN: what an aqueous cell can actually win ═════════════════════════════════
    //
    // Cu2+ + 2e- -> Cu. The marquee one, and the first metal this arc is for.
    HalfReaction(listOf(Species.Copper to 1), listOf(Species.Copper to 1), 2, 340),
    // Ag+ + e- -> Ag
    HalfReaction(listOf(Species.Silver to 1), listOf(Species.Silver to 1), 1, 800),

    // ══ THE ANODE, in an aqueous cell ═════════════════════════════════════════════════════════
    //
    // O2 + 4 H+ + 4e- -> 2 H2O. ⭐ Read backwards — which is what an anode does — this is
    // `2 H2O -> O2 + 4 H+ + 4e-`: the oxygen a cell evolves and the acid it regenerates, in one row
    // that nobody had to write twice.
    HalfReaction(
        oxidised = listOf(Species.Oxygen to 1, Species.Hydrogen to 2),
        reduced = listOf(Species.Water to 2),
        electrons = 4,
        standardMillivolts = 1230,
    ),
)
