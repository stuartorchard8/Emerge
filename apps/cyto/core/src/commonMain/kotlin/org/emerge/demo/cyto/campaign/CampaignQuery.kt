package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.cells.CellType

/**
 * A read-only snapshot of the world the campaign's [Gate.World] predicates run against. Assembled once
 * per frame by the host from [org.emerge.demo.cyto.CytoController.worldStats] plus the host-owned UI
 * flags. Flat pass-through accessors keep authored predicates terse (`it.cellCount`, `it.maxBiomass`).
 */
class CampaignQuery(
    val stats: WorldStats,
    val matterOverlayOn: Boolean,
    val paused: Boolean,
    val selectedGenome: String?,
) {
    val tick: Long get() = stats.tick
    val cellCount: Int get() = stats.cellCount
    val maxBiomass: Int get() = stats.maxBiomass
    val focused: FocusedCell? get() = stats.focused
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
) {
    companion object {
        val EMPTY = WorldStats(0L, 0, emptyMap(), 0, emptySet(), null)
    }
}

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

/** The currently-selected cell, if any — the fields campaign objectives care about. */
class FocusedCell(
    val type: CellType,
    val biomass: Int,
    val geneCount: Int,
    val cytoplasm: Map<String, Int>,
)
