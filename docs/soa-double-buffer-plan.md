# SoA Double-Buffer Plan

**Status:** design sketch (2026-07-08). No code yet. This is the durable home for the decision to
re-back the engine's ECS storage with double-buffered struct-of-arrays columns, and the record of
what that costs and gains.

## Motivation

Two storage worlds coexist in the engine today:

| | AoS (canonical) | SoA (cyto's) |
|---|---|---|
| Store | `ComponentTable<T>` = immutable `data class` over `Map<EntityId,T>` | `ComponentColumns<T>` over a hand-written `ColumnStore<T>` (primitive arrays) |
| Mutation | copy-on-write — `put` does `LinkedHashMap(values)`, a full copy | mutate slot in place |
| Reducer | `reduce(cfg, state: S, inputs): S` — pure, returns a fresh state | systems mutate a persistent `SoaWorld` in place, return `Unit` |
| Lifetime | rebuilt every tick | persists across ticks |

The AoS model reallocates touched tables every tick (GC churn); the SoA model mutates in place
(fast, but throws away the pure-reducer contract and forces a per-frame `materialize` bridge back to
`SimState` for renderer/save/hit-test). Cyto runs on the SoA world and measured **1.4×→4.5×** faster
(250→4000 cells), moving the 60fps ceiling from ~1500 to past 4000 — but only cyto benefits, and it
paid the model + boilerplate cost by hand.

**Goal:** make double-buffered SoA *the* engine ECS backend, so every game gets the throughput without
hand-rolling a private world — and, crucially, land the storage layout that hardware-accelerated
parallelism actually requires (you cannot vectorize a hashmap of objects).

## The idea: double-buffer all durable state

Take the graphics / cellular-automaton pattern. Each durable component column is stored **twice**
(front + back), allocated once and swapped, never reallocated per tick.

- During tick N, every system **reads the front** buffer (= end-of-tick-N-1, frozen) and **writes the
  back** buffer.
- At the tick barrier, **swap** front/back.

This threads the needle in the user's framing: the visible state is never mutated in place (front is
immutable for the whole tick), and nothing is reallocated per tick (buffers are reused).

Cyto is a cellular automaton; read-grid-N / write-grid-N+1 / swap is the textbook CA implementation.
This is the native pattern for the sim, not a foreign import.

### Durable vs scratch split

Only **durable component state** is double-buffered. **Intra-tick scratch** (accumulated forces,
contact lists, `CellWork`, `CytoPipelineState`) stays single-buffered mutate-in-place — it is rebuilt
each tick from the frozen front and discarded at the swap, so it never needed a snapshot. This split
already matches the phase structure cyto has.

## What double-buffering recovers (vs a plain mutate-in-place pivot)

1. **Determinism-by-construction.** The pure reducer's real value was referentially-transparent reads:
   a system sees a consistent frozen input. Frozen-front reads restore exactly that without per-tick
   allocation. We lose the literal value-return, keep the property that made it worth having.

2. **Lock-free, deterministic parallelism — the headline.** Read-front/write-back is the GPU
   compute-pass model: readers share an immutable input buffer (no locks); writers own disjoint slot
   ranges of the output buffer (no contention). Parallelism becomes "partition the slot range across
   threads." No reader observes a partial write and writers are disjoint, so it is **bit-identical
   regardless of thread scheduling** — which the old `Fork.kt` write-log/merge could not cheaply
   guarantee. This is the substrate the hardware-accel goal needs. The entire fork/merge apparatus
   becomes unnecessary.

3. **Cheap flat snapshots.** The front buffer is always a consistent, contiguous snapshot between
   ticks — save/resync is a linear serialize (no per-object gather). Rollback netcode: keep the last K
   back-buffers in a ring; each is a flat memcpy to stash and restore. Strictly better than the AoS
   value-snapshot story.

## The semantic shift to decide consciously

Read-front/write-back means **every read sees last tick's value, not this tick's in-progress writes** —
the **Jacobi** model, not Gauss-Seidel. Two systems in the same phase no longer see each other's
writes.

- For cyto this is largely *good*: the spring solver was already deliberately moved to Jacobi for this
  order-independence.
- It breaks **read-your-writes within a tick** (today's `SoaPhase` contract explicitly makes earlier
  systems' writes visible to later ones) and **accumulators** (system A adds gravity, system B adds
  drag to the same field — pure write-back has B clobber A).

Resolution: accumulators and any read-your-writes logic operate on **single-buffered scratch** within
the tick (the durable/scratch split above), committing to the back buffer once. This is a real
restructuring cost for systems that currently rely on intra-phase write visibility — inventory it per
system during migration.

## New costs

- **2× memory for durable state.** Usually a good trade (RAM is cheaper than GC pressure), but must be
  sized for cyto, whose per-cell data is not tiny.
- **Per-tick carry-forward copy.** Needed only for state that *persists but was not rewritten* this
  tick. Fully-overwritten fields (positions integrated every tick) need no copy — just swap.
  Per-column policy: fully-rewritten → swap only; sparsely-updated → memcpy-forward then overwrite.
  Most cyto cells change every tick, so much of the copy is data that would be rewritten anyway.
  Static-heavy sims want dirty-range tracking to avoid copying unchanged data.

## The real wrinkle: persistent variable-length columns

"Strict memcpy from one contiguous block to another" holds for **POD columns** — cyto's
`CytoCellColumnStore` has `logicalRadius: LongArray`, `wear: IntArray`, `type`, `sticky` (flat,
memcpy-trivial). It does **not** hold for its **reference columns**: `cytoplasm`/`biomass` are
`Array<MoleculeStore?>` and `genome` is `Array<List<Gene>?>` — pointers to heap objects, variable-length
per cell.

Double-buffering those is not a memcpy:

- **Shallow copy** (copy the reference) → front and back share the same `MoleculeStore`; mutating back
  corrupts the frozen front. Defeats the snapshot.
- **Deep copy per tick** → reintroduces exactly the per-tick allocation we are trying to kill.
- **Honest fix:** a **persistent / copy-on-write `MoleculeStore`** (structural sharing — unchanged cells
  share, mutated cells fork), or an **arena/pool with generational slots**, or flattening chemistry into
  a fixed-width dense column (see the separate "remove dynamic arrays from cyto" work — if per-cell chem
  becomes a fixed-size dense array, it double-buffers as a pure memcpy and this wrinkle disappears).

This is the genuine unknown and the hard part of the design. It is **cyto-specific** (jagged per-entity
chemistry); fixed-width-only sims (drockets, scavengers) double-buffer as pure memcpy with none of it.

## Codegen scope (near-prerequisite)

KMP has no zero-cost reflection, so column stores are hand-written today (like `ComponentCodec`). A KSP
annotation on a component `data class` should generate:

1. `ColumnStore` field arrays + scatter/gather.
2. **swap + carry-forward-copy** logic per column.
3. flat serialize/deserialize for save + snapshot.
4. a **per-field policy hook**: POD field → memcpy; reference/variable-length field → the chosen
   persistent-column strategy.

Codegen is what makes "double-buffer everything" ergonomic rather than a maintenance swamp — it is
where the POD-vs-jagged distinction is handled once instead of per component.

## Net effect on the trade-offs

| Loss under a plain SoA pivot | Under double-buffering |
|---|---|
| Pure reducer / determinism | **Recovered** (frozen-front reads) |
| Fork/merge rebuilt | **Better** — slot-range partition, lock-free, deterministic; enables target parallelism |
| Value snapshots (save/netcode/rollback) | **Recovered / improved** (front is a flat snapshot; ring = cheap rollback) |
| Hand-written column stores | **Unchanged** — codegen fixes it |
| `data class` freebies (equals/copy) | Still lost |
| — new cost — | 2× memory; per-tick carry-forward copy; **persistent variable-length columns for jagged data** |

Double-buffering erases the *model* losses (the ones touching the engine spine) and leaves the
*ergonomic* loss (codegen) plus the memory/copy costs and the jagged-column problem.

## Where the performance actually comes from

This work is a **foundation swap, not a throughput win in itself** — worth stating plainly so it isn't
oversold. The fixed-capacity cell chem (Phase 1) is arguably a slight steady-state *memory increase* in
isolation (a median ~14-species cell's per-tick `.copy()` now allocates `IntArray(32)` instead of ~16).
The payoff is in what the uniform double-buffered layout **enables**, none of which exists yet:

1. **Eliminate the per-tick `.copy()` allocation.** Today biology copies each cell's chem store every
   tick (`CytoSoaSystems.kt` ~L301). With two pooled double-buffer slabs that are swapped, there is no
   per-cell/per-tick allocation at all — this is the real GC win, and uniform fixed-size backing is its
   precondition.
2. **SIMD / vectorization** over contiguous uniform primitive columns — structurally impossible on a
   hashmap-of-objects, and the substrate the hardware-acceleration goal needs.
3. **Lock-free deterministic parallelism** — read-front/write-back with slot-range partitioning,
   replacing the fork/join that was a net loss on Stu's high-turbo CPU (`reference_cyto_perf_levers`).

Banked separately this session (a genuine, measured throughput win, not foundation): the `resolvedCount`
cache-of-a-cache collapse, ~18% off biology.

## Suggested sequencing

1. **Prove the generality gap** — hand-port one fixed-width AoS demo (drockets or scavengers) onto the
   existing `SoaPhase`/`SoaWorld`, double-buffered. Surfaces the migration cost and the Jacobi
   restructuring without touching the engine API, and is a pure-memcpy case (no jagged wrinkle).
2. **Build KSP column-store codegen** — kill the ergonomic tax; make it generate swap/copy/serialize +
   the per-field policy hook.
3. **Solve persistent variable-length columns for cyto** — decide COW `MoleculeStore` vs arena vs
   fixed-width dense chem (tie this to the "remove dynamic arrays from cyto" work).
4. **Re-back `ComponentStore`** — route the ~212 AoS read sites through columns; make the
   pure-reducer → double-buffered-world contract change the explicit, golden-gated cutover.

## Open questions

- Does slot-range-partitioned parallelism actually beat sequential on Stu's high-single-core-turbo CPU?
  (Multicore fan-out collapses turbo — see `reference_cyto_perf_levers`. SIMD *within* a thread over
  contiguous columns is the likelier first win and is independent of the turbo issue.)
- Fixed-width dense chem would remove the jagged wrinkle entirely — is cyto's species count bounded
  enough to make per-cell chem a fixed-size array? (Feeds directly into the dynamic-array-removal work.)
- Compaction interacts with double-buffering: a `compact()` reorders slots; both buffers must agree on
  slot identity across the swap. Needs a defined ordering (compact on one buffer, carry the remap).
