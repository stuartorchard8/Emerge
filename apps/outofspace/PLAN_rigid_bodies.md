# Rigid Bodies — one body structure, colliders, and rotation that is physically real

*Written 2026-08-13. **Supersedes step 4 of `PLAN_trig_free_rotation.md`**, which scoped
"`RigidBody` orientation and collisions imparting torque" without a collider model and without the
unification requirement. Steps 1–3 of that plan (integer trig, vessel `ang`/torque, world-frame
camera) stand and are prerequisites.*

**Step 1 is built (`72d943b5`, `cc417e8c`). Everything else in this document is not.**

⚠️ **Two `@Ignore`d claims are owed back by steps 3 and 4**, both measured rather than assumed —
each passes with the vessel's spin forced to zero and fails with it live:
`RockContactTest.a body cannot tunnel through a bulkhead` (containment during a long bounce) and
`RockTest.a body falling under straight-down gravity does not drift sideways`. Neither is a
regression; before step 1 the vessel's angle was a number nothing read, so both were measuring a
room that could not tilt. **Do not soften either bound** — containment is the thing that is broken,
and it is broken for the reason this plan exists.

⚠️ **Found while building step 1: the vessel was rotating about its grid *origin*, not its centre of
mass.** Step 2 of the rotation plan booked torque about the centre of mass and then integrated the
angle without moving the origin, which spins the ship about a corner of the pad. Fixed by
[Pose.turnedAbout]. It was invisible for exactly as long as nothing read `ang`.

---

## 1. What this is for

Stu, 2026-08-13, in order of how much they constrain the design:

1. **Vessels and rocks eventually unify** under one grid + collider rigid-body structure. *Anything
   that diverges them further is discouraged.* This is the binding constraint, and it is the one
   that most of the decisions below fall out of.
2. **Collisions between rotated bodies**, including **rock against rock** — which does not exist in
   any form today — and stacking.
3. **One disc per tile** for bodies now, degrading naturally as extractors bite tiles away. (Stu
   raised and then rejected the alternative himself: a single large disc spanning the asteroid plus
   surface discs, which breaks down the moment a rock is mined.)
4. **Selective per-cell shapes later** — some cells becoming OBBs, some staying discs.
5. **Triangular half-tiles eventually**, in *both* asteroids and vessel machines, for more dynamic
   shapes visually and in collision.
6. **None of 3–5 may require redoing the others.** Adding the triangle must be adding a shape, not
   rewriting the solver.

Requirement 6 is the whole reason this is a plan and not a patch. The current collision code fails
it in a specific and unfixable way, described in §2.

---

## 2. What is there today

Facts, checked 2026-08-13, not recollection.

| | Today |
|---|---|
| Body shape | `RigidBody.width/height/cells` — an axis-aligned box plus a row-major `BooleanArray`. `RigidBody.kt:21` |
| Body orientation | **None.** No `ang`, no `angImpulse`, no angular anything. |
| Hull shape | Tile lookups via `structure.isImpermeable(grid.index(tx, ty))`. `RockContact.kt:95` |
| Overlap test | Per body cell, per covered tile, boolean. `overlapsHull`, `RockContact.kt:81` |
| Contact normal | **Inferred from axis re-tests** — "if I move on x alone, do I still overlap?" `RockContact.kt:185` |
| Contact point | **Does not exist.** Nothing in the file computes one. |
| Friction | None. `RockContact.kt:9` says so: *"Axis-aligned, frictionless (no rotation → no torque; grids → axes, not contact manifolds)."* |
| Body vs body | **Does not exist.** `driftBodies` maps over bodies independently; each sweeps against the hull and passes straight through every other body. `RigidBody.kt:275` |
| Solver | Single pass, per body, sweep-and-bounce inside `sweepBody`. `RockContact.kt:126` |
| Vessel rotation | `ang`/`angImpulse`/`netTorque` exist and are **read by nothing in the sim.** Only the renderer consumes `ang`. |

Three of those are structural, meaning no amount of adding shapes fixes them:

- **A normal inferred from axis re-tests can only ever be ±x or ±y.** Give a body an angle and the
  test still answers "left", because the question it asks is about the axes, not about the geometry.
- **No contact point means no torque, even in principle.** `τ = r × J` needs an `r`.
- **A per-body single-pass sweep cannot stack.** Resting one rock on another requires the two
  contacts to be solved *together*; solved in sequence, the upper body's support is applied before
  the lower body knows it is being leaned on, and the pair sinks or jitters. This is the change Stu's
  "body → hull → body stacking" asks for, and it is a change to the *shape of the tick*, not to a
  formula.

One correction to `PLAN_trig_free_rotation.md`'s warning, since it would otherwise set the order of
work: **the "six standing free-body failures" are not six red tests.** The Out of Space suite is
green. There is exactly one `@Ignore`d free-body contact test — `RockContactTest.kt:137`, *"a body
that lands on the deck settles and stays put"* — plus unrelated ignores for pressure residue
(`FlightTest.kt:53`), a jam test (`VesselSimTest.kt:247`), and the energy ledgers. The ground is
considerably better than the plan claims. The one ignored test is a genuine target for this work.

---

## 3. The three abstractions that must never need redoing

This section is requirement 6 made concrete. If these three hold, adding the triangle is adding a
shape; if any one of them leaks, it is a rewrite.

### 3.1 A contact is a point, a normal, and a depth — and the solver knows nothing else

```
class Contact(
    val a: BodyRef, val b: BodyRef,   // b may be the vessel
    val pointX: Long, val pointY: Long,   // millitiles, world-ish frame (see §5)
    val normalX: Long, val normalY: Long, // unit, Frac raw
    val depth: Long,                      // millitiles of penetration
)
```

Everything downstream — restitution, friction, the resting threshold, torque, the ledger — is
written against **this and nothing else**. It never asks what shape produced it. That single rule is
what makes a new shape cost one narrow-phase function and zero solver changes, and it is exactly the
rule the present code breaks by deciding the normal from an axis re-test in the middle of the sweep.

### 3.2 Shape is per cell, and dispatch is a pair table

```
sealed interface CellShape { Disc; Box; Triangle(quadrant) }   // Disc now; the others are stubs
```

A body is a grid of cells; each filled cell carries a `CellShape`. The narrow phase is a table
indexed by the pair:

| | Disc | Box | Triangle |
|---|---|---|---|
| **Disc** | **now** | **now** | later |
| **Box** | **now** | later (SAT) | later |
| **Triangle** | later | later | later |

Two pairs are needed on day one and both are exact, cheap, and closed-form in integers — no SAT, no
clipping, no iteration:

- **Disc vs Box** — the hull is boxes (§4), so this is every rock-against-ship contact. Closest
  point on the box to the disc centre; normal is the difference, depth is `r − |d|`.
- **Disc vs Disc** — every rock-against-rock contact. Normal is the centre difference, depth is
  `r₁ + r₂ − |d|`.

Adding `Triangle` later means writing `Disc×Triangle` and `Box×Triangle` and filling in two cells of
that table. The solver, the ledger and the tick order do not move.

### 3.3 A body is a pose, a momentum, and a cell grid — and the vessel is one of them

The convergent structure, which the vessel and `RigidBody` should be **growing toward** and never
away from:

```
pose:      positionX/Y, ang
momentum:  impulseX/Y, angImpulse
shape:     grid + cells + per-cell CellShape
mass:      MassDistribution (mass, comX, comY, gyrationSq)   // already exists, Rotation.kt
```

The vessel already has every one of these except the per-cell shape — step 2 gave it `ang`,
`angImpulse` and `MassDistribution`, and `massDistribution()` already walks machines, conduits and
bridges. `RigidBody` has the cell grid and is missing the whole angular half.

**The practical form of "do not diverge them further":** the narrow phase and the solver must be
written against the *interface* above, with the vessel passed as one operand, from the first commit.
Not "rocks now, vessel later" — that is precisely the divergence Stu ruled out, and it is what
produces a second collision path that then has to be deleted. The vessel can keep its own storage;
what must be shared is the code that reads it.

---

## 4. Shape: discs per tile, and the one thing discs cannot do

Discs per tile, as Stu asked. Rotation moves only the centres, so a disc body is correct at any
angle with no shape maths at all — which is the property that makes this the right thing to build
first. It also degrades exactly as wanted: bite a tile out of an asteroid and its disc goes with it.

### ⚠️ A union of discs cannot represent a flat surface. Measured, not asserted:

| Disc radius | Peak | Midpoint between two adjacent discs | Scallop depth |
|---|---|---|---|
| 0.5 tile | 0.500 | **0.000** | **0.500 tile** |
| 0.6 tile | 0.600 | 0.332 | 0.268 tile |
| 0.707 tile (covers the corner) | 0.707 | 0.500 | 0.207 tile |
| 1.0 tile | 1.000 | 0.866 | 0.134 tile |

At the natural radius of half a tile, **adjacent discs merely touch and the notch between them is a
full half-tile deep** — a surface of such discs is a row of circles, not a wall. Even at the radius
that covers the tile corners, a fifth of a tile of scallop remains, and it never goes away: no
radius makes a union of discs flat.

Two consequences, and the first is a day-one decision rather than a later refinement:

- **The hull and machine casings must be boxes, not discs.** This is not a deferral of requirement
  4 — it is requirement 4 arriving immediately, because a rock sliding along a disc-built wall
  would drop into a notch every tile. Today the hull already *is* boxes, for free, because it is a
  tile grid; the work is to keep it that way rather than to build it.
- **Discs are right for rubble and stay right.** A rock resting on a flat deck rests on its lowest
  disc, which is stable. Lumpiness while rolling reads as rubble, which `drawBody`'s KDoc already
  says is the intended read.

So the pair that carries all the weight on day one is **Disc vs Box**, and it is the exact pair that
is easiest to get right.

**Radius: half a tile**, giving a rock the inscribed-disc silhouette rather than a bulging one. The
scallop is *internal* to the rock's own outline and only matters where two bodies of discs touch
each other, which is rock-on-rock — where it reads as rubble catching on rubble, correctly.

### 4.1 The 0.1-tile tolerance on unconnected edges — kept, and generalised

Stu, 2026-08-13: keep it. Carried over from `PLAN_rigid_debris` §3, where it existed to stop a sharp
corner pinching against a hull tile. `RigidBody.TOLERANCE = Flight.PER_TILE / 10L` already exists as
a constant and **nothing reads it**; this is what finally reads it.

**The rule, stated per shape rather than per body:**

> A cell's collider is inset by `TOLERANCE` on each of its **unconnected** faces — a face with no
> solid cell of the same body beyond it. **Connected faces are kept at the full tile boundary.**

The "connected faces stay full" half is not a detail, it is what makes the rule safe. Inset *every*
face and a hull wall stops being a wall: it becomes a row of separated boxes with a notch between
each pair, which is §4's scalloping problem arriving by a different route. Only the outside of a
body is shaved; its internal seams stay welded.

Per shape:

| Cell shape | What the inset means |
|---|---|
| **Box** (hull, machine casings) | A genuine per-face inset. **This is where the rule does its work**, because a box is the only cell that has a corner to pinch with. |
| **Disc** (rubble) | **Satisfied intrinsically — do not shrink the radius.** A disc of radius half a tile already stands 0.207 tile clear of the tile corner, twice the tolerance asked for, and a corner that does not exist cannot pinch. ⚠️ Shaving a disc to r = 0.4 would take two adjacent cells from *touching* to a **0.2 tile gap**, turning the body into a bag of loose circles with notches an external disc could nestle into. The tolerance is a maximum on how sharp a cell may be, and a disc already satisfies it. |
| **Triangle** (later) | A per-face inset like the box, including the hypotenuse. |

So the rule reads uniformly as *"no cell may present a corner sharper or more exposed than
`TOLERANCE` allows"*, and each shape satisfies it in its own way — which is the form it has to take
to survive §3.2's requirement that a new shape costs one function.

---

## 5. The frame — delete the vessel frame, do not patch it

**This is step one, and the fix is not the one an earlier draft of this section proposed.**

Today:

- A body's **position** is in the vessel's grid frame. `RigidBody.kt:29`
- A body's **impulse** is in the **world** frame, because the vessel's frame accelerates and is
  therefore not inertial. `RigidBody.kt:32`
- `sweepBody` compares them directly: `relative(impulse, shipVelocity)`, `RockContact.kt:161`.

That subtraction is only valid while the two frames share their axes. **A nonzero vessel `ang`
silently invalidates it** — not approximately, by the full rotation.

The obvious repair is to patch the conversion with `R(−vesselAng)` and a `ω × r` term. **Do not do
that.** It buys a rotating reference frame and all its fictitious forces — Coriolis, centrifugal,
Euler — permanently, in exchange for a bug fix. And it cannot survive §3.3 anyway: **you cannot
unify vessels and rocks while one of them defines the coordinate system.** A privileged "vessel grid
frame" is exactly the divergence Stu ruled out, expressed as a coordinate choice.

### 5.1 Everything is stored in world coordinates

The vessel gets a pose like any other body. Bodies store position **and** impulse in the world
frame. No conversion, no fictitious forces, no `ω × r` anywhere.

The grid survives untouched in the role it is actually good at. `PLAN_grid_vs_continuous.md` §4
already named the distinction and it is the right one:

> The grid is being conflated with two separate abstractions: **the grid as an addressing scheme**,
> and **the grid as a shape constraint.** These are independent.

The grid stays the **addressing scheme** — fluids, heat, transport, occupancy, room detection,
ledgers, all unchanged, all still indexed by tile. It stops being the **frame**. A collision query
against the vessel transforms the query point into the vessel's local space *once*, then indexes
tiles exactly as today:

```
local = R(−vessel.ang) · (pWorld − vessel.pos)      // once per body per substep, not per cell
tile  = grid.index(floorTile(local.x), floorTile(local.y))
```

That is the standard "transform the query into the other body's local space" trick, it is one
rotation per query rather than one per cell, and **it is the line where the vessel's `ang` stops
being decorative.**

### 5.2 Stu's precision point, quantified

The reason for clinging to the vessel frame was float precision degrading away from the origin.
`Long` removes it, and by a wide margin:

| | Range | Spacing at 10⁶ tiles | Spacing at 10⁹ tiles |
|---|---|---|---|
| `Long` at `PER_TILE` = 1e9 | ±9.2 × 10⁹ tiles | **10⁻⁹ tile** | **10⁻⁹ tile** |
| `Float` | — | 0.06 tile | 60 tiles |

Uniform everywhere, which is the property that makes a world frame viable at all.

### 5.3 ⚠️ The trap that replaces the one we are deleting

Absolute world coordinates are large, and **a product of two of them overflows `Long` past 3.04
tiles from the origin.** Not 3.04 million — three tiles. Every squared distance, every cross
product, every SAT projection must be computed on **differences** reduced to a local origin (the
contact pair's own frame) before any multiply. Absolute coordinates may be added and subtracted;
they may never be multiplied.

This is the same class of hazard as the rescale's, and it fails the same way — silently, as a
wrapped value that reads like an absence. It wants an assertion in the prototype (§6) on every
intermediate, and it is the single thing most likely to go wrong in this step.

---

## 6. Numerics to settle before writing Kotlin

Per the method that paid off in step 1: prototype in Python emulating Kotlin's exact semantics
(truncating division, arithmetic `shr`, a `Long` overflow assertion on every intermediate) before
transcribing.

| Quantity | Where it can go wrong |
|---|---|
| **Absolute world coords** | §5.3. **A product of two overflows past 3.04 tiles from the origin.** Reduce to a difference first, always. |
| Body `gyrationSq` | Reuse `Rotation.MassDistribution`. ⚠️ Never materialise `Σ m·r²` — step 2's lesson, and a rock at a microgram per unit is 83 tonnes. |
| Contact point → torque | `torqueAbout` already works in millitiles and already exists. Body centres are at `Flight.PER_TILE` (1e9); the existing conversion is `/(PER_TILE / MILLI_TILE)` = 1e6. |
| Disc-vs-box closest point | Clamp in millitiles. `d² ` at 1e5 millitiles squares to 1e10 — fine, but `r² ` comparisons must not be done in `PER_TILE` units, where they square to 1e18 and leave one multiply of headroom. |
| Normal, unit length | `Norm`/`Frac` raw is `Int.MAX_VALUE` = one. A depth in millitiles times a normal raw is 1e5 × 2.1e9 = 2.1e14 — fine. |
| Reduced mass | Already a reduced fraction at `RockContact.kt:150` for exactly this reason. The angular version, `1/(1/m + r²/I)`, needs the same treatment and is worse: it is quadratic in mass *and* carries an `r²`. |
| Restitution/resting | Existing `restingSpeed` chain is single-rounding by design, `RockContact.kt:40`. Preserve that property; do not split the chain. |

Determinism: contacts must be **generated and solved in a stable order** (body index, then cell
index), never in the order a broad phase happens to emit them. Same class of bug as the
`LockstepHost` input-ordering issue already on record.

---

## 7. Proposed steps

Each lands green, with a failing test written first. Sizes are relative, not calendar.

| # | Step | Why here | Size |
|---|---|---|---|
| **1** | ✅ **BUILT `cc417e8c`** (transform `72d943b5`). **The world frame (§5).** Bodies store position *and* impulse in world coordinates; the vessel gets a pose; collision queries transform into vessel-local space once. Delete the grid frame from the physics; the grid stays the addressing scheme. | **Stu's call: first.** It is the change that makes the vessel's existing `ang` physical, and every later step would otherwise be built on a frame that is about to be deleted. Shape-independent. | M |
| **2** | **`Contact` + solver skeleton.** Replace `sweepBody`'s inline bounce with: broad phase → contact list → iterative solve → integrate. Keep discs out of it: generate the *existing* axis-aligned hull contacts, but as `Contact` values with a real point and normal. | Proves the new tick shape against behaviour that already works and is already tested, so any regression is the solver's. **This is the step that buys stacking.** | M |
| **3** | **Body `ang`, `angImpulse`, gyration.** The exact mirror of what step 2 of the rotation plan did to the vessel; `Rotation.kt` already has the machinery. Torque applied at the contact point from step 2, on both operands. | A contact now has an `r`, so torque is free. First step where a rock visibly spins. | S |
| **4** | **`CellShape.Disc` + the pair table.** Disc-vs-Box (hull) and Disc-vs-Disc (rock-on-rock), with friction looked up per contacting cell pair. Bodies become discs; the hull stays boxes per §4. | The narrow phase, now that everything it feeds is in place. | M |
| **4b** | **`fromMachine` + the tolerance rule** (§9.5). Dismantling spawns a `BodyKind.FRAGMENT` body, exposed edges shaved by `RigidBody.TOLERANCE`. | Makes a dead enum arm reachable and gives the anti-pinch constant its first reader. First rotating bodies in ordinary play rather than in a test. | S |
| **5** | **Body-vs-body broad phase + stacking.** | Needs 2 and 4. | M |
| **6** | **The vessel as an operand.** Vessel passed through the same narrow phase and solver as a body of box cells, colliders on every tile (Stu: optimise later). | The unification payoff. | L |
| — | *Later, unblocked by the above:* `CellShape.Box` per cell, `CellShape.Triangle`, OBB-vs-OBB SAT. | Each is one narrow-phase function. | — |

Test targets worth naming now, because they are the ones that discriminate:

- **`RockContactTest.kt:137` un-`@Ignore`d** — a body that lands on the deck settles and stays put.
  It is already written and already the right test.
- A rock striking the ship **off the centre of mass** spins the ship; striking **through** it does
  not. The centreline case is the discriminator, exactly as in step 2.
- A rock dropped on a stack of two rocks leaves all three at rest — the stacking test, which step 1
  must pass and the current architecture cannot.
- A rock hanging motionless in world space **drifts in an arc** across the deck of a spinning ship
  (§5), and drifts in a straight line across a ship that is only translating.

---

## 8. Settled by Stu, 2026-08-13

1. **The frame goes first.** "We've been clinging to it for too long." The reason for clinging was
   float precision degrading away from the origin, and `Long` makes that concern evaporate (§5.2).
2. **Restitution and friction become mixture- and form-dependent — later.** So they start as the
   two constants that exist today, but **the narrow phase must look them up from the two contacting
   cells from day one**, defaulting to those constants. A single global read baked into the solver
   is a redo; a lookup that currently returns a constant is not. This is a one-line difference now
   and the whole difference later.
3. **Colliders for every tile on the grid.** No derived boundary-cell list. Optimise later — and
   note the optimisation is invisible to everything above the broad phase, so it stays cheap to add.
4. **Rock-on-rock scalloping** is accepted as rubble-on-rubble texture (§4). The first `Box` cells
   go to the hull, where they are needed for correctness rather than feel.

---

## 9. The adjacent plans, assessed

Read 2026-08-13 against the code as it actually is. Three of the four have been partly overtaken
by work that was done without marking them.

### 9.1 `PLAN_unified_bodies.md` — **~90% BUILT, never marked as such**

Its whole §2 decision shipped. `RigidBody`, `BodyKind`, the single `bodies: List<RigidBody>`,
`driftBodies`, `sweepBody`, `overlapsHull(… body: RigidBody …)`, `BodyStep`, `bodyImpulseX/Y`,
`rockBlob`, and `reachableCell`/`biteCell` operating on a `RigidBody` all exist today. The plan is
a description of the present, not of a future.

Two things it specified are **not** built:

- **`RigidBody.fromMachine(at, grid, machine)`** — does not exist anywhere. Grep finds zero hits.
- **`BodyKind.FRAGMENT` is never constructed.** It is a live branch in `massPerTile`,
  `capacityPerTile` and the `init` require, reachable only from tests. A dead arm of the enum.

One thing the code does *better* than the plan asked: bodies carry **per-cell `TileEnergy`**, not
the plan's single `joules`. That was the unit rescale's doing and it should not be regressed.

**Verdict: mark it BUILT, carry `fromMachine` forward, delete nothing.**

### 9.2 `PLAN_rigid_debris.md` — **premise obsolete; three ideas worth keeping**

Its §7, the pivot of the plan, replaces this call:

```kotlin
debris.spill(origin, spoilsOf(machine))
```

**There is no `Debris` system in the codebase.** No `Debris.kt`, no references in `commonMain`, and
`spoilsOf` (`Vessel.kt:687`) is **dead code with zero callers**. Everything the plan says about
debris/fragment interaction, `TILE_CAP`, "contents become debris, casing becomes fragment", and the
`DebrisTest` updates describes a system that no longer exists.

Its §2 (machine as one body, not per-tile pieces) and §3 (the `BodyFragment` type) were correct and
were absorbed into `RigidBody` — §9.1.

Three things survive and should merge into this plan:

1. **The tolerance rule.** 0.1 tile shaved from *exposed* edges only, connected edges kept at the
   full boundary, to stop a sharp corner pinching against a hull tile. `RigidBody.TOLERANCE` exists
   as a constant and **nothing reads it**. This is real, unbuilt, and it is a collision concern —
   which is to say it belongs here rather than in a debris plan. It is also the plan's own
   top-ranked risk, correctly.
2. **`fromMachine`** — the missing factory from §9.1, which is where the tolerance rule is applied.
3. **Its open questions 2 and 3 are now answered, oppositely to the plan.** It said "V1: no
   rotation" and "V1: no fragment-fragment collision". Both are core requirements of this plan.

Its §6 "V1 simplification: a fragment is eaten in one bite" is already obsolete — `biteCell`
(`Extractors.kt:136`) already consumes a `RigidBody` cell by cell, which was the plan's V2.

**Verdict: supersede it. Fold the tolerance rule and `fromMachine` in here; the rest is archaeology.**

### 9.3 `PLAN_grid_vs_continuous.md` — **the central idea is load-bearing; the recommendation is overtaken**

Its §4 is the best paragraph in any of these documents and §5.1 of this plan now rests on it:

> The grid is being conflated with two separate abstractions: **the grid as an addressing scheme**,
> and **the grid as a shape constraint.** These are independent.

Keep that permanently. What is overtaken:

- It recommends **Path C** (grid-rasterised bodies, rotation grafted on) short-term and **Path B**
  (polygon bodies, SAT, tile-rasterised ledgers) medium-term. The per-cell collider design in §3.2
  is neither: it gets Path B's benefits — genuine rotation, real contact geometry, arbitrary
  silhouettes — at close to Path C's cost, because a per-cell convex shape needs **no polygon
  decomposition** and no topology repair. Convex decomposition of concave rubble was the expensive
  part of Path B, and per-cell shapes route around it entirely.
- Its §3.2 says non-axis-aligned shapes are "not possible" and lists rotation as a *visual*
  limitation. That was true of the representation it was describing and is what this plan changes.
- Its **§7.5 execution-order table is stale in four ways**: `PLAN_unified_bodies` is marked "Not
  built" (~90% built), `PLAN_trig_free_rotation` is marked "Not built" (steps 1–3 built), it
  recommends the direction-vector rotation approach that was **rejected**, and it lists a
  `PLAN_grid_vs_continuous` "Path C trajectory" as superseding the angle-centric approach when the
  angle-centric approach is what actually shipped.

**Verdict: keep §4 (quoted here), retire §7.5 outright, note that §5/§6 are overtaken by §3.2 here.**

### 9.4 `PLAN_vessel_rotation.md` — already superseded

By `PLAN_trig_free_rotation.md`, which itself is now built through step 3. No content to recover.

### 9.5 What this plan absorbs

| From | What |
|---|---|
| `PLAN_rigid_debris` | The tolerance rule (0.1 tile, exposed edges only) as a **collision** concern; `fromMachine`; machines dismantle into bodies |
| `PLAN_unified_bodies` | Already built — this plan continues it by giving the unified body its angular half |
| `PLAN_grid_vs_continuous` §4 | Grid as addressing scheme, not frame — now §5.1, the basis of the whole frame change |

Added to §7 as step 4b:

> **Step 4b — `fromMachine` + the tolerance rule.** Dismantling a machine spawns a
> `BodyKind.FRAGMENT` body with exposed edges shaved by `RigidBody.TOLERANCE`, which finally makes
> the enum arm reachable and gives the anti-pinch rule a user. Small, and it is the first thing that
> produces rotating bodies in ordinary play rather than in a test.
