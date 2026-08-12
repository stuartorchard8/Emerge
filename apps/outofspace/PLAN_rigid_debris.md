# Rigid-machine debris

> ## ⛔ SUPERSEDED 2026-08-13 by `PLAN_rigid_bodies.md` §9.2
>
> **Its premise no longer exists.** §7 pivots on replacing `debris.spill(origin, spoilsOf(machine))`
> — there is no `Debris` system in the codebase, and `spoilsOf` is dead code with zero callers.
> Its §2/§3 (`BodyFragment`) were absorbed into `RigidBody` by `PLAN_unified_bodies`, which is built.
> Its §6 "V1: one bite" is obsolete: `biteCell` already eats a body cell by cell.
> Its open questions 2 and 3 ("no rotation", "no fragment-fragment collision") are answered
> **oppositely** — both are core requirements now.
>
> **What was carried forward:** the tolerance rule (0.1 tile, exposed edges only) and `fromMachine`.
> Everything else is archaeology.


*Plan, 2026-08-XX. Nothing built. Companion to `PLAN_dynamic_grid.md` — the dynamic grid
remaps solid tiles between grids; this plan puts solid things **between** grids and gives them
their own frame.*

---

## 1. What this is for

Dismantling a machine today calls `debris.spill(origin, spoilsOf(machine))`: the machine is deleted,
its contents become a Resource pile on the tile below, and the pile settles by gravity like gravel.
That works for a heap of ingots, but the player asked for something different: **the machine itself
floats free as a rigid body** that can drift into an extractor (a grinder later) and be ground back
into its constituent resources.

The current debris system was built for "what falls out of a machine when you take it apart" —
spilled contents, not the machine. Converting the machine to a rigid body is a larger change than
the current tile-to-resource transition, but it is structurally cleaner because it keeps the machine
as one object with its own shape, temperature, and momentum, rather than unrolling it into
per-tile Resource piles.

**Goal.** When the player removes a machine, it spawns as a rigid body at the machine's former
footprint with a 0.1 tile radius tolerance on unconnected edges. It drifts with gravity, bounces off
hull, and can be eaten by extractors tile-by-tile.

**What this is not.** This does not change how dismantled *contents* (Resource piles already inside
the machine) behave — those still become debris. This plan is about the machine's casing / structure
becoming a rigid body. The contents are a separate question (§9).

---

## 2. The decision that shapes everything else

There are two versions of this and they differ by what becomes rigid.

**A. Full machine as one rigid body.** The entire footprint is one `BodyFragment` with the
machine's footprint shape. The machine is removed from `machines[]` and added to a new
`bodies: List<BodyFragment>` alongside `rocks`. One object, one collision shape, one temperature.

**B. Machine broken into per-tile rigid pieces.** Each tile of the machine becomes its own small
rigid body (similar to how `Debris` currently works but with physics). The machine is deleted and
doeszens of bodies spawn in its footprint.

**Recommendation: A.** Three reasons, in descending order of force.

- **Extractor interaction is cell-based, not tile-based.** `biteCell` (Extractors.kt:109) already
  eats a single cell from a rock, reducing the rock's `filled` count and redistributing heat and
  momentum. A one-piece rigid body maps cleanly onto this: the extractor eats cells from the body's
  footprint shape, and when `filled` reaches zero the body is removed. Breaking the machine into
  per-tile pieces would require a cell-to-piece mapping, a piece-removal system, and bookkeeping
  for what happens when a piece is fully consumed (does the remaining shape stay connected?).
  That is a hard geometry problem with no clear answer.
- **Temperature and momentum are per-object, not per-tile.** A machine is one thermal mass. Splitting
  it into per-tile pieces means tracking temperature across N pieces, merging them on extraction,
  and deciding what happens to the thermal energy when a piece is ground off. With one body, the
  extractor's existing `biteCell` pattern works directly: take the cell's share of joules, reduce
  `filled`, redistribute.
- **Fewer objects to collide, fewer to simulate.** A 5×5 smelter becomes 25 bodies under B instead
  of 1. A 3×3 processor becomes 9. The rigid body system is already iterating over all bodies each
  tick; multiplicative blow-up is real.

The rest of this plan assumes A, and notes where B would differ.

---

## 3. The new type

```kotlin
/**
 * A detached machine floating free in the grid. Owned by the vessel's rigid-body system.
 *
 * Two frames: [impulseX/Y] in world frame, [positionX/Y] in vessel's grid frame.
 * Shape is the machine's footprint with [TOLERANCE] shaved off unconnected edges.
 */
class BodyFragment(
    /** Machine type — needed for grinder interaction, rendering, and future cost accounting. */
    val kind: MachineKind,
    /** The shape's bounding box, in cells. Same as [MachineKind.size]. */
    val width: Int,
    val height: Int,
    /** Which cells of that box are solid, row-major. Derived from footprint minus tolerance. */
    val cells: BooleanArray,
    /** Top-left corner of [cells], in the vessel's grid frame. */
    val positionX: Long,
    val positionY: Long,
    /** Momentum in world frame (not vessel frame — ship's frame accelerates). */
    val impulseX: Long,
    val impulseY: Long,
    /** Thermal energy, in the millijoules [Material] documents. */
    val joules: Long,
) {
    val massGrams: Long get() = filled * MATERIAL.gramsPerTile
    val capacity: Long get() = filled * MATERIAL.capacityPerTile

    /** How many cells are still solid — the counter that drives extraction. */
    val filled: Int get() = cells.count { it }

    /**
     * True while the fragment is completely on deck (centre over hull, no cells in vacuum).
     * Determines whether it counts toward [inTransitGrams].
     */
    val restingOnDeck: Boolean = ...

    companion object {
        /** 0.1 tile in billionths. Shaved from unconnected edges to prevent physics pinches. */
        const val TOLERANCE: Long = Flight.PER_TILE / 10L

        /** What fragment casings are made of thermally — same as rocks. */
        val MATERIAL: Material = Material.Firebrick

        /**
         * Build from a machine at [at] in [grid]. The origin is the machine's centre tile;
         * the fragment's positionX/Y is the top-left corner of its bounding box in grid frame.
         */
        fun fromMachine(at: Int, grid: Grid, machine: Machine): BodyFragment = ...
    }
}
```

The footprint shape (solid cells) is derived from `MachineKind.size` and the tolerance rule:

- Connected edges (edges where the machine touches another machine tile) are kept at full tile
  boundary — no tolerance shaving.
- Unconnected edges (edges exposed to air/vacuum) are shaved inward by `TOLERANCE` (0.1 tile).

For example, a 3×3 machine on a tile with one neighbour:
- The edge touching the neighbour stays at the full boundary.
- The other three exposed edges are shaved inward by 0.1 tile, giving a bounding box of
  roughly 2.8 × 2.8 cells in practice (the rasterised cells array has fewer filled cells than
  the full 3×3).

For a 5×5 smelter (all edges exposed):
- All four edges shaved 0.1 tile → bounding box 4.8 × 4.8 → rasterised to a 5×5 cells array
  where the outermost 10% of edge cells are removed.

The rasterisation into `BooleanArray` mirrors what `Rock.blob` does: iterate the bounding box,
mark cells where the physical rectangle covers the cell centre.

**⚠️ Open question.** Is `MachineKind` needed on the fragment? The player will eventually want to
know what kind of machine they dismantled (for rendering, for a grinder's interaction). But if
machines don't yet cost resources from a stockpile, `kind` is dead weight. Answer: keep it for now
— it's an int + reference, negligible compared to the rest, and removing it later is cheaper
than adding it back.

---

## 4. Where fragments live in VesselState

A new field on `VesselState`:

```kotlin
val fragments: List<BodyFragment> = emptyList(),
```

Stored alongside `rocks` and `debris`. The fragment list is iterated every tick for drift and
collision, just like `rocks`.

Fragments are **not part of "aboard"** while drifting — same semantics as rocks. They only count
toward `inTransitGrams` and `massGrams` when `restingOnDeck` is true.

**⚠️ Open question.** Rocks are *never* aboard (they are external mass). Fragments *start* detached
but should become aboard once they rest on deck — because they are the player's own machine, not an
external rock. This is a design decision: do fragments ever slow the ship while floating, or only
when settled? Answer: only when settled. A floating fragment is what the player just removed from
the ship; it should not retroactively penalise the vessel's mass budget. The transition from
detached → aboard happens when `restingOnDeck` becomes true (see §5).

The ledger would look like:

```
fragmentGrams == baselineFragmentGrams + droppedGrams − groundGrams
```

Where `droppedGrams` comes from the `Edit.Remove` that spawns the fragment, and `groundGrams`
comes from extractors grinding fragments. The `baselineFragmentGrams` is the fragment mass the
world started with (likely zero — fragments are almost always player-spawned).

**⚠️ Open question.** Does `droppedGrams` need its own ledger field on `VesselState`, or can we
reuse `capturedGrams` (currently rock-specific)? Answer: a new field `fragmentDroppedGrams` on
`VesselState`. Don't share `capturedGrams` — it is tied to the rock identity, and the plan says
fragments are a separate concept. If rocks and fragments ever merge, the split makes the merge
clean.

---

## 5. Drift and collision

The new `driftFragments` function mirrors `driftRocks`:

```kotlin
fun driftFragments(
    grid: Grid,
    structure: StructureMap,
    fragments: List<BodyFragment>,
    platingGravity: Frac2,
    shipVelocityX: Long,
    shipVelocityY: Long,
    shipMassGrams: Long,
    shipAcceleration: Frac2,
): FragmentStep
```

**Gravity.** Same as rocks: `platingFeltBy` returns the plating gravity vector only for tiles
centred on the vessel's deck. Fragments in freefall drift in a straight line; fragments on deck
fall toward the lowest hull point.

**Sweep collision.** Same as `sweepRock`: sub-stepped sweep against the hull, bounce with
restitution. The tolerance (0.1 tile) on unconnected edges helps here — it prevents the sharp
corner pinch that would cause a fragment to wedge against a wall.

**Resting detection.** Same as rocks: when velocity drops below `restingSpeed`, the fragment
stops. At that point, check if the fragment's centre is over deck tiles. If yes, set
`restingOnDeck = true` and add its mass to `inTransitGrams` (the fragment is now "aboard"
as loose material on the deck, not aboard as part of the vessel structure).

**⚠️ Open question.** Should fragments rest on top of other debris (Resource piles)? Currently
debris is not solid to rocks — `overlapsHull` only checks `StructureMap`, not `Debris`. A fragment
drifting into a pile of ingots would fall through. Answer for V1: fragments do not interact with
debris. Debris is for resources; fragments are for machine casings. If fragments need to rest on
debris, add a debris-colider pass. This is a V2 concern.

---

## 6. Extractor interaction

An extractor plate is already permeable to rocks and does not block `overlapsHull`. The same
property makes it work for fragments: the fragment can overlap the extractor's footprint.

`biteCell` (Extractors.kt:109) already handles cell-by-cell consumption. For fragments, the
extractor's `reachableCell` logic (Extractors.kt:71) finds the nearest solid cell of the fragment
to the plate's centre, and `biteCell` removes it.

But fragments are not rocks — they have a different shape, different material, and different
composition (a machine casing is made of a single Material, not ore). The extractor needs to know
what to produce when grinding a fragment.

**V1 simplification.** A fragment is eaten as a single unit: one bite removes the entire fragment
from the world and deposits its mass into the extractor's buffer as `Resource(Form.MachineParts,
Mixture.EMPTY)` — or simply as raw mixture. No partial-grinding of the fragment shape. When the
extractor's output buffer fills, the resource is pushed out on the product port like any other
machine.

This avoids the hard geometry problem of "which cells to eat from a 5×5 fragment" and lets
the extractor code stay simple. The player sees the fragment disappear into the extractor in one
bite, like a rock.

**V2.** Gradual grinding — the fragment shrinks visually and mechanically as cells are eaten.
This requires `biteCell`-style logic for fragments (reduce `filled`, redistribute joules/impulse)
and a way to update the cell array without leaving holes.

---

## 7. Spawning: the new Edit.Remove

The edit pipeline (OutofspaceSim.kt:760) currently calls:

```kotlin
debris.spill(origin, spoilsOf(machine))
machines[origin] = null
```

Replace with:

```kotlin
val fragment = BodyFragment.fromMachine(origin, grid, machine)
fragments.add(fragment)
fragmentDroppedGrams += fragment.massGrams
built(fragment.joules)
machines[origin] = null
```

The machine's *contents* (Resource piles inside the machine) are handled separately — they become
debris as before. Only the casing (the machine structure itself) becomes a fragment.

**⚠️ Open question.** What about the machine's internal buffers? `Machine` has `input` and
`buffer` fields for Resource piles. When dismantled, the contents need to spill as debris. This is
already handled by `spoilsOf(machine)` — keep that call before the fragment spawn, so contents go
to debris and the casing becomes a fragment.

---

## 8. What stays the same

- The rigid body physics system (sweep collision, restitution, drift astern, plating gravity) —
  already built for rocks, reused for fragments.
- The `RockContact` object — shared between rocks and fragments.
- The grid's role as the vessel's frame — fragments use the same grid frame as rocks.
- The reducer pipeline ordering — fragments drift in the same late-tick pass as rocks.
- The dynamic grid — fragments are translated on remap, same as rocks.

---

## 9. What changes: the full diff

### VesselState (Vessel.kt)
- Add `fragments: List<BodyFragment> = emptyList()`
- Add `fragmentDroppedGrams: Long = 0L` (and `baselineFragmentGrams`)
- `inTransitGrams` gains a term for resting fragments
- `massGrams` gains a term for resting fragments
- `vesselMassGrams` function gains a fragments parameter

### Debris.kt / DebrisWork.kt
- No changes. Debris stays as-is for Resource piles.

### Rock.kt / RockContact.kt
- `RockContact` is shared — no changes needed for fragments to use it.
- `driftRocks` is NOT reused directly — it is rock-specific (uses `Rock` type).
- Create `driftFragments` in a new `BodyFragments.kt` or alongside `Rock.kt` as a separate function.
- The sweep collision logic is the same, but `overlapsHull` takes a `Rock`. Either refactor
  `overlapsHull` to be generic, or create `overlapsHullFragment`.

### Save.kt
- Add serialization for `fragments` (position, cells, impulse, joules, kind).
- Add `fragmentDroppedGrams` to the save format.

### OutofspaceSim.kt
- Replace `debris.spill(origin, spoilsOf(machine))` with fragment spawn + debris spill.
- Add `driftFragments` call in the reducer pipeline (same location as `driftRocks`).
- Add fragment conservation check in the reducer.

### Footprint.kt / MachineKind.kt
- Add a method to derive the tolerance-shaved cell array from `MachineKind.size`.

### GridGrowth.kt
- Remap fragment positions on grid resize, same as rocks.

---

## 10. Tests

**BodyFragmentTest** — new file.

- `fromMachine produces correct cell array for a 3×3` — verify the cells match the expected shape.
- `fromMachine shaves 0.1 tile on exposed edges` — verify filled count is reduced.
- `fromMachine keeps connected edges at full boundary` — test with a machine adjacent to another.
- `tolerance prevents wedge on wall` — sweep a fragment against a hull tile and verify it does not
  get stuck.
- `drifting fragment adds mass to aboard when resting on deck` — verify `inTransitGrams` changes.
- `drifting fragment does NOT add mass when in freefall` — verify mass stays off-ship.
- `conservation: fragment mass is tracked in fragmentLedger` — verify the identity.
- `extractor removes entire fragment in one bite` — verify fragment disappears and mass is
  delivered to buffer.
- `two independent runs produce identical fragment behaviour` — determinism check.
- `save/load carries fragments` — round-trip test.

**Existing tests that need changes:**

- `DebrisTest.dismantling a full storage spills its contents instead of deleting them` — the
  machine casing now becomes a fragment; only the *contents* become debris. The assertion about
  `s.debrisGrams == 9_000L` still holds (contents), but the overall state now includes a fragment
  with the casing mass.
- `DebrisTest.a heap keeps its forms apart` — same: contents become debris, casing becomes
  fragment.
- `DebrisTest.material spilled outside the hull goes overboard` — a fragment floating over vacuum
  should not be vented (fragments are not debris). The test may need to check that a fragment
  drifts off without affecting `ventedGrams`.

---

## 11. Open questions

1. **What resource form does an extractor produce from a fragment?** V1 says `MachineParts` or
   raw mixture. Should it be a new `Form`? Or reuse an existing one? This depends on whether
   machines cost resources from the stockpile yet — if they don't, the output form is a
   placeholder that will be changed later.

2. **Should fragments have orientation?** Machines are `Directed` (have a facing). A floating
   fragment could preserve that orientation, which matters for: (a) rendering (which way does the
   smelter's output port face?), (b) future grinder interaction (does the grinder care which side
   the fragment presents?), (c) visual legibility (player can read which machine they dismantled).
   Adding rotation to `BodyFragment` means storing angle + angular impulse and applying it in
   the drift pass. This is a non-trivial addition. V1 answer: **no rotation** — fragments are
   axis-aligned rectangles. Rotation is V2.

3. **What happens when two fragments collide?** Rocks don't collide with each other — they only
   collide with the hull. Two rocks can pass through each other. Fragments are bigger and more
   numerous; passing through each other might look wrong. But adding fragment-fragment collision
   is more expensive (O(n²) broadphase) and adds a second collision target. V1 answer: **no
   fragment-fragment collision**. They pass through each other like rocks. If this is unacceptable,
   add a broadphase sweep.

4. **How many fragments can the world hold?** Rocks are typically 0–3 (one per captured rock +
   field). Fragments could be dozens — the player might dismantle a whole factory. The drift pass
   iterates all fragments; with 50 fragments it is still cheap (50 × sweep is ~1000 tile checks).
   But if fragments accumulate (player dismantles everything and they all float around), this could
   become a performance issue. V1 answer: no cap. If performance degrades, add a cap or a
   "compact fragments" operation.

5. **Do fragments conduct heat with nearby bodies?** Rocks do not — `bodiesOf` (Body.kt:64) does
   not include rocks. Should fragments conduct? V1 answer: **no conduction**. Fragments are not
   part of the solid heat ledger. They carry their own `joules` which are counted in
   `solidJoules` (like rocks), but they do not exchange heat with nearby machines. This is
   consistent with how rocks behave and keeps the model simple.

6. **What about bridges and conduits?** Bridges are currently dismantled into per-slot `Resource`
   piles (OutofspaceSim.kt:737). Conduits become debris too (OutofspaceSim.kt:747). Should they
   become fragments? Bridges are already three-tile spans — they map well to a rigid body.
   Conduit segments are one tile — they could become small fragments. V1 answer: **no**. Only
   deck machines (non-permeable, non-conduit) become fragments. Bridges and conduits stay as
   debris (their contents only — the metal of a bridge segment is currently its `carried` Resource,
   which is the right granularity for debris). This is a simplification that can be revisited.

7. **What about the `isPermeable` property?** Extractors are permeable. If a fragment spawns
   inside an extractor's footprint (player removes the extractor itself), does the fragment fall
   through the extractor or rest on it? V1 answer: fragments obey the same permeability rules
   as rocks — they do not bounce off extractor plates. A fragment spawned on an extractor
   (by removing the extractor) will fall through the extractor's tile (since it is not solid to
   fragments either). This is correct: the extractor is a plate, not a block.

8. **Can the player pick up a fragment and place it back?** Currently there is no "pick up" edit.
   Fragments are created by dismantle and consumed by extractors. If the player wants to reposition
   a fragment, they would need to extract it (grind it) and rebuild. V1 answer: **no pick up**.
   Fragments are permanent until consumed. If this is undesirable, add an `Edit.CollectFragment`
   that removes a fragment from the world and credits its mass back to the stockpile (V2).

9. **Should fragments be subject to the same `TILE_CAP` limitation as debris?** Debris has
   `TILE_CAP = 400_000L` — a pile stops falling into a tile when it hits 400 kg. Fragments are
   solid bodies; they do not "pile up" in the same way. A fragment either rests on deck (its centre
   over hull tiles) or it falls. If two fragments land on the same tile, they sit side by side
   (different positions). V1 answer: no cap. Fragments can stack on the same tile by resting
   side-by-side. If this causes visual overlap issues, the renderer can handle it (draw offset).

---

## 12. Risks, ranked

1. **The tolerance rule is fiddly to implement correctly.** Computing which edges are "connected"
   (touch another machine tile) requires knowing the machine's neighbours at dismantle time. A
   machine on the edge of the grid has fewer neighbours. A machine surrounded by hull has all
   edges connected. This needs a careful implementation in `fromMachine` — iterate the machine's
   footprint, check each edge cell against the occupancy grid, and only shave exposed edges.

2. **Fragment-fragment collisions are ignored, which may look wrong.** Two 3×3 fragments passing
   through each other is physically unrealistic. But adding collision doubles the work per tick
   and is the first step toward a full rigid body engine, which is out of scope. If this becomes
   a problem, a simple bounding-box overlap check can be added without full collision resolution.

3. **The "aboard transition" is tricky.** A fragment starts detached (not in `massGrams`), then
   when it rests on deck it should join `inTransitGrams`. This means the fragment's mass needs to
   be added to the vessel's mass *after* it has come to rest — not at spawn time. The transition
   needs to be atomic (one tick to the next) to avoid mass appearing or disappearing mid-tick.
   The simplest implementation: check `restingOnDeck` at the end of `driftFragments`, and if a
   fragment just transitioned, add its mass to `inTransitGrams` via the accumulator that the
   reducer uses.

4. **Extractor produces placeholder output.** Without machine cost accounting, the grinder output
   form is a placeholder (`MachineParts` or raw mixture). This is a V2 concern but worth knowing:
   the output form will need to change when machines are given a resource cost.

5. **Tests: dismantling now creates two things.** Every dismantling test now has two assertions:
   one for debris (contents) and one for fragments (casing). The existing `DebrisTest` assertions
   about debris mass still hold; new assertions need to be added for fragment mass.

6. **Renderer: fragments need drawing.** The renderer already draws rocks. Fragments need a
   similar draw call: draw the cell array at `positionX/Y`, tinted by `MachineKind` (not by ore
   colour). The renderer update is straightforward — copy the rock draw logic, change the colour
   source.

---

## 13. Estimate

| Phase | Days | Notes |
|---|---|---|
| **BodyFragment type** | 0.5 | Shape derivation, tolerance rule, `fromMachine`, basic equals/hashCode |
| **VesselState fields** | 0.5 | New fields, mass ledger, `inTransitGrams` integration |
| **Drift + collision** | 1.5 | `driftFragments`, `overlapsHullFragment`, resting detection, aboard transition |
| **Spawn on dismantle** | 0.5 | Replace `debris.spill` call in `removeMachine`, add fragment spawn |
| **Extractor interaction** | 0.5 | One-bite removal, placeholder output, ledger booking |
| **Conservation ledgers** | 0.5 | `fragmentDroppedGrams`, `baselineFragmentGrams`, balance checks |
| **Save/load** | 0.5 | Serialize/deserialize fragments, migrate saves |
| **Grid remap** | 0.25 | Translate fragments on grid resize (same as rocks) |
| **Tests** | 2.0 | `BodyFragmentTest` (10+ tests), update `DebrisTest`, determinism check |
| **Integration + cleanup** | 0.5 | Reducer pipeline wiring, ledger wiring, review |
| **Total** | **~7.0** | |

**V2 extensions (not included):** rotation (±2d), fragment-fragment collision, gradual cell-by-cell
extraction, player pick-up, proper grinder interaction with orientation. These could add 3–5 more
days.

---

## 14. What we would know it worked

- Every existing test green after updating dismantling assertions.
- A world with all machines removed: every machine casing is a floating fragment, every machine
  contents pile is debris on the deck.
- Fragments on a level deck settle to rest; fragments in freefall drift in straight lines.
- An extractor eats a floating fragment and deposits its mass in the output buffer.
- `fragmentLedger == 0` on every tick.
- A save with fragments loads identically.
- A determinism check: dismantle, let fragments settle, extract one, digest — run again from
  the same save, digest is identical.
- Visual: dismantled machines float briefly, fall to the deck, and can be ground by extractors.
