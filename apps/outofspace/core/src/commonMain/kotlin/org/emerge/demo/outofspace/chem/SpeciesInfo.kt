package org.emerge.demo.outofspace.chem

/**
 * Everything the game knows about one species, in the shape a reader wants it — what it is made of,
 * and every reaction it takes part in either end of.
 *
 * ⛔ **A view over the tables, never a second copy of them.** The three reaction tables are three
 * classes because they are three different mechanisms — heat alone, heat with air, heat with a solid
 * reagent — and each of them is arranged for the sweep that runs it: [DECOMPOSITION_OF] is indexed
 * by ordinal for the tick loop, [REDUCTION_GROUPS] is grouped by the reagent they contend for. None
 * of those shapes answers "what happens to magnesite?", which is the only question a player asks. So
 * this file flattens all three into one row type and looks *up* from a species, and it derives that
 * flattening rather than restating it: a reaction added to any table appears here without anybody
 * remembering to add it, which is [MINERALS]' argument and `MineralTest`'s.
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
    /** Positive is **endothermic**, the sign convention of all three tables. */
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
 * Order is table order — oxidations, then decompositions, then reductions — which is arbitrary and
 * only has to be stable, since this list is read by a panel and never by the sim.
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
                inputs = listOf(r.oxide to r.oxideUnits, r.reductant to r.reductantUnits),
                products = r.products,
                onsetKelvin = r.onsetKelvin,
                enthalpyPerKg = r.enthalpyPerKg,
            ),
        )
    }
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
