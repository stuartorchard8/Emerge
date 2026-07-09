# Cyto — next session orientation (2026-07-09)

Start-here pointer for the next cyto perf session. Read this + memory
`reference_cyto_perf_levers` first. Working discipline (unchanged): commit each focused change on
`main` (no branches), golden-gate everything, show diffs before committing, A/B with the clean
per-JVM bench (never the contaminated back-to-back run).

## ⚠️ Golden gate is RED at baseline (recapture needed)
The `cyto: 4x world size` commit (`2ede0271`) changed world geometry (`CytoMatterField`/`CytoUnits`)
without recapturing the golden digests, so **6 tests fail at HEAD regardless of any code change**:
`CytoGoldenTest.{mutationOn, growthMutationOff, weldHealColony, scriptedInteractions, stickyWeldPair}`
and `CytoSoaSpecTest.acrossOrientedDivisionGrowsA2DSheetNotAThread`. `parallelMatchesSequential` still
passes, so the equivalence gate is intact — but the digest gate needs re-capturing (a gameplay/world
decision for Stu) before it can catch regressions again. Until then, verify perf changes by comparing
the failing-set with/without the change (identical set ⇒ bit-identical), not by "all green".

## Where we are (what just landed)
- **Biology `finish` is now parallel (2026-07-09, commit `dfd1ce04`).** `detect-then-apply`: cell-local
  `finishCompute` (degrade + death + radius) runs slot-partitioned via `ColumnPartition.disjoint`;
  `degrade` stages its grid deposit onto `CellWork` and the deposit + divide/destroy + weld-heal/morphogen
  harvests replay serially in k-order. @8192-spread/8-core: `bio:finish` 5444→~4550µs (~15%), whole PAR
  tick 22.8→~21.6ms. Modest because the serial apply loop (map harvests) was intentionally left serial.
- **Re-profiled bio-sub mix (PAR µs @8k):** exchange 4640, **finish 4550**, build 3000, genes 1550,
  writeback 1690, internalTouching 820. After this commit the top serial remaining is **writeback (~1690,
  needs a per-cell RNG re-baseline → new golden)**; build/genes/exchange/finish now all parallel.

- **The SoA-native lifecycle is COMPLETE — the AoS round-trip is deleted.** Detach, destroy, weld,
  weld-heal, and division all run in place on the persistent `CytoWorld` (`applyLifecycle` in
  `CytoSoaReducer`); `bridgeLifecycle` and the dead `CytoLifecycleSystem` (255 lines) are gone, plus
  the orphaned intent classes and the dead `toSimState(includeImpulse)` param. Full write-up +
  perf: `docs/cyto-soa-lifecycle-plan.md` (marked complete).
- **Result (clean A/B @8192-spread / 10278 cells, parallel biology ON in both arms):** lifecycle
  30ms → 88µs; **whole tick PAR 72.9 → 28.2ms (2.59×)**; biology PAR 23.0 → 17.4ms.
- **The all-core-clock tax is relieved and biology parallelism now converts:** at HEAD, PAR 28.2ms
  vs SEQ 41.0ms = **1.46× whole-tick from biology parallelism** — up from the old 1.09× when the
  serial lifecycle ran 22% slower under the all-core clock. So parallelism is now paying off.
- **The only remaining AoS bridge in the tick is `bridgeInteraction`** (runs only on pointer input —
  spawns/taps — so it's off the hot path).

## The standing goal
Make cyto fast enough to afford a **4–16× larger world** (Stu shrank the torus to `CELLS_PER_AXIS=32`
purely for perf headroom; he'd re-inflate if perf allowed). Bigger world ⇒ more grid-cells traversed
⇒ biology (esp. exchange) gets MORE expensive, so the lever is cutting biology cost — via parallelism
on the hot sub-phases and/or behavioural caps.

## Next steps (in recommended order)

### 1. Re-profile the biology sub-phase mix now that lifecycle is off the tick (cheap, sets priorities)
The % shares in `cyto-parallel-next-session.md` (exchange 36%, build/genes/finish ~18%) predate this
work. Re-run the `profile` bench and read the `bio-sub` SEQ/PAR breakdown to re-rank the remaining
serial sub-phases before investing. Note the `profile` PAR variant **already enables parallel biology**
(`bioParallelThreshold = 2` when an executor is present) and keeps springs sequential to isolate the
biology effect — so its PAR/SEQ numbers already reflect parallel biology.
```
./gradlew :demos:cyto:jvmTest --tests "*CytoBench.profile*" --rerun-tasks \
  -Dcytobench=1 -Dcytocells=8192 -Dcytospread=1        # runs SEQ then PAR; /tmp/cytobench_out.txt
# add -Dcytovariant=seq|par to run one variant per JVM (avoids JIT/clock cross-contamination)
```
Sweep harness: `scratchpad/bio_parallel_sweep.sh` (8-core) / `bio_sweep_former.sh` (20-core `former`).

### 2. Tier-2 biology parallelism — the remaining headroom — see `docs/cyto-parallel-next-session.md`
That doc has the full roadmap + patterns (`disjoint` / `additive` / `grid-cell` / `detectThenApply`
in `ColumnPartition`). Remaining sub-phases, by share of biology @8k:
- **finish (~18%)** — `detectThenApply`: per-cell degrade/biomassRadius/death compute parallel, the
  grid deposit (death recycling) serial. Not started.
- **writeback (~8%)** — `disjoint`, but needs a **per-cell RNG re-baseline** (mutation draws) → a new
  golden. Not started.
- build / genes / exchange already parallel; internalTouching / quanta intentionally serial (<2%).
- Amdahl ceiling for full biology+lifecycle parallel ≈ **1.7–1.8× whole-tick** at a realistic ~5× on
  the parallel portion — real, not order-of-magnitude.

### 3. Behavioural levers (bit-CHANGING — a gameplay decision, do with Stu)
Per the 2026-07-04 finding, code micro-opts on biology are exhausted; the residual cost is
quad-tree-footprint + genome-volume bound. **Capping genome growth / blob (clonal-cluster) size** is
more leverage than further micro-opts, but it changes the sim (new goldens, selection dynamics) — so
decide it on gameplay merits, not perf alone.

## Small remaining debt (optional tidy-ups)
- Biology sub-phase mix should be re-profiled now that lifecycle is off the tick — the % shares in
  `cyto-parallel-next-session.md` predate this work; exchange/finish/writeback shares will have grown.
- `CytoInteractionSystem` is the last hot-adjacent AoS bridge; only matters if pointer interaction
  ever lands on the perf-critical path (currently it doesn't).

## Gates (must stay green — this is the whole discipline)
```
./gradlew :demos:cyto:jvmTest --tests "*CytoGoldenTest*" --tests "*CytoSoaSpecTest*"
```
- `CytoGoldenTest`: 5 golden scenarios (growth / mutation / interact + **weldHeal + stickyWeld**),
  `parallelMatchesSequential`, `grownStateRoundTrips`. NB there is **no** `CytoSoaEquivalenceTest` —
  the AoS oracle was retired; the goldens ARE the bit-identity gate.
- `CytoSoaSpecTest`: property invariants (welding, conservation, chem cap, oriented division, save
  round-trip). A deliberate behaviour change (e.g. a parallel re-baseline) means re-capturing the
  affected golden digests and justifying it in the commit — never silently.
