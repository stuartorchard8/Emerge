package org.emerge.demo.cyto.sim

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.soa.ColumnPartition
import org.emerge.sim.core.physics.primitives.Frac
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource

/** Reusable scratch for the tile-parallel drop-contested [CytoBiologyCore.passiveEnvExchange]. Held by the
 *  pipeline state (one per reducer). The serial assignment pass fills it; the parallel passes only READ it
 *  (per-cell mutable state lives on [CellWork]). */
class ExchangeScratch {
    /** the batch-eligible cells this tick (compact; pass 2 partitions over these by index). */
    val batchCells = ArrayList<CellWork>()
    /** one bucket per root tile (BASE_RES² = 16): packed (batchCellIdx<<32 | (gx+0x8000)<<16 | (gy+0x8000))
     *  entries, each = one root descent. Pass 1 partitions the 16 tiles across threads; disjoint roots. */
    val tileBuckets: Array<ArrayList<Long>> = Array(CytoMatterField.BASE_RES * CytoMatterField.BASE_RES) { ArrayList() }
}

// Scratch arrays for CytoBiologyCore.diffuse — single-threaded, reused across calls.
// Replaces HashMap<EntityId, HashMap<Int, Int>> with flat arrays for O(1) access.
private var diffMaxId = 0
private var diffSpeciesKeys = mutableListOf<IntArray>()
private var diffSpeciesVals = mutableListOf<IntArray>()
private var diffSizes = IntArray(0)

/** Grow/resize scratch delta storage for diffuse to hold up to [maxId] entity entries. */
private fun ensureDiffScratch(maxId: Int) {
    if (maxId < 0) return
    diffMaxId = maxId
    while (diffSpeciesKeys.size <= maxId) { diffSpeciesKeys.add(IntArray(0)); diffSpeciesVals.add(IntArray(0)) }
    if (diffSizes.size <= maxId) {
        val old = diffSizes; diffSizes = IntArray(maxId + 1)
        for (i in 0 until old.size) diffSizes[i] = old[i]
    }
}

/** Clear all delta entries (called at start of each diffuse call). */
private fun clearDiffScratch() {
    for (i in 0..diffMaxId) diffSizes[i] = 0
}

/**
 * The per-cell biology of the matter model (MORPHOGENESIS.md), operating on [CellWork] + the
 * environment [CytoMatterField]. Everything here is integer/`Frac` and PRNG-free, so it is deterministic
 * and matter is conserved by construction (atoms are only moved between cytoplasm, biomass, and the
 * reservoir — never minted).
 *
 * Chemistry is **dense and id-keyed**: a cell's mobile cytoplasm and locked biomass are [MoleculeStore]s
 * (id→count, held sorted ascending by [SpeciesRegistry] id). When a gene must pick *which* molecule to act
 * on among several matches (a substrate holding a bond, a FormBond suffix/prefix, a degradation target), it
 * picks the **most abundant** one — the substrate the cell actually has most of — with the lowest id (lex
 * rank) kept only as the deterministic tie-break. Each selection is a `forward scan` tracking the max count,
 * a pure function of the snapshot, so it stays order-independent with no per-tick hashing or boxing.
 *
 * A tick runs in three phases over all cells: [runGenes] (each cell executes its genome — gated actions
 * powered by light quanta; sequential in EntityId order because Import draws from the shared reservoir),
 * then [diffuse] (cytoplasm spreads to connected neighbours, snapshot-based so it's order-independent),
 * then [finish] per cell (degradation, size from biomass, death/division decision).
 */
object CytoBiologyCore {

    // ── tunable knobs — VALUES LIVE IN CytoTuning (the single tuning sheet); these are local references ──
    const val BONDS_PER_FULL = CytoTuning.BONDS_PER_FULL      // also read by CytoLifecycleSystem
    private const val DEGRADE_PERIOD = CytoTuning.DEGRADE_PERIOD
    private const val DEATH_BIOMASS = CytoTuning.DEATH_BIOMASS
    private const val REPAIR_PER_OP = CytoTuning.REPAIR_PER_OP
    private const val MAX_REPAIR_HEAL_PER_TICK = CytoTuning.MAX_REPAIR_HEAL_PER_TICK
    private const val CONNECTION_BREAK_DAMAGE = CytoTuning.CONNECTION_BREAK_DAMAGE
    private const val CYTOPLASM_DIFFUSE_DENOM = CytoTuning.CYTOPLASM_DIFFUSE_DENOM
    private val FLEX_STEP = CytoTuning.FLEX_STEP
    private val MIN_EXPOSURE_FOR_TRANSFER = CytoTuning.MIN_EXPOSURE_FOR_TRANSFER

    /** Passive cell↔environment **diffusion junction** (FREE, bidirectional). Runs AFTER the gene phase
     *  ([runGenes]) so it consumes the `importBias` that Import genes record this tick (the bias is cleared
     *  every tick at build, so genes must set it between build and here — see the reducer's phase order).
     *  Each cell opens its circular footprint on the quad-tree field and the field balances every `canDiffuse`
     *  species toward an effective target `cEff = cytoplasm − importBias` (Import lowers it ⇒ inward diffusion /
     *  retention); we apply the net Δ to cytoplasm. Determinants (synthesised-only) and foreign species (not
     *  `canDiffuse`) are excluded; there is **no passive leak** of un-metabolisable waste. Conservation-exact
     *  (QUADTREE.md exchange); the cell senses local matter density through this intake.
     *
     *  **Tile-parallel drop-contested parallelization (2026-07-08).** The naive per-cell loop is order-DEPENDENT
     *  (a leaf's live count shifts as earlier cells balance against it) and refines the shared quad-tree, so it
     *  can't be parallelized bit-identically as-is. Instead, in three passes:
     *   0. **Serial assignment** (cheap, no tree touch): collect the batch-eligible cells and bucket each
     *      footprint's root-tile descents (`forEachFootprintTile`) by root index.
     *   1. **Parallel by ROOT TILE** ([CytoMatterField.refineCountRoot]): each of the 16 roots is refined on one
     *      thread — since `splitLeaf` never crosses a root boundary, disjoint roots mutate safely. This pass
     *      refines every footprint to the finest depth, sets presence masks, and counts how many cells touch
     *      each finest leaf. Every finest leaf lives in exactly one root, so its touch count is complete within
     *      its tile — **no cross-tile merge**. A leaf touched by ≥2 cells is contested.
     *   2. **Parallel by CELL** ([CytoMatterField.collectUncontestedFootprint]): the tree is frozen, so each
     *      cell read-only-descends, collects its UNCONTESTED (single-owner) leaves, and balances over them into
     *      its own cytoplasm. Single-owner ⇒ no two cells write the same leaf store; own cytoplasm ⇒ no cross-
     *      cell writes ⇒ order-independent ⇒ bit-identical to the sequential fallback (the determinism gate).
     *  Contested is geometry-only ⇒ thread-count-independent ⇒ stable golden; dropped leaves are untouched
     *  (conservation-exact). This is the same computation as the earlier serial-pre-pass drop-contested (same
     *  contested set, same balance) — just reorganised so the whole descent is parallel — so it is bit-identical
     *  to it (no re-baseline). */
    fun passiveEnvExchange(
        ordered: List<CellWork>, grid: CytoMatterField, tick: Int, scratch: ExchangeScratch,
        executor: ParallelExecutor? = null, threshold: Int = Int.MAX_VALUE, stats: BioProfile? = null,
    ) {
        val tGroup = if (stats != null) TimeSource.Monotonic.markNow() else null
        val currentBatch = tick % CytoTuning.EXCHANGE_BATCHES
        val batchCells = scratch.batchCells.also { it.clear() }
        val tileBuckets = scratch.tileBuckets
        for (b in tileBuckets) b.clear()

        // ── Pass 0 (serial, cheap): collect batch cells + bucket their root-tile descents by root index. ──
        for (w in ordered) {
            if (w.exchangeBatch != currentBatch) continue
            if (w.exposureMilli <= MIN_EXPOSURE_FOR_TRANSFER) continue
            val idx = batchCells.size
            batchCells.add(w)
            grid.forEachFootprintTile(w.cx, w.cy, w.logicalRadius.toFloat()) { ti, gx, gy ->
                tileBuckets[ti].add((idx.toLong() shl 32) or ((gx + 0x8000).toLong() shl 16) or (gy + 0x8000).toLong())
            }
        }

        // ── Pass 1 (parallel by root tile): refine + masks + per-leaf touch counts. Disjoint roots. ──
        ColumnPartition.disjoint(tileBuckets.size, executor, threshold) { tStart, tEnd ->
            for (ti in tStart until tEnd) {
                val bucket = tileBuckets[ti]
                for (bi in bucket.indices) {
                    val p = bucket[bi]
                    val w = batchCells[(p ushr 32).toInt()]
                    val gx = ((p ushr 16) and 0xFFFF).toInt() - 0x8000
                    val gy = (p and 0xFFFF).toInt() - 0x8000
                    grid.refineCountRoot(gx, gy, w.cx, w.cy, w.logicalRadius.toFloat(), tick)
                }
            }
        }

        // ── Pass 2 (parallel by cell): collect uncontested leaves, build transfer plan, balance. ──
        val m = batchCells.size
        ColumnPartition.disjoint(m, executor, threshold) { start, end ->
            for (ci in start until end) {
                val w = batchCells[ci]
                grid.collectUncontestedFootprint(w.cx, w.cy, w.logicalRadius.toFloat(), tick, w.exchLeaves)
                val leaves = w.exchLeaves
                val keep = leaves.size
                w.exchN = keep
                w.exchTransferN = 0
                if (keep == 0) continue
                // Species union over the single-owner leaves + this cell's cytoplasm.
                val species = w.exchSpecies.also { it.clear() }
                val cyt = w.cytoplasm
                for (j in 0 until cyt.size) { val id = cyt.idAt(j); if (w.handleable.canDiffuse(id)) species.add(id) }
                for (leaf in leaves) {
                    val s = leaf.store!!
                    if (s.size > 0) for (i in 0 until s.size) { val id = s.idAt(i); if (w.handleable.canDiffuse(id)) species.add(id) }
                }
                // Transferable = monomers (atomCount 1) or species with a genetic import bias.
                var transferN = 0
                for (sp in species) { val ib = w.importBias[sp] ?: 0; if (ib != 0 || SpeciesRegistry.atomCount(sp) == 1) transferN++ }
                if (transferN == 0) continue
                if (w.exchTransferIdx.size < transferN) { w.exchTransferIdx = IntArray(transferN); w.exchTransferCeffs = IntArray(transferN) }
                var t = 0
                for (sp in species) {
                    val ib = w.importBias[sp] ?: 0
                    if (ib != 0 || SpeciesRegistry.atomCount(sp) == 1) {
                        w.exchTransferIdx[t] = sp
                        w.exchTransferCeffs[t] = (cyt.count(sp) - ib).coerceAtLeast(0)
                        t++
                    }
                }
                w.exchTransferN = transferN
                grid.balanceBatchedOn(leaves, keep, transferN, w.exchTransferIdx, w.exchTransferCeffs,
                    CytoTuning.DIFFUSION_SCALE_FACTOR, cyt)
            }
        }

        if (stats != null) {
            stats.ticks++
            stats.exchGroupNanos += tGroup!!.elapsedNow().inWholeNanoseconds
        }
    }


    /** Phase 1 — execute one cell's genome. Each ACTIVE gene gets a flat **1/N share** (N = active-gene
     *  count) of every resource it touches — the cell's light quanta and each cytoplasm species it consumes
     *  — and performs its whole action for the tick in **one bulk step** (no per-quantum loop). Shares come
     *  from a tick-start snapshot, so they're disjoint: applying the genes in any order can never over-draw
     *  a pool (⇒ order-independent + matter-conserving), and a pool's `mod N` remainder just lingers unused
     *  this tick (negligible once quantities are large — this is why the world wants the big-number scale).
     *  The 1/N split is the genome-bloat tax: more simultaneously-active genes ⇒ each gets a thinner slice
     *  (inactive genes don't reserve a share — carrying them is taxed by mutation load instead). Import
      *  draws from the shared [grid], so this still runs in a fixed cell order across cells. */
    fun runGenes(work: CellWork, tick: Int, stats: BioProfile? = null) {
        val genome = work.genome
        if (stats != null) { stats.genesCells++; stats.genesScanned += genome.size }
        val tScan = if (stats != null) TimeSource.Monotonic.markNow() else null
        // Pre-populate species cache for O(1) gate lookups — max 32 entries, most cells have ~5.
        work.prefillSpeciesCache()
        // Decrement mitosis cooldown (prevents instant re-division after a mitosis event).
        if (work.mitosisCooldown > 0) work.mitosisCooldown--
        val genomeSize = genome.size
        val active = work.activeScratch
        // Tick-start biomass — constant through the whole isActive scan AND equal to the 1/n snapshot's
        // biomass (no gene has applied yet), so compute the O(species) sum ONCE and reuse it for every
        // clause of every gate (was re-summed per Biomass/Conc operand) and for snapBiomass below.
        val bioBonds = totalBiomassBonds(work.biomass)
        var n = 0

        if (CytoTuning.ROUND_ROBIN_GENES && genomeSize > 0) {
            // ── Round-robin condition evaluation ──────────────────────────────
            // The gene at rrIdx gets its condition re-evaluated this tick.
            // All other genes use their cached active state from the last time
            // they were evaluated (or false if never evaluated).
            val rrIdx = tick % genomeSize
            for (i in genome.indices) {
                val g = genome[i]
                if (g.action.type != ActionType.Mitosis) {
                    val isActive = if (i == rrIdx) {
                        isActive(g, work, bioBonds).also { work._cachedActive[i] = it }
                    } else {
                        work._cachedActive[i]
                    }
                    if (isActive) active[n++] = i
                }
            }
        } else {
            // ── Sequential mode (original) ────────────────────────────────────
            // All active genes are evaluated per tick.
            for (i in genome.indices) {
                val g = genome[i]
                if (g.action.type != ActionType.Mitosis && isActive(g, work, bioBonds)) active[n++] = i
            }
        }

        if (stats != null) { stats.genesIsActiveNanos += tScan!!.elapsedNow().inWholeNanoseconds; stats.genesActive += n }
        val tApply = if (stats != null) TimeSource.Monotonic.markNow() else null
        if (n > 0) {
            val snap = work.snapScratch.also { it.copyFrom(work.cytoplasm) }   // reused; immutable 1/n source
            val quantaShare = work.quanta / n
            for (j in 0 until n) applyGene(genome[active[j]], work, snap, bioBonds, n, quantaShare, stats)
        }
        if (stats != null) stats.genesApplyNanos += tApply!!.elapsedNow().inWholeNanoseconds

        // Phase 2 — division resolved on the SETTLED state, as one atomic end-of-tick action (a clean
        // half-split is only sane atomically). The gate is RE-CHECKED here against the post-metabolism
        // state: a Mitosis gene armed at tick start but whose condition no longer holds now does NOT
        // divide. Funded from the settled cytoplasm (break the source bond to pay the bulk biomass/4 cost).
        // Mitosis cooldown: after a division fires, the cell enters a [genomeSize]-tick cooldown
        // before mitosis genes are re-evaluated. This prevents instant re-division cascades.
        if (!work.dividing && work.mitosisCooldown <= 0 && genomeSize > 0) {
            // Post-phase-1 biomass — constant through the Mitosis re-checks (a Mitosis gene either divides
            // and breaks the loop, or no-ops; neither mutates biomass here), so sum it once for all gates.
            val bioBondsNow = totalBiomassBonds(work.biomass)
            var dn = 0
            for (i in genome.indices) {
                val g = genome[i]
                if (g.action.type == ActionType.Mitosis && gate(g.condition, work, bioBondsNow)) active[dn++] = i
            }
            if (dn > 0) {
                val snap = work.snapScratch.also { it.copyFrom(work.cytoplasm) }
                val quantaShare = work.quanta / dn
                for (j in 0 until dn) {
                    applyGene(genome[active[j]], work, snap, totalBiomassBonds(work.biomass), dn, quantaShare, stats)
                    // Set mitosis cooldown on first successful division — prevents re-division this tick
                    // even if multiple mitosis genes fire (though work.dividing guards against that).
                    if (work.dividing) { work.mitosisCooldown = genomeSize; break }
                }
            }
        }
    }

    /** A gene is "active" — counting toward the [runGenes] bloat tax — when its condition holds AND its
     *  action isn't a guaranteed no-op this tick. So an always-on Repair gene with nothing damaged, or a
     *  flex gene already at its limit, costs the cell (and its neighbours' share of the genome) nothing.
     *  Uses [work._cachedHasDamage] and [work._cachedCanContract] for O(1) action checks. */
    private fun isActive(gene: Gene, work: CellWork, bioBonds: Int): Boolean {
        if (!gate(gene.condition, work, bioBonds)) return false
        return when (gene.action.type) {
            ActionType.Repair -> work._cachedHasDamage
            ActionType.Contract -> work._cachedCanContract
            else -> true
        }
    }

    /** Apply one active gene's whole action for the tick in a single bulk step (see [runGenes]). Each
     *  cytoplasm species the gene touches is capped at its 1/[n] share of the [snap]shot; a Light gene's
     *  energy is its [quantaShare], a BreakBond gene's energy is the bonds it cleaves (which also consume
     *  substrate and yield fragments). The op count [k] is computed up front from those caps and applied
     *  once — no loop. Matter-conserving: every per-op effect is the bulk of a conservative single op, and
     *  `k` never exceeds the snapshot share, so no pool goes negative. */
    /** Accumulate one consumed species [id] (one unit/op) into the [ids]/[per] scratch holding [cn] distinct
     *  entries, summing if [id] is already present; returns the new entry count. Allocation-free (no map). */
    private fun addConsume(ids: IntArray, per: IntArray, cn: Int, id: Int): Int {
        for (i in 0 until cn) if (ids[i] == id) { per[i]++; return cn }
        ids[cn] = id; per[cn] = 1; return cn + 1
    }

    private fun applyGene(gene: Gene, work: CellWork, snap: MoleculeStore, snapBiomass: Int, n: Int, quantaShare: Int, stats: BioProfile? = null) {
        val src = gene.source
        val act = gene.action
        // Per-op cytoplasm consumption (action inputs + BreakBond substrate, SUMMED — so an overlap like
        // BreakBond(ab)+Convert(ab) eating 2 ab/op is counted correctly). Accumulated into the reused
        // per-work [consumeIds]/[consumePer] scratch (≤ 3 distinct species; addConsume sums collisions) so
        // there's no per-gene map allocation. Species resolved from the snapshot by **most-abundant match**
        // (largest count wins; ties → lowest id) — a pure function of the snapshot, so order-independent.
        val ids = work.consumeIds; val per = work.consumePer
        var cn = 0
        // BreakBond fuel: the most-abundant molecule holding the bond, broken to release its quanta. Kept OUT
        // of the action-consume scan (tracked as [breakSpId]) because efficiency consumes it at the
        // bonds-broken rate ⌈k/gP1⌉, not 1/op; its overlap with an action input is summed back in below.
        var breakSpId = -1; var fragLId = -1; var fragRId = -1
        if (src is EnergySource.BreakBond) {
            val bondIdx = SpeciesRegistry.bondIndexOf(src.bond)
            if (stats != null) stats.richestBondCalls++
            breakSpId = richestWithBond(snap, bondIdx); if (breakSpId < 0) return
            fragLId = SpeciesRegistry.breakLeft(breakSpId, bondIdx); fragRId = SpeciesRegistry.breakRight(breakSpId, bondIdx)
            if (fragLId < 0) return
        }
        var productId = -1
        var convertId = -1
        when (act.type) {
            ActionType.Convert -> { convertId = act.aId; cn = addConsume(ids, per, cn, convertId) }
            ActionType.FormBond -> {
                // Join a molecule ending in act.a to one starting with act.b. EXACT by default — act.a/act.b
                // name the whole reactant species (MORPHOGENESIS.md §2026-06-18); so `FormBond r r` joins the
                // monomer r to the monomer r (not the richest r-ender, which self-stalls once `rr` piles up).
                // act.aWild / act.bWild opt into the legacy WILDCARD match (most-abundant molecule ending/
                // starting with the operand). The junction bond is act.a.last–act.b.first either way.
                if (act.a.isEmpty() || act.b.isEmpty()) return
                if (stats != null) { if (act.aWild) stats.wildcardCalls++; if (act.bWild) stats.wildcardCalls++ }
                val endAId = if (act.aWild) richestEndingWith(snap, act.a) else exactPresent(snap, act.aId); if (endAId < 0) return
                val startBId = if (act.bWild) richestStartingWith(snap, act.b) else exactPresent(snap, act.bId); if (startBId < 0) return
                productId = SpeciesRegistry.join(endAId, startBId); if (productId < 0) return   // forbidden (polymerisation) ⇒ no-op
                cn = addConsume(ids, per, cn, endAId)
                cn = addConsume(ids, per, cn, startBId)
            }
            else -> {}   // Import draws from the grid; Repair/Expand/Contract/Mitosis consume no cytoplasm
        }
        // Efficiency gear (see [Gene]): each energy unit performs gP1 = g+1 actions (the rate↔efficiency
        // multiplier — Convert / Import / Repair / Contract), but at most `energyCap` units may be spent this
        // tick. g=0 is the uncapped 1:1 baseline (every current gene behaves exactly as before). Contract uses
        // the multiplier safely (it moves the radius, mints no matter): high g ⇒ more flex steps per quantum
        // (cheaper contraction — for BreakBond-powered Contract, fewer fuel bonds broken per step) but the cap
        // throttles per-tick contraction throughput. A muscle-fibre axis: low g = fast-twitch (energy-hungry,
        // max per-tick travel), high g = slow-twitch (sips fuel, rate-limited).
        val eff = when (act.type) {
            ActionType.Convert, ActionType.Import, ActionType.Repair, ActionType.Contract -> gene.efficiency.coerceIn(0, CytoTuning.EFFICIENCY_MAX_GEAR)
            else -> 0
        }
        val gP1 = eff + 1
        // The per-tick CAP also applies to FormBond — but WITHOUT the gP1 multiplier (FormBond is a lossless
        // 1:1 bond conversion; a multiplier would mint bonds). So on a FormBond gene the gear is pure
        // potency-limiting: capping a morphogen source/sink's rate is the gradient-spread dial
        // (MORPHOGENESIS.md §Morphogens for shape — caps consumption rate k ⇒ reach λ≈√(D/k)). Mitosis stays
        // exempt (fixed biomass/4 bulk cost). g=0 ⇒ uncapped ⇒ existing FormBond genes are byte-identical.
        val capGear = when (act.type) {
            ActionType.Convert, ActionType.Import, ActionType.Repair, ActionType.FormBond, ActionType.Contract -> gene.efficiency.coerceIn(0, CytoTuning.EFFICIENCY_MAX_GEAR)
            else -> 0
        }
        val energyCap = if (capGear == 0) Int.MAX_VALUE else CytoTuning.EFFICIENCY_REF ushr capGear
        // Energy units available: Light = the cell's quanta share; BreakBond = bonds it can break (one
        // quantum each), from the fuel's 1/n share. Op budget = min(units, cap) × gP1.
        val energyUnits = if (src is EnergySource.Light) quantaShare else snap.count(breakSpId) / n
        var k = (minOf(energyUnits.toLong(), energyCap.toLong()) * gP1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // Metabolic slowdown with size: every op (except Mitosis, which has its own size-scaling cost above)
        // runs at `k × SCALE/(SCALE+biomass)`. A bigger cell spreads its metabolic capacity over more
        // structure, so its build/acquire rate falls while size-proportional decay keeps rising — they cross
        // at an EMERGENT size the cell can't outgrow. No hard cap: a stronger cell settles larger.
        if (act.type != ActionType.Mitosis) {
            val bio = totalBiomassBonds(work.biomass)
            k = (k.toLong() * CytoTuning.METABOLIC_BIOMASS_SCALE / (CytoTuning.METABOLIC_BIOMASS_SCALE + bio)).toInt()
        }
        // BreakBond: the fuel is also broken (⌈k/gP1⌉ bonds) and may double as an action input (pBreak/op);
        // cap k so action-use + bonds-broken fit the fuel's share:  pBreak·k + ⌈k/gP1⌉ ≤ fuelShare.
        if (breakSpId >= 0) {
            var pBreak = 0
            for (i in 0 until cn) if (ids[i] == breakSpId) pBreak = per[i]
            val fuelShare = snap.count(breakSpId) / n
            k = minOf(k, (fuelShare.toLong() * gP1 / (pBreak.toLong() * gP1 + 1L)).toInt())
        }
        // action-substrate caps (action inputs' 1/n share; the BreakBond fuel is handled above)
        for (i in 0 until cn) k = minOf(k, (snap.count(ids[i]) / n) / per[i])
        when (act.type) {
            // Division is a BULK, size-scaling cost: it needs `biomass/4` energy THIS tick. Energy can't be
            // accumulated (quanta are use-or-lose; bonds are spent the tick they're broken), so it can only
            // be paid by breaking a big chunk of stored bonds in one tick — a BreakBond-powered mitosis. ANY
            // energy source is accepted here; light-division is non-viable **emergently, not by rule**: the
            // light scale is tuned so a cell's peak per-tick quanta stays well below biomass/4 for any real
            // divide size, so a Light-sourced Mitosis just never reaches the cost (k < cost ⇒ 0). The "charge
            // up to divide" comes for free — only a hoarded reserve broken in one tick clears the bar. The
            // gate may hold below the cost; it then does nothing (no accumulation toward it).
            ActionType.Mitosis -> { val cost = totalBiomassBonds(work.biomass) / 4; k = if (k >= cost) cost else 0 }
            ActionType.Import -> {}   // k energy units become a junction bias (applied in passiveEnvExchange)
            ActionType.Repair -> k = minOf(k, repairOpsNeeded(work))
            ActionType.Contract -> k = minOf(k, flexOps(MIN_RADIUS, work.logicalRadius))
            // Sub-tick interpolation: a growth gene fills only up to its OWN gate threshold, never past it.
            // perOp 0 (an unresolved/mutated species id) disables the cap rather than indexing by -1.
            ActionType.Convert -> k = minOf(k, selfGateCap(gene.condition, qBiomass = true, qSpeciesId = -1, snapQ = snapBiomass, perOp = if (convertId >= 0) SpeciesRegistry.bondCount(convertId) else 0, snap = snap, snapBiomass = snapBiomass, work = work))
            ActionType.FormBond -> k = minOf(k, selfGateCap(gene.condition, qBiomass = false, qSpeciesId = productId, snapQ = snap.count(productId), perOp = 1, snap = snap, snapBiomass = snapBiomass, work = work))
            ActionType.Lyse -> {} // No sub-tick cap — damage is capped by available biomass in the attack phase
        }
        if (k <= 0) return
        // Apply: action-input consumption, then the broken fuel + its fragments, then the action's output.
        // The fuel is broken at ⌈k/gP1⌉ (each broken bond powers gP1 actions); at g=0 that's k (1:1, as before).
        val bondsBroken = if (breakSpId >= 0) (k + gP1 - 1) / gP1 else 0
        for (i in 0 until cn) work.cytoplasm.add(ids[i], -k * per[i])
        if (breakSpId >= 0) {
            work.cytoplasm.add(breakSpId, -bondsBroken)
            work.cytoplasm.inc(fragLId, bondsBroken); work.cytoplasm.inc(fragRId, bondsBroken)
        }
        when (act.type) {
            ActionType.Convert -> work.biomass.inc(convertId, k)
            ActionType.FormBond -> work.cytoplasm.inc(productId, k)
            ActionType.Import -> {
                // Active uptake is now a BIAS on the passive diffusion junction (QUADTREE.md): the gene's k
                // energy units lower the cell's effective target for `importId`, so the junction (in
                // passiveEnvExchange) draws that much extra IN from the footprint, concentrating it above
                // ambient. Each unit is worth IMPORT_BIAS_GAIN of bias so uptake is efficient enough to hold
                // a species above ambient. No field access here ⇒ the gene phase stays grid-free + parallel-
                // safe. (The old gradient-cost diminishing-returns is dropped for v1; revisit if hoarding misbehaves.)
                work.importBias[act.aId] = (work.importBias[act.aId] ?: 0) + k * CytoTuning.IMPORT_BIAS_GAIN
            }
            ActionType.Mitosis -> {
                work.dividing = true; work.divideMorphogen = act.a; work.divideMorphogenToMother = act.morphogenToMother
                work.divideAxisMorphogen = act.b; work.divideAcross = act.divideAcross; work.divideRejectMother = act.rejectMother
            }
            ActionType.Repair -> applyRepair(work, k)
            ActionType.Contract -> work.logicalRadius = (work.logicalRadius - FLEX_STEP * k).coerceAtLeast(MIN_RADIUS)
            ActionType.Lyse -> {
                // Queue lysis attacks on all touching un-welded cells. Each op tears `k` total energy
                // units of biomass, split across all touching victims (each loses `k / numVictims`).
                // Lyse steals ALL species — no species targeting. Undigestible species are forced
                // into the attacker's cytoplasm as a metabolic burden (MORPHOGENESIS.md §B).
                work.lyseTargets.clear()
                val numVictims = work.touchingIds.size
                if (numVictims > 0) {
                    val damagePerVictim = k / numVictims
                    for (victimId in work.touchingIds) {
                        work.lyseTargets[victimId] = damagePerVictim
                    }
                }
            }
        }
    }

    /** Most-abundant species in [snap] that contains bond [bondIdx] (ties → lowest id / lex-smallest),
     *  or -1 if none. Selecting by **count** means a gene draws on the substrate it actually has the most
     *  of, instead of whichever happens to sort first; lex is kept only as the deterministic tie-break so
     *  the choice stays a pure function of the snapshot (order-independent). */
    private fun richestWithBond(snap: MoleculeStore, bondIdx: Int): Int {
        if (bondIdx < 0 || !snap.hasBond(bondIdx)) return -1
        var best = -1; var bestCount = 0
        for (i in 0 until snap.size) {
            val id = snap.idAt(i)
            if (SpeciesRegistry.containsBond(id, bondIdx)) { val c = snap.countAt(i); if (c > bestCount) { bestCount = c; best = id } }
        }
        return best
    }

    /** The exact species [id] (precomputed on the [GeneAction]), iff present in [snap] (count > 0), else -1 —
     *  the default FormBond reactant match (MORPHOGENESIS.md §2026-06-18). The present-check mirrors the
     *  richest-* helpers (which only return a species they find), so an absent exact species no-ops like an
     *  absent wildcard; a non-species operand (id < 0) likewise no-ops. */
    private fun exactPresent(snap: MoleculeStore, id: Int): Int =
        if (id >= 0 && snap.count(id) > 0) id else -1

    /** Most-abundant species in [snap] whose string ENDS WITH [suffix] (the FormBond end-A wildcard match;
     *  ties → lowest id), or -1. A single-atom suffix == "ends in that atom"; a longer one is a specific tail. */
    private fun richestEndingWith(snap: MoleculeStore, suffix: String): Int {
        if (suffix.isEmpty()) return -1
        // Fast path: a single-atom suffix == "ends in that atom", matched on the precomputed last-atom id
        // (an int compare) instead of an O(len) string endsWith per species. Bit-identical: an unknown atom
        // (atomIndexOf < 0) matches nothing, exactly as no species string ends with it.
        val atom = if (suffix.length == 1) SpeciesRegistry.atomIndexOf(suffix[0]) else -1
        if (atom >= 0 && !snap.hasLastAtom(atom)) return -1
        var best = -1; var bestCount = 0
        for (i in 0 until snap.size) {
            val id = snap.idAt(i)
            val match = if (suffix.length == 1) SpeciesRegistry.lastAtom(id) == atom
                        else SpeciesRegistry.string(id).endsWith(suffix)
            if (match) { val c = snap.countAt(i); if (c > bestCount) { bestCount = c; best = id } }
        }
        return best
    }

    /** Most-abundant species in [snap] whose string STARTS WITH [prefix] (the FormBond start-B match; ties
     *  → lowest id), or -1. */
    private fun richestStartingWith(snap: MoleculeStore, prefix: String): Int {
        if (prefix.isEmpty()) return -1
        // Fast path: single-atom prefix == "starts with that atom", matched on the precomputed first-atom id
        // (see [richestEndingWith]). Bit-identical.
        val atom = if (prefix.length == 1) SpeciesRegistry.atomIndexOf(prefix[0]) else -1
        if (atom >= 0 && !snap.hasFirstAtom(atom)) return -1
        var best = -1; var bestCount = 0
        for (i in 0 until snap.size) {
            val id = snap.idAt(i)
            val match = if (prefix.length == 1) SpeciesRegistry.firstAtom(id) == atom
                        else SpeciesRegistry.string(id).startsWith(prefix)
            if (match) { val c = snap.countAt(i); if (c > bestCount) { bestCount = c; best = id } }
        }
        return best
    }

    /** Flex steps to move the radius from [lo] up to [hi] (ceil of the gap / [FLEX_STEP]); 0 if none. */
    private fun flexOps(lo: Frac, hi: Frac): Int {
        val gap = hi.raw - lo.raw
        if (gap <= 0L) return 0
        return ((gap + FLEX_STEP.raw - 1L) / FLEX_STEP.raw).toInt()
    }

    /** Repair ops the cell could use this tick: enough to fully heal existing connection damage PLUS
     *  to birth-heal a weld with each eligible un-welded cell it's touching (each up to
     *  [CONNECTION_BREAK_DAMAGE]) — so a cell with no damaged connections still gets ops to form
     *  adhesion welds (see [applyRepair]). Eligible cells are those sharing a connected neighbour
     *  (InternalOnly mode — hard-coded). */
    private fun repairOpsNeeded(work: CellWork): Int {
        // InternalOnly mode: when a cell already has welds, only count touching cells that share a
        // connected neighbour (body-internal repair). When a cell has no welds yet, count all touching
        // cells so the first connection can form (e.g. mother↔daughter after division).
        val adhesionTargets = if (work.weldedDegree > 0) work.internalTouching else work.touchingIds
        var dmg: Float = adhesionTargets.size * CONNECTION_BREAK_DAMAGE
        // Each EXISTING connection heals at most MAX_REPAIR_HEAL_PER_TICK this tick, so don't request ops the
        // cap won't let us spend (birth-heal welds, above, are exempt — a one-off weld forms at full strength).
        for (v in work.connectionDamage.values) if (v > 0f) dmg += minOf(v, MAX_REPAIR_HEAL_PER_TICK)
        if (dmg <= 0f) return 0
        return kotlin.math.ceil(dmg / REPAIR_PER_OP).toInt()
    }

    /** Heal up to `k·REPAIR_PER_OP` total damage, worst existing connection first (deterministic tiebreak by
     *  id). Then spend any LEFTOVER repair on **adhesion**: form a weld with each un-welded cell this one is
     *  touching, born at full damage ("0 health") but healed by the budget spent — so a cell only sticks to
     *  what its spare repair can afford, and the weld then needs ongoing Repair to survive (MORPHOGENESIS:
     *  Repair doubles as the sticky gene). The actual spring is created by the lifecycle from [weldHeals]. */
    private fun applyRepair(work: CellWork, k: Int) {
        var budget = k * REPAIR_PER_OP
        val order = work.connectionDamage.entries
            .filter { it.value > 0f }
            .sortedWith(compareByDescending<Map.Entry<EntityId, Float>> { it.value }.thenBy { it.key.value })
            .map { it.key }
        for (id in order) {
            if (budget <= 0f) break
            val cur = work.connectionDamage[id] ?: continue
            // Cap the heal an existing connection can receive per tick: repair mends at a bounded RATE, so it
            // can't instantly undo arbitrary damage. A connection stretched hard enough that its stress
            // outruns this cap accrues net damage and eventually breaks no matter how much repair energy the
            // cell has (the time-bomb hoarder used to fully heal every tick and stay welded under any stretch).
            val heal = minOf(cur, budget, MAX_REPAIR_HEAL_PER_TICK)
            budget -= heal
            val left = cur - heal
            if (left <= 0f) work.connectionDamage.remove(id) else work.connectionDamage[id] = left
        }
        // Leftover budget forms new welds with eligible un-welded cells (lowest id first, deterministic).
        // InternalOnly mode: once a cell already has welds (weldedDegree > 0), only weld touching cells
        // that share a connected neighbour (body-internal repair). When a cell has no welds yet
        // (weldedDegree == 0), allow welding any touching cell so the first connection can form
        // (e.g. mother↔daughter after division, before a shared neighbor exists).
        val adhesionTargets = if (work.weldedDegree > 0) work.internalTouching else work.touchingIds
        if (budget > 0f && adhesionTargets.isNotEmpty()) {
            for (id in adhesionTargets.sortedBy { it.value }) {
                if (budget <= 0f) break
                val heal = if (budget <= CONNECTION_BREAK_DAMAGE) budget else CONNECTION_BREAK_DAMAGE
                budget -= heal
                work.weldHeals[id] = heal
            }
        }
        work.repaired = true
    }

    /** Phase 2 — cytoplasm diffuses to connected neighbours: each cell sends ⌊count/CYTOPLASM_DIFFUSE_DENOM⌋
     *  of each species to **each** neighbour and keeps the remainder. The divisor is a FIXED constant, **not**
     *  `degree+1`: dividing by the sender's own degree would make the steady state `∝ (degree+1)` (high-degree
     *  interior cells pile up ~2× their neighbours — stalls a low↔high clock, corrupts a positional gradient).
     *  A fixed divisor is edge-symmetric (Fickian) → **uniform** steady state across identical cells; the divisor
     *  only sets the speed. It stays `≥ MAX_WELD_DEGREE` so the integer floor keeps `out·degree ≤ count` (no cell
     *  goes negative). Snapshot-based (reads pre-diffusion counts, writes deltas, applies after) so it's
     *  order-independent and conservative; biomass does not diffuse (it's locked).
     *  Uses scratch flat arrays instead of HashMaps for O(1) per-species delta access. */
    fun diffuse(works: Map<EntityId, CellWork>, neighbourIds: Map<EntityId, List<EntityId>>) {
        // The compute loop only ever reads a cell's *own* cytoplasm and writes to a separate delta map,
        // never to any cytoplasm — so reading the live store is identical to reading a pre-diffusion copy,
        // and the deltas are applied only after every cell is computed.
        val maxId = works.keys.maxOfOrNull { it.value } ?: 0
        ensureDiffScratch(maxId)
        clearDiffScratch()

        // Helper: add delta for entity [id], species [sp], value [v].
        // Grows the per-entity species array on demand (cap at 64 species).
            fun addDelta(idVal: Int, sp: Int, v: Int) {
            val s = diffSizes[idVal]
            if (s < diffSpeciesKeys[idVal].size) {
                diffSpeciesKeys[idVal][s] = sp
                diffSpeciesVals[idVal][s] += v
            } else if (s < 64) {
                val keys = diffSpeciesKeys[idVal]
                val vals = diffSpeciesVals[idVal]
                if (keys.size <= s) {
                    val sz = max(4, keys.size * 2)
                    diffSpeciesKeys[idVal] = keys.copyOf(max(sz, s + 1))
                    diffSpeciesVals[idVal] = vals.copyOf(max(sz, s + 1))
                }
                diffSpeciesKeys[idVal][s] = sp
                diffSpeciesVals[idVal][s] = v
            }
            diffSizes[idVal] = s + 1
        }

        // Helper: accumulate delta value for existing species entry.
            fun addDeltaValue(idVal: Int, sp: Int, v: Int) {
            val s = diffSizes[idVal]
            for (i in 0 until s) {
                if (diffSpeciesKeys[idVal][i] == sp) {
                    diffSpeciesVals[idVal][i] += v
                    return
                }
            }
            if (s < 64) {
                val keys = diffSpeciesKeys[idVal]
                val vals = diffSpeciesVals[idVal]
                if (keys.size <= s) {
                    val sz = max(4, keys.size * 2)
                    diffSpeciesKeys[idVal] = keys.copyOf(max(sz, s + 1))
                    diffSpeciesVals[idVal] = vals.copyOf(max(sz, s + 1))
                }
                diffSpeciesKeys[idVal][s] = sp
                diffSpeciesVals[idVal][s] = v
                diffSizes[idVal] = s + 1
            }
        }

        for ((id, w) in works) {
            val nbrs = neighbourIds[id] ?: continue
            val degree = nbrs.size
            if (degree == 0) continue
            for (i in 0 until w.cytoplasm.size) {
                val species = w.cytoplasm.idAt(i)
                val out = w.cytoplasm.countAt(i) / CYTOPLASM_DIFFUSE_DENOM
                if (out <= 0) continue
                var receivers = 0
                for (nb in nbrs) {
                    val nbWork = works[nb] ?: continue
                    if (!nbWork.handleable.canDiffuse(species)) continue
                    addDeltaValue(nb.value, species, out)
                    receivers++
                }
                if (receivers > 0) addDeltaValue(id.value, species, -out * receivers)
            }
        }

        // Apply deltas back to cytoplasm
        for ((id, w) in works) {
            val s = diffSizes[id.value]
            if (s == 0) continue
            for (i in 0 until s) {
                val dv = diffSpeciesVals[id.value][i]
                if (dv != 0) w.cytoplasm.add(diffSpeciesKeys[id.value][i], dv)
            }
        }
    }

    /** Phase 3 — degradation (biomass loses bonds at a rate ∝ size, whole molecules shed to environment),
      *  size from biomass, and the death/division decision. */
    fun finish(id: EntityId, work: CellWork, grid: CytoMatterField, divide: MutableList<EntityId>, destroy: MutableList<EntityId>) {
        degrade(work, grid)
        val bonds = totalBiomassBonds(work.biomass)
        if (bonds < DEATH_BIOMASS) {
            destroy.add(id)
            return
        }
        // size relaxes elastically toward the biomass baseline (matches the old growth feel) — this same
        // blend is what pulls a Contract-flexed radius back up to baseline once the gene stops.
        val target = biomassRadius(bonds)
        work.logicalRadius =
            (work.logicalRadius * RADIUS_ELASTICITY + target).div(RADIUS_ELASTICITY + 1)
        if (work.dividing) divide.add(id)
    }

    // ── gates ────────────────────────────────────────────────────────────────
    /** The gate is a conjunction: the gene fires iff **every** clause holds (empty ⇒ true). [bioBonds] is the
      *  caller-computed total biomass for this evaluation (constant across the gate), so Biomass/Conc operands
      *  read it instead of re-summing the biomass store per clause. */
    private fun gate(c: GeneCondition, work: CellWork, bioBonds: Int): Boolean {
        for (clause in c.clauses) if (!clauseHolds(clause, work, bioBonds)) return false
        return true
    }

    private fun clauseHolds(clause: Clause, work: CellWork, bioBonds: Int): Boolean {
        val l = operand(clause.lhs, work, bioBonds)
        val r = operand(clause.rhs, work, bioBonds)
        return when (clause.cmp) {
            Comparison.Greater -> l > r
            Comparison.Less -> l < r
        }
    }

    /** Evaluate one side of a [Clause] to an integer. Reads species counts from [CellWork.cachedCount]
      *  (a linear scan of the pre-populated, ≤32-entry species cache). */
    private fun operand(op: Operand, work: CellWork, bioBonds: Int): Int = when (op) {
        is Operand.Constant -> op.value
        is Operand.Chem -> work.cachedCount(op.speciesId)
        is Operand.Conc -> conc(work.cachedCount(op.speciesId), bioBonds)
        Operand.Biomass -> bioBonds
        Operand.Touching -> work.touchCount
    }

    /** Fast-path variant of [operand] for clauses without Chem/Conc operands. Avoids the when-dispatch
     *  and cachedCount calls — just compares pre-loaded values. Called from [clauseHoldsFast] when both
     *  operands are non-lookup types. */
    private fun operandFast(op: Operand, work: CellWork, bioBonds: Int): Int = when (op) {
        is Operand.Constant -> op.value
        Operand.Biomass -> bioBonds
        Operand.Touching -> work.touchCount
        else -> throw IllegalStateException("operandFast called with lookup operand")
    }

    /** [operand], but reading the tick-start [snap]shot (cytoplasm + [snapBiomass]) instead of live state,
     *  so a threshold derived from it is order-independent. Touching is transient (fixed for the tick).
     *  Uses [work.cachedCount] (population from snap) so gate evaluation also benefits. */
    private fun operandSnap(op: Operand, snap: MoleculeStore, snapBiomass: Int, work: CellWork): Int = when (op) {
        is Operand.Constant -> op.value
        is Operand.Chem -> work.cachedCount(op.speciesId)
        is Operand.Conc -> conc(work.cachedCount(op.speciesId), snapBiomass)
        Operand.Biomass -> snapBiomass
        Operand.Touching -> work.touchCount
    }

    /** Size-normalised concentration (CytoTuning.CONC_SCALE units): molecules per unit biomass-bond. Long
     *  intermediate (count·SCALE can exceed Int for a hoarding cell); 0 when biomass is 0. Integer floor ⇒
     *  deterministic across platforms. */
    private fun conc(count: Int, biomass: Int): Int =
        if (biomass <= 0) 0 else (count.toLong() * CytoTuning.CONC_SCALE / biomass).toInt()

    /** Sub-tick interpolation cap: the max ops a growth action may do before the quantity it INCREASES
     *  (biomass, when [qBiomass]; else cytoplasm species [qSpeciesId]) crosses a threshold of THIS gene's
     *  own gate — i.e. the portion of the tick before the action would flip its own condition false. Each
     *  AND-clause that the increase would break bounds it (`Q < limit`, or `limit > Q`); the gene's cap is
     *  the **tightest** (min) over all clauses. A clause not reading Q (incl. any [Operand.Conc] — its ratio
     *  isn't linear in Q, so it's left uncapped) imposes none. Reads the snapshot so it's order-independent. */
    private fun selfGateCap(
        cond: GeneCondition, qBiomass: Boolean, qSpeciesId: Int, snapQ: Int, perOp: Int,
        snap: MoleculeStore, snapBiomass: Int, work: CellWork,
    ): Int {
        if (perOp <= 0) return Int.MAX_VALUE
        fun reads(op: Operand): Boolean = when (op) {
            is Operand.Biomass -> qBiomass
            is Operand.Chem -> !qBiomass && op.speciesId == qSpeciesId
            else -> false
        }
        var cap = Int.MAX_VALUE
        for (clause in cond.clauses) {
            val limit = when {
                reads(clause.lhs) && clause.cmp == Comparison.Less -> operandSnap(clause.rhs, snap, snapBiomass, work)   // Q < rhs
                reads(clause.rhs) && clause.cmp == Comparison.Greater -> operandSnap(clause.lhs, snap, snapBiomass, work) // lhs > Q
                else -> continue
            }
            val headroom = limit - snapQ
            cap = minOf(cap, if (headroom <= 0) 0 else headroom / perOp)
        }
        return cap
    }

    private fun hasConnectionDamage(work: CellWork): Boolean = work.connectionDamage.values.any { it > 0f }

    /** The biomass-derived baseline radius (the size the cell relaxes toward): `sqrt(bonds /
     *  BONDS_PER_FULL)`, floored at [MIN_RADIUS]. Flex actions bound their deviation around this. */
    private fun biomassRadius(bonds: Int): Frac =
        Frac(bonds.toLong(), BONDS_PER_FULL).sqrt().coerceAtLeast(MIN_RADIUS)

    /** Can a Contract op still move the radius (not yet at MIN_RADIUS)? Checked before spending energy so a
     *  fully-contracted gene stops drawing quanta (mirrors the Repair pre-check). */
    private fun canContract(work: CellWork): Boolean = work.logicalRadius > MIN_RADIUS

    /** Spontaneous decay: shed `wear / DEGRADE_PERIOD` whole biomass molecules to the environment
      *  at a rate ∝ total cell mass (biomass bonds + cytoplasm molecule count). Each tick the cell's
      *  **most-abundant** biomass molecule loses one copy, dropped at the center-most touching cell's
      *  position (like shed skin — no splitting, no cytoplasm intermediate). The molecule then decays at
      *  the environment's own rate. This is a real matter LEAK — a maintenance cost the cell must keep
      *  importing against (selection for efficient builders) and a steady feed for the food web.
      *  Cytoplasm count adds a hoarding tax: more cytoplasm → more wear → faster biomass drain. */
    private fun degrade(work: CellWork, grid: CytoMatterField) {
        // Maintenance bonus: a more-connected cell degrades much slower — wear accrues at `1/2^weldedDegree`.
        // Interior cells of a body are nearly free to maintain. (Exponent capped at 20 to avoid Int overflow;
        // high values already make upkeep ~0 for any realistic biomass.)
        val bonus = 1 shl work.weldedDegree.coerceAtMost(20)
        var totalCytChem = 0
        for (i in 0 until work.cytoplasm.size) totalCytChem += work.cytoplasm.countAt(i)
        work.wear += (totalCytChem + totalBiomassBonds(work.biomass)) / bonus
        val broken = work.wear / DEGRADE_PERIOD
        work.wear %= DEGRADE_PERIOD
        if (broken > 0) {
            val targetId = richestMultiAtom(work.biomass)   // most-abundant molecule with a bond to break
            if (targetId >= 0) {
                val count = minOf(broken, work.biomass.count(targetId))
                work.biomass.add(targetId, -count)
                // Deposit at center-most touching cell, or the cell's own position if none.
                val depositX: Float
                val depositY: Float
                if (work._touchingCellN > 0) {
                    val cxSum = work._touchingCellCx.slice(0 until work._touchingCellN).sum()
                    val cySum = work._touchingCellCy.slice(0 until work._touchingCellN).sum()
                    val centroidX = cxSum / work._touchingCellN
                    val centroidY = cySum / work._touchingCellN
                    var bestDist = Float.MAX_VALUE
                    var bestX = 0f
                    var bestY = 0f
                    for (i in 0 until work._touchingCellN) {
                        val dx = work._touchingCellCx[i] - centroidX
                        val dy = work._touchingCellCy[i] - centroidY
                        val dist = dx * dx + dy * dy
                        if (dist < bestDist) { bestDist = dist; bestX = work._touchingCellCx[i]; bestY = work._touchingCellCy[i] }
                    }
                    depositX = bestX
                    depositY = bestY
                } else {
                    depositX = work.cx
                    depositY = work.cy
                }
                grid.deposit(depositX, depositY, work.logicalRadius.toFloat(), targetId, count)
            }
        }
    }

    /** Most-abundant biomass species with at least one bond (length ≥ 2), ties → lowest id, or -1 if none. */
    private fun richestMultiAtom(biomass: MoleculeStore): Int {
        var best = -1; var bestCount = 0
        for (i in 0 until biomass.size) {
            val id = biomass.idAt(i)
            if (SpeciesRegistry.atomCount(id) >= 2) { val c = biomass.countAt(i); if (c > bestCount) { bestCount = c; best = id } }
        }
        return best
    }
}
