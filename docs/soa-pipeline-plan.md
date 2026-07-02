# SOA Pipeline Plan

## Goal

Eliminate per-tick allocation hot path in the Emerge engine by migrating demos from AoS (`EcsBuilder`/`SimState`) to SOA (`SoaWorld` + column stores). Each demo's hot systems iterate raw field arrays with zero allocation; cold systems (lifecycle, interaction, serialization) run through the existing AoS path at phase boundaries.

**Target demos:** Cyto (done), Drockets (next), Scavengers (later). Norns is excluded — it uses hand-written OOP, not the engine ECS.

---

## Current State

### Cyto — Vertical Slice (Complete)

`CytoWorld` wires `SoaWorld` + ~8 domain columns + `SpringCsr`. Hot systems (reset, contacts, biology, connections, forces, integrate) run on raw `IntArray`/`LongArray` fields. Cold systems (lifecycle, interaction) bridge via `SimState` materialization.

**Results on benchmark** (`cyto-save-17-jun.bin`, ~2417 cells, 500 warmup + 2000 measure):

| | Baseline | After CSR | Δ |
|---|---|---|---|
| Allocation | 37,582 MB | 23,350 MB | −38% |
| KB/tick | 18,350 | 11,400 | −38% |
| Tick avg | 36,781μs | 25,150μs | −32% |
| GC collections | 60 | 37 | −38% |

**Baseline benchmarks — `cyto-save-20260702.bin`** (recorded 2026-07-02, 500 warmup + 2000 measure):

Load state: 2051 cells, 3850 connections, genome size: min=17 med=17 max=17, cyto species: min=7 med=8 max=9, cyto molecules: min=478 med=4919 max=12969
End population: 2243 cells

| Metric | Sequential | Parallel (8 workers) |
|---|---|---|
| Allocation | 34,187 MB | 34,206 MB |
| KB/tick | 16,693 | 16,702 |
| Tick avg | 31,552μs | 30,649μs |
| Tick p50 | 28,872μs | 28,270μs |
| Tick p95 | 46,840μs | 46,040μs |
| Tick max | 77,932μs | 65,234μs |
| GC collections | 69 | 69 |
| GC pause | 508 ms | 496 ms |
| FPS headroom | 0.53x | 0.55x |

Phase breakdown (sequential):

| Phase | Avg μs | Max μs | Share |
|---|---|---|---|
| biology | 23,287 | 48,845 | 42.5% |
| bio:exchange | 8,689 | 17,862 | 15.8% |
| bio:genes | 6,536 | 13,575 | 11.9% |
| bio:diffuse | 5,132 | 15,427 | 9.4% |
| forces | 2,397 | 11,363 | 4.4% |
| lifecycle | 2,209 | 35,105 | 4.0% |
| connections | 2,137 | 10,767 | 3.9% |
| bio:build | 1,438 | 8,569 | 2.6% |
| bio:finish | 794 | 7,579 | 1.5% |
| contacts | 780 | 1,345 | 1.4% |
| interact | 670 | 14,705 | 1.2% |

Key observations:
- `biology` is 42.5% of time (gene apply + writeback + quanta + internalTouching = ~56% of biology)
- `bio:exchange` 15.8% — species scanning across grid cells
- `bio:diffuse` 9.4% — matter diffusion
- Forces + connections = 8.3% — spring solve is cheap relative to biology
- Parallel gives minimal gain (30,649 vs 31,552 — only 3% faster) because biology is single-threaded
- GC pause is tiny (508ms over 2000 ticks) — the allocation rate is the main problem, not GC pressure

### Drockets — Partial Migration (Hybrid)

`DrocketsSoaReducer` (642 lines) has 6 phases hand-written in SOA: `reset`, `forceGather`, `integrate`, `aiAndMotion`, `attachment`. Remaining 2 phases (`contactAndLifecycle`, `effects`) bridge through AoS.

~200-800 entities, 5 entity types (drocket, knight, planet, particle, atmosphere), 18 component types.

### Engine Infrastructure — Available

| Layer | Status | Notes |
|-------|--------|-------|
| `SoaWorld` | Engine, used | Mutable-in-place, insertion-ordered |
| `ComponentColumns` | Engine, used | Dense-set + sparse reverse lookup |
| `ColumnStore<T>` | Engine, used | ~17 engine types hand-written |
| `ColumnPartition` | Engine, used | `disjoint`, `AdditivePartition`, `detectThenApply` |
| `SpringCsr` | Engine, used | CSR adjacency over dense slots |
| `SoaCompat` | Engine, used | AoS-compatible view for cold systems (~25 systems) |

### Engine Infrastructure — Missing

| Gap | Impact | Effort |
|-----|--------|--------|
| No `SoaBuilder` API | Hot systems lack `getComponent`/`update`/`remove` surface | 1 day |
| No `SoaPipeline`/`SoaPhase` | Phase orchestration is hand-dispatched | 2 days |
| No `materializeToSimState()` | SoaWorld → SimState conversion is demo-specific | 1 day |
| No codegen for `ColumnStore` | Each new component type requires ~200 lines by hand | 3-5 days |
| No fork/merge for `SoaWorld` | Parallel isolated phases unavailable | Out of scope — cold systems use AoS fork/merge |

---

## Target Architecture

### Hot Systems — Sequential on Shared World

Hot systems run one-after-another on the mutable `SoaWorld`. No fork/merge. This is the allocation elimination path.

```
SoaPhase(name, systems[], Sequential)
    └── for (system in systems) system.update(cfg, SoaBuilder, inputs)
```

The 38% allocation reduction and 29% tick time reduction on Cyto came from eliminating per-tick HashMaps/ArrayLists, not from parallel execution. Fork/merge is not the bottleneck for hot systems.

### Cold Systems — Isolated via AoS

Systems that need entity lifecycle (spawn/destroy) or fork/merge run as `EcsSystem<C, SimState, I>` through the existing pipeline. They materialize to `SimState` once per phase boundary, fork, run, merge.

```
SoaPhase(name, systems[], Isolated)
    └── snapshot = world.materializeToSimState()
    └── for (system in systems) { fork → system.update(cfg, fork, inputs) }
    └── for (fork in forks) world.applyFromSimState(fork.build())
```

### Renderer — Still Reads SimState

Zero change to rendering. The renderer reads `SoaFrame` which wraps a materialized `SimState` + optional `SpringCsr` for connection lines. Materialization happens at display cadence (~100 Hz), not every tick.

### Serialization — SimState → bytes

Save/load stays AoS-compatible. The codec reads `SimState` which is a thin materialization of the SoA columns.

---

## Implementation Plan

### Phase 0: Engine Additions (3 days)

1. **`SoaBuilder`** — thin wrapper over `SoaWorld`
   - `getComponent(id, type)` — gather from columns
   - `update(id, type) { ... }` — gather → apply → scatter
   - `remove(id)` — tombstone across columns
   - `forEachSlot(type, action)` — allocation-free iteration (raw slot index)
   - `writeLog` — for fork/merge replay (nullable)

2. **`SoaWorld.materializeToSimState()`** — generic conversion
   - Iterates all `ComponentColumns`, calls `gatherAt` for each alive slot
   - Produces `SimState` with `ComponentStore` of `ComponentTable`s

3. **`SoaPhase` / `SoaPipeline` / `run()`** — phase orchestration
   - `SoaPhase<C>(name, systems[], kind: PhaseKind)`
   - `PhaseKind.Sequential` → hot path
   - `PhaseKind.Isolated` → cold path (via `materializeToSimState()`)
   - `run(cfg, world, inputs, pipeline)` — main entry point

### Phase 1: Cyto Refactor (2-3 days)

- Convert `CytoSoaReducer` phases → `SoaPhase<CytoConfig>` list
- Convert hot systems → `SoaSystem<CytoConfig>`
- Keep cold systems as `EcsSystem<C, SimState, CytoInput>` in `Isolated` phases
- Remove manual phase dispatch boilerplate
- Update golden tests to pass `springData` through pipeline runner

### Phase 2: Drockets Full Migration (1-2 weeks)

**Drockets is the next migration target.** It already has a working hybrid SOA reducer (`DrocketsSoaReducer`, 642 lines) with regression tests proving bit-identical results. Remaining work:

1. Convert remaining bridged phases (`contactAndLifecycle`, `effects`) from SimState bridge → pure SOA
   - `ContactSystem` — already uses `ColumnPartition.detectThenApply` in SOA path
   - `CrashSystem`, `BounceSystem`, `RollingResistanceSystem` — iterate contacts, modify impulse
   - `ParticleSystem`, `DrocketParticleSystem` — spawn particles (cold system, can stay Isolated)
   - `RespawnSystem`, `DamageSystem` — entity lifecycle

2. Wire up `SoaPipeline<DrocketsConfig>` from `DrocketsSoaReducer`

3. Add Drockets-specific column stores if any new ones needed (e.g., `ParticleColumnStore` — engine already has one)

4. Update regression tests: `DrocketsSoaPhaseEquivalenceTest`, `DrocketsSoaEquivalenceTest`

**Why Drockets over Scavengers:**
- Higher entity count (200-800 vs ~50-50) — SOA benefits scale with entity count
- More diverse entity types (5 types with different component subsets) — AoS wastes more memory bandwidth
- Already has a working SOA reducer with regression tests
- Scavengers' hot path is `ForceFieldSystem` which is O(n^2) pair-check — algorithmic improvement (spatial hashing, Barnes-Hut) would help more than SOA layout

### Phase 3: Scavengers Migration (3-5 days, future)

- All 8 phases run through `EcsSystem` pipeline — start converting one phase at a time
- Hot path is `ShipThrustSystem` (simple input→impulse) + `ForceFieldSystem` (O(n^2) pair-check)
- `ForceFieldSystem` would benefit more from algorithmic improvement than SOA alone

### Phase 4: Codegen (nice-to-have, 3-5 days)

- KSP plugin to generate `*ColumnStore` implementations from component data classes
- Reduces per-component wiring from "write 200 lines" to "annotate 1 data class"

---

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Hot system parallelism | Sequential on shared world | Fork/merge adds complexity; hot systems already fast after allocation elimination |
| Cold system fork/merge | AoS (SimState) via isolated phases | Cold systems rarely need it; materialization cost amortized at phase boundary |
| Renderer | Still reads SimState via materialization | Zero change to rendering; materialization at display cadence |
| Serialization | SimState → bytes | Codec stays AoS; materialization at save time |
| Entity lifecycle | Tombstone + deferred compaction at phase boundaries | Matches Cyto's pattern; avoids mid-phase CSR rebuilds |
| ColumnStore | Hand-written per component type | Acceptable for now; codegen in Phase 4 |
| SoaBuilder API | `getComponent`/`update`/`remove` + `forEachSlot` | Thin wrapper, minimal surface area, consistent with engine's existing APIs |

---

## Tradeoffs

| Aspect | Tradeoff |
|--------|----------|
| Mixed API surface | Hot systems use raw columns; cold systems use `SoaCompat` → objects. Developers need to know which is which. |
| Materialization overhead | `SoaWorld` → `SimState` involves object allocation, but only at phase boundaries (not per-tick). |
| Deferred compaction | Tombstones are compacted at phase boundaries, not immediately. Side-tables (CSR, adjacency lists) must rebuild on compaction. |
| No generic SOA query system | Multi-component queries (e.g., "all entities with Transform + Motion") not available. Hot systems iterate by column and check membership. |
| Engine API split | Two system types (`SoaSystem<C>` and `EcsSystem<C, S, I>`) — adds documentation/mental overhead. |

---

## Success Criteria

1. **Engine** — `SoaBuilder`, `SoaPipeline`, `materializeToSimState()` in engine core, used by at least one demo
2. **Cyto** — `CytoSoaReducer` migrated to `SoaPipeline` without regression in golden tests or benchmark
3. **Drockets** — All phases pure SOA (no SimState bridge), bit-identical regression tests pass
4. **Allocation** — Per-tick allocation reduced to <5MB for 1000+ entity demos (target: match or beat Cyto's 11.4 MB/tick)
