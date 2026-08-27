package org.emerge.demo.outofspace.chem

/**
 * Everything the game knows about one species, in the shape a reader wants it — what it is made of,
 * and every reaction it takes part in either end of.
 *
 * ⛔ **A view over the tables, never a second copy of them.** The four reaction tables are four
 * classes because they are four different mechanisms — heat alone, heat with the room's air, heat
 * with a solid reagent, and a fuel burning in the air it is already mixed with — and each of them is
 * arranged for the sweep that runs it: [DECOMPOSITION_OF] is indexed by ordinal for the tick loop,
 * [REDUCTION_GROUPS] is grouped by the reagent they contend for. None of those shapes answers "what
 * happens to magnesite?", which is the only question a player asks. So this file flattens all four
 * into one row type and looks *up* from a species, and it derives that flattening rather than
 * restating it: a reaction added to any table appears here without anybody remembering to add it,
 * which is [MINERALS]' argument and `MineralTest`'s.
 *
 * ⚠️ **A table this file has not been taught about is a species the panel says nothing happens to**,
 * and it says it in the confident voice it uses for everything else. [COMBUSTIONS] existed for five
 * days before this list knew about it, and for those five days a player who clicked METHANE in a
 * room slowly filling with it was told the gas was inert. That is the failure mode of a flattening
 * that has to be *added* to, and it is why `SpeciesReferenceTest` now counts the rows of every table
 * against this one rather than checking a reaction it happens to remember.
 *
 * Nothing here runs in a tick. The lists are built once and read by a panel.
 */

/** Which of the three mechanisms a [ReactionInfo] came from — what the player must arrange for it. */
enum class ReactionKind(val label: String) {
    /** Heat alone, in a [org.emerge.demo.outofspace.world.machine.ThermalDecomposer] or anywhere hot enough. */
    Heat("HEAT"),

    /** Heat and the oxygen in the room's air. */
    Burn("AIR"),

    /** Heat and a solid reagent mixed into the charge. */
    Reduce("REAGENT"),

    /**
     * Heat, and a fuel that is **already in the air** alongside the oxygen it burns in — a
     * [Combustion] rather than an [Oxidation].
     *
     * ⚠️ **Not the same thing to arrange as [Burn], which is why it is not the same row.** A [Burn]
     * asks the player to put a solid somewhere airy and hot; this one asks for nothing but a room,
     * because `offGas` has already filled it with methane. What the article is really telling a
     * player here is the temperature at which the corridor they are standing in becomes a bomb.
     */
    Fire("GAS FIRE"),
}

/**
 * One reaction, as a list of what goes in and a list of what comes out.
 *
 * Units are formula units, exactly as the source tables state them — `4 FE` in the rust row is the
 * `4` of `4 Fe + 3 O₂ → 2 Fe₂O₃`. They are not masses and are not meant to be read as ratios of
 * mass; the tables derive the mass ratios from these and the molar masses, and so should anything
 * else that needs them.
 */
class ReactionInfo(
    val kind: ReactionKind,
    val inputs: List<Pair<Species, Int>>,
    val products: List<Pair<Species, Int>>,
    val onsetKelvin: Int,
    /** Positive is **endothermic**, the sign convention of all four tables. */
    val enthalpyPerKg: Long,
) {
    /** Whether this reaction takes energy out of the matter to happen, rather than giving it back. */
    val isEndothermic: Boolean get() = enthalpyPerKg > 0L

    fun consumes(species: Species): Boolean = inputs.any { it.first == species }

    fun produces(species: Species): Boolean = products.any { it.first == species }
}

/**
 * Every reaction in the game, flattened.
 *
 * Order is table order — oxidations, decompositions, reductions, gas fires, then the store-agnostic
 * rows — which is arbitrary and only has to be stable, since this list is read by a panel and never
 * by the sim.
 */
val ALL_REACTIONS: List<ReactionInfo> = buildList {
    for (o in OXIDATIONS) {
        add(
            ReactionInfo(
                kind = ReactionKind.Burn,
                inputs = listOf(o.reactant to o.reactantUnits, Species.Oxygen to o.oxygenUnits),
                products = listOf(o.product to o.productUnits),
                onsetKelvin = o.onsetKelvin,
                enthalpyPerKg = o.enthalpyPerKg,
            ),
        )
    }
    for (d in DECOMPOSITIONS) {
        add(
            ReactionInfo(
                kind = ReactionKind.Heat,
                inputs = listOf(d.reactant to d.reactantUnits),
                products = d.products,
                onsetKelvin = d.onsetKelvin,
                enthalpyPerKg = d.enthalpyPerKg,
            ),
        )
    }
    for (r in REDUCTIONS) {
        add(
            ReactionInfo(
                kind = ReactionKind.Reduce,
                inputs = withCatalyst(
                    listOf(r.oxide to r.oxideUnits, r.reductant to r.reductantUnits),
                    r.catalyst,
                    r.catalystUnits,
                ),
                products = withCatalyst(r.products, r.catalyst, r.catalystUnits),
                onsetKelvin = r.onsetKelvin,
                enthalpyPerKg = r.enthalpyPerKg,
            ),
        )
    }
    for (c in COMBUSTIONS) {
        add(
            ReactionInfo(
                kind = ReactionKind.Fire,
                inputs = listOf(c.fuel to c.fuelUnits, Species.Oxygen to c.oxygenUnits),
                products = c.products,
                onsetKelvin = c.onsetKelvin,
                enthalpyPerKg = c.enthalpyPerKg,
            ),
        )
    }
    for (r in REACTIONS) {
        add(
            ReactionInfo(
                // ⚠️ **Derived, not stated — and this is the point of the whole plan.** A
                // [Reaction] holds no kind, because what store its matter is in is not its business.
                // What the *player* must arrange is still a real distinction and still worth
                // printing, so it is worked out from the reagents here, where it is a label rather
                // than a fact the simulation acts on.
                kind = kindOf(r),
                inputs = r.reagents,
                products = r.products,
                onsetKelvin = r.onsetKelvin,
                enthalpyPerKg = r.enthalpyPerKg,
            ),
        )
    }
}

/**
 * What a player has to arrange for [reaction] — [ReactionKind] worked out rather than declared.
 *
 * Oxygen among the reagents and a fluid principal is a fire; oxygen with a solid principal is
 * something burning in the room's air; a second solid reagent is something mixed into the charge;
 * and one reagent is heat and nothing else. Those are the four the four old tables encoded
 * structurally, recovered from the only thing that ever actually distinguished them.
 *
 * ⚠️ **The label may be wrong here in a way it could not be before, and that is the trade.** A
 * table said what it was; this infers it. The inference is a panel's caption and nothing in the
 * simulation reads it, so the cost of getting it wrong is a misleading word — against a store claim
 * that was wrong for three rows and cost them their existence.
 */
private fun kindOf(reaction: Reaction): ReactionKind {
    val takesOxygen = reaction.reagents.any { it.first == Species.Oxygen }
    val gaseousPrincipal = reaction.principal.isFluid
    return when {
        takesOxygen && gaseousPrincipal -> ReactionKind.Fire
        takesOxygen -> ReactionKind.Burn
        reaction.reagents.size > 1 -> ReactionKind.Reduce
        else -> ReactionKind.Heat
    }
}

/**
 * [entries] with [catalyst] added to them, or [entries] unchanged if there is no catalyst.
 *
 * ⛔ **A catalyst is a reactant that appears on both sides, and the reference says exactly that.**
 * Called once for the inputs and once for the products, it turns [Reduction.catalyst]'s separate
 * field back into the formula the row's own documentation is written in:
 * `100 ALGAE + 6 WATER + 6 CO₂ → 101 ALGAE + 6 OXYGEN`. Nothing about it is a new kind of
 * ingredient, so the panel needs no new row type, no "NEEDS" line and no third lookup — a player
 * reads a hundred going in and a hundred and one coming out, and that *is* what a catalyst is.
 *
 * ⚠️ **Added to an existing entry rather than appended beside it.** Photosynthesis already lists
 * `Algae to 1` in its products — the one net new unit — so appending would print `1 ALGAE` and
 * `100 ALGAE` as two separate chips on the same side, which reads as two different things.
 */
private fun withCatalyst(
    entries: List<Pair<Species, Int>>,
    catalyst: Species?,
    units: Int,
): List<Pair<Species, Int>> {
    if (catalyst == null) return entries
    // First on the side that did not already name it, so the two lines read as the one formula the
    // row is documented by: `100 ALGAE + 6 WATER + 6 CO₂ → 101 ALGAE + 6 OXYGEN`. The bloom is the
    // subject of the sentence on both sides, and burying it behind the reagents on one of them
    // would hide the only thing the reader has to compare.
    if (entries.none { it.first == catalyst }) return listOf(catalyst to units) + entries
    return entries.map { (species, n) -> if (species == catalyst) species to (n + units) else species to n }
}

/** Every reaction [species] is an ingredient of, coldest first — the cheapest thing to try first. */
fun reactionsConsuming(species: Species): List<ReactionInfo> =
    ALL_REACTIONS.filter { it.consumes(species) }.sortedBy { it.onsetKelvin }

/** Every reaction [species] comes out of, coldest first — the routes to making it. */
fun reactionsProducing(species: Species): List<ReactionInfo> =
    ALL_REACTIONS.filter { it.produces(species) }.sortedBy { it.onsetKelvin }


/**
 * What [species] is made of, richest element first: atoms per formula unit and share of the mass.
 *
 * Empty for an element, which is made of itself. The two numbers are both wanted and neither
 * substitutes for the other — the formula is what the reactions are written in, and the mass share
 * is what decides whether a rock is worth hauling ([massPartsPerThousand]'s argument).
 */
fun compositionOf(species: Species): List<ElementShare> {
    val formula = MINERALS[species] ?: return emptyList()
    val byMass = massPartsPerThousand(species)
    return formula.map { (element, atoms) -> ElementShare(element, atoms, byMass[element] ?: 0) }
        .sortedByDescending { it.partsPerThousand }
}

/** One element's place in a mineral's formula. */
class ElementShare(val element: Species, val atoms: Int, val partsPerThousand: Int)

/**
 * How much of a reference rock is [species], written the way the size of the number deserves.
 *
 * [Species.relativeAbundance] is parts per hundred million by mass, which is the right unit to
 * *store* — one scale across nine orders of magnitude, so forsterite and osmium are comparable
 * integers — and the wrong one to read. "49" says nothing; "490 ppb" says osmium is a part per
 * billion, which is the fact that decides whether it is worth chasing. So the unit is chosen per
 * value: percent down to a hundredth of a per cent, then parts per million, then parts per billion.
 *
 * Empty for anything that does not occur loose — see [occursNaturally], which is the more useful
 * statement about those and is made instead.
 */
fun abundanceOf(species: Species): String {
    val parts = species.relativeAbundance
    if (parts <= 0) return ""
    // Percent, to a tenth, while there is a tenth of a percent to see.
    if (parts >= 100_000) {
        val tenths = parts / 100_000
        return "${tenths / 10}.${tenths % 10}%"
    }
    if (parts >= 100) return "${parts / 100} ppm"
    return "${parts * 10} ppb"
}

/**
 * Whether [species] is something a rock can simply contain.
 *
 * ⚠️ **A "no" here is the interesting answer, not a missing one.** Almost every element in the game
 * has abundance zero, and that is the whole two-tier model: aluminium is far commoner than gold and
 * never occurs loose, so a player who wants it has to *make* it. Stating that is what turns an empty
 * abundance row into a direction to look — at [reactionsProducing], which is on the same page.
 */
val Species.occursNaturally: Boolean get() = relativeAbundance > 0

/**
 * Where [species] stands among everything a rock can contain — 1 is the commonest.
 *
 * A rank because a lone number in parts per hundred million is unreadable without the rest of the
 * table beside it, and the rest of the table is not something a panel can show. 0 for anything that
 * does not occur naturally.
 */
fun abundanceRank(species: Species): Int {
    if (!species.occursNaturally) return 0
    return Species.NATURAL.count { it.relativeAbundance > species.relativeAbundance } + 1
}
