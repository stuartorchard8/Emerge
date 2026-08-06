# Unified rigid bodies

*Plan, 2026-08-07. Nothing built.*

---

## 1. The problem

`Rock` and the planned `BodyFragment` (rigid-machine debris) are the same thing written twice.

Both are:
- A `BooleanArray` shape (cells) in a `width × height` bounding box
- Position in the vessel's grid frame (`positionX/Y`)
- Momentum in world frame (`impulseX/Y`)
- Movable by `driftRocks`/`driftFragments` (identical sweep-against-hull algorithm)
- Collision-tested by `sweepRock`/`overlapsHullFragment` (identical hull overlap check)
- Tethered to plating gravity via `platingFeltBy`
- Subject to `RockContact.restingSpeed` and `RockContact.restitution`
- Carrying thermal energy (`joules` → `kelvin` via `MATERIAL`)
- Included in `solidJoules` for the thermal ledger
- Drifting in a list alongside rocks (`rocks`, `fragments`)
- Never aboard — mass never joins `massGrams` or `inTransitGrams`

The only structural difference is:
- `Rock` has a `composition: Mixture` (ore body) and a `kind: Material` (`Firebrick`)
- `BodyFragment` would have `kind: MachineKind` and no mixture (casing)

This duplication means:
- Two drift functions (`driftRocks` / `driftFragments`)
- Two overlap functions (`overlapsHull` / `overlapsHullFragment`)
- Two sweep functions (`sweepRock` / `sweepRockFragment`)
- Two lists in `VesselState` (`rocks` / `fragments`)
- Two ledgers (`rockGrams` / `fragmentGrams`)
- Two step result types (`RockStep` / `FragmentStep`)
- Two save entries and two migration paths
- The `restingOnDeck` / aboard transition for fragments, which doesn't exist for rocks

It also means the fragment plan's open questions 3, 5, and 9 are answered before the debris plan starts — the "aboard" concept doesn't exist.

---

## 2. The decision

**One type, two kinds.**

```kotlin
enum class BodyKind { ROCK, FRAGMENT }

class RigidBody(
    /** What kind of body this is — determines composition vs kind metadata. */
    val kind: BodyKind,
    /** The shape's bounding box, in cells. */
    val width: Int,
    val height: Int,
    /** Which cells of that box are solid, row-major. */
    val cells: BooleanArray,
    /** Top-left corner of [cells], in the vessel's grid frame. */
    val positionX: Long,
    val positionY: Long,
    /** Momentum in world frame (not vessel frame — ship's frame accelerates). */
    val impulseX: Long,
    val impulseY: Long,
    /** Thermal energy, in the millijoules [Material] documents. */
    val joules: Long,
    /**
     * What a rock is made of chemically (ore). Null for fragments — they carry
     * [machineKind] instead, which is needed for rendering and future grinder interaction.
     */
    val oreComposition: Mixture? = null,
    /**
     * Machine type for fragments. Null for rocks. Needed for rendering and
     * future grinder interaction.
     */
    val machineKind: MachineKind? = null,
) {
    val massGrams: Long get() = filled * MATERIAL.gramsPerTile
    val capacity: Long get() = filled * MATERIAL.capacityPerTile
    val kelvin: Int get() = if (capacity <= 0L) Temperature.SPACE_KELVIN else (joules / capacity).toInt()
    val centreX: Long get() = positionX + width * Flight.PER_TILE / 2L
    val centreY: Long get() = positionY + height * Flight.PER_TILE / 2L
}
```

`RigidBody` is a drop-in replacement for both `Rock` and `BodyFragment`.

**Rocks become:** `RigidBody(BodyKind.ROCK, oreComposition = ..., machineKind = null, ...)`
**Fragments become:** `RigidBody(BodyKind.FRAGMENT, oreComposition = null, machineKind = ..., ...)`

The `fromMachine` factory becomes `RigidBody.fromMachine(at, grid, machine, tolerance)`.
The `Rock.blob` factory becomes `RigidBody.rockBlob(radius, positionX, positionY, composition, impulseX, impulseY, kelvin)`.

**Tolerance rule (0.1 tile on all body shapes):**
- The `TOLERANCE = Flight.PER_TILE / 10L` constant lives on `RigidBody` companion object.
- It applies to fragment shapes derived from machine footprints: exposed edges are shaved inward by 0.1 tile.
- For rocks, the tolerance is not applied — `Rock.blob` already rasterises a disc shape.
- The tolerance matters for collision: it prevents sharp corners from pinching against hull tiles.
- Rocks don't need it because they're already circular.

**One list: `bodies: List<RigidBody>`**
- Replaces both `rocks: List<Rock>` and `fragments: List<BodyFragment>`.
- Stored in `VesselState` alongside `debris`.
- `bodiesOfRock` / `bodiesOfFragment` → `bodyKinds()` — returns `List<BodyKind>` for the list.

**One drift function: `driftBodies`**
- Replaces `driftRocks` and `driftFragments`.
- Signature: `fun driftBodies(grid, structure, bodies, platingGravity, shipVelocityX, shipVelocityY, shipMassGrams, shipAcceleration): BodyStep`
- Iterates over all bodies, dispatching sweep logic by `BodyKind` only where needed (currently neither needs dispatch — the algorithm is identical).
- Returns `BodyStep(bodies, handedX, handedY)` — one result type for everything.

**One collision function: `overlapsHull(grid, structure, body: RigidBody, atX, atY)`**
- Currently `overlapsHull(grid, structure, rock: Rock, atX, atY)`.
- Change the parameter type from `Rock` to `RigidBody`.
- The function only reads `rock.width`, `rock.height`, `rock.cells`, and the return value.
- All three reads are identical for rocks and fragments.

**One sweep function: `sweepBody(grid, structure, body, shipVelocityX, shipVelocityY, shipMassGrams, restingSpeedX, restingSpeedY): Swept`**
- Currently `sweepRock`.
- Change the parameter type from `Rock` to `RigidBody`.
- The function only reads `body.massGrams`, `body.positionX`, `body.positionY`, `body.impulseX`, `body.impulseY`, and `body.width` / `body.height` (via the overlap call).
- All reads are identical.
- Rename to `sweepBody` for clarity (sweeping a body, not a rock specifically).

**One result type: `BodyStep`**
- Replaces `RockStep` and `FragmentStep`.
- `data class BodyStep(val bodies: List<RigidBody>, val handedX: Long, val handedY: Long)`

**One step in the sim: `bodiesDrifted = driftBodies(...)`**
- Currently `rocksDrifted = driftRocks(...)`.
- The sim passes `w.bodies` and gets back `bodiesDrifted.bodies`.

**One set of fields on VesselState:**
- `rocks: List<Rock>` → `bodies: List<RigidBody>`
- `capturedGrams`, `baselineRockGrams` → `bodyCapturedGrams`, `baselineBodyGrams`
- `rockImpulseX`, `rockImpulseY` → `bodyImpulseX`, `bodyImpulseY`
- `rockGrams` getter → `bodyGrams`
- `rockImpulseX + handedX` → `bodyImpulseX + handedX`

The fields are renamed to be generic ("body" instead of "rock") because they now cover both kinds.

**One ledger:**
```
bodyGrams == baselineBodyGrams + bodyCapturedGrams − bodyExtractedGrams
```
- `bodyCapturedGrams` — mass from dropped rocks (Edit.DropRock).
- `bodyExtractedGrams` — mass from extractors grinding rocks/fragments.
- For rocks today: `bodyGrams == bodyCapturedGrams − bodyExtractedGrams` (baseline is zero for new worlds).
- For fragments: same identity holds. The fragment is captured when the machine is dismantled, and extracted when ground.

**No aboard transition.** Neither rocks nor fragments are ever "aboard". They are always external bodies. Their mass never joins `massGrams` or `inTransitGrams`. This is the key simplification that kills the `restingOnDeck` concept.

**Why no aboard is correct:** A rock was never aboard — it's an external object. A fragment is the player's own machine casing, but once dismantled it is in the same state as a rock: floating in freefall, drifting by gravity, not part of the vessel's mass budget. The player *could* re-attach it (future feature), but that's not part of this work. Until re-attachment, it behaves exactly like a rock: external, not aboard.

---

## 3. Thermal ledger

Rocks contribute to `solidJoules` (Body.kt:127) via the `rocks` parameter:
```kotlin
for (r in rocks) sum += r.joules
```

This becomes:
```kotlin
for (b in bodies) sum += b.joules
```

**No change to the thermal ledger identity:** `stored + radiated − generated == baselineJoules`. Rocks and fragments both carry their `joules` in the solid body of the vessel, counted by `solidJoules`. The baseline `baselineJoules = solidJoules(machines, conduits, bridges, rocks)` becomes `solidJoules(machines, conduits, bridges, bodies)`.

**No change to `bodiesOf` (Body.kt:64):** It only enumerates deck-mounted solids (machines, conduits, bridges). Rocks and fragments are not included — they don't participate in solid-heat conduction. This stays the same for both kinds.

---

## 4. What changes: the full diff

### New file: RigidBody.kt (replaces Rock.kt + planned BodyFragment.kt)

- `enum class BodyKind { ROCK, FRAGMENT }`
- `class RigidBody` with all fields from both `Rock` and `BodyFragment`
- `companion object`:
  - `TOLERANCE: Long = Flight.PER_TILE / 10L` (for fragment shape derivation)
  - `MATERIAL: Material = Material.Firebrick` (shared material)
  - `fun rockBlob(...)` — the `Rock.blob` factory
  - `fun fromMachine(at: Int, grid: Grid, machine: Machine, tolerance: Long = TOLERANCE)` — the fragment factory
- `RigidBody.copy(...)` — copies all fields with defaults
- `RigidBody.equals` / `hashCode` / `toString`

### Changed: Rock.kt

- `Rock` class deleted.
- `RockContact` stays — but `overlapsHull` and `sweepRock` parameter types change from `Rock` to `RigidBody`.
- `RockStep` → `BodyStep`.
- `driftRocks` → `driftBodies`, parameter `rocks: List<Rock>` → `bodies: List<RigidBody>`.
- `platingFeltBy` — stays the same, parameter `centreX/Y` (no type dependence).
- All internal references to `Rock.` change to `RigidBody.` (e.g., `RigidBody.MATERIAL`).

### Changed: VesselState (Vessel.kt)

- `val rocks: List<Rock>` → `val bodies: List<RigidBody>`
- `val capturedGrams` → `val bodyCapturedGrams`
- `val baselineRockGrams` → `val baselineBodyGrams`
- `val rockImpulseX` → `val bodyImpulseX`
- `val rockImpulseY` → `val bodyImpulseY`
- `val rockGrams` → `val bodyGrams: Long get() = bodies.sumOf { it.massGrams }`
- `solidJoules(machines, conduits, bridges, rocks)` → `solidJoules(machines, conduits, bridges, bodies)`
- `baselineJoules` recomputes from `solidJoules` — no change needed beyond the parameter rename.
- `remapped`: rocks list → bodies list (same position translation).

### Changed: OutofspaceSim.kt

- `driftRocks(` → `driftBodies(`, `w.rocks` → `w.bodies`
- `rocksDrifted.rocks` → `bodiesDrifted.bodies`
- `rocksDrifted.handedX` → `bodiesDrifted.handedX`
- `w.rockHandedX` → `w.bodyHandedX`
- `state.rockImpulseX + handedX` → `state.bodyImpulseX + handedX`
- `capturedGrams` → `bodyCapturedGrams`
- `rockImpulseX` → `bodyImpulseX`
- `rockImpulseY` → `bodyImpulseY`
- `vesselMassGrams` — no change (doesn't include bodies).
- `Edit.DropRock` — creates a `RigidBody(BodyKind.ROCK, ...)` instead of `Rock(...)`.

### Changed: Save.kt

- `"captured"` → `"bodycaptured"` (save format migration)
- `"baselinerock"` → `"baselinebody"`
- `"rockimpulse"` → `"bodyimpulse"`
- `"rocks"` serialization → `"bodies"` serialization (new format: kind, width, height, cells, position, impulse, joules, oreComposition, machineKind)
- `"baselinerock"` → `"baselinebody"`

### Changed: OutofspaceHud.kt

- `"Mass"` key references `s.rockGrams` → `s.bodyGrams`
- `"Captured"` references `s.capturedGrams` → `s.bodyCapturedGrams`
- Balance check `s.rockGrams == s.baselineRockGrams + s.capturedGrams - s.extractedGrams` → `s.bodyGrams == s.baselineBodyGrams + s.bodyCapturedGrams - s.extractedGrams`

### Changed: Body.kt

- `solidJoules(..., rocks: List<Rock>)` → `solidJoules(..., bodies: List<RigidBody>)`
- `for (r in rocks)` → `for (b in bodies)`

### Changed: RockField.kt

- `baselineRockGrams` → `baselineBodyGrams`
- `capturedGrams` → `bodyCapturedGrams`
- Comments referencing "rock ledger" → "body ledger"

### Changed: StarterVessel.kt

- `baselineRockGrams` → `baselineBodyGrams`
- `capturedGrams` → `bodyCapturedGrams`

### Changed: GridGrowTest / remapped

- `rocks` → `bodies` in all test fixtures and assertions.
- `remapped` test that checks rock position translation → same, just with `RigidBody` type.

### New test: RigidBodyTest

- `fromMachine produces correct cell array for a 3x3`
- `fromMachine shaves tolerance on exposed edges`
- `fromMachine keeps connected edges at full boundary`
- `tolerance prevents wedge on wall`
- `drifting body adds momentum to body ledger`
- `body never joins massGrams` (the key assertion — bodies are never aboard)
- `conservation: bodyLedger == 0`
- `sweep body against hull bounces`
- `two independent runs produce identical body behaviour`
- `save/load carries bodies` (round-trip)
- `BodyKind.ROCK and BodyKind.FRAGMENT share drift logic` (same drift function, different shapes)

### Tests that need changes

- `DebrisTest` — dismantling assertions unchanged (debris is still contents, not the casing). But any test that checks the world state now has `bodies` instead of `rocks` — no change to values, just type.
- `RockContactTest` — `sweepRock` → `sweepBody`, `Rock` → `RigidBody`. Values unchanged.
- `GridGrowthTest` — `rocks` → `bodies`, values unchanged.
- `VesselMassTest` — no change (bodies don't affect `massGrams`).

---

## 5. What this enables for the debris plan

The debris plan (`PLAN_rigid_debris.md`) opens questions that this plan answers:

**Open question 3 (aboard transition):** Solved. Bodies are never aboard. A fragment is the player's own machine casing, but once dismantled it is external — same as a rock. It floats, drifts, and can be extracted. Its mass never enters `massGrams` or `inTransitGrams`.

**Open question 5 (heat conduction):** Answered. Bodies are not part of `bodiesOf` — they don't conduct with deck-mounted solids. They carry their own `joules`, counted in `solidJoules`, but do not exchange heat with anything. Same as rocks.

**Open question 9 (TILE_CAP):** Answered. Bodies don't pile up. They're solid objects that rest on deck tiles by collision, not by weight accumulation. No cap needed.

**The fragment ledger** (`fragmentDroppedGrams`) merges into the body ledger (`bodyCapturedGrams`). Dismantling a machine to create a fragment books to `bodyCapturedGrams`, just like dropping a rock books to `bodyCapturedGrams`. One identity:
```
bodyGrams == baselineBodyGrams + bodyCapturedGrams − bodyExtractedGrams
```

---

## 6. Risks

1. **Save migration.** The save format changes from `"rocks"` (list of Rock records) to `"bodies"` (list of RigidBody records). The parser needs to handle both formats: `"rocks"` → convert each rock to `RigidBody(BodyKind.ROCK, ...)`, `"bodies"` → use directly. This is straightforward but adds migration code.

2. **Extraction needs to know which kind.** The extractor's `biteCell` logic currently works on `Rock.composition`. For fragments, it needs `machineKind` to determine output. V1 answer: fragments are one-bite removal (whole fragment gone at once), producing `Resource(Form.MachineParts, mixture)`. No cell-by-cell grinding in V1.

3. **Render needs to know which kind.** The renderer draws rocks with ore colour. It needs to draw fragments with `machineKind` colour. This is a simple branch in the draw call.

4. **`RockContact` name.** The object is now shared between rocks and fragments. Consider renaming to `BodyContact` for clarity, but this is cosmetic and can be done later.

---

## 7. Estimate

| Phase | Days | Notes |
|---|---|---|
| **RigidBody type** | 0.5 | `BodyKind`, `RigidBody` class, factories, equals/hashCode/toString |
| **Unify drift/sweep/collision** | 1.0 | `driftBodies`, `sweepBody`, `overlapsHull` param change, `BodyStep` |
| **VesselState rename** | 0.5 | `rocks` → `bodies`, `capturedGrams` → `bodyCapturedGrams`, etc. |
| **Sim loop wiring** | 0.5 | `OutofspaceSim.kt` references, `Edit.DropRock` update |
| **Save/load migration** | 0.5 | `"rocks"` → `"bodies"`, migrate old format, serialize new |
| **Hud updates** | 0.25 | `rockGrams` → `bodyGrams`, etc. |
| **Body.kt update** | 0.25 | `solidJoules` parameter change |
| **Tests** | 1.5 | `RigidBodyTest` (10+ tests), update `RockContactTest`, `GridGrowthTest`, `DebrisTest` |
| **Integration + cleanup** | 0.5 | Compile all targets, gate |
| **Total** | **~5.0** | |

This is the pre-work. The debris plan (Phase 2) adds fragment spawn on dismantle, extractor one-bite, and the remainder of the body ledger. That's the ~3 days from the original plan, on top of this ~5 days of unification.

---

## 8. What we would know it worked

- Every existing test green after renaming `rocks` → `bodies`.
- A `RigidBody(BodyKind.ROCK, ...)` produced by `RigidBody.rockBlob(...)` drifts identically to the old `Rock.blob(...)`.
- A `RigidBody(BodyKind.FRAGMENT, ...)` produced by `RigidBody.fromMachine(...)` drifts correctly with the tolerance-shaved shape.
- `bodyGrams == baselineBodyGrams + bodyCapturedGrams − bodyExtractedGrams` on every tick.
- Bodies never appear in `massGrams` or `inTransitGrams`.
- Save with bodies loads and drifts identically to a world built from scratch with the same bodies.
- Determinism: dismantle, drift, digest — same digest every run.
