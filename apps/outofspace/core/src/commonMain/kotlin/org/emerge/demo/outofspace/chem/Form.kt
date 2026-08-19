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
