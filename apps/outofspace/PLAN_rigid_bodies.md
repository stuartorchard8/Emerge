# Rigid Bodies — one body structure, colliders, and rotation that is physically real

*Written 2026-08-13. **Supersedes step 4 of `PLAN_trig_free_rotation.md`**, which scoped
"`RigidBody` orientation and collisions imparting torque" without a collider model and without the
unification requirement. Steps 1–3 of that plan (integer trig, vessel `ang`/torque, world-frame
camera) stand and are prerequisites.*

**Nothing in this document is built.**

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

---

## 5. The frame problem — this is what makes vessel rotation physical

Independent of collision, and arguably the thing Stu is actually pointing at. Today:

- A body's **position** is in the vessel's grid frame. `RigidBody.kt:29`
- A body's **impulse** is in the **world** frame, because the vessel's frame accelerates and is
  therefore not inertial. `RigidBody.kt:32`
- `sweepBody` compares them directly: `relative(impulse, shipVelocity)`, `RockContact.kt:161`.

That subtraction is only valid while the two frames share their axes. **The moment the vessel has a
nonzero `ang`, they do not**, and the comparison is silently wrong — not approximately, but by the
full rotation. This is the `ω × r` item left open at the end of step 2, and it is not a refinement:
it is the difference between the vessel's angle being a number in a save file and being a fact about
the world.

Correctly, a body's velocity *across the grid* is:

```
v_grid = R(−vesselAng) · v_world  −  v_shipLinear  −  ω × r
```

where `r` is from the vessel's centre of mass to the body, and `ω × r = (−ω·r_y, ω·r_x)`.

That third term is what a rotating reference frame does, and it is why a rock hanging motionless in
space drifts in an arc across the deck of a spinning ship. It enters at `OutofspaceSim.kt:307`, the
call that hands `driftBodies` the ship velocity.

⚠️ **`ω × r` has the same division-order trap step 2 hit, and it bites harder.** `angVel` is `Coord`
raw per tick and `Rotation.RAW_PER_RADIAN` is 6.8e8. A ship turning once a minute at four ticks a
second is about **1.8e7 raw/tick**, so `angVel / RAW_PER_RADIAN` **truncates to zero** and the whole
term vanishes — silently, reading as an absence rather than an error, which is this codebase's
standing failure mode. It must be `scaledRatio(angVel, RAW_PER_RADIAN, r)` with the radius as the
*scale*, never a division followed by a multiply.

---

## 6. Numerics to settle before writing Kotlin

Per the method that paid off in step 1: prototype in Python emulating Kotlin's exact semantics
(truncating division, arithmetic `shr`, a `Long` overflow assertion on every intermediate) before
transcribing.

| Quantity | Where it can go wrong |
|---|---|
| `ω × r` | §5. Division order. **Silent zero.** |
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
| **1** | **`Contact` + solver skeleton.** Replace `sweepBody`'s inline bounce with: broad phase → contact list → iterative solve → integrate. Keep discs out of it entirely: generate the *existing* axis-aligned hull contacts, but as `Contact` values with a real point and normal. | Proves the new tick shape against behaviour that already works and is already tested. Nothing about shapes changes, so any regression is the solver's. **This is the step that buys stacking.** | M |
| **2** | **Body `ang`, `angImpulse`, gyration.** The exact mirror of what step 2 did to the vessel; `Rotation.kt` already has the machinery. Torque applied at the contact point from step 1, on both operands. | Now a contact has an `r`, so torque is free. First step where a rock visibly spins. | S |
| **3** | **The frame fix (§5).** `R(−vesselAng)` and `ω × r` into the grid/world conversion. | Makes the vessel's `ang` physical. Independent of shapes; could equally be step 0. | S |
| **4** | **`CellShape.Disc` + the pair table.** Disc-vs-Box (hull) and Disc-vs-Disc (rock-on-rock), with friction. Bodies become discs; the hull stays boxes per §4. | The narrow phase, now that everything it feeds is in place. | M |
| **5** | **Body-vs-body broad phase + stacking.** | Needs 1 and 4. | M |
| **6** | **The vessel as an operand.** Vessel passed through the same narrow phase and solver as a body of box cells. | The unification payoff. | L |
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

## 8. Open questions for Stu

1. **Order.** Step 3 (the frame fix) is independent and small, and it is the one that makes the
   existing vessel rotation mean something. Worth doing **first**, ahead of the collision work?
2. **Restitution and friction as dials.** Restitution is currently a hard `1/2` "tuned for
   legibility, not measured" (`RockContact.kt:14`). Friction is new and will want the same
   treatment. Per-material, or one pair of constants to start?
3. **Rock-on-rock scalloping.** §4 accepts it as rubble-on-rubble texture. If two large asteroids
   grinding against each other should slide smoothly instead, that is the first place a `Box` cell
   would earn its keep — but it is a feel call, not a correctness one.
4. **Does the vessel keep its tile grid as its collider grid** (step 6), or does it get a derived
   boundary-cell list? Interior cells can never be contacted, so a boundary list is a large constant
   saving on a 96×60 grid — but it has to be rebuilt whenever the player places a tile.
