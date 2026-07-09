# Cyto — next session orientation (2026-07-09)

Start-here pointer for the next cyto perf session. Read this + memory
`reference_cyto_perf_levers` first. Working discipline (unchanged): commit each focused change on
`main` (no branches), golden-gate everything, show diffs before committing, A/B with the clean
per-JVM bench (never the contaminated back-to-back run).

## ✅ Golden gate GREEN again (was red after the 4× world)
The `cyto: 4x world size` commit (`2ede0271`, CELLS_PER_AXIS 32→64, MAX_DEPTH 6→7) changed world
geometry without recapturing goldens and introduced a real light-starvation regression. Both fixed
2026-07-09; **all 44 `CytoGoldenTest` + `CytoSoaSpecTest` cases pass.** Two commits:
1. `50689c61` — recaptured all 5 golden scenarios for the enlarged torus (pure world-size re-baseline;
   parallelMatchesSequential + grownStateRoundTrips held).
2. `99ed1104` — **`LIGHT_FALLOFF` now scales with the world** (`CELLS_PER_AXIS/4`, was a fixed 8). The
   moving daylight band's half-width was a fixed logical constant, so in the doubled torus it covered
   only half the relative slice and — starting at −HALF, twice as far from a center-seeded cell — its
   gaussian tail reached the founder ~225 ticks later. The `acrossOrientedDivision` autotroph never
   caught daylight within its 1500-tick budget and starved (biomass 2474→dead, never dividing). Scaling
   FALLOFF keeps the day-band at a constant 1/8 of the torus span (self-similar day/night under any
   world size); re-baselined the two light-driven goldens (growth, interact). **Lesson: after a world
   size change, audit fixed logical constants (`LIGHT_FALLOFF`, `LIGHT_ORBIT_PERIOD`, …) for ones that
   should scale with `CELLS_PER_AXIS` — period-doubling alone does NOT fix onset timing.**

## Where we are (what just landed)
- **Biology `finish` is now parallel (2026-07-09, commit `dfd1ce04`).** `detect-then-apply`: cell-local
  `finishCompute` (degrade + death + radius) runs slot-partitioned via `ColumnPartition.disjoint`;
  `degrade` stages its grid deposit onto `CellWork` and the deposit + divide/destroy + weld-heal/morphogen
  harvests replay serially in k-order. @8192-spread/8-core: `bio:finish` 5444→~4550µs (~15%), whole PAR
  tick 22.8→~21.6ms. Modest because the serial apply loop (map harvests) was intentionally left serial.
- **Biology `writeback` is now parallel (2026-07-09, commit `c6cb0734`).** Replaced the single shared-LCG
  mutation draw with a **per-cell splitmix64 stream** keyed on (world seed, entity id, tick) — order-
  independent, so the loop runs via `ColumnPartition.disjoint`. splitmix64 avalanching guarantees adjacent
  entity ids (clonal clusters) get decorrelated streams (`MutationRngTest` guards it). Bit-changing:
  re-baselined `mutationOn` only (growth/interact never draw). writeback ~1690→~1255µs (~25%).
- **Re-profiled bio-sub mix (PAR µs @8k):** exchange ~4600, finish ~4550, build ~3000, genes ~1550,
  writeback ~1255, internalTouching ~820. **All per-cell biology sub-phases are now parallel.** The only
  serial ones left are internalTouching (~5%, cross-cell weld-neighbour set — order-sensitive) and the
  tiny quanta/diffuse/slab-swap tails (<2% each). Per-cell biology parallelism is essentially exhausted —
  the next lever is either the **serial exchange pre-pass / internalTouching**, the **spring solver**, or
  the **behavioural caps** (below).

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

Bench (clean per-JVM variant — always use this, never back-to-back):
```
./gradlew :demos:cyto:jvmTest --tests "*CytoBench.profile*" --rerun-tasks \
  -Dcytobench=1 -Dcytocells=8192 -Dcytospread=1 -Dcytovariant=par   # or seq; /tmp/cytobench_out.txt
```
Sweep harness: `scratchpad/bio_parallel_sweep.sh` (8-core) / `bio_sweep_former.sh` (20-core `former`).
**Caveat:** after many back-to-back benches the machine thermally throttles — all phases inflate ~20%
together. Compare a phase's SHARE / A-B against the parent commit, not raw absolute tick, when the box
is warm.

### 1. Per-cell biology parallelism is DONE (finish + writeback landed this session)
build / genes / exchange / finish / writeback are all `ColumnPartition`-parallel now. Remaining serial:
**internalTouching (~5%)** — the cross-cell weld-neighbour set (order-sensitive; would need `additive`
or a detect-then-apply re-baseline for a small share) — and the tiny quanta/diffuse/slab-swap tails.
Diminishing returns on further per-cell biology work; look higher up the tick.

### 2. Next parallelism frontiers — DEPENDS ON SCENARIO (autotroph vs multicellular)
Profile the target scenario with `-Dcytosave=/abs/path.bin` (welded colonies) — the phase mix differs
sharply from the non-welding autotroph:

**(a) Non-welding autotroph (grown 8698 cells, PAR, 14.2ms):** biology **72%** (all sub-phases parallel),
lifecycle 8%, and **weld physics ~0** (springSolve ~4µs, connections ~10µs — cells never weld). For THIS
scenario the spring/connections phases are non-targets and biology's serial exchange pre-pass / lifecycle
are the only levers left.

**(b) Multicellular "support genome" save (`cyto-support-save.bin`, 2619 welded cells) — the likely
gameplay target.** SEQ 17.7ms → PAR 11.3ms (1.57×). Per-cell cost is **2.4× the autotroph** (4.1 vs
1.7µs). Biology 12948→6140 (**2.11×**), springSolve 1375→868 (1.58×, already parallel).
- **`ConnectionsSystem` + `DragSystem` — DONE (2026-07-09, commit `3aca7dda`).** Both weld-physics phases
  were serial and ran SLOWER under PAR than their own SEQ (all-core-clock tax). Parallelised bit-identically
  (Drag: `disjoint`; Connections loop-2: `detectThenApply` over cells, emit break-keys, serial prune;
  loop-1 min-map stays serial). Same-snapshot PAR before→after: **connections 2002→1396µs (0.78×→1.12× vs
  SEQ), drag 788→326µs (0.77×→1.87×)** — ~1070µs clock-tax relief. New `parallelMatchesSequentialWeldedColony`
  test (packed moving sticky grid) covers the welded parallel path the tiny weld goldens didn't. No re-baseline.
- **Weld physics is now handled.** Remaining PAR cost in the welded scenario is biology (genes ~2100 /
  exchange ~1600 / diffuse ~800 µs — the last two the serial-ish ones) and the still-serial connections
  loop-1 min-map (cheap). Biology's serial **exchange pre-pass** and **lifecycle** are the deepest levers
  left; beyond that it's the behavioural caps below.

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
