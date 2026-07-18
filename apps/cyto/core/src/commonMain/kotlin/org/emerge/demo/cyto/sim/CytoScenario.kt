package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType

/** How many founders of one [type] to seed. Only [CellType.Collector] (autotroph) and [CellType.Muscle]
 *  (heterotroph) carry a real starter genome today — other types spawn genome-less and quickly die, so the
 *  New/Custom UI only offers those two. [genome] overrides the type's preset genome when non-null (the
 *  campaign uses this to seed a hand-picked starter, e.g. a grow-only autotroph); null = the type default. */
data class FounderSpec(val type: CellType, val count: Int, val genome: List<Gene>? = null)

/** How founders are laid out across the torus. */
enum class Distribution {
    /** All founders near the world origin (the classic single-seed start; a colony that must spread). */
    Clustered,
    /** Founders scattered on a jittered grid across the whole world (independent colonies, contested midpoints). */
    Scattered,
}

/**
 * A **starting world recipe** the title-screen *New* flow builds a fresh sim from — the tunable/seed values a
 * player picks, separate from the fixed [CytoTuning] laws. The *geometry + day/night* fields drive
 * [CytoWorldConfig] (they resize the torus and reshape the light cycle); the *seed* fields drive
 * [createCytoInitialState] (matter reservoir level + which founders to place where).
 *
 * The [DEFAULT] recipe reproduces the historical hard-coded start exactly (so "New → Genesis" == the old
 * boot world). [PRESETS] are the base scenarios; [custom] is the editable starting point for the Custom screen.
 */
data class CytoScenario(
    val name: String,
    val worldSize: Int = CytoWorldConfig.DEFAULT_CELLS_PER_AXIS,
    val dayTicks: Long = 900L,
    val nightTicks: Long = 2700L,
    val matterLevel: Int = CytoSeed.MATTER_UNIFORM_LEVEL,
    val founders: List<FounderSpec> = listOf(FounderSpec(CellType.Collector, 1)),
    val distribution: Distribution = Distribution.Clustered,
    /** **Chemical aliases** for this world's curated genome (species token → display name, e.g. `rg` →
     *  "fuel"). Purely a readability aid layered over the built-in [SpeciesNames]; the sim never sees it.
     *  The campaign authors these so its molecules read as what they *do* in that genome. Empty = built-in
     *  names only. */
    val aliases: Map<String, String> = emptyMap(),
) {
    /** Total founders across all species — the count the layout scatters. */
    val founderCount: Int get() = founders.sumOf { it.count }

    companion object {
        /** Byte-for-byte the historical boot world (single autotroph at origin, standard matter + cycle). */
        val DEFAULT = CytoScenario(name = "Genesis")

        val PRESETS: List<CytoScenario> = listOf(
            DEFAULT,
            CytoScenario(
                name = "Twin Colonies",
                founders = listOf(FounderSpec(CellType.Collector, 2)),
                distribution = Distribution.Scattered,
            ),
            CytoScenario(
                name = "Long Nights",
                dayTicks = 700L, nightTicks = 4300L,
                matterLevel = (CytoSeed.MATTER_UNIFORM_LEVEL * 3) / 2,
            ),
            CytoScenario(
                name = "Wide World",
                worldSize = 128,
                founders = listOf(FounderSpec(CellType.Collector, 4)),
                distribution = Distribution.Scattered,
            ),
            CytoScenario(
                name = "Predator & Prey",
                founders = listOf(FounderSpec(CellType.Collector, 3), FounderSpec(CellType.Muscle, 1)),
                distribution = Distribution.Scattered,
                matterLevel = CytoSeed.MATTER_UNIFORM_LEVEL * 2,
            ),
        )

        /** The starting point the Custom screen edits (a copy of [DEFAULT] renamed). */
        val custom: CytoScenario get() = DEFAULT.copy(name = "Custom")
    }
}
