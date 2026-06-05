package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.sim.CytoConfig

/**
 * Applies the structural changes the SoA pipeline requests (weld / division / death) to a
 * [CytoWorld] at the lifecycle barrier — the SoA analogue of `CytoLifecycleSystem`. Structural
 * edits batch here so the hot phases see a stable slot layout within a tick; daughters append
 * (largest id, order preserved), dead cells tombstone, and the CSR rebuilds once when changed.
 *
 * NOTE: full implementation lands with the growing-colony gate; for now the settled colony
 * never raises an intent, so this asserts emptiness rather than silently dropping work.
 */
class CytoLifecycle(private val cfg: CytoConfig) {
    fun apply(
        w: CytoWorld,
        weldLo: List<Int>,
        weldHi: List<Int>,
        divideIds: List<Int>,
        destroyIds: List<Int>,
    ) {
        if (weldLo.isNotEmpty() || divideIds.isNotEmpty() || destroyIds.isNotEmpty()) {
            throw NotImplementedError(
                "SoA lifecycle (weld/divide/destroy) lands with the growing-colony gate; " +
                    "welds=${weldLo.size} divides=${divideIds.size} destroys=${destroyIds.size}",
            )
        }
    }
}
