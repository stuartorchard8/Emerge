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
| **exchange** | **36** | needs reformulation | ⛔ blocked — see below |
| finish | 18 | detectThenApply (per-cell + serial grid deposit) | Tier 2, not started |
| writeback | ~8 | disjoint + **per-cell RNG re-baseline** | Tier 2, not started |
| lifecycle (19% of *tick*) | — | detectThenApply | Tier 3, hardest |

## The exchange blocker (tomorrow's main topic — Stu is sketching ideas)
`passiveEnvExchange` (CytoBiologyCore.kt:73, grid primitives CytoMatterField.kt
:198–312) **cannot be parallelized bit-identically**, because:
1. **Order-dependent, not just racy:** `balanceBatched` moves
   `(leaf.count(sp) − cEff/n)/denom` against the leaf's *live* count, so if cell A
   depletes a leaf, cell B draws differently. Sequential cell order is load-bearing.
2. **Footprints straddle boundaries:** a cell's disc spans multiple quad-tree
   leaves, so no clean leaf-partition also cleanly partitions cells.
3. **Single-threaded cursor:** grid holds one shared `fpLeaves` — any parallel
   version must make it reentrant (pass the leaf list, don't store it).

Grid-cell partitioning is therefore a poor fit: race-free only with boundary
serialization/coloring, and **still not bit-identical** (any reorder changes the
result) → needs a re-baseline anyway, plus boundary bookkeeping.

**Key lever:** every transfer is **conservation-exact regardless of order** — only
the *distribution* changes. So the real fix is to make exchange
**order-independent**, after which parallelism is trivial AND bit-identical across
cores. The read-front/write-back form: snapshot each leaf at tick start, every cell
computes its draw against the *frozen* counts, accumulate cell→leaf deltas
additively (`AdditivePartition`), apply once with a per-leaf clamp when demand >
supply. This also re-baselines (frozen draws ≠ sequential depletion), but is
strictly better than partitioning: simpler, no boundary logic, deterministic on any
core count, and matches the double-buffer philosophy already adopted for cells.

Both viable paths need a **conscious golden re-baseline** — that's Stu's decision,
and where his non-grid-cell ideas should drive. If his ideas make exchange
order-independent, they dominate the partition approach outright; build on those.

## Expectation-setting
Full biology+lifecycle parallel ≈ 54% of tick; at a realistic ~5× on the parallel
portion, Amdahl gives ~1.7–1.8× whole-tick — real, not order-of-magnitude. Every
step golden-gated except the two deliberate re-baselines (exchange reformulation;
writeback per-cell RNG). Uncommitted: nothing — tree clean at `54ae5d3b`.
