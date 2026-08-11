package org.emerge.demo.fluidlab.chem

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
 * Every *mineral* has an entry; fluids do not, so smelting something mostly water yields slag rather
 * than an exception. The other way smelting fails is the ore simply being too impure to be worth
 * refining — see [smelt].
 */
val SMELT_PRODUCTS: Map<Species, Form> = mapOf(
    Species.Iron to Form.IronIngot,
    Species.Aluminum to Form.AluminumIngot,
    Species.Copper to Form.CopperIngot,
    Species.Titanium to Form.TitaniumIngot,
    Species.Silica to Form.SiliconCrystal,
    Species.Carbon to Form.CarbonFiber,
    Species.RareEarth to Form.RareEarthPowder,
    Species.Uranium to Form.EnrichedUranium,
)
