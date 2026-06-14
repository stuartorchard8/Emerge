# Cyto SoA landing plan — toward the large-population end state

Status: planning (2026-06-14). Owner: perf track. Companion to `MORPHOGENESIS.md` (biology design is
the contract; this doc is the data-layout/performance contract).

## Why (the measured case)

`profileCytoScale` replicates the real save's evolved cells to N≈500/2k/5k and profiles per phase. The
phases scale very differently:

| phase | N≈100 | N≈2–8k | scaling |
|---|---|---|---|
| matter diffusion (`interact`) | 14% | <5% | **O(grid)=constant** — flatlines, *not* a lever |
| biology | 33% | <15% | O(N) but modest per-cell — *recedes* at scale |
| **spring solve (`forces`)** | 29% | **41–48%** | O(N), dominant |
| **connection maintenance** | small | ~17% | O(N) |
| **contacts** | 7% | 15–20% | O(N), + density cliff near sources |

Total allocation climbs to **~720 MB/tick at N≈21k** (GC pauses into the seconds), dominated by the
per-tick immutable `ComponentTable` copy-on-write rebuild plus spring solver/maintenance temporaries.

**Conclusion:** the scale bottleneck is the *physics* + the *per-tick component-store copy churn* — not
biology, not chemistry. SoA targets both directly:

- `SoaWorld` mutates columns **in place** — no per-tick `SimState`/table rebuild → the 720 MB/tick churn
  disappears structurally.
- `SpringCsr` (compressed-sparse-row springs) targets the #1–#2 phases (`forces`/`connections`).
- Columnar physics iterates primitive arrays instead of chasing boxed components in hash maps.

Drockets proved this pattern under equivalence tests but **never wired it live**; cyto's SoA path was
deleted whole in the matter rework (`c3bd291`). The job is to **re-add cyto's columns and land SoA as
the live runtime**, gated for byte-faithful behaviour.

## What already exists (reuse, don't rebuild)

**Engine framework — intact, generic, drockets-exercised** (`engine/.../ecs/soa/`):
- `SoaWorld` — in-place columnar world; entity allocation **byte-identical to `EcsWorld`** (monotonic
  cursor, live-id set, dense insertion-order slots); `compact()` returns a slot remap for side-tables.
- `ComponentColumns<T>` / `ColumnStore<T>` — sparse-set membership + hand-written scatter/gather/moveSlot.
- `PhysicsColumns` — stock stores for **Transform/Motion/Impulse/Collider/Material** (raw Int/Long
  arrays). **Reusable as-is.**
- `SpringCsr` — CSR adjacency (`offset`/`otherSlot`/`otherId`/`restRaw`/`stiffRaw`/`dampRaw`) + a
  per-edge `edgeAux: FloatArray` (our connection damage). `build`/`rebuildFrom`.
- `SoaCompat` — AoS↔SoA shim: `update`/`entries`/`forEachSlot`. Lets a phase run the **unmodified AoS
  system** via a materialize→run→reload bridge.
- `ColumnPartition` — bit-identical parallel patterns: `disjoint`, `AdditivePartition` (order-independent
  integer impulse accumulation), `detectThenApply`.
- **No SoA physics *systems* exist** — only data + helpers. Drockets ports the hot ones inline in its
  reducer; we do the same.

**Drockets reference pattern** (`demos/drockets/.../soa/`, model to mirror):
- `DrocketsWorld(fromSimState/toSimState)` wraps `SoaWorld` + object side-tables for irregular shapes.
- `DrocketsSoaReducer.tick(world, input)` runs ported phases **in place** and **bridges** hard phases
  (`bridge(w, phases)` = `SimBuilder(w.toSimState())` → `runSequential(AoS systems)` → `fromSimState`).
- Impulse is a **dense accumulator**, re-densified after each bridge.
- Gated by `DrocketsSoaEquivalenceTest` (structural per-table equality, lockstep vs AoS, every checkpoint)
  and `DrocketsWorldRoundTripTest` (lossless `fromSimState→toSimState`). **Shadow only — `DrocketsController`
  still runs the AoS reducer.**

**Deleted cyto path** (recover from `git show c3bd291^:<path>`):
- `CytoWorld`, `CytoCellColumnStore`, `CytoSoaReducer`, `CytoLifecycle`, `CytoSoaEquivalenceTest`,
  `CytoPerfBenchmark`, `CytoEnergyGrid`.
- **Reusable:** the slot-aligned `CytoWorld` design, physics column stores, `SpringCsr` + `edgeAux`, the
  `CytoLifecycle` structural path, and reducer phases reset/contacts/connections/forces/grab/lifecycle/
  integrate/interact. `touch`/`sticky`/`type`/`genome`/`mass` columns carry over.
- **Obsolete (chemistry):** `energy`/`energyPending` Frac columns + `extraChem` side-tables, `CytoEnergyGrid`,
  `divideCharge`, and the old `runReactions` biology — all superseded by the matter model.

## Key design decisions

1. **Defer the chemistry redesign.** Keep `cytoplasm`/`biomass` as **object columns**
   (`Array<Map<String,Int>?>`) in `CytoCellColumnStore` initially — gather/scatter the existing
   `CytoCellComponent` verbatim. This is faithful and low-risk. It does *not* speed biology — but biology
   is <15% at scale, and the win we're chasing is physics + copy-churn. The interned-int-species rework
   (the lever that *would* speed biology) is a **later, separate slice**, not a blocker for landing SoA.
2. **Matter reservoir stays a singleton on the world**, not a column — `CytoMatterGrid` (already
   copy-on-write) held on `CytoWorld`, mutated in place per tick. `ConnectionStateComponent` damage maps
   onto `SpringCsr.edgeAux`.
3. **Correctness gate = structural equality + conservation + round-trip**, per drockets: lockstep the SoA
   reducer against the AoS `CytoReducer` (kept as the reference oracle), compare component tables every
   checkpoint, assert the matter-conservation invariant, and assert `toSimState` round-trips losslessly.
4. **Live runtime via the controller**, which is already shaped for it: `CytoController` buffers input and
   materializes once per frame; today it calls the AoS reducer (its docstring still describes the SoA
   path). Flipping it: hold a `CytoWorld`, step in place, `CytoFrame(world.toSimState())` once/frame.
   Render / hit-test / readouts / `CytoSaveCodec` all keep consuming `SimState` unchanged.

## ⚠ Implementation finding (2026-06-14): phases can't be bridged piecemeal

Slices 0–1 are **done and green** (`CytoSoaEquivalenceTest`). Starting Slice 2 surfaced a constraint
that **revises the rest of the plan**: the pipeline phases are tightly coupled by two intra-tick
handoffs that don't survive a materialize/reload boundary —

- **Impulse** is accumulated across `reset → contacts → connections → forces → grab/drag` and consumed
  by `integrate`. `toSimState` drops it (it's transient), so a bridge between any of these loses it.
- **Events**: `contacts` emits `WeldIntent`; `biology` emits `CellDivisionIntent`/`CellDestroyIntent`;
  `lifecycle` consumes all of them. These flow within one `runSequential`; split the producer from the
  consumer across separate bridges and they vanish. The producers (contacts@3, biology@4) and consumer
  (lifecycle@7) span almost the whole pipeline, with `connections`/`forces` in between.

So there is **no safe "port one phase, bridge the rest" intermediate** — the coupled core
(`contacts … lifecycle`) must move to in-place columns as a unit (carrying impulse in columns + intents
in reducer lists). Slices 2 and 3 therefore **merge into one in-place port**. The gate mechanism shifts
from "bridge the unported phases" to **per-phase isolation tests** (load a state → run the AoS phase via
a minimal builder vs the in-place phase → diff), which pinpoints divergence without needing a bridge.

Two viable shapes for the remaining work (see the open question at the bottom):
- **(A) Full in-place port** — reset/contacts/connections/forces/grab/drag/lifecycle/integrate/biology
  all on columns; removes every bridge and the whole copy-churn. Biggest win, biggest bit-identity risk
  (the matter biology — `CellWork`, gene loop incl. the futile-cycle guard, PRNG-ordered mutation,
  diffusion, mass/momentum — is the hard part).
- **(B) Physics-in-place, biology bridged** — port the physics cluster in-place (the dominant
  forces/connections/contacts cost + the per-tick physics copy-churn), keep `biology` (+the
  event-coupled `lifecycle` segment) bridged via event-marshaling for now, port biology last. Captures
  most of the measured win at much lower risk; biology retains its (≤15%) copy cost until the final step.

## Status (2026-06-14)

- ✅ **Slice 0–1** — `CytoWorld` columns + bridged reducer, equivalence-gated.
- ✅ **Slice 2** — physics phases (reset/contacts/connections/forces[grab+drag+Gauss-Seidel spring]/
  integrate) in place on columns + `SpringCsr` + impulse columns; biology + lifecycle bridged.
  Bit-identical vs AoS over 250 ticks (two faithful normalizations: impulse excluded, connection
  damage zero-normalized — both matching AoS's own `?: 0f` semantics).
- ✅ **Slice 4** — **SoA is the LIVE runtime**: `CytoController` drives `CytoSoaReducer` over a
  persistent world, materializing once/frame. Already faster than AoS at the save's ~107 cells
  (1249 → 915 µs/tick, 1.37×; max tail 13193 → 3130 µs), win grows with N. `benchCyto` has a SOA variant.
- ⏳ **Slice 3** (remaining) — port **biology + lifecycle** in place to delete the last two bridges
  (the per-tick materialize churn). The hard, bit-identity-critical part (gene loop incl. the
  futile-cycle guard, PRNG-ordered mutation, diffusion, mass/momentum; division's cytoplasm/biomass
  split). Do it under the same equivalence gate.
- ⏳ **Slice 5** (remaining) — turn on `ColumnPartition` parallelism for the physics phases at scale;
  replace the object cytoplasm/biomass columns with interned-int columns (the deferred chemistry lever).

## Slices (each independently landable and gated)

> Order rationale: **bridge-everything first** so we have a green equivalence gate *before* optimizing
> (done — Slices 0–1). Then port the coupled core in-place, gated by per-phase isolation tests. The
> physics phases port largely verbatim from the recovered energy-model reducer (they're
> chemistry-agnostic); biology + lifecycle-division differ for matter and carry the real risk.

**Slice 0 — `CytoWorld` + columns (pure addition, no runtime change).**
- Recover `CytoWorld`/`CytoCellColumnStore` from `c3bd291`; strip the energy columns; add object columns
  for `cytoplasm`/`biomass` + `wear`; keep `type`/`sticky`/`stickyTemp`/`genome`/`logicalRadius`. Reuse
  the physics column stores + `SpringCsr` (edgeAux = connection damage). Hold `CytoMatterGrid` on the world.
- `fromSimState`/`toSimState` (model on `DrocketsWorld`), incl. the grid singleton + spring/damage tables.
- **Gate:** `CytoWorldRoundTripTest` — `fromSimState(decode(save)).toSimState()` equals the original
  per-table (cells, transforms, springs, connection damage, matter grid, PRNG seed, tick, lastEntityValue),
  on a grown/advanced state.

**Slice 1 — `CytoSoaReducer` skeleton, full bridge (correctness scaffold).**
- `tick(world, input)` that bridges **every** phase via `SoaCompat` (materialize → `runSequential` over
  the existing AoS pipeline → reload). Equivalence is then trivially true; the only risk under test is
  round-trip fidelity.
- **Gate:** `CytoSoaEquivalenceTest` — lockstep AoS vs SoA over N ticks on the save + a *growing* colony;
  structural per-table equality at each checkpoint + matter-conservation invariant. Mirror the drockets test.

**Slice 2 — port the hot physics phases to columns (the actual win).** One sub-slice per phase, re-running
the gate after each; each reuses the exact `Frac`/`Coord` operators on column raws (bit-identical by
construction):
- `reset` (ImpulseReset) — zero impulse columns.
- `integrate` — `vel += impulse; pos += vel` in place.
- `forces` (SpringConstraint) — solve over `SpringCsr` via `AdditivePartition` (order-independent integer
  impulse). **The #1 phase.**
- `connections` (CytoConnectionMaintenance) — refresh rest lengths, accrue stress into `edgeAux`, flag
  breaks; `disjoint` partition.
- `contacts` (ContactSystem broadphase + CytoContact) — `SpatialGrid` over columns, `detectThenApply`.
- Biology / interact / lifecycle stay bridged here. After this slice the per-tick table copies for the
  ported phases are gone and forces/connections run on the CSR — the dominant cost removed.

**Slice 3 — port biology + lifecycle + interact.**
- `biology`: build `CellWork` per cell from columns + the object cytoplasm/biomass columns + the matter
  grid; run `CytoBiologyCore` **unchanged**; write back. Import stays sequential (shared grid, EntityId
  order) — preserve that ordering and the mutation-PRNG order for byte-identity.
- `lifecycle`: recover `CytoLifecycle` (division/death/weld/detach → `SoaWorld.compact` + CSR rebuild),
  adapted to matter (daughters split cytoplasm/biomass; ascending-mother-id allocation).
- `interact`: matter diffusion already runs on the grid singleton → operate on `world.grid` in place.
- **Gate:** equivalence incl. growth/division/death scenarios + conservation.

**Slice 4 — flip to the live runtime.**
- `CytoController`: hold a `CytoWorld` (native `createCytoInitialWorld`, or `fromSimState(createCytoInitialState())`),
  step the SoA reducer in place; `CytoFrame` from `world.toSimState()` once per frame. Save =
  `toSimState→encode`; restore = `decode→fromSimState` (unchanged formats).
- Keep AoS `CytoReducer` as the CI oracle and for headless probes (or migrate probes later).
- **Gate:** run the app (desktop) — visual + save/load parity; equivalence test stays in CI.

**Slice 5 — optimization (ongoing, post-landing).**
- Turn on `ColumnPartition` parallel paths for forces/connections/contacts/biology at the N where fan-out
  pays (executor exists; was a net loss at N=100, pays at thousands).
- **Chemistry → interned-int columns**: replace the object cytoplasm/biomass columns with dense
  interned-species int columns (the bounded-species property from MORPHOGENESIS) — removes the residual
  biology allocation and makes biology array-indexed. Gated by equivalence.

## Risks / watch items

- **Determinism:** Import draws from the shared grid sequentially in EntityId order; mutation PRNG advances
  per gene in EntityId order. Both must match AoS exactly — these are the byte-identity tripwires.
- **`toSimState` is load-bearing** (render + save read it) — the Slice 0 round-trip gate guards it; keep it green.
- **Contacts density cliff:** broadphase drifts toward O(k²) per grid cell as colonies pack near the 4
  fixed matter sources (the long-window profile hit 70% at N≈25k). Independent of SoA; may need a per-cell
  cap or dense-region handling as N grows. Track with `profileCytoScale`.
- **Matter grid sharing across the materialize boundary** — the grid is already COW; ensure `toSimState`
  hands out the same instance semantics so a frame's render doesn't alias a mutating grid.

## Validation (every slice)

- `CytoSoaEquivalenceTest` (structural, lockstep vs AoS) + matter-conservation invariant — the go/no-go gate.
- `profileCytoScale` before/after — confirm the allocation + phase-time win at N≈2k/5k.
- `benchCyto` — single-population regression check.

## Rough effort

Slices 0–1 ≈ 1 day (scaffold + gates) · Slice 2 ≈ 1–2 days (the win) · Slice 3 ≈ 1–2 days · Slice 4 ≈ ½ day
· Slice 5 ongoing. The bridge-first ordering means a green gate exists from end of Slice 1, so every later
step is validated against a known-good baseline.
