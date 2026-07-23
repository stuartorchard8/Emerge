package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.cells.CellType

/**
 * A read-only snapshot of the world the campaign's [Gate.World] predicates run against. Assembled once
 * per frame by the host from [org.emerge.demo.cyto.CytoController.worldStats] plus the host-owned UI
 * flags. Flat pass-through accessors keep authored predicates terse (`it.cellCount`, `it.maxBiomass`).
 */
class CampaignQuery(
    val stats: WorldStats,
    val paused: Boolean,
    val selectedGenome: String?,
) {
    val tick: Long get() = stats.tick
    val cellCount: Int get() = stats.cellCount
    val maxBiomass: Int get() = stats.maxBiomass
    /** The cell selected **right now**, or null. Ask this only about live selection ("click your cell") —
     *  for anything about what the player has BUILT, ask [lineage], which outlives both the click and the
     *  cell. */
    val focused: FocusedCell? get() = stats.focused

    /** What the player has built — see [Lineage]. Null only before they have authored anything at all. */
    val lineage: Lineage? get() = stats.lineage

    /** Nothing alive. The campaign treats this as a recoverable state, not a dead end: the player's genome
     *  outlives their cells ([Lineage]), so the coach can offer to put it back. */
    val extinct: Boolean get() = stats.cellCount == 0

    fun countOf(type: CellType): Int = stats.countByType[type] ?: 0
}

/** Sim-derived world facts, produced by a single scan of the cell component table (mirrors what
 *  `readouts()` / `heldCellInfo()` already do). No host/UI state here. */
class WorldStats(
    val tick: Long,
    val cellCount: Int,
    val countByType: Map<CellType, Int>,
    val maxBiomass: Int,
    val speciesPresent: Set<String>,
    val focused: FocusedCell?,
    val lineage: Lineage? = null,
) {
    companion object {
        val EMPTY = WorldStats(0L, 0, emptyMap(), 0, emptySet(), null)
    }
}

/**
 * **What the player has built** — the genome, read as the campaign cares about it.
 *
 * Deliberately separate from [FocusedCell], and deliberately not derived from the selected cell: a chapter
 * goal like "add a MITOSIS gene" is a fact about the player's work, and it must not stop being true because
 * they clicked away, or because the cell they wrote it on has since died. Every field here is a function of
 * a gene list alone, which is exactly why it can outlive any particular cell.
 *
 * Sourced (in order) from the selected cell's genome, the player's last authored genome, then the largest
 * surviving cell's — see `CytoController.worldStats`.
 */
class Lineage(
    val geneCount: Int,
    /** The species the first CONVERT gene locks into biomass, or null if there is no CONVERT gene. Empty
     *  string = the gene exists but its chemical is unset. Also the coach's `{chem}` token. */
    val convertChem: String? = null,
    /** The chemical product of the first CONVERT gene's energy source, or null if there is no CONVERT gene or the
     * energy source is Light. Empty string = the gene uses bond but its bond chemical is unset. */
    val convertProduct: String? = null,
    /** The tightest `Biomass < N` ceiling on a CONVERT gene — the growth cap. Null = grows without limit. */
    val convertBiomassCap: Int? = null,
    /** The tightest `Biomass > N` floor on a DIVIDE gene — the divide cap. Null = divide uncontrollably. */
    val divideBiomassMinimum: Int? = null,
    /** Carries a division gene at all, whatever powers it. */
    val hasDivide: Boolean = false,
    /** Carries a light powered bond break for the campaign waste chemical. */
    val hasPhotosynthesis: Boolean = false,
    /** True if a division gene leaves its daughters **welded** (sever off). */
    val divideWelds: Boolean = false,
    /** What the division gene synthesises for energy: null = none, or still on Light; "" = bonding but the
     *  pair is incomplete; else the product. Also the coach's `{bond}` token. */
    val mitosisProduct: String? = null,
    /** Whether the division gene's fuel reaction consumes the very monomer the CONVERT gene grows on — the
     *  campaign's branch point. Null when there is nothing to compare. */
    val divideFuelConflicts: Boolean? = null,
    /** A Contract ("muscle") gene powered by chemistry rather than Light. */
    val contractOnChem: Boolean = false,
    /** A Contract gene that fires while the `bb` morphogen is PRESENT rather than absent. */
    val contractOnMarked: Boolean = false,
)

/** A cell reduced to what a headless (CPU) renderer / agent view needs: logical position + radius, type,
 *  size, and whether it's the selected cell. Produced by `CytoController.agentCells()`. */
class AgentCell(
    val id: Int,
    val x: Float,
    val y: Float,
    val radius: Float,
    val type: CellType,
    val biomass: Int,
    val selected: Boolean,
)

/**
 * The currently-selected cell — its **live state**, the part that is genuinely about this cell right now
 * and not about the genome it runs. Anything genome-shaped lives on [Lineage] instead, so a gate asking
 * what the player built keeps working when nothing is selected (or nothing is alive).
 */
class FocusedCell(
    val type: CellType,
    val biomass: Int,
    val cytoplasm: Map<String, Int>,
)
