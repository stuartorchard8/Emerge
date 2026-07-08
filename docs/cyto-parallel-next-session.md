# Cyto biology parallelization — session handoff (2026-07-08)

Resume point for parallelizing cyto's biology phase. Read this + memory
`reference_cyto_perf_levers` first. Working discipline unchanged: commit each
focused change on `main` (no branches), golden-gate everything, show diffs before
committing, A/B with the clean per-JVM bench (never the contaminated back-to-back).

## The goal (recap)
Make cyto fast enough to afford a 4–16× larger world (Stu shrank the torus to
`CELLS_PER_AXIS=32` purely for perf headroom; he'd re-inflate if perf allowed).
Multicore is the lever — but only if applied to the *hot* paths.

## What we proved about parallelism (the load-bearing findings)
- **Per-cell compute genuinely scales here — NOT memory-bandwidth-bound.** Clean
  per-JVM A/B @8192 cells, 8 cores: `genes` 2.54×, `build` 1.49×. This was the
  decisive open question; the answer is a green light.
- The earlier "multicore = ~1.1×, dead end" reading was **wrong** — it was a
  *coverage* artifact. Only `genes` (~7% of tick) was parallel. Confirmed across
  8-core, 20-core (`former`), clumped and even-spread populations.
- Biology sub-phase composition (clean SEQ, per-JVM). Two profiles because the mix
  shifts with population:
  - 1955-cell save: exchange 24%, genes 21%, writeback 20%, build 15%, finish 13%.
  - 8192 cells: **exchange 36%** (5587µs), build+genes ~18% each, finish 18%.
  `exchange` dominates and grows with N — it's the prize.
- Bench knobs added: `CytoBench` prints per-sub-phase SEQ/PAR; `-Dcytospread=1`
  tiles founders evenly; `-Dcytocells=N -Dcytovariant=seq|par`. Sweep harness:
  `scratchpad/bio_parallel_sweep.sh` (local) + `bio_sweep_former.sh` (20-core).

## Landed this session (on main)
1. `fa760a14` — column-slab chem double-buffer fixed & completed (the prior
   session left it miswired: never-set `count` → no-op swap + CellWork mutating a
   disconnected private buffer). Makes gene writes slot-local.
2. `54ae5d3b` — **Tier 1 parallelization**: `build` + `genes` run parallel via
   `ColumnPartition.disjoint`. `build` made partition-safe (expoScratch → CellWork,
   back-buffer grow hoisted, diffuse maps + batch assignment in a serial tail).
   `internalTouching` + `quanta` deliberately kept sequential (sub-ms → dispatch
   overhead net-negative). Golden-gated bit-identical.

## Parallelization roadmap — status
Pattern legend: disjoint / additive / grid-cell / detectThenApply (see
`ColumnPartition`, which already implements the first three).

| Sub-phase | % biology @8k | pattern | status |
|---|---|---|---|
| build | 18 | disjoint | ✅ done (1.49×) |
| genes | 18 | disjoint | ✅ done (2.54×) |
| internalTouching / quanta | <2 | — | intentionally serial (too small) |
| **exchange** | **36** | tile-parallel drop-contested | ✅ done — **2.34× (SEQ→PAR)**; descent now parallel. See below. |
| finish | 18 | detectThenApply (per-cell + serial grid deposit) | Tier 2, not started |
| writeback | ~8 | disjoint + **per-cell RNG re-baseline** | Tier 2, not started |
| lifecycle (19% of *tick*) | — | detectThenApply | Tier 3, hardest |

## Exchange — DECIDED approach (2026-07-08, Stu): conflict-detect + drop-contested
`passiveEnvExchange` (CytoBiologyCore.kt:73; grid primitives CytoMatterField.kt
:161–312) is order-DEPENDENT (`balanceBatched` moves `(leaf.count(sp) − cEff/n)/denom`
against a leaf's *live* count, so cell order matters), so it can't be parallelized
bit-identically. Chosen path makes it order-INDEPENDENT by only exchanging on leaves
a single cell touches this batch, and **dropping contested leaves** (conscious golden
re-baseline — accepted):

1. **Per-thread conflict grids.** One 2-bit-per-tile grid per thread
   (unaccessed / accessed / contested), reused across ticks (memset to clear). Use a
   plain dense grid (not the quad-tree) for stable reusable memory. **Resolution must
   be ≥ the finest quad-tree leaf** (coarser = safe-but-less-parallel; finer/misaligned
   = unsafe false-clean). Memory is a non-issue (2 bits × tiles × N = KB–few MB).
2. **Pass 1 (parallel, cells split over threads):** each thread marks the tiles under
   its cells' footprints in its own grid — `accessed` on first touch, `contested` on
   any later touch within that thread.
3. **Pass 2 (combine):** a tile is globally contested iff any thread marked it
   `contested` OR ≥2 threads marked it `accessed`. Accumulate into grid 0 (the truth).
4. **Pass 3 (parallel, per cell):** exchange each cell **only on its uncontested
   leaves** (per-tile-partial — cells keep their ~4 uncontested *central* leaves since
   base radius = 2 leaves; only shifting peripheral leaves get dropped). Contested
   leaves are inaccessible this tick — **dropped, not deferred to a serial tail.**

Why this is safe: uncontested leaves are single-owner ⇒ order-independent ⇒
deterministic; the contested set is geometry-only ⇒ **thread-count-independent**, so
the new golden is stable across any N. Conservation-exact (dropped leaves untouched).
Decide the `bucket = cEff/n` denominator explicitly (n = uncontested-leaf count,
varies per tick) — it's a re-baseline either way.

**PREREQUISITE HAZARD — don't miss (Claude flagged, not in Stu's sketch):**
`openFootprint`/`descendNode` **REFINE the quad-tree** (`splitLeaf` at line 182) and
stamp `lastAccessTick` during traversal — a shared *structural* mutation, unsafe even
when final leaves are disjoint (root→leaf path is shared). Method is explicitly "NOT
re-entrant, sequential only." So the parallel passes MUST be preceded by a **serial
pre-pass that refines + enumerates each exchanging cell's footprint** (cache per-cell
leaf lists). After that the tree is frozen; parallel mark/exchange only mutate leaf
*stores* (disjoint on uncontested) and read cached leaf lists. Also need a read-only
footprint enumeration (don't mutate `presenceMask`/`fpLeaves` in parallel). This serial
refine/enumerate is ~1/3 of exchange cost (the quad-tree descent) → caps the in-exchange
parallel win to the ~2/3 balance/transfer portion (Amdahl within exchange).

**FIRST STEP before building — DONE (2026-07-08, commit `fca26d58`).** `ExchangeProbe`
(off in production; `-Dcytoexchprobe=1`, golden bit-identical) tallies cells-per-fine-leaf
per batch and attributes transfer movement to contested (≥2-cell) vs single-owner leaves.
`CytoBench.exchangeContention`, 600 batches:
- **Naturally-grown colony (1485 cells): 0% leaves contested, 0% movement dropped.**
  avg cells/leaf = 1.00 — the exchange-batching already staggers cells enough that
  co-location within a batch is nil.
- Seeded 8192 spread evenly (grew to 10.6k, *denser than natural*): 5.4% leaves
  contested, **10.2% of transfer magnitude dropped**, 55% of cells touch ≥1 contested
  leaf (but keep their central uncontested leaves).
- Seeded 8192 fixed-spacing: degenerate — 18-unit spacing wraps the 32-wide torus and
  stacks *exactly* 8 cells/leaf → 100% contested. A seeding artifact, not representative.

**Verdict: GREEN LIGHT.** Contested fraction tracks *density* (cells/leaf), not N. At
realistic colony density the drop-contested sacrifice is ~zero; it only reaches ~10% if
cells are packed tighter than a grown colony. If Stu re-inflates the torus in proportion
to population (the whole point), density — and thus contested fraction — stays ≈0.
Stu's premise confirmed. **Next: build the drop-contested parallel exchange** (serial
refine/enumerate pre-pass → parallel mark → parallel per-cell exchange on uncontested
leaves), per the numbered plan above.

## Exchange — BUILT (2026-07-08, commit `debdaddc`), but Amdahl-capped
Drop-contested exchange is implemented and green: serial pre-pass refines+caches each cell's
footprint leaves and tallies cells-per-leaf; contested (≥2-cell) leaves are dropped; a parallel
`ColumnPartition.disjoint` pass balances each cell over its single-owner leaves (new
`CytoMatterField.balanceBatchedOn`, thread-safe, applies Δ straight to cytoplasm). SEQ==PAR held;
only INTERACT golden re-baselined (GROWTH+MUTATION byte-identical — zero contested at their density).

**Clean per-JVM A/B @ seeded 8192 spread (grew to 10278 cells, 8 cores):**
- exchange **18458µs → 16787µs = only 1.10×**. biology 45372→35807 (1.27×), whole tick 97.6→86.8ms.
- (build 1.88×, genes 2.37× — those scale; exchange doesn't.)

**Why only 1.10×:** at this density exchange is dominated by the **serial quad-tree descent**
(`openFootprint` refines the tree + stamps access → must stay serial), and ONLY the balance/transfer
portion parallelizes. The predicted "serial descent ~1/3, caps the win" was optimistic here — at
high spatial spread the descent is a much larger share (sparse leaves ⇒ cheap balance), so the
parallel fraction is small. **Next lever for exchange = parallelize the descent itself**: split into
(a) a serial refine-ONLY pass (cheap at steady state — the tree is already refined, few `splitLeaf`s
fire) then (b) a parallel read-only enumerate + per-thread touch-count maps merged (the original
per-thread-conflict-grid design). Deferred — decide with Stu whether the exchange win justifies it,
given lifecycle (30ms, now co-dominant with biology) is the bigger untouched serial block.

## Exchange — DESCENT NOW PARALLEL (2026-07-08, commit `c90b9645`)
The 1.10× wall was the serial quad-tree descent (measured: descent 51.7% / plan 33.9% / balance
14.4% of exchange — only balance was parallel). Fixed by **tile-partitioning the descent** (Stu's
idea): the tree is 16 independent root subtrees, `splitLeaf` never crosses a root boundary, so:
- **Pass 0** serial: bucket each footprint's per-root descents by root index (cheap, no tree touch).
- **Pass 1** parallel BY ROOT TILE: refine each root on one thread + set masks + count per-leaf
  touches. Every finest leaf lives in exactly one root ⇒ touch count is complete per-tile ⇒ **no
  merge**, and the contested set folds into this pass for free.
- **Pass 2** parallel BY CELL: tree frozen ⇒ each cell read-only-descends, collects its uncontested
  single-owner leaves, balances into its own cytoplasm. Disjoint ⇒ SEQ==PAR.
**Bit-identical to `debdaddc`** (same contested set + balance, reorganised): all three goldens
unchanged, parallelMatchesSequential + conservation held — NO re-baseline.

**Clean per-JVM A/B @ 8192 spread (10278 cells, 8 cores):**
- exchange **13708µs SEQ → 5861µs PAR = 2.34×** (was 1.10× at debdaddc). genes 2.03×, build 1.65×,
  biology-total 37262→23710 (1.57×).
- Bonus: tile-parallel SEQ exchange (13708µs) is *lower* than debdaddc-SEQ (~18458µs) — the
  integer-index refine + read-only enumerate avoids the old openFootprint's HashSet touch-count +
  leaf-copy + footprintSpecies overhead, so it's faster even single-threaded despite descending twice.
- Caveat: 16 fixed tiles ⇒ clumped colonies load-imbalance (untested; spread balances well).

**⚠️ Whole-tick win is only 1.09× (79.6→72.9ms) despite biology 1.57×** — the all-core-clock ceiling
([[reference_cyto_perf_levers]]): pinning 8 cores for parallel biology drops the CPU below single-core
turbo, so the STILL-SERIAL lifecycle runs **22% SLOWER in PAR (24.5→29.9ms)**, clawing back most of
the biology gain. **Lifecycle (~30ms, ~41% of the PAR tick) is now the single biggest serial block —
the clear next target;** until it's parallel too, further biology parallelism keeps paying this tax.

## Expectation-setting
Full biology+lifecycle parallel ≈ 54% of tick; at a realistic ~5× on the parallel
portion, Amdahl gives ~1.7–1.8× whole-tick — real, not order-of-magnitude. Golden
re-baselines expected for exchange (drop-contested) and later writeback (per-cell RNG);
everything else stays bit-identical. Uncommitted: nothing — tree clean at `54ae5d3b`.
