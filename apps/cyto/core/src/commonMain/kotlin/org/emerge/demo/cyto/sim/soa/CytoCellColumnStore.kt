package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.MoleculeStore
import org.emerge.demo.cyto.sim.SpeciesRegistry
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
 *
 * **Double-buffered chemistry (column-slab swap):** cytoplasm/biomass are stored as front + back
 * slab buffers (genome is single-buffered — see [swapBuffers]). Each tick: (1) the biology phase copies
 * front → back for each live slot and hands the back store to CellWork by reference, (2) genes mutate the
 * back buffer in place via those references, (3) `swapBuffers()` swaps front↔back in O(count) — this is the
 * write-back barrier that commits all mutations atomically. `moveSlot()` synchronizes both buffers
 * during compaction so slot identity is preserved across swaps.
 */
class CytoCellColumnStore : ColumnStore<CytoCellComponent> {
    var logicalRadius = LongArray(0); private set    // Frac raw
    var wear = IntArray(0); private set
    var type = IntArray(0); private set              // CellType.ordinal
    var sticky = BooleanArray(0); private set
    var stickyTemp = BooleanArray(0); private set
    var genome = arrayOfNulls<List<Gene>>(0); private set

    // Molecule counts: id-keyed stores (held by reference).
    var cytoplasm = arrayOfNulls<MoleculeStore>(0); private set
    var biomass = arrayOfNulls<MoleculeStore>(0); private set

    // Visual read-model (single-buffered, front only): per-species CYT→BIO built this tick, written
    // straight into the front column at the biology writeback barrier (not double-buffered — it's a
    // derived per-tick signal, never read back into sim state). Published via [gather] for the renderer.
    var cytToBio = arrayOfNulls<MoleculeStore>(0); private set
    // Visual read-model (front only): BIO→ENV decay this tick — a single species id + count (degrade
    // sheds one species per tick). speciesId = -1 / count = 0 means nothing decayed. Same lifecycle.
    var bioToEnvSpecies = IntArray(0); private set
    var bioToEnvCount = IntArray(0); private set

    // Double-buffer: back buffers swapped with front at the biology write-back barrier.
    // CellWork.reset() reads front → mutates CellWork's back buffer; writeback() swaps.
    // Genome is NOT double-buffered: genes only READ it, and the mutation system writes the
    // mutated genome directly to the front buffer after biology completes (see BiologySystem).
    var backCytoplasm = arrayOfNulls<MoleculeStore>(0); private set
    var backBiomass = arrayOfNulls<MoleculeStore>(0); private set

    override fun ensureCapacity(capacity: Int) {
        if (logicalRadius.size >= capacity) return
        logicalRadius = logicalRadius.copyOf(capacity)
        wear = wear.copyOf(capacity)
        type = type.copyOf(capacity)
        sticky = sticky.copyOf(capacity)
        stickyTemp = stickyTemp.copyOf(capacity)
        genome = genome.copyOf(capacity)
        cytoplasm = cytoplasm.copyOf(capacity)
        biomass = biomass.copyOf(capacity)
        cytToBio = cytToBio.copyOf(capacity)
        bioToEnvSpecies = bioToEnvSpecies.copyOf(capacity)
        bioToEnvCount = bioToEnvCount.copyOf(capacity)
        backCytoplasm = backCytoplasm.copyOf(capacity)
        backBiomass = backBiomass.copyOf(capacity)
    }

    /** Ensure back buffers have the given capacity (null-initialized). */
    fun ensureCapacityBack(capacity: Int) {
        if (backCytoplasm.size >= capacity) return
        backCytoplasm = backCytoplasm.copyOf(capacity)
        backBiomass = backBiomass.copyOf(capacity)
    }

    /**
     * Swap front↔back buffer references for cytoplasm and biomass columns.
     * Called once at the biology write-back barrier — after all cells' genes have mutated
     * the back buffer and scalar state has been committed. O(count) pointer swap. [count] is the
     * caller's live entity count (world.count); every live slot's back buffer must have been seeded
     * this tick. Genome is deliberately excluded: it is not double-buffered (genes only read it; the
     * mutation system writes the mutated genome straight to the front buffer).
     */
    fun swapBuffers(count: Int) {
        for (i in 0 until count) {
            val tmp = cytoplasm[i]; cytoplasm[i] = backCytoplasm[i]; backCytoplasm[i] = tmp
            val tmpBio = biomass[i]; biomass[i] = backBiomass[i]; backBiomass[i] = tmpBio
        }
    }

    override fun scatter(slot: Int, value: CytoCellComponent) {
        logicalRadius[slot] = value.logicalRadius.raw
        wear[slot] = value.wear
        type[slot] = value.type.ordinal
        sticky[slot] = value.sticky
        stickyTemp[slot] = value.stickyTemp
        cytoplasm[slot] = MoleculeStore.of(value.cytoplasm, CytoTuning.CELL_CHEM_CAP)
        biomass[slot] = MoleculeStore.of(value.biomass, CytoTuning.CELL_CHEM_CAP)
        // Derived visual signal: not persisted through scatter (starts empty; refilled each tick at writeback).
        cytToBio[slot] = null
        bioToEnvSpecies[slot] = -1
        bioToEnvCount[slot] = 0
        genome[slot] = value.genome
        // Also initialize back buffers — the swap will pick them up.
        // For freshly-scattered entities (spawn), the back buffer starts as a copy.
        ensureCapacityBack(slot + 1)
        backCytoplasm[slot] = MoleculeStore.of(value.cytoplasm, CytoTuning.CELL_CHEM_CAP)
        backBiomass[slot] = MoleculeStore.of(value.biomass, CytoTuning.CELL_CHEM_CAP)
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
        cytToBio = cytToBio[slot]?.toStringMap() ?: emptyMap(),
        bioToEnv = if (bioToEnvCount[slot] > 0 && bioToEnvSpecies[slot] >= 0)
            mapOf(SpeciesRegistry.string(bioToEnvSpecies[slot]) to bioToEnvCount[slot]) else emptyMap(),
    )

    override fun moveSlot(dst: Int, src: Int) {
        logicalRadius[dst] = logicalRadius[src]
        wear[dst] = wear[src]
        type[dst] = type[src]
        sticky[dst] = sticky[src]
        stickyTemp[dst] = stickyTemp[src]
        genome[dst] = genome[src]
        cytoplasm[dst] = cytoplasm[src]
        biomass[dst] = biomass[src]
        cytToBio[dst] = cytToBio[src]
        bioToEnvSpecies[dst] = bioToEnvSpecies[src]
        bioToEnvCount[dst] = bioToEnvCount[src]
        // Also move the back-buffer references so compaction stays in sync.
        backCytoplasm[dst] = backCytoplasm[src]
        backBiomass[dst] = backBiomass[src]
    }
}
