package org.emerge.demo.outofspace.chem

/**
 * Material form (orthogonal to composition). Declaration order = tie-break + serialisation order.
 * Powder (Os/Slag) merges on contact; discrete forms (ingots/crystals) stay separate.
 */
enum class Form {
    /** Raw blended rock, straight out of the ground. The only form that is normally impure. */
    Ore,

    /** The waste stream of refining. Can be re-processed; it is ore with the good bits taken out. */
    Slag,

    // ── Tier 1: smelted from ore, one per species ──
    IronIngot,
    AluminumIngot,
    CopperIngot,
    TitaniumIngot,
    SiliconCrystal,
    CarbonFiber,
    RareEarthPowder,
    EnrichedUranium,
    ;

    /** Powder (heap, no internal structure) vs discrete object. Powders merge on conveyor; discrete forms stay separate. */
    val isPowder: Boolean get() = this == Ore || this == Slag

    companion object {
        val ALL: List<Form> = entries.toList()
    }
}

/**
 * What smelting a mixture yields, chosen by its dominant species.
 *
 * ⚠️ **This map is a stopgap, and it is now visibly one.** It used to be true that "every mineral has
 * an entry"; with the species table rebuilt around real minerals there are over a hundred, and
 * enumerating a `Form` for each is not the answer — the answer is that smelting should *decompose* a
 * mineral into its elements by molar mass, which is what `chem/Minerals.kt` holds the formulae for
 * and what [smelt] does not yet do.
 *
 * Until that lands, this keeps the existing eight products working by naming the species that
 * actually reach a smelter today. [Species.Quartz] stands where `Silica` did — same substance, same
 * numbers, a mineral's name — and [Species.Monazite] where the fictional `RareEarth` did, because a
 * rare-earth phosphate is the thing you would really be feeding in. Everything else smelts to slag,
 * which is the honest outcome for an ore no process exists for yet.
 */
val SMELT_PRODUCTS: Map<Species, Form> = mapOf(
    Species.Iron to Form.IronIngot,
    Species.Aluminum to Form.AluminumIngot,
    Species.Copper to Form.CopperIngot,
    Species.Titanium to Form.TitaniumIngot,
    Species.Quartz to Form.SiliconCrystal,
    Species.Carbon to Form.CarbonFiber,
    Species.Monazite to Form.RareEarthPowder,
    Species.Uranium to Form.EnrichedUranium,
)
