package org.emerge.demo.cyto.sim

import kotlin.math.abs

/**
 * Throwaway measurement probe for the [CytoBiologyCore.passiveEnvExchange] parallelization decision
 * (see `docs/cyto-parallel-next-session.md`). Answers the two "measure before building" questions for the
 * DECIDED drop-contested approach:
 *   (a) contested fraction — how many leaves (and cells) would be dropped because ≥2 cells in the same
 *       exchange batch touch the same fine leaf (order-dependence ⇒ can't parallelize bit-identically), and
 *   (b) dropped-transfer magnitude — how much matter movement happens *on* those contested leaves and would
 *       therefore be skipped this tick, vs. the movement kept on single-owner leaves.
 *
 * Off in production (`enabled == false`); the exchange path checks the flag on the hot loop. NOT thread-safe
 * — it only runs on the sequential exchange path. A leaf is "contested" iff ≥2 batch-eligible cells touch it
 * (this is exactly the thread-count-independent contested set of the parallel design: any two cells sharing a
 * leaf force it contested whether they land in the same thread partition or different ones).
 */
object ExchangeProbe {
    var enabled = false

    // ── per-batch working state (rebuilt each passiveEnvExchange call) ──
    /** leaf identity → number of batch-eligible cells that touched it this batch. */
    val touchCount = HashMap<QuadNode, Int>()
    /** leaves with touchCount ≥ 2 this batch (order-dependent ⇒ dropped by the parallel plan). */
    val contested = HashSet<QuadNode>()

    // ── accumulators (summed over every measured batch) ──
    var batches = 0L
    var cells = 0L                // batch-eligible cells that actually exchanged (n>0)
    var contestedCells = 0L       // of those, cells whose footprint includes ≥1 contested leaf
    var leafTouches = 0L          // Σ footprint leaves with multiplicity (per-cell leaf counts summed)
    var distinctLeaves = 0L       // Σ distinct leaves touched per batch
    var contestedLeaves = 0L      // Σ contested (≥2-cell) leaves per batch
    var keptMag = 0L              // Σ |movement| on single-owner leaves (survives the parallel plan)
    var droppedMag = 0L           // Σ |movement| on contested leaves (skipped by the parallel plan)

    fun reset() {
        touchCount.clear(); contested.clear()
        batches = 0; cells = 0; contestedCells = 0
        leafTouches = 0; distinctLeaves = 0; contestedLeaves = 0
        keptMag = 0; droppedMag = 0
    }

    /** Called by [CytoMatterField.balanceBatched] per leaf-movement to attribute magnitude. */
    fun attribute(leaf: QuadNode, movement: Int) {
        if (movement == 0) return
        if (contested.contains(leaf)) droppedMag += abs(movement).toLong()
        else keptMag += abs(movement).toLong()
    }

    fun report(): String {
        val b = batches.coerceAtLeast(1)
        val leaves = distinctLeaves.coerceAtLeast(1)
        val c = cells.coerceAtLeast(1)
        val mag = (keptMag + droppedMag).coerceAtLeast(1)
        fun pct(n: Long, d: Long) = 100.0 * n / d
        return buildString {
            appendLine("  exchange-contention over $batches batches:")
            appendLine("    cells/batch=${cells / b}  contestedCells=${contestedCells} (${"%.1f".format(pct(contestedCells, c))}%)")
            appendLine("    distinctLeaves/batch=${distinctLeaves / b}  leafTouches/batch=${leafTouches / b}  (avg cells/leaf=${"%.2f".format(leafTouches.toDouble() / leaves)})")
            appendLine("    contestedLeaves=${contestedLeaves} of ${distinctLeaves} (${"%.1f".format(pct(contestedLeaves, leaves))}% of touched leaves)")
            appendLine("    transfer magnitude: kept=${keptMag}  dropped=${droppedMag}  → DROPPED ${"%.2f".format(pct(droppedMag, mag))}% of all movement")
        }
    }
}
