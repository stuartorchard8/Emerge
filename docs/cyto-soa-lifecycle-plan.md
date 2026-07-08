# SoA-native lifecycle — incremental plan (2026-07-08 handoff)

Goal: eliminate the per-tick AoS round-trip in the lifecycle bridge, moving toward a fully
SoA-native `CytoLifecycleSystem` (Stu's long-term option 3), delivered incrementally.
Read this + memory `reference_cyto_perf_levers` + `docs/cyto-parallel-next-session.md` first.
Working discipline unchanged: commit each focused change on `main`, golden-gate everything
(bit-identity is the gate here), show diffs, clean per-JVM A/B for perf.

## Why (the measured problem)
Exchange is now parallel (2.34×) but whole-tick only moved 1.09× — the all-core-clock tax:
parallel biology pins 8 cores below turbo, so the **serial lifecycle runs 22% slower** and
eats the gain. Lifecycle is ~30ms PAR (~41% of tick) and is the next bottleneck.

**Lifecycle is a materialization problem, not a parallelization problem.** Sub-timing
(`bridgeLifecycle`, commit `43d567ef`, @8192 spread): **toSim 12% / update 20% / fromSim 68%**.
The actual lifecycle logic (`CytoLifecycleSystem.update`) is only ~20%; ~80% is the AoS
round-trip, dominated by `fromSim` = `builder.build()` + `CytoWorld.fromSimState()` — a full
O(n) rebuild of every SoA column + CSR from freshly-materialized maps, every tick a
weld/divide/destroy fires.

## The load-bearing research findings (all verified this session)
1. **The SoA framework already has every incremental primitive needed** (engine
   `SoaWorld`/`ComponentColumns`/`SpringCsr`):
   - `SoaWorld.createEntity()` → monotonic id from `lastEntityValue` (matches SimBuilder's allocator).
   - `world.add(id, type, value)` → `ComponentColumns.put` = append new slot in **insertion order**
     (mirrors AoS `ComponentTable`) or overwrite in place.
   - `world.removeEntity(id)` → tombstones the id in every column; slot reclaimed at compact.
   - `world.compact()` → per-column `compact()` **stable-partitions** live slots (preserving
     insertion order) via `store.moveSlot`, returns `oldSlot→newSlot` remap (−1 = removed).
   - `SpringCsr.rebuildFrom(count, entityIdAt, slotOf, springsAt, edgeAuxAt)` → rebuild CSR over
     the (post-compact) ordering; grows arrays only when needed.
2. **In-tick CSR rebuild from SoA state is already done and is CHEAP.** `pruneEdges`
   (CytoSoaReducer.kt:355 AND CytoSoaSystems.kt:880 — duplicated, cleanup candidate) snapshots
   surviving adjacency per slot and calls `csr.rebuildFrom` when springs break. The `connections`
   phase profiles at ~0µs → **the CSR rebuild is sub-ms; the expensive part is purely the SimState
   map round-trip.** This is the proof that SoA-native structural edits will be fast.
3. **Biology + lyse are ALREADY SoA-native** (`processLyseAttacks` mutates `w.cell.biomass[slot]`
   etc. in place on `CytoWorld`). Only *structural* edits (add/remove entity, spring topology)
   still round-trip. So this work is the last AoS bridge in the hot tick.
4. **Biology double-buffer interaction is handled by the framework:** `CytoCellColumnStore.scatter`
   initializes BOTH front and back cytoplasm/biomass buffers for a new slot; `moveSlot` syncs both
   during compaction. Lifecycle runs AFTER the biology `swapBuffers`, so front holds committed
   state; a daughter added via `world.add` gets valid back buffers for next tick's `reset`.
5. **A new cell needs 6 component columns + CSR edges** (see `spawnCell`/`spawnBody`): Transform,
   Motion, Impulse, Collider, Material, RenderShape, + CytoCellComponent. SoA-native division must
   `world.add` all of them for the daughter (mirroring `fromSimState`'s per-cell adds).
6. **Impulse:** the current bridge gathers/scatters impById across the round-trip because
   `fromSimState` zeroes impulse. SoA-native never zeroes it → survivors keep impulse untouched,
   daughters get `ImpulseComponent()` (zero) via scatter → matches the round-trip. Impulse is
   excluded from the golden digest anyway (transient). Low risk.

## ⚠️ CRITICAL FINDING that reshapes the sequencing (event mix)
Measured `LcMixProbe` (temp, uncommitted — see below), **seeded 8192 spread**, 600 measure ticks:
- lifecycle fires on **45.3%** of ticks; of those, **100% are DIVISION ticks** (1.35 divisions/tick),
  **ZERO welds/heals/destroys/detaches**. Spread founders never touch ⇒ no welding ⇒ pure division.

**This inverts option 1's premise.** Option 1 (SoA-native for {destroy, weld, heal, detach, damage},
round-trip only on division ticks) would capture **nothing** on a division-dominated colony, because
every lifecycle tick falls back to the round-trip. In an *actively growing* colony, **division is the
COMMON event, not the rare one.**

### ✅ RESOLVED (2026-07-09): the mix is REGIME-SPLIT, and cleanly so
Ran `-Dlcmix=1` with NO `-Dcytocells` (grow one founder 22000 ticks → measure 600). Result:
`cells=1445, lifecycle fires 18.2% of ticks, divisionTicks=0 (100% division-FREE), destroys=1.09/tick,
welds=heals=detaches=0`. Combined with the earlier spread finding, the event mix splits by colony regime:
- **Growing colony (young spread):** 100% DIVISION, nothing else.
- **Mature colony (saturated, ~carrying capacity — the long-run gameplay state):** 100% DESTROY, nothing else.
- **Welds/heals/detaches appear in NEITHER regime.** (Colony welds happen via division's own `addSpring`
  rewiring, not the contact `WeldIntent` path, which needs the weld gene / rarely fires in default cfg.
  Detach is player-pointer-only. At saturation every adjacent pair is already welded ⇒ no NEW welds.)

**Sequencing decision (this INVERTS the old critical-finding pessimism about Step 1):**
- Step 1 (SoA-native DESTROY + DETACH) is NOT worthless — it captures **100% of lifecycle ticks in the
  mature steady state**. Since the round-trip is a fixed O(n) rebuild paid on *every* lifecycle tick
  regardless of event type, killing it on the mature colony's 18.2% is a real whole-tick win. **Do Step 1 first.**
- Step 3 (DIVISION) is what the growth phase needs (the hard case, but well-understood). Do it second.
- Step 2 (WELD + HEAL) is rarely/never hit in either measured regime → do it LAST; until then the
  per-tick fallback covers weld ticks for correctness.

## Recommended plan (assuming division is common — confirm with the natural-grown mix first)
Because division dominates in a growing colony, sequence by **doing the whole SoA-native bridge at
once but landing it behind a per-tick fallback**, tackling the events in dependency order. Each step
is bit-identity-gated; keep the AoS round-trip as the fallback until the SoA path covers a tick's
whole event set, then flip.

**Step 0 (prep).** Extract a single `applyLifecycleSoa(w, events): CytoWorld?` entry that returns
null when it can't yet handle the tick's event set (→ caller uses the existing round-trip). This lets
each event type land incrementally while staying green.

**Step 0 (prep). ✅ DONE (2026-07-09).** `applyLifecycleSoa(w, destroy, input, readyWelds, divide): CytoWorld?`
extracted in CytoSoaReducer.kt; called at the top of `bridgeLifecycle` before the round-trip, returns null
to fall back. Helpers `depositCellMatterSoa` / `markPairsBroken` / `rebuildAfterStructuralEdit` alongside.

**Step 1 — SoA-native DESTROY + DETACH (no entity allocation, no new order). ✅ DONE (2026-07-09).**
Landed & green: golden (all 3 scenarios byte-identical), CytoSoaSpecTest, CytoSoaEquivalenceTest.
Confirmed exercised: in the mature natural colony ALL 109/109 lifecycle ticks take the SoA path
(soaPathHits==lifecycleTicks). Snapshot-adjacency-before-compact + `csr.rebuildFrom` reproduces the
round-trip's survivor + edge order; deposits are additive per (leaf,species) ⇒ order-free. Impulse left
untouched on survivors (round-trip's gather/scatter was a no-op net; excluded from the digest anyway).
Design notes below kept for reference:
- destroy: `depositCellMatter` (already SoA-capable via `w.grid.deposit` + `w.cell` stores),
  `world.removeEntity(id)`, mark broken pairs.
- detach: mark all of a cell's pairs broken.
- Then `world.compact()` (if any removal) + `csr.rebuildFrom` over survivors dropping broken pairs
  (reuse the `pruneEdges` pattern; fold destroy into it). Handle the compact remap.
- Gate: only take this path when the tick has NO welds/heals/divisions; else fall back. Bit-identical
  to the round-trip's destroy path (same deposit, same removal, same order).

**Step 2 — SoA-native WELD + WELD-HEAL (no entity allocation).**
- Reproduce `addSpring`'s degree-cap + `springExists` dedup + symmetric add + `ConnectionState`
  initial-damage, but as adjacency edits on a snapshot, then `csr.rebuildFrom`. WeldHeal =
  weld with `initialDamage = breakDamage − heal`.
- Ordering MUST match `CytoLifecycleSystem.update`: detach, destroy, weld, weld-heal (the `welded`
  set dedups across weld+heal). Preserve it exactly.
- Gate: take the SoA path when the tick has {destroy, detach, weld, heal} but NO division.

**Step 3 — SoA-native DIVISION (the hard one; entity allocation + spring rewiring). = option 3.**
- Port `divide()`: compute `splitNormal` (free-space or morphogen-gradient), `floorSplit` cytoplasm
  + biomass (deposit remainders to grid — already SoA-capable), `radiusForBiomass`, daughter placement.
- Allocate daughter: `world.createEntity()` + `world.add` all 6 physics columns + CytoCellComponent
  (mirror `spawnCell`/`spawnBody` values; `cellMass` from atoms). **Append order = division-intent
  order** to match the round-trip's `builder.spawnCell` order (⇒ same slot order + same entity ids).
- Rewire springs: mother's `ahead` neighbours move to daughter, `side` neighbours add to daughter,
  mother↔daughter spring; the "can't split" death case (removeEntity + deposit). Then compact + rebuild.
- Remove the round-trip entirely; delete `toSimState(includeImpulse)`-for-bridge usage, the impById
  gather/scatter, and the `lc:*` timing probe.

**Bit-identity is the whole game.** The gates that must stay green: `CytoGoldenTest` (all three
scenarios — should be byte-identical, this is a pure refactor of the SAME logic), `parallelMatchesSequential`,
`grownStateRoundTrips`, and conservation. The risk areas:
- **Slot ordering** after edits must equal the AoS `ComponentTable` order the round-trip produced:
  `put` appends in insertion order, `compact` stable-partitions — so survivors-in-order then
  daughters-in-intent-order. Verify against the round-trip.
- **Entity-id allocation:** `world.createEntity` and `SimBuilder`'s allocator both advance from
  `lastEntityValue`; process divisions in the same order ⇒ same ids. VERIFY they match exactly
  (check SimBuilder's spawn id path).
- **CSR edge order** within a slot must match (rebuildFrom preserves `springsAt` order; the
  round-trip preserves spring-list order). Snapshot adjacency in the same order the AoS path emits.

## Expectation-setting
CSR rebuild is sub-ms (proven by `pruneEdges`), and SoA-native edits are primitive-array ops +
one compact + one rebuild, vs the current ~15–20ms map round-trip. Realistic: lifecycle ~26ms →
a few ms once division is native (steps 1–2 alone won't help a division-dominated colony — see the
critical finding). Whole-tick: removing ~20ms of serial lifecycle also RELIEVES the all-core-clock
tax on the rest, so the biology parallelism already landed should finally convert to whole-tick wins.

## State of the tree at handoff (updated 2026-07-09)
- Committed & green: exchange tile-parallel (`c90b9645`), cleanup (`362c9bb5`), lifecycle sub-timing
  (`43d567ef`), docs. **Step 0 + Step 1 (SoA-native destroy+detach)** landed this session.
- **`LcMixProbe` throwaway DELETED** — the event-mix question is answered (regime split, recorded above).
- **NEXT: Step 3 — SoA-native DIVISION** (the growth-phase common case). Step 2 (weld/heal) stays on the
  round-trip fallback for now (never seen in either measured regime; do it last). The division port
  (`divide()`) is the substantial one — allocate the daughter via `world.createEntity` + `world.add` of all
  6 physics columns + CytoCellComponent in division-intent order, rewire ahead/side springs, then reuse
  `rebuildAfterStructuralEdit`. Verify entity-id allocation matches SimBuilder and slot/edge order matches
  the round-trip (the risk areas listed above).
