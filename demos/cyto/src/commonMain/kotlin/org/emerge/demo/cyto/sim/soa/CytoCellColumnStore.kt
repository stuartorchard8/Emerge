package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.Gene
import org.emerge.sim.core.ecs.soa.ColumnStore

/**
 * Dense primitive columns for the per-cell biology that the engine physics schemas don't
 * cover. Chemistry is **energy-only on the fast path**: every cell carries a single dense
 * [energy]/[energyPending] column, which is the case for every cell type in the benchmark and
 * growing-colony scenarios (Blank/Support/Stem produce no enzymes, so no reactions ever mint a
 * second species). Cells that *do* carry extra chemicals are handled by an object side-table in
 * [CytoChemistry], keyed by EntityId — this store keeps only the bounded dense state.
 *
 * [scatter]/[gather] bridge to [CytoCellComponent] for the loader/snapshot/compat paths; the
 * reducer reads the public field arrays directly via a slot index.
 */
class CytoCellColumnStore : ColumnStore<CytoCellComponent> {
    var energy = FloatArray(0); private set
    var energyPending = FloatArray(0); private set
    var logicalRadius = FloatArray(0); private set
    var divideCooldown = FloatArray(0); private set
    var touch = FloatArray(0); private set
    var type = IntArray(0); private set       // CellType.ordinal
    var sticky = BooleanArray(0); private set
    var stickyTemp = BooleanArray(0); private set
    // The per-cell genome — a reference column (the one non-primitive). Preset genomes are shared
    // immutable lists, so a cell holds a reference, not a copy; null is read as the empty genome.
    var genome = arrayOfNulls<List<Gene>>(0); private set

    override fun ensureCapacity(capacity: Int) {
        if (energy.size >= capacity) return
        energy = energy.copyOf(capacity); energyPending = energyPending.copyOf(capacity)
        logicalRadius = logicalRadius.copyOf(capacity); divideCooldown = divideCooldown.copyOf(capacity)
        touch = touch.copyOf(capacity); type = type.copyOf(capacity)
        sticky = sticky.copyOf(capacity); stickyTemp = stickyTemp.copyOf(capacity)
        genome = genome.copyOf(capacity)
    }

    override fun scatter(slot: Int, value: CytoCellComponent) {
        energy[slot] = value.chemicals[ENERGY] ?: 0f
        energyPending[slot] = value.pendingTransfers[ENERGY] ?: 0f
        logicalRadius[slot] = value.logicalRadius
        divideCooldown[slot] = value.divideCooldown
        touch[slot] = value.touch
        type[slot] = value.type.ordinal
        sticky[slot] = value.sticky
        stickyTemp[slot] = value.stickyTemp
        genome[slot] = value.genome
    }

    override fun gather(slot: Int): CytoCellComponent = CytoCellComponent(
        type = CellType.entries[type[slot]],
        chemicals = mapOf(ENERGY to energy[slot]),
        logicalRadius = logicalRadius[slot],
        divideCooldown = divideCooldown[slot],
        sticky = sticky[slot],
        pendingTransfers = mapOf(ENERGY to energyPending[slot]),
        touch = touch[slot],
        stickyTemp = stickyTemp[slot],
        genome = genome[slot] ?: emptyList(),
    )

    override fun moveSlot(dst: Int, src: Int) {
        energy[dst] = energy[src]; energyPending[dst] = energyPending[src]
        logicalRadius[dst] = logicalRadius[src]; divideCooldown[dst] = divideCooldown[src]
        touch[dst] = touch[src]; type[dst] = type[src]
        sticky[dst] = sticky[src]; stickyTemp[dst] = stickyTemp[src]
        genome[dst] = genome[src]
    }

    companion object {
        const val ENERGY = "energy"
    }
}
