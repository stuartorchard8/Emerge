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

### 2.1. Circular buffer semantics (offset-based)

The array uses **offset tracking** rather than a true circular buffer. The backing store is a fixed
`IntArray(15 * 15)`, indexed as `state[row * 15 + col]` where `row ∈ 0..14`, `col ∈ 0..14`.

```kotlin
private val state = IntArray(15 * 15)  // flat, row-major
private var baseChunkX: Int = 0        // real-world chunk at state[0][0]
private var baseChunkY: Int = 0        // real-world chunk at state[0][0]

fun stateAt(chunkX: Int, chunkY: Int): Int {
    val col = chunkX - baseChunkX      // = 0 → 14 for window
    val row = chunkY - baseChunkY
    require(col in 0..14 && row in 0..14) { "chunk ($chunkX,$chunkY) outside window" }
    return state[row * 15 + col]
}

fun setStateAt(chunkX: Int, chunkY: Int, value: Int) {
    val col = chunkX - baseChunkX
    val row = chunkY - baseChunkY
    require(col in 0..14 && row in 0..14) { "chunk ($chunkX,$chunkY) outside window" }
    state[row * 15 + col] = value
}
```

On vessel chunk crossing:

```kotlin
fun onVesselChunkMove(newVesselChunkX: Int, newVesselChunkY: Int) {
    val dx = newVesselChunkX - baseChunkX
    val dy = newVesselChunkY - baseChunkY

    // Vessel moved within the window — just recenter.
    // (This shouldn't happen; the vessel is always at [7][7].)
    // If vessel jumped > 7 chunks, reset everything.
    if (kotlin.math.abs(dx) > 7 || kotlin.math.abs(dy) > 7) {
        resetWindow(newVesselChunkX, newVesselChunkY)
        return
    }

    // Shift: the new base is old base minus the vessel's movement.
    // If vessel moved +3 in X, the window slides -3, so new base = old base - 3.
    baseChunkX = newVesselChunkX - 7
    baseChunkY = newVesselChunkY - 7
}
```

After shifting, apply NEAR zone rules (section 3) and initialize new entries (section 2.2).

### 2.2. Initial population

The array starts with the 5×5 NEAR zone marked NEAR and everything else UNPOPULATED.
`reset()` sets NEAR on the 5×5 block centered at `[7][7]` so the initial state is
correct without needing a post-shift pass.

This means:

- On the first tick after activation, the nearest chunk to the vessel (at the edge of NEAR zone)
  will be the first spawn target.
- The world fills gradually from the center outward over ~200 ticks (225 chunks at 1/tick).
- The initial `RockField.scatter()` rocks coexist with the spawner. They are in the NEAR zone
  (by design, since they scatter around the vessel), so they are never touched by the spawner.
- When the vessel moves away from its starting area and returns, those chunks will be POPULATED
  (left NEAR → became POPULATED), preventing immediate re-spawning.

**This is intentional.** The first playthrough gets a gradual reveal. Replayability comes from the
initial RockField scatter pattern varying by seed, not from the spawner.

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
    - New entries shifted in from outside the window → `UNPOPULATED`.

**Note:** `reset()` marks the 5×5 NEAR zone at `[7][7]` during initialization, so the first
tick starts with a correct NEAR zone without needing the post-shift pass. The post-shift
NEAR pass is only needed when the vessel moves and the window shifts.

The NEAR-zone transition gives **local consistency**: if the vessel leaves a region and returns,
those chunks are marked POPULATED, not UNPOPULATED. This prevents immediate re-spawning of rocks in
a recently-visited area.

## 4. Spawning

Each tick (after activation delay):

1. Scan the 15×15 array for `UNPOPULATED` chunks (exclude NEAR zone).
2. Select the **nearest** UNPOPULATED chunk to the vessel (nearest to `[7][7]` in Chebyshev
   distance, tie-break: lower row, then lower col).
3. If found:
    - Mark it `POPULATED`.
    - Call `spawnPointsForChunk()` which returns `List<Rock>` — 2–4 rocks at deterministic
      positions within that chunk, each with zero impulse.
    - The caller (`process()`) applies grid bounds, distance-from-vessel, and overlap checks.
    - Record the valid rocks in the world.

`spawnPointsForChunk` returns `List<Rock>` directly (not `List<RockSpawnPoint>`). The caller
is responsible for filtering: grid bounds, `SPAWN_RADIUS`, and `wouldOverlap()`. This keeps
the spawning logic self-contained (hash → RNG → positions → Rock objects) while letting the
caller filter based on world state.

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

### 5.1. Despawn ledger booking

Spawner rocks are **free mass** — they are not tracked by `baselineRockGrams`. When they despawn,
the rock ledger (`rockGrams == baselineRockGrams + capturedGrams − extractedGrams`) is **not**
affected because:

1. `baselineRockGrams` only includes rocks from `RockField.scatter()` (world creation).
2. World-spawned rocks are free mass — the ledger already diverges by their total mass (see
   `RockSpawnerTest` → `world-spawned rocks diverge the rock ledger`).
3. Despawning removes them from `rocks` but this is invisible to the ledger since they were never
   booked in the first place.

**No despawn booking is needed.** The divergence is a feature, not a bug. It tells us the spawner
is working: new mass enters and leaves the vessel frame freely.

The existing test `world-spawned rocks diverge the rock ledger` checks that `divergence < 0`.
Under the new system this still holds: while any spawner rock exists in the world list,
`rockGrams > baselineRockGrams + capturedGrams - extractedGrams`, so divergence stays negative.
Despawn causes rocks to leave the world (shrinking `rockGrams` toward the baseline), but the
test fixture runs with the vessel stationary near spawn area, so rocks are always present.
No ledger test changes needed.

### 5.2. Despawn is per-rock position check, not chunk lifecycle

Despawn runs **every tick on every rock**, regardless of whether the vessel moved or the window
shifted. Each rock is checked: is its chunk within the 15×15 window? If not, remove it.

This means two independent paths to despawn:

- **Stationary rocks (asteroids):** the rock never moves. It despawns when the vessel moves away
  and the window shifts past the rock's chunk. The rock's position didn't change — the window did.
- **Moving rocks (drifting rigid bodies):** the rock drifts via physics. When it drifts outside the
  window bounds, it despawns **on that tick**, regardless of whether the vessel moved. The window
  may be stable; the rock moved out of it.

Both paths use the same position check — they just differ in why the rock is out of bounds.

This is important because rocks are rigid bodies (see `PLAN_unified_bodies.md`). A drifting rock
can exit the window on its own momentum. The despawn pass catches it immediately; it doesn't wait
for the chunk system to notice.

**Shared constants:** `WINDOW_SIZE = 15` defines the despawn window. The same boundary
`[baseX - 7, baseX + 7]` is used for both spawn eligibility and despawn. There's no separate
"despawn distance" constant — the window *is* the despawn distance.

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

### Phase 0: Preserve current, add test scaffolding
- Do NOT modify `RockSpawner` yet.
- Add a test that verifies the current set-based behavior still passes (baseline).
- Add a test for the new one-chunk-per-tick invariant (will fail until built).

### Phase 1: Chunk-state array + offset tracking
- Define `NEAR = 0`, `UNPOPULATED = 1`, `POPULATED = 2` constants.
- Implement `state` flat array + `baseChunkX/Y` + `stateAt()/setStateAt()`.
- Implement `reset()` → all UNPOPULATED **except** 5×5 NEAR zone at `[7][7]`.
- **Test:** array indexing round-trips, reset produces NEAR at center + UNPOPULATED elsewhere.

### Phase 2: Array shifting on vessel chunk crossing
- Implement `onVesselChunkMove()`: recenter base so vessel is at `[7][7]`.
- Handle jump > 7 chunks: full reset.
- **Test:** vessel moves 1 chunk → array shifts, center follows, values preserved for overlapping chunks.
- **Test:** vessel jumps 10 chunks → full reset to UNPOPULATED (with NEAR at center).
- **Test:** vessel oscillates → base tracking stays correct.

### Phase 3: NEAR zone rules (post-shift only)
- After shift, iterate all 225 entries:
  - Chebyshev dist ≤ 2 from center → NEAR
  - Was NEAR, now outside → POPULATED
  - New entries (shifted in) → UNPOPULATED
- **Test:** NEAR zone always covers 5×5 at `[7][7]`.
- **Test:** leaving NEAR marks POPULATED (prevents immediate re-spawn).
- **Test:** returning to a previously-NEAR chunk → it's POPULATED (no re-spawn).
- **Test:** initial state → NEAR at center, everything else UNPOPULATED.

### Phase 4: Spawn logic (one chunk per tick)
- `spawnPointsForChunk` → `List<Rock>` (returns `Rock` objects directly, not `RockSpawnPoint`).
- Scan array for UNPOPULATED outside NEAR.
- Select nearest to center (min Chebyshev distance, tie-break: lower row, then lower col).
- Call `spawnPointsForChunk(chunkX, chunkY)` → `List<Rock>`.
- Mark chunk POPULATED.
- Apply grid bounds, distance-from-vessel, overlap checks per rock.
- Reuse `ORE_BODIES` and `wouldOverlap()` — no changes to existing helpers.
- **Test:** one chunk spawned per tick (not all at once).
- **Test:** nearest UNPOPULATED selected (not farthest).
- **Test:** NEAR zone never gets rocks.
- **Test:** POPULATED chunks never re-spawn.
- **Test:** spawns use existing deterministic positions (golden-safety).

### Phase 5: Despawn logic (window-bounded)
- For each rock, compute its chunk from position.
- Check if chunk ∈ `[baseX-7, baseX+7] × [baseY-7, baseY+7]`.
- Remove rocks outside window.
- **Test:** rocks despawn when their chunk leaves the window.
- **Test:** rocks near window edge survive one tick.
- **Test:** ledger divergence test still passes (free mass behavior unchanged).

### Phase 6: Integration + test migration
- Wire into `OutofspaceSim.process()` (same interface: `process()` returns `List<Rock>`).
- Update all 7 existing `RockSpawnerTest` tests for new behavior.
- Verify gate: `./gradlew test` passes.
- **Test:** end-to-end — vessel explores, world populates gradually, despawn cleans up.

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

Rocks can move (they're rigid bodies with drift physics, see `PLAN_unified_bodies.md`). However,
the despawn pass runs every tick regardless — it recomputes each rock's chunk from its current
position and checks window bounds. This is O(rocks) per tick where rocks ≤ MAX_ACTIVE = 20, so
recomputation is trivial even for drifting rocks. **Option C** is simplest and handles both
stationary and moving rocks correctly.

**Verdict:** Option C.

### 11.6 What about the initial NEAR zone — should it be populated from the start?

The NEAR zone starts UNPOPULATED (since the whole array starts UNPOPULATED). After the vessel
moves away and returns, the NEAR zone will be marked NEAR (by the zone rules), but the chunks
outside NEAR that the vessel previously visited will be POPULATED.

This is correct: the vessel's starting area is protected (NEAR), and areas it explored before are
POPULATED (no re-spawn). When a new vessel starts, the entire array is UNPOPULATED except the
starting NEAR zone — rocks begin appearing at the edge of NEAR and spread outward.

**This gives a natural "reveal" on first play: the player sees rocks near them, then as they
explore, more chunks populate.** On replay, the initial RockField scatter gives variety, but the
spawner's gradual population is the same.

### 11.7 What if the vessel stays still for a long time?

The spawner continues spawning 1 chunk/tick, even if the vessel hasn't moved. After ~200 ticks
(about 67 seconds at 3 TPS), all 225 chunks will be POPULATED and spawning stops.

This is fine: the player has a stable world to explore. When they move again, new chunks appear
beyond the window edge as UNPOPULATED and populate gradually.

### 11.8 What about the `MIN_ROCKS_FOR_SPAWN` guard?

The current spawner has `MIN_ROCKS_FOR_SPAWN = 4` — it only spawns if rock count is below this.
The new design doesn't need this guard. The one-chunk-per-tick rate naturally caps at ~20 rocks
(MAX_ACTIVE) because despawn removes rocks from the far edges faster than spawning adds them.

**Decision:** Remove `MIN_ROCKS_FOR_SPAWN`. The gradual spawning rate + MAX_ACTIVE despawn provides
natural throttling. If needed, MAX_ACTIVE can be tuned.
## 12. Edge cases

### 12.1. Vessel jumps many chunks (e.g., teleport, lag spike)
If the vessel moves > 7 chunks in one tick, the window can't recenter. The spawner does a full
reset (all UNPOPULATED). On the next tick, the vessel is within the new window and chunks begin
populating from the edge of NEAR. This is correct — the player is in a fresh area.

### 12.2. Vessel oscillates rapidly
If the vessel bounces back and forth across chunk boundaries (e.g., hitting a wall), the spawner
will keep recentering. This is fine: the NEAR zone rules prevent re-spawning in the same area, and
the one-chunk-per-tick rate limits total spawns.

### 12.3. Window edge coincides with vessel position
The window is always centered on the vessel. The vessel is always at `[7][7]` of the array. There
is no case where the vessel is at the edge — the window follows the vessel.

### 12.4. Grid bounds — rocks spawn outside the vessel grid
The `spawnPointsForChunk()` logic checks `spawnTileX < 0 || spawnTileY < 0 || spawnTileX >=
gridWidth || spawnTileY >= gridHeight`. Rocks outside the grid are not spawned. This is unchanged
from the current implementation.

### 12.5. Rock overlap during spawn
The `wouldOverlap()` check prevents spawning a rock that would overlap an existing one. If all
4 spawn points in a chunk are blocked, zero rocks spawn for that chunk (not a failure — just no
room). The chunk is marked POPULATED regardless, preventing re-try.

### 12.6. MAX_ACTIVE — when to enforce
MAX_ACTIVE is enforced by despawn, not by blocking spawns. Rocks near the window edge despawn when
their chunk leaves the window (stationary rocks) or when they drift outside (moving rocks). If the
vessel is stationary and MAX_ACTIVE is exceeded (impossible at 1/tick with despawn, but theoretically),
drifting rocks will drift out of bounds first and stationary rocks at the window edge will despawn
as the window shifts.

### 12.7. Drifting rocks despawn independently of chunk state
A drifting rigid body (see `PLAN_unified_bodies.md`) can accumulate impulse from physics. On the
tick it drifts outside the 15×15 window, the despawn pass catches it immediately — it doesn't
wait for the chunk system to notice. This is the same position check used for stationary rocks.

### 12.8. Tie-breaking in nearest-UNPOPULATED selection
When multiple UNPOPULATED chunks are at the same Chebyshev distance, the spawner selects the one
with the smallest row index, then smallest column index. This is deterministic and produces consistent
behavior. The choice doesn't affect gameplay — any nearest chunk is fine.

## 13. Proposed constants

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

## 14. Acceptance criteria

What we'd know it worked:

1. **Every existing test green** after replacing the `RockSpawner` body (same interface).
2. **One chunk per tick invariant**: run the spawner with the vessel stationary past activation —
   the rock count increases by exactly 1 chunk (2–4 rocks) per tick for the first ~20 ticks, then
   stabilizes at ≤ MAX_ACTIVE due to despawn.
3. **NEAR zone protection**: the 5×5 zone around the vessel never spawns rocks. Verify by checking
   that no rock's chunk falls within Chebyshev distance 2 of the vessel's chunk.
4. **POPULATED on exit**: move the vessel away from an area, then back. No rocks spawn in chunks
   that were previously NEAR (they are now POPULATED).
5. **Despawn at window edge**: move the vessel far from an area. Stationary rocks in that area
    despawn when their chunk leaves the 15×15 window. Drifting rocks despawn on the tick their
    position falls outside the window, regardless of chunk state. No distance calculation needed.
6. **Deterministic spawning**: same chunk hash produces same rock positions every run (golden-safety).
7. **Rock ledger divergence unchanged**: world-spawned rocks are still free mass; the ledger
   divergence test passes with identical behavior.
8. **No regression on existing constants**: `MAX_ACTIVE = 20`, `ACTIVATE_AFTER_TICK = 200`,
   `CHUNK_SIZE = 32`, `SPAWN_RADIUS = 10` — these are unchanged from current implementation.
9. **Gate green**: `./gradlew test` passes with all targets (JVM + JS).
10. **Performance**: no noticeable frame cost. The 15×15 array scan is ~225 iterations — trivial.
    The despawn check is O(rocks) where rocks ≤ MAX_ACTIVE = 20.

## 15. Invariants

1. `[7][7]` of the array always refers to the vessel's current chunk.
2. No chunk in the NEAR zone is ever spawned into.
3. No chunk that is POPULATED is ever spawned into.
4. At most one chunk is spawned per tick.
5. A rock is despawned if and only if its chunk is outside the 15×15 window.
6. The rock count never exceeds MAX_ACTIVE (20).
7. All newly spawned rocks carry zero impulse (set by SPAWN_IMPULSE = 0L). Drift physics may later
    impart impulse to rocks, but spawn-time impulse is always zero.
