package org.emerge.demo.outofspace.chem

/**
 * What a pile of matter has been *made into*. Orthogonal to what it is made *of*: a
 * [Form.SteelAlloy] still carries the exact species that went into it, impurities and all.
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

    // ── Tier 2: alloys and composites ──
    SteelAlloy,
    AluminumAlloy,
    TitaniumAlloy,
    CopperWire,
    SiliconWafer,
    CarbonComposite,
    Superconductor,
    NuclearCell,

    // ── Tier 3: components ──
    StructuralFrame,
    FuelTank,
    HeatShielding,
    ElectricalSystem,
    GuidanceSystem,
    LifeSupport,
    Thruster,
    ReactionWheel,
    DistanceSensor,
    Fabricator,

    // ── Tier 4: major systems ──
    PropulsionSystem,
    CommandModule,
    PowerSystem,
    RocketStructure,

    // ── Tier 5: whole-vessel assemblies ──
    RocketBody,
    ControlSystems,
    Vessel,
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

/**
 * Every recipe, as `output to (inputA, inputB)`. Order of the two inputs is not significant — see
 * [recipeFor].
 *
 * The tree is deliberately binary the whole way up: two things go in, one comes out. It keeps the
 * crafting UI trivial and it means a fabricator never needs an ingredient list.
 */
val RECIPES: Map<Form, Pair<Form, Form>> = mapOf(
    // Tier 2 — alloys and composites
    Form.SteelAlloy to (Form.IronIngot to Form.CarbonFiber),
    Form.AluminumAlloy to (Form.AluminumIngot to Form.TitaniumIngot),
    Form.TitaniumAlloy to (Form.TitaniumIngot to Form.RareEarthPowder),
    Form.CopperWire to (Form.CopperIngot to Form.SiliconCrystal),
    Form.SiliconWafer to (Form.SiliconCrystal to Form.RareEarthPowder),
    Form.CarbonComposite to (Form.CarbonFiber to Form.TitaniumAlloy),
    Form.Superconductor to (Form.RareEarthPowder to Form.CopperWire),
    Form.NuclearCell to (Form.EnrichedUranium to Form.TitaniumAlloy),

    // Tier 3 — components
    Form.StructuralFrame to (Form.SteelAlloy to Form.AluminumAlloy),
    Form.FuelTank to (Form.AluminumAlloy to Form.AluminumAlloy),
    Form.HeatShielding to (Form.CarbonComposite to Form.TitaniumAlloy),
    Form.ElectricalSystem to (Form.CopperWire to Form.CopperWire),
    Form.GuidanceSystem to (Form.SiliconWafer to Form.Superconductor),
    Form.LifeSupport to (Form.AluminumAlloy to Form.ElectricalSystem),
    Form.Thruster to (Form.SteelAlloy to Form.HeatShielding),
    Form.ReactionWheel to (Form.SteelAlloy to Form.ElectricalSystem),
    Form.DistanceSensor to (Form.SiliconWafer to Form.CopperWire),
    Form.Fabricator to (Form.TitaniumAlloy to Form.ElectricalSystem),

    // Tier 4 — major systems
    Form.PropulsionSystem to (Form.Thruster to Form.FuelTank),
    Form.CommandModule to (Form.GuidanceSystem to Form.LifeSupport),
    Form.PowerSystem to (Form.NuclearCell to Form.ElectricalSystem),
    Form.RocketStructure to (Form.StructuralFrame to Form.HeatShielding),

    // Tier 5 — whole-vessel assemblies
    Form.RocketBody to (Form.PropulsionSystem to Form.RocketStructure),
    Form.ControlSystems to (Form.CommandModule to Form.PowerSystem),
    Form.Vessel to (Form.RocketBody to Form.ControlSystems),
)

/** Reverse index, built once: an unordered input pair → the form it makes. */
private val RECIPES_BY_INPUTS: Map<Long, Form> =
    RECIPES.entries.associate { (output, inputs) -> recipeKey(inputs.first, inputs.second) to output }

/** The form made by combining [a] and [b] in either order, or null if that is not a recipe. */
fun recipeFor(a: Form, b: Form): Form? = RECIPES_BY_INPUTS[recipeKey(a, b)]

/** Order-independent key for a pair of forms. */
private fun recipeKey(a: Form, b: Form): Long {
    val lo = minOf(a.ordinal, b.ordinal).toLong()
    val hi = maxOf(a.ordinal, b.ordinal).toLong()
    return lo * Form.ALL.size + hi
}
