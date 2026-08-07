# Chunk-state array rock spawner

*Plan, 2026-08-08. Nothing built. Replaces `RockSpawner` — the current chunk-set approach tracks
which chunks are active but has no local structure, no proximity tiers, and no graded scheduling.*

---

## 1. What this is for

The current `RockSpawner` tracks active chunks with `Set<Pair<Int, Int>>`. It activates all chunks
within a fixed Chebyshev radius (4) of the vessel, spawning 2–4 rocks per newly active chunk. This
works, but chunk tracking is implicit — there's no local structure the player or debugger can
inspect, no concept of "this chunk was recently near, now it's not", and the activation radius is a
global constant with no graded proximity (a chunk at distance 1 and distance 4 behave identically).

**Goal.** Replace the set-based approach with an explicit 15×15 chunk-state array that provides:

1. **Local structure** — a bounded window of chunks around the vessel with one of three states.
2. **Proximity tiers** — NEAR (protected buffer), POPULATED (known/handled), UNPOPULATED (needs
   rocks).
3. **Deterministic scheduling** — one UNPOPULATED chunk per tick, selected by nearest-to-vessel.
4. **Clean transitions** — chunks shifting out of the window are forgotten; returning chunks get a
   clean slate.
5. **Simple despawn** — rocks outside the 15×15 window are despawned. No distance calculation
   needed.

## 2. The array

A 15×15 array storing chunk states. The **center element** at index `[7][7]` always refers to the
chunk containing the vessel. Array coverage: chunks from `(vesselChunkX - 7)` to `(vesselChunkX + 7)`
in both axes, inclusive.

**States** (encoded as `Short` or `Int`):

- `NEAR = 0` — within 5×5 zone centered on vessel's chunk. Not touched by spawner.
- `UNPOPULATED = 1` — eligible for spawning.
- `POPULATED = 2` — already populated or was NEAR (left the zone). Not touched by spawner.

The NEAR zone is a 5×5 square centered on the vessel's chunk. In array coordinates, that's
`row ∈ [6,8]` and `col ∈ [6,8]`.

## 3. Array transitions on vessel chunk crossing

When the vessel moves into a new chunk (detected via `vesselChunkX` / `vesselChunkY` changing):

1. **Shift the array** so that `[7][7]` now refers to the new vessel chunk.
   - If the vessel moved right: shift array left by 1 (or update a base offset).
   - If the vessel moved left: shift array right by 1.
   - Same logic for Y (up/down).
   - **Implementation:** use a circular buffer or reallocate a new 15×15 array and copy. A circular
     buffer with a `(baseX, baseY)` offset is most efficient.

2. **Initialize new entries** (ones that shifted in from outside the window) as `UNPOPULATED`.

3. **Apply NEAR zone rules** to all entries in the window:
   - If a chunk is now within 5×5 of center → mark `NEAR` (regardless of previous state).
   - If a chunk was previously `NEAR` but is now outside the 5×5 zone → mark `POPULATED`
     (regardless of whether it was actually populated or not).

The NEAR-zone transition gives **local consistency**: if the vessel leaves a region and returns,
those chunks are marked POPULATED, not UNPOPULATED. This prevents immediate re-spawning of rocks in
a recently-visited area.

## 4. Spawning

Each tick (after activation delay):

1. Scan the 15×15 array for `UNPOPULATED` chunks (exclude NEAR zone).
2. Select the **nearest** UNPOPULATED chunk to the vessel (nearest to `[7][7]` in Chebyshev
   distance).
3. If found:
   - Mark it `POPULATED`.
   - Spawn 2–4 rocks at deterministic positions within that chunk.
   - Record the rocks in the world.

Only **one** UNPOPULATED chunk is spawned per tick, giving a gradual, spread-out population of the
world.

## 5. Despawning

Rocks are tracked by their tile position (or chunk coordinate). On each spawner tick:

- A rock is **in-bounds** if its chunk coordinates fall within the 15×15 window:
  ```
  rockChunkX ∈ [baseX - 7, baseX + 7]
  rockChunkY ∈ [baseY - 7, baseY + 7]
  ```
- Rocks outside this range are removed.

This is a simple range check. Chunks that have shifted out of the 15×15 window automatically lose
their rocks. No distance calculation, no chunk set lookups.

## 6. State management

The spawner object holds:

```kotlin
private var stateBuffer: Array<IntArray> = Array(15) { IntArray(15) { UNPOPULATED } }
private var baseChunkX: Int = 0   // real-world chunk at buffer[0][0]
private var baseChunkY: Int = 0
private var lastVesselChunkX: Int = Int.MIN_VALUE
private var lastVesselChunkY: Int = Int.MIN_VALUE
```

On each `process()` call:

```
if tick < ACTIVATE_AFTER_TICK: return
vesselChunkX/Y = compute(...)
if vesselChunkX == lastVesselChunkX && vesselChunkY == lastVesselChunkY:
    // Vessel hasn't crossed a chunk boundary — still try spawning 1 chunk.
    spawnOneTick(...)
    return
// Vessel crossed a boundary — shift the array.
shiftArray(vesselChunkX, vesselChunkY)
applyNearZoneRules()
markNewEntriesUnpopulated()
lastVesselChunkX/Y = vesselChunkX/Y
spawnOneTick(...)
```

## 7. Rock storage

Rocks carry `positionX` and `positionY` in tile-scale billionths (`Flight.PER_TILE`), so chunk
coordinates are derivable:

```kotlin
val rockTileX = rock.positionX / Flight.PER_TILE
val rockChunkX = rockTileX / CHUNK_SIZE  // integer division, floor for negatives
```

Since rocks have zero impulse and never move, computing chunk from position each tick is free.
No extra fields, no map bookkeeping.

**Decision:** Compute chunk from rock position on each despawn tick. Option C (compute on the fly)
is simplest and has no state to maintain.

## 8. Integration

The current `RockSpawner` returns `List<Rock>` from `process()`. The new design keeps this same
interface — the caller (`OutofspaceSim.process`) doesn't need to change. The internal representation
changes, but the contract is the same.

## 9. Behavior comparison

| Aspect | Current (Set-based) | New (Array-based) |
|--------|---------------------|-------------------|
| Chunk tracking | `Set<Pair<Int,Int>>` | 15×15 array with NEAR/POPULATED/UNPOPULATED |
| Activation | All chunks in radius activated at once | One UNPOPULATED chunk per tick |
| Spawning | All new chunks spawn immediately | Gradual: 1 chunk/tick |
| Proximity | Binary (in-radius / out-of-radius) | Graded via nearest-UNPOPULATED selection |
| NEAR buffer | None — all chunks treated equally | 5×5 protected zone, no spawning |
| Leaving a region | Chunks become inactive, rocks despawn beyond 160 tiles | Chunks marked POPULATED on exit; return gets clean slate |
| Despawn | Distance-based from vessel | Window-bound: rocks outside 15×15 despawn |
| Max rocks | Hard cap at MAX_ACTIVE | Implicit: ~225 chunks × 2–4 rocks, but 1/tick rate caps active count |
| Debuggability | Set of pairs — opaque | 15×15 grid — inspectable, visualizable |

## 10. Implementation steps

### Step 1: Define the chunk-state array structure
- `ChunkState` enum or constants: `NEAR`, `UNPOPULATED`, `POPULATED`
- 15×15 backing array with circular buffer semantics (or offset tracking)
- `baseChunkX`, `baseChunkY` tracking which real-world chunk maps to buffer `[0][0]`

### Step 2: Array shifting logic
- On vessel chunk crossing, compute the shift delta (how many steps the vessel moved).
- If shift ≤ 7 in either direction, the new vessel chunk is within the 15×15 window — shift and
  recenter.
- If shift > 7 (vessel jumped many chunks at once), reset the entire array (rare edge case).
- Apply NEAR zone rules after shift.

### Step 3: NEAR zone rules
- After shift, mark all chunks within Chebyshev distance ≤ 2 from center as `NEAR`.
- Mark all chunks that were `NEAR` but are now outside distance 2 as `POPULATED`.
- Initialize any new entries (shifted in from outside) as `UNPOPULATED`.

### Step 4: Spawn logic
- Scan the array for `UNPOPULATED` entries outside NEAR zone.
- Select the one with minimum Chebyshev distance to `[7][7]`.
- Mark it `POPULATED`.
- Spawn 2–4 rocks at deterministic positions (reuse existing `spawnPointsForChunk` logic).

### Step 5: Despawn logic
- For each rock, compute its chunk coordinate from position.
- Check if that chunk is within the 15×15 window.
- Remove rocks outside the window.

### Step 6: Tests
- Update existing 7 tests to work with new behavior.
- Add tests for: array shifting, NEAR zone protection, POPULATED transition on exit, one-chunk-per-tick
  spawning, despawn on window exit.

## 11. Open questions

### 11.1 What rate does one-chunk-per-tick produce?

At 1 chunk/tick × 3 ticks/second (assuming 3 TPS game speed) = 3 chunks/second. With 2–4
rocks/chunk, that's 6–12 rocks/second. But MAX_ACTIVE caps at 20, so rocks will despawn faster
than they spawn once the cap is reached. The world won't fill up — it'll stabilize around 20 rocks.

The current system also caps at 20 but spawns all new chunks at once. The new system is more
gradual and local. The player sees rocks appear one chunk at a time as they explore, which feels
more organic.

**Verdict:** This rate is fine. The gradual spawning is a feature, not a bug.

### 11.2 What about the initial rock field?

The initial `RockField.scatter()` rocks are placed at world creation. They coexist with the
spawner. The NEAR zone (5×5 chunks around the vessel) overlaps the initial rock field region.
Rocks already there don't need re-spawning — the NEAR zone handles this naturally.

If the initial field has rocks scattered beyond NEAR, those rocks won't be "tracked" by the array.
When the vessel moves away and back, those chunks won't be marked POPULATED — they'd become
UNPOPULATED again. This is **correct behavior** for the initial field: it's a one-time setup, and
the spawner shouldn't re-populate areas that were initially seeded.

The initial field is independent of the spawner. Rocks from `RockField` are tracked in
`baselineRockGrams`. Rocks from the spawner are free mass.

### 11.3 How to handle the transition from the current system?

The current `RockSpawner` is already committed. This plan is a **replacement**.

1. Keep the current implementation as-is until the new one is built and tested.
2. The plan document lives alongside the code.
3. When building, replace the entire `RockSpawner` body — the interface (`process()`, constants,
   `reset()`) stays the same.

### 11.4 Should the 15×15 window size be configurable?

Probably not. 15×15 = 480 tiles × 15 = 48 tiles per chunk = 480 tiles radius. That's a 960-tile-wide
window. With 32-tile chunks, that's 30 chunks across. The player can explore a lot before hitting
the window edge. 15 is a good fixed size — not too small (chunks fall off the edge), not too large
(memory/cache).

**Verdict:** Fixed at 15.

### 11.5 Rock storage — which option?

| Option | Pros | Cons |
|--------|------|------|
| A: Rock stores chunk | Simple, single field | Extra field on `Rock`, needs updating if rock moves |
| B: Map by chunk | Fast despawn | Map overhead, needs to maintain keys |
| C: Compute from position | No extra state, no map | Recalculates every tick |

Since rocks don't move (zero impulse), recomputation is free. **Option C** is simplest.

**Verdict:** Option C.

## 12. Proposed constants

```kotlin
const val CHUNK_SIZE: Int = 32              // unchanged
const val WINDOW_SIZE: Int = 15             // 15×15 array
const val NEAR_RADIUS: Int = 2              // Chebyshev distance — gives 5×5 NEAR zone
const val MAX_ACTIVE: Int = 20              // unchanged
const val ACTIVATE_AFTER_TICK: Int = 200    // unchanged
const val SPAWN_RADIUS: Int = 10            // unchanged (min tiles from vessel)
const val SPAWN_IMPULSE: Long = 0L          // unchanged
```

`NEAR_RADIUS = 2` means chunks at Chebyshev distance 0, 1, 2 from center are NEAR. That's 5×5 = 25
chunks protected.

## 13. Invariants

1. `[7][7]` of the array always refers to the vessel's current chunk.
2. No chunk in the NEAR zone is ever spawned into.
3. No chunk that is POPULATED is ever spawned into.
4. At most one chunk is spawned per tick.
5. A rock is despawned if and only if its chunk is outside the 15×15 window.
6. The rock count never exceeds MAX_ACTIVE (20).
7. All spawned rocks carry zero impulse.
