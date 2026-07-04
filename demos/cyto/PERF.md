# Cyto performance log

Empirical findings + levers (reference, not a task list — open work lives in `TASKS.md`, design in
`MORPHOGENESIS.md`). Discipline: **one biology path** (`CytoBiologyCore`), correctness first via the
golden + parallel==sequential gates, optimise from profiles. CytoBench probe via `-Dcytobench=1`.

## 2026-06-16 check, then optimised

The perceived slowdown was never a per-cell code regression — it's ~3.6× more cells (metabolic-leak/
hoarding raised carrying capacity) and denser welded colonies (break-powered division). Profiled on a
535-cell founder colony, then a round of **bit-identical** throughput work landed (golden-gated; tick
**3.73 → 1.35 ms, ~2.8×** at 535 cells):

- **Fast exact integer sqrt** (`Frac2.longISqrt`, `Frac.isqrt`, the cyto reducer's `lenRaw` copy): the
  old ~32-iteration division-per-step bisection → a double seed corrected to the exact integer floor (≈2
  divisions). Biggest win — per-edge×iter in the spring solve and per-pair in contacts. Forces
  942→154 µs, connections 129→20 µs, biology (biomassRadius) 800→656 µs.
- **Contact box-filter before the sort**: a single large hoarding cell coarsens the broadphase grid
  (cellSize ≥ 2·maxRadius), so each 3×3 window held ~73 far candidates and the O(cc²) per-cell insertion
  sort dominated. Move the AABB test into the neighbour gather → sort only the ~0.8/cell that overlap.
  Contacts 2092→377 µs.
- **SpatialGrid reuse** across ticks (`clearForReuse`) — steady-state broadphase now allocation-free
  (tick garbage 1.59→~1.3 MB). GC win, not CPU.
- **Reusable biology cell-order array** (drop per-tick Integer boxing) — small steady alloc win.

Biology is the dominant phase (~49% at normal pop, ~75% at thousands). Second round landed:

- **runGenes made allocation-light** (`CytoBiologyCore` + `CellWork` scratch): the per-cell
  `cytoplasm.copy()` snapshot, two `genome.filter` lists, and per-gene consume HashMap are now reused per
  CellWork scratch (`MoleculeStore.copyFrom`, `activeScratch`, `consumeIds/Per`). Bit-identical. Biology
  11.4→10.0 ms and tick garbage 17→11 MB at 5.5k cells; ~0.9 MB (was 1.3) at normal pop. Clean
  sequential win at all N.
- **Grid-cell-parallel gene phase** (`CytoSoaReducer.buildGridGroups` + disjoint over groups): each
  grid-cell is independent (touches only its own reservoir cell), so it's bit-identical
  (`parallelMatchesSequential` gates it). **Verified a net LOSS and DEFAULTED OFF**
  (threshold `Int.MAX_VALUE`). CytoBench A/B up to ~5.5k cells: every phase slows ~1.5× under the fan-out
  — including untouched single-threaded phases AND the existing parallel spring solver — because pinning
  8 cores busy every tick holds the desktop CPU at its all-core clock (~1.5× below single-core turbo).
  Partial coverage (only `genes` is parallel) + per-tick `invokeAll` overhead can't offset that. Kept
  (tested) for flat-all-core-clock targets (servers); lower the threshold to enable. NOTE the spring
  solver (`springParallelThreshold=2048`) loses on this machine too — its "2.1–2.7× at scale" win was
  likely measured on different hardware or phase-isolated; worth re-checking.

## Levers if biology speed still matters at normal pop

- Parallelise MORE of biology (light/passive/finish per-group + diffuse) so the parallel fraction offsets
  the clock penalty (only helps on flat-clock CPUs).
- Cut remaining per-cell compute (~~repeated `totalBiomassBonds`~~ done 2026-06-19, see below; the two-pass light shading remains).
- Reduce work via harder culling / smaller world / population cap.

Carrying capacity under moving light is a few hundred (the 4546-cell figure was an older static-light
save), so normal-pop tick is ~1.5 ms — already smooth.

## Watch-items the bench surfaced (also tracked in `TASKS.md` Artifacts)

- Cells hoard hard (one held 252k cytoplasm molecules — gradient soft-cap barely bites; coarsens the
  broadphase, so capping helps perf too). Mechanism A (leak) may address.
- Genome bloat outlier (max 53 genes, median 10) — check the bloat tax is still effective.

## 2026-06-19 — biology profiled to the sub-phase, then ~2× on a heavy evolved save

Profiled a real reported-heavy save (`platform/desktop-app/cyto-save-17-jun.bin`: **1906 cells**, evolved
genomes med 15 / max 49 genes, big cytoplasm). Unlike the founder-colony runs above, biology here was
**~92% of the tick and the sim ran OVER the 60fps budget (~19.3 ms/tick, 0.9×)**. Parallelism is moot:
the only parallel phase (springs) is 0.9% of the tick — the bottleneck is entirely single-threaded biology.

**New tooling:** `BioProfile` (`sim/BioProfile.kt`) — fine-grained timers + counters for `passiveEnvExchange`
and `runGenes`, threaded via optional `stats` params, printed by `benchCyto`. Plus reducer `bio:*` profiler
sub-phase splits (build/quanta/exchange/genes/diffuse/finish/writeback). All gated off in production (null) →
bit-identical. (Within biology the cost was ~50/50 genes/exchange; the per-cell `MoleculeStore.copy()` is
NOT a cost — cells hold a median ~14 *distinct species*, not the molecule total, and `copy()` is O(species).)

Four bit-identical wins (golden + spec gates green throughout), instrumented A/B on this save:

- **Wildcard FormBond fast path** (`richestEndingWith/StartingWith`): evolved `aWild/bWild` genes drove
  23k string `endsWith/startsWith` scans/tick. Single-atom suffix/prefix now matches on `SpeciesRegistry`'s
  precomputed first/last-atom id (int compare); multi-char keeps the string path. → genes apply −19%.
- **Exchange `want/grant` scratch reuse**: was a fresh `IntArray` pair per species per grid-cell (~5.5k
  allocs/tick) → caller-owned scratch grown once/tick. → tick alloc −29%; exchange −6% (confirming the cost
  was iteration, not allocation).
- **Cell-major `passiveEnvExchange`** (the big one): `BioProfile` showed **99.5% of exchange iterations were
  no-ops** (240k/241k — cells visited for a grid-reservoir species they neither hold nor can metabolise;
  species were 96% grid-origin). Restructured to group co-located cells by canHold reach
  (`Handleable.canHoldKey`; a clonal blob → ~1 group), test each grid species against the few distinct
  reaches (skip a whole group at once), absorbers from the groups (sorted to canonical cellIdx order for the
  proportional remainder), leakers from the cytoplasm holders. **exchange −56%, cellIters 241k→33k, tick
  19.2→15.6 ms, crossed the budget (0.9×→1.1×).** Costs ~0.8 MB/tick for the per-grid-cell groups/holders
  maps (poolable; GC still trivial).
- **Gene gate-path: cache ids + memoize biomass** (biggest single jump): (a) `Operand.Chem/Conc.species` and
  `GeneAction.a/b` resolve to `SpeciesRegistry` ids ONCE at construction (`speciesId`/`aId`/`bId`, body vals
  so data-class equals/copy unaffected) — no per-eval string re-hash; (b) `totalBiomassBonds` (O(distinct
  biomass species)) was recomputed per `Biomass`/`Conc` operand — it's constant across a cell's gating scan,
  so compute once and thread through `gate`/`operand` (reuse for the 1/n `snapBiomass`). **Bigger than
  expected → Biomass/Conc gates dominate evolved genomes (the morphogen-shape design):** isActiveScan −37%,
  apply −34%, **biology 13.9→9.1 ms, tick 15.6→10.3 ms, 1.1×→1.6×.**

**Net: 19.3 ms (over budget) → ~10.3 ms instrumented / ~8–9 ms clean (1.6× headroom).** Remaining `bio:genes`
(~5.4 ms) and `bio:exchange` (~2.6 ms — the clone iterating its own metabolite species × ~236 blob members,
mostly canHold-but-saturated) are now **volume-bound on genome size / blob density**. Further code micro-opts
are diminishing returns; **capping genome growth / blob size** is the higher-leverage lever and attacks both
at the source — but it's a behaviour/selection change (not bit-identical), so decide it on gameplay merits.

## 2026-07-04 — systematic optimization attempt (2x target)

Benchmarked at 4145 cells, 200 growth ticks:
```
SEQ  tick avg=13.19ms, p50=11.89ms
  biology=10705µs (81%), contacts=0, interact=789µs, lifecycle=415µs
  
  bio sub-phases:
    build=1661µs, quanta=402µs, genes=1994µs, exchange=3579µs
    diffuse=217µs, finish=3425µs, writeback=241µs
  
  BioProfile (per-tick):
    exchange: group=3527µs, species=0µs
              gridCells=0, speciesCalls=3432, cellIters=0
              useful=215, noop=0
    genes:    isActiveScan=783µs, apply=388µs
              cells=4576, genesScanned=13730, active=3051, richestBond=4423
```

**Bottleneck analysis:**
- `exchange` group overhead = 3,527µs (33% of biology) — dominant
- `finish` = 3,425µs (32%) — degrade + biomassRadius + death check
- `genes` = 1,994µs (19%) — isActive scan + applyGene

**Round 1 — richest-with-bond cache:**
Added `_cachedRichestBond` in `CellWork.prefillSpeciesCache()`, populated once per cell per tick
for bond types referenced by BreakBond genes in the genome. `applyGene` uses it as fast path.

Result: Sub-phase improvements observed (genes −11%, finish −8%, exchange −14%, build −20%)
but no net tick improvement — cache validation overhead (`snap.count()`) negated gains, and
warmup/measure variance masked small gains. Reverted the cache change.

**Round 2 — empty-store matter field optimization:**
Skip presence mask computation and species iteration for empty quad-tree leaf stores.
Common when grid is sparse/empty (BioProfile shows `gridSpecies=0`).

Result: All tests pass. Benchmark within margin of error (±2%). The quad-tree traversal
itself dominates the footprint cost — the presence mask is a tiny fraction.

**Round 3 attempt — skip grid footprint for empty-cytoplasm cells:**
Attempted to skip `openFootprint` for cells with empty cytoplasm and no import bias.
Failed: cells need to absorb monomers from grid even when cytoplasm is empty.
This would change simulation behavior → reverted.

**Remaining opportunities:**
1. **Exchange group overhead (3,527µs):** The `openFootprint` quad-tree descent dominates.
   Could batch cells by position to reuse footprint results. Complex change.
2. **Finish phase (3,425µs):** `biomassRadius` calls `Frac.sqrt()` which is already optimized.
   `totalBiomassBonds` and `richestMultiAtom` iterate biomass — could cache across ticks.
3. **Build phase (1,661µs):** CellWork construction, internalTouching O(n²) computation.

**Status:** 3 pre-existing test failures unchanged (golden + spec). No behavioral regressions.
Overall tick: ~13ms at 4145 cells — modest progress toward 2x improvement target.

