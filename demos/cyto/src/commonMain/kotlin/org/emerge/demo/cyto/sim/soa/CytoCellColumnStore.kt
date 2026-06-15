package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.MoleculeStore
import org.emerge.sim.core.ecs.soa.ColumnStore
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Dense columns for the matter-model per-cell biology that the engine physics schemas don't cover
 * ([CytoCellComponent]). The scalar state is unboxed primitive arrays; the molecule counts are held
 * id-keyed in a [MoleculeStore] per slot (dense chemistry) and the genome stays an object column.
 *
 * [scatter]/[gather] bridge to [CytoCellComponent], converting the id-keyed stores to/from its
 * string-keyed maps — a cold boundary (loader / snapshot / lifecycle); the hot biology phase reads the
 * stores directly by slot index and never touches strings.
 */
class CytoCellColumnStore : ColumnStore<CytoCellComponent> {
    var logicalRadius = LongArray(0); private set    // Frac raw
    var wear = IntArray(0); private set
    var type = IntArray(0); private set              // CellType.ordinal
    var sticky = BooleanArray(0); private set
    var stickyTemp = BooleanArray(0); private set
    // Molecule counts: id-keyed stores (held by reference; the reducer reassigns per tick). The genome
    // stays an object column (immutable per tick), so round-trip is exact.
    var cytoplasm = arrayOfNulls<MoleculeStore>(0); private set
    var biomass = arrayOfNulls<MoleculeStore>(0); private set
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
        cytoplasm[slot] = MoleculeStore.of(value.cytoplasm)
        biomass[slot] = MoleculeStore.of(value.biomass)
        genome[slot] = value.genome
    }

    override fun gather(slot: Int): CytoCellComponent = CytoCellComponent(
        type = CellType.entries[type[slot]],
        logicalRadius = Frac(logicalRadius[slot]),
        cytoplasm = cytoplasm[slot]?.toStringMap() ?: emptyMap(),
        biomass = biomass[slot]?.toStringMap() ?: emptyMap(),
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
