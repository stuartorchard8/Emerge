package org.emerge.demo.cyto.sim

/**
 * Throwaway fine-grained accumulator for the two biology hot phases ([CytoBiologyCore.passiveEnvExchange]
 * and [CytoBiologyCore.runGenes]) — the level below the reducer's `bio:*` profiler splits. Off in
 * production (the `stats` params default null); the reducer threads one in only when explicitly given, the
 * benchmark resets it after warmup and prints [summary] after the measure window.
 *
 * NOT thread-safe — like [org.emerge.sim.core.ecs.PipelineProfiler], it assumes the single-threaded biology
 * path (bioParallelThreshold left at its default OFF). Times are summed nanos over the window; counts are
 * summed events; [summary] divides by [ticks] for per-tick figures.
 */
class BioProfile {
    var ticks = 0L

    // ── passiveEnvExchange ──
    var exchGroupNanos = 0L       // building the by-grid-cell grouping (+ species-union HashSets)
    var exchSpeciesNanos = 0L     // the per-species exchangeSpecies inner loops
    var exchGridCells = 0L        // Σ non-empty grid-cells visited
    var exchSpeciesCalls = 0L     // Σ exchangeSpecies calls (= Σ distinct species per grid-cell)
    var exchCellIters = 0L        // Σ (species × cells) iterations — the core exchange work
    var exchMaxCellsInCell = 0L   // max cells sharing one grid-cell (clustering peak)
    var exchUseful = 0L           // (species × cell) visits that actually exchanged (leaker or absorber)
    var exchNoop = 0L             // visits where the cell neither holds nor can-hold the species (pure waste)
    var exchGridSpecies = 0L      // Σ grid-reservoir species per grid-cell (the env-origin half of the union)

    // ── runGenes ──
    var genesCells = 0L           // runGenes invocations (≈ population)
    var genesScanned = 0L         // Σ genes examined by the isActive scan
    var genesActive = 0L          // Σ applyGene calls (active genes)
    var genesIsActiveNanos = 0L   // time in the isActive gating scan
    var genesApplyNanos = 0L      // time in the applyGene loop
    var richestBondCalls = 0L     // Σ richestWithBond scans (BreakBond fuel pick)
    var wildcardCalls = 0L        // Σ richestEndingWith/StartingWith scans (FormBond wildcard string match)

    fun reset() {
        ticks = 0L
        exchGroupNanos = 0L; exchSpeciesNanos = 0L
        exchGridCells = 0L; exchSpeciesCalls = 0L; exchCellIters = 0L; exchMaxCellsInCell = 0L
        exchUseful = 0L; exchNoop = 0L; exchGridSpecies = 0L
        genesCells = 0L; genesScanned = 0L; genesActive = 0L
        genesIsActiveNanos = 0L; genesApplyNanos = 0L
        richestBondCalls = 0L; wildcardCalls = 0L
    }

    fun summary(): String {
        val t = ticks.coerceAtLeast(1)
        fun us(n: Long) = n / 1000 / t
        return buildString {
            appendLine("  bio-profile over $ticks ticks (per-tick):")
            appendLine("    exchange: group=${us(exchGroupNanos)}us species=${us(exchSpeciesNanos)}us")
            appendLine("              gridCells=${exchGridCells / t}  speciesCalls=${exchSpeciesCalls / t}  cellIters=${exchCellIters / t}  maxCellsPerGridCell=$exchMaxCellsInCell")
            appendLine("              useful=${exchUseful / t}  noop=${exchNoop / t}  gridSpecies=${exchGridSpecies / t}  (cytoOnlySpeciesCalls=${(exchSpeciesCalls - exchGridSpecies) / t})")
            appendLine("    genes:    isActiveScan=${us(genesIsActiveNanos)}us apply=${us(genesApplyNanos)}us")
            appendLine("              cells=${genesCells / t}  genesScanned=${genesScanned / t}  active(applyGene)=${genesActive / t}  richestBond=${richestBondCalls / t}  wildcard=${wildcardCalls / t}")
        }
    }
}
