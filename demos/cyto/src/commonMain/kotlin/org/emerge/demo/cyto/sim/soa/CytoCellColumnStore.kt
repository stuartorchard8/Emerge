package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.Gene
import org.emerge.sim.core.ecs.soa.ColumnStore
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Dense columns for the matter-model per-cell biology that the engine physics schemas don't cover
 * ([CytoCellComponent]). The scalar state is unboxed primitive arrays; the variable-size molecule
 * maps and the genome stay **object columns** (`Array<…?>`) for now — the species set is bounded
 * (no polymerisation, MORPHOGENESIS.md) so these will later become interned-int columns, but that
 * rework is deferred: it doesn't help the scale bottleneck (physics + per-tick copy churn), and
 * holding the maps by reference keeps the AoS↔SoA round-trip exact and the SoA landing low-risk.
 *
 * [scatter]/[gather] bridge to [CytoCellComponent] (loader / snapshot / compat); ported hot phases
 * read the public field arrays directly by slot index.
 */
class CytoCellColumnStore : ColumnStore<CytoCellComponent> {
    var logicalRadius = LongArray(0); private set    // Frac raw
    var wear = IntArray(0); private set
    var type = IntArray(0); private set              // CellType.ordinal
    var sticky = BooleanArray(0); private set
    var stickyTemp = BooleanArray(0); private set
    // Object columns: held by reference (the maps/lists are immutable per tick), so round-trip is exact.
    var cytoplasm = arrayOfNulls<Map<String, Int>>(0); private set
    var biomass = arrayOfNulls<Map<String, Int>>(0); private set
    var genome = arrayOfNulls<List<Gene>>(0); private set

    override fun ensureCapacity(capacity: Int) {
        if (logicalRadius.size >= capacity) return
        logicalRadius = logicalRadius.copyOf(capacity)
        wear = wear.copyOf(capacity)
        type = type.copyOf(capacity)
        sticky = sticky.copyOf(capacity)
        stickyTemp = stickyTemp.copyOf(capacity)
        cytoplasm = cytoplasm.copyOf(capacity)
        biomass = biomass.copyOf(capacity)
        genome = genome.copyOf(capacity)
    }

    override fun scatter(slot: Int, value: CytoCellComponent) {
        logicalRadius[slot] = value.logicalRadius.raw
        wear[slot] = value.wear
        type[slot] = value.type.ordinal
        sticky[slot] = value.sticky
        stickyTemp[slot] = value.stickyTemp
        cytoplasm[slot] = value.cytoplasm
        biomass[slot] = value.biomass
        genome[slot] = value.genome
    }

    override fun gather(slot: Int): CytoCellComponent = CytoCellComponent(
        type = CellType.entries[type[slot]],
        logicalRadius = Frac(logicalRadius[slot]),
        cytoplasm = cytoplasm[slot] ?: emptyMap(),
        biomass = biomass[slot] ?: emptyMap(),
        genome = genome[slot] ?: emptyList(),
        wear = wear[slot],
        sticky = sticky[slot],
        stickyTemp = stickyTemp[slot],
    )

    override fun moveSlot(dst: Int, src: Int) {
        logicalRadius[dst] = logicalRadius[src]
        wear[dst] = wear[src]
        type[dst] = type[src]
        sticky[dst] = sticky[src]
        stickyTemp[dst] = stickyTemp[src]
        cytoplasm[dst] = cytoplasm[src]
        biomass[dst] = biomass[src]
        genome[dst] = genome[src]
    }
}
