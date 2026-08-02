package org.emerge.demo.outofspace.chem

/**
 * What a pile of matter has been *made into*. Orthogonal to what it is made *of*: a
 * [Form.IronIngot] still carries the exact species that went into it, impurities and all.
 *
 * As with [Species], declaration order is part of the contract — it is the tie-break and the
 * serialisation order. Append, do not reorder.
 *
 * ### Difference from the Godot recipe table
 * The original had a first tier of recipes taking named ores (`iron_ore + carbon_ore → iron_ingot`)
 * *and* a `smelt` that turned blended ore into an ingot by its dominant species. Those are two
 * answers to the same question, and the blended one is the better game: it makes ore quality matter
 * everywhere instead of at a lookup. So the named `*_ore` forms are gone and smelting is the only
 * way into tier one. Everything from alloys upward is unchanged.
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

    /**
     * Whether this form is a **powder** — a heap with no internal structure — rather than a discrete
     * object.
     *
     * The difference is what happens when two lots of it meet on a conveyor. Tip two piles of ore
     * together and you have one pile of ore, at a purity in between, and no way back: that is what
     * powder *is*. Put two ingots on the same belt and they are still two ingots; they can share a
     * line all day and be told apart at the end of it.
     *
     * So this decides whether material bunching up against a blockage may merge. It is the reason
     * routing 41% ore into a line carrying 75% concentrate is a genuine mistake with a genuine cost —
     * the refining that separated them is undone by the two touching — while sending ingots of four
     * different metals down one belt is merely untidy.
     *
     * Only the two blended forms qualify. Everything from tier one upward has been *made* into
     * something, and being made into something is exactly what stops it flowing back together.
     */
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
