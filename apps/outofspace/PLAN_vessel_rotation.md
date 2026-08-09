# Vessel grid rotation

*Plan, 2026-08-09. Nothing built. Companion to `PLAN_unified_bodies.md` and `PLAN_rigid_debris.md`.

---

## 1. The problem

Rocks, fragments, machines, debris, fluids, heat, conduits, bridges, air, momentum — everything in outofspace is defined relative to the vessel's grid. The grid is the frame: `Rock.positionX/Y` lives in grid coordinates, `platingGravity` points along grid axes, the Eulerian fluid solver (`AirField`, `MomentumField`, `ApertureField`) is stamped on the grid, heat conduction uses grid adjacency, debris settling uses `downDirection()` which returns one of four grid-aligned `Direction` values.

If the vessel rotates in the world (thrust off-centre, asymmetric asteroid impact), the grid rotates too. The question is: **do we actually rotate the grid, and what breaks if we do?**

---

## 1.5. The grid's dual role — what makes rotation hard

Right now, the grid is **both**:

1. **The building system** — snap-to-grid placement. The mouse clicks into grid coordinates, the editor snaps machine footprints to tile indices, `StructureMap` records what is where.
2. **The collision system** — `StructureMap.isImpermeable(grid.index(tx, ty))` is how the sim checks if a rock hits the hull.

These are the **same data structure**. `grid.index(tx, ty)` serves the mouse and the collision checker identically.

If you rotate the ship, these split apart. The building system needs snap-to-grid (axis-aligned, integer tiles). The collision system needs to check against a rotated hull.

**Why this matters:** the entire sim is built on the assumption that grid coordinates are a single, consistent frame. The fluid solver, heat conduction, debris settling, machine placement — all expect the grid to be axis-aligned. Rotating the grid means rotating every subsystem that reads it.

This section analyses three options for what happens when the ship has a non-zero angle. Each option answers the question "what rotates?" differently.

---

## 1A. Option A — the grid rotates

Everything rotates. The grid is now at angle θ. Hull tiles are at rotated positions. Mouse clicks and rock collisions must inverse-rotate their coordinates into grid frame, snap to tiles, and look up `StructureMap`.

**Collision check (inverse-rotate + snap):**

```
val dx = rockWorldX - shipCentreX
val dy = rockWorldY - shipCentreY
val localX = dx * cos(θ) + dy * sin(θ)
val localY = -dx * sin(θ) + dy * cos(θ)
val tileX = floor(localX / tileSize)
val tileY = floor(localY / tileSize)
val hits = structureMap.get(tileX, tileY) != null
```

**Mouse:** same inverse-rotation, then snap to tile.

**Works for arbitrary angles.** But breaks the grid's purpose. The grid was chosen because tile-based checks are free — integer indexing, no trig, no branches. Now every rock every tick needs two trig calls + two floor ops. For 50 rocks that's 100 trig calls per tick. Acceptable for desktop, expensive for JS/mobile.

**What breaks:**

| Subsystem | Impact |
|---|---|
| Fluid solver | **Breaks.** Staggered grid (x-faces at `w+1 × h`, y-faces at `w × h+1`) assumes axis-aligned. Rotated grid needs a completely different index topology. |
| Heat conduction | **Breaks.** Topology is four-cardinal adjacency on an axis-aligned lattice. A rotated grid has diagonal adjacency, changing the contact graph. |
| Debris settling | **Breaks.** `downDirection()` returns one of four grid-aligned directions. Diagonal tiles would need new logic. |
| Machine placement | **Breaks.** Machines snap to grid tiles. Rotated grid → machines at arbitrary angles → machine footprints no longer line up with fluid/heat cells. |
| Rocks | **Requires transforms.** Every rock position must be inverse-rotated into grid frame each tick. O(n) trig per tick. |
| Mouse | **Requires transforms.** Same inverse-rotation + snap-to-grid. |
| Render | **Requires transforms.** Ship and entities drawn at rotated positions. |

**Verdict:** Option A fundamentally reshapes how the sim works. The grid is the sim. Rotating it means rethinking every subsystem that reads it. High cost, no clear benefit over Option C for the first pass.

---

## 1B. Option B — grid stays, hull becomes a separate polygon

The grid stays for building/placement only. The hull is a separate rotated shape — a set of AABBs rotated by θ. Collision and mouse hits use the rotated hull.

**Implementation:**

```
val hullRotated = hullAABBs.map { it.rotate(θ, shipCentre) }
rockHitsHull = hullRotated.any { it.contains(rock) }
```

The rotated hulls are precomputed when θ changes (not per-tick). Cost is O(m) where m = number of hull AABBs (typically 10–50), not O(n) where n = number of rocks.

**Tradeoffs:**

| Subsystem | Impact |
|---|---|
| Fluid solver | **Unchanged.** Grid stays axis-aligned. |
| Heat conduction | **Unchanged.** Grid topology is unchanged. |
| Debris settling | **Unchanged.** Grid alignment preserved. |
| Machine placement | **Unchanged.** Grid is axis-aligned. |
| Collision | **Works.** SAT on precomputed rotated AABBs. Fast. |
| Mouse | **Works.** Same rotated hull. |
| Rocks | **Unchanged.** Still live in grid frame. |

**Verdict:** Option B is the upgrade path. 90% of the feel (ship rotates, rocks bounce off rotated hull) with 20% of the work (no subsystem changes, just a new collision shape). Precomputing rotated hulls means no per-tick trig.

---

## 1C. Option C — grid stays, gravity is the ONLY effect (current plan)

The grid stays. The hull stays. Ship angle only changes:
- `rotate(platingGravity, θ)` — gravity direction in grid frame rotates
- Renderer — ship appears to tip over

**What changes:**
- `VesselState.angle` is no longer zero. Used to derive felt gravity.
- Renderer applies rotation transform.
- All physics/subsystems unchanged — they still see axis-aligned grid.

**What does NOT change:**
- Collision — still uses `StructureMap.isImpermeable()` on axis-aligned grid. A rotated ship's hull is still the same grid tiles.
- Mouse — still snaps to grid tiles.
- Fluids, heat, debris, machines — all unchanged.

**The tradeoff:** a rotated ship's hull still has the same grid footprint. If the ship rotates 45°, the visual hull is a diamond, but the collision hull is still the original rectangle. Rocks hit the rectangle, not the diamond. This is visually inconsistent but physically defensible — the sim frame doesn't rotate, only the visual frame does.

**Verdict:** Option C is what the rest of the sim expects. Zero subsystem changes. The cleanest starting point.

---

## 2. Upgrade path: C → B

Start with C. Validate the angular momentum system and the visual rotation feel. If collision against the rotated hull is needed (for game feel — a ship that visually tips should have a hull that visually intercepts), add Option B's separate hull polygon. The grid stays, the hull polygon does the collision. No subsystem changes.

This is a low-risk upgrade: the hull polygon is computed once per angle change, not per tick. It does not touch fluids, heat, or any other subsystem.

---

## 3. Current coordinate frames (re-stated for clarity)

| Quantity | Frame | Units |
|---|---|---|
| `VesselState.positionX/Y` | World (inertial) | billionths of tile |
| `VesselState.grid` | Vessel frame origin at (0, 0) | tile indices |
| `Rock.positionX/Y` | Vessel-grid frame | billionths of tile |
| `Rock.impulseX/Y` | World (inertial) | gram·billionths |
| `VesselState.velocityX/Y` | World (computed) | billionths/tick |
| `VesselState.gravity` | Vessel frame (setting) | Frac2 (tiles/tick²) |
| Renderer `camX/Y` | Grid tile coords | tiles |

**Key constraint:** `+y` is down in grid coordinates (screen-down = gravity-down). This is documented in `Direction.kt:6` and baked into every subsystem.

**Current state:** `VesselState.angle = 0` always. `VesselState.gravity` is `(0, 1)` or `(0, 0)` for normal flight and freefall. All gravity vectors are axis-aligned.

---

## 4. What "vessel grid rotation" means

Rotating the vessel means rotating its reference frame in the world. If the ship is at world angle `θ`, a world vector `v` has grid-frame components:

```
v_grid.x = v_world.x · cos(θ) + v_world.y · sin(θ)
v_grid.y = -v_world.x · sin(θ) + v_world.y · cos(θ)
```

The inverse transforms `v_grid → v_world`.

**Two interpretations:**

### 4A. Grid rotation (the grid itself rotates)

The grid's origin and axes rotate with the vessel. Every subsystem that reads the grid must handle a rotated frame. This is what "real" rotation means physically.

### 4B. Visual-only rotation (the grid stays fixed, the renderer rotates)

The grid stays axis-aligned. The renderer applies a rotation transform to everything drawn. Physics, fluids, heat — all unchanged. Only the visual layer knows about rotation.

These produce different results when the rotation is **caused by physics** (thrust offset, collision). In 4B, the ship visually rotates but gravity doesn't change direction — the player feels a disconnect. In 4A, gravity in the grid frame rotates, so rocks "fall" sideways, fluids slosh, etc. — but the renderer can either draw rotated or draw axis-aligned and accept the visual mismatch.

---

## 5. Impact analysis for grid rotation (4A / Option A)

### 5.1 Rocks and debris

**Position transformation:** Rocks store `positionX/Y` in grid frame. On each tick, to compute world-frame collision or render, the grid position must be rotated to world frame. This requires:

- `cos(θ)` and `sin(θ)` computed once per tick (not per body)
- A multiply-add per body per axis: `x_world = x_grid · cos(θ) - y_grid · sin(θ)`
- Conversion from billionths back to tile coords for sweep/collision

**Cost:** O(n) trig calls per tick (n = number of bodies), currently O(1). Kotlin/JS `Math.cos`/`Math.sin` are available but expensive. A lookup table or fixed-point approximation would be needed for performance.

**Sweep collision:** `sweepRock()` operates in grid frame. If the grid is rotating, the hull tiles move in grid frame too — but they don't, because the grid *is* the hull's frame. The hull is always axis-aligned in grid coordinates. So sweep collision is **unchanged**.

**The real question:** rocks are external objects. When the ship rotates, does the rock's grid position change? Yes — if the ship rotates 90° clockwise, a rock at grid `(10, 20)` would have a different world position relative to the ship. But the rock was never attached to the ship. Its grid position stays the same; its world position changes because the ship's world orientation changed. The rock doesn't "move" in grid coordinates.

**Verdict:** Rocks don't need coordinate transforms. The grid frame is the ship frame. A rock at grid `(10, 20)` is still at `(10, 20)` regardless of ship rotation. Its world-frame position relative to the ship changed, but that's because the ship rotated — the rock was always external.

**BUT:** if the ship rotates due to physics (angular momentum from offset thrust), the ship's angular orientation changes, which changes how gravity manifests in the grid frame. That's the real physical effect.

### 5.2 Gravity

**The physical effect:** If the ship's engines thrust at an angle, or if the ship has angular orientation, the "floor" the crew stands on is no longer the +y edge of the hull. It's the edge perpendicular to the ship's up vector.

**In grid coordinates:** Gravity is a vector that can point in any direction. Currently `gravity = (0, 1)` (down) or `(0, 0)` (freefall). With rotation, `gravity` could be `(0.707, 0.707)` (diagonal), `(-1, 0)` (left), etc.

**Subsystems that consume gravity:**

| Subsystem | Current assumption | With rotated gravity |
|---|---|---|
| Plating gravity for rocks (`Rock.kt`) | Gravity is axis-aligned | **No change** — `platingFeltBy` passes `gravity` through |
| Fluid drift (`Drift.kt`) | Settling uses `gravity.x`/`gravity.y` to choose face | **Works** — `Drift.kt` already uses `alongX = gravity.x.raw.abs > gravity.y.raw.abs` |
| Buoyancy (`Buoyancy.kt`) | Gravity on excess mass, any direction | **Works** — `Buoyancy.kt:11` says "handles any gravity vector" |
| Debris settling (`Debris.kt`) | `downDirection()` returns one of 4 `Direction` values | **Breaks** — diagonal gravity returns `null` or nearest axis |
| Resting speed (`RockContact.kt`) | `restingSpeed()` uses gravity magnitude | **Works** — uses absolute values |
| Experienced gravity (`Flight.kt`) | `deckGravity - shipAcceleration` | **Works** — subtraction is frame-independent |

**The debris problem:** `Debris.kt:141` — `downDirection(gravity)` quantizes to N/S/E/W. With diagonal gravity, a debris pile would freeze or jitter. This is already documented at `Debris.kt:124-136` — "a gravity that leans 99 parts down and 1 part right falls down; nothing else is a defensible rule." The quantization is a feature, not a bug, for tile-aligned piles.

**Fix:** Extend `downDirection` to also return diagonal directions (NE, NW, SE, SW). Or keep 4-direction and accept that diagonal gravity quantizes to the nearest axis (which `downDirection` already does at `Debris.kt:141`).

**Verdict:** Gravity rotation is the **simplest** thing to add. It's a vector that already accepts any direction. The main change is in `Edit.Thrust` and the angular dynamics that set `VesselState.gravity`.

### 5.3 Fluid simulation (Eulerian)

**The good news:** The fluid solver already handles any gravity direction.

- `Drift.kt:19` — species drift uses `gravity.x.raw` and `gravity.y.raw` separately, choosing which face to move along based on magnitude. This works for diagonal gravity.
- `Buoyancy.kt:11-12` — "Boussinesq buoyancy: gravity on gas excess vs. ambient at same pressure. **Handles any gravity vector** (not just axis-aligned)."
- `StepFluid.kt` — the full step (drift → pressure → buoyancy → advection) doesn't assume axis-aligned gravity.

**The caveat:** `EdgeGrid.kt:9` says "+y = down (screen-down = gravity-down)." This is a convention, not a constraint. The solver would work with any gravity direction.

**Verdict:** Fluid simulation **works without changes** for rotated gravity. The grid is the simulation domain; gravity is a parameter. Rotate gravity, run the same solver.

### 5.4 Heat conduction

**Current:** `SolidHeat.kt` builds a contact graph from grid adjacency. Adjacency is `grid.neighbour()` — four directions (N/S/E/W). Heat diffuses along these edges.

**With rotation:** Grid adjacency is still four cardinal directions. The contact graph doesn't change. Heat conduction is purely topological — it doesn't know about gravity or orientation.

**Verdict:** No changes needed.

### 5.5 Machine placement and orientation

**Current:** Machines are placed on grid tiles with square footprints. `MachineKind.size` gives the footprint. `Direction` gives facing (for port placement and extraction).

**With rotation:** If the ship rotates 90°, machines are now visually rotated but still on grid tiles. The grid footprint doesn't change. But if the player expects machines to "fall" in the new gravity direction, the machine's position in grid coordinates might need to shift.

**Actually:** Machines are **part of the grid structure** — they're not free-moving bodies. They don't drift, they don't fall. Their position is locked to grid tiles. So machine placement is **unchanged** by rotation.

**Verdict:** No changes needed for machine placement.

### 5.6 Renderer

**This is where rotation is felt most acutely.**

**Current:** The renderer draws grid tiles and entities as axis-aligned rectangles. `OutofspaceRenderer.kt:683-686`: "Because the only primitive here is an axis-aligned rectangle — **there is no rotation in the instanced rect shader**"

**With rotation:**

- **Grid tiles:** Drawn as axis-aligned rectangles. No change needed visually if rotation is just a gravity direction change.
- **Rocks:** Drawn as axis-aligned rectangles. If the ship rotates, the rock's appearance relative to the hull should change — but since the rock is at a fixed grid position, it just sits there. No visual rotation needed.
- **Machines:** If the ship is "upside down," machines are drawn upside down. Currently, machine rendering uses `Direction` (facing) to determine draw orientation, not a world angle. The facing would need to track the ship's rotation.

**The renderer's camera:** `FrameShift` tracks grid growth and keeps the camera centered on the built area. With rotation, the camera would need to rotate with the ship — or the ship would rotate visually within a fixed camera frame.

**Verdict:** The renderer needs the most changes for **visible rotation** (ship appears to tip over). If rotation is purely a gravity direction change (ship stays upright, gravity points sideways), the renderer barely changes.

### 5.7 Angular momentum for the vessel itself

**Current:** `VesselState` has `rockImpulseX/Y` for rock momentum exchange, but no `angularMomentum` or `angularVelocity` for the vessel itself.

**With rotation:** The vessel needs:
- `angularVelocity` (rad/tick or degrees/tick) — scalar, since we're in 2D
- `angularMomentum` — scalar, in gram·radians (or similar)
- Torque from offset thrust: `τ = r × F` where `r` is the distance from centre of mass to thrust point
- Torque from rock collisions: `τ = r × J` where `r` is the distance from CoM to impact point

**Moment of inertia:** The ship's moment of inertia depends on mass distribution. With machines at various grid positions, each machine contributes `m · r²` to the total `I`. This changes as machines are placed/removed.

**Verdict:** This is a **new system** that's independent of whether the grid rotates or just gravity rotates. It's needed for physically interesting rotation behavior.

---

## 6. Impact analysis for visual-only rotation (4B / Option C)

### 6.1 Physics

**Grid:** Stays axis-aligned. All subsystems unchanged.

**Gravity:** Stays `(0, 1)` or `(0, 0)`. The ship's angular orientation doesn't affect gravity direction in the grid frame.

**Verdict:** Zero physics changes.

### 6.2 Renderer

**The ship visually rotates.** The renderer applies a rotation transform to the camera or to ship-drawn entities. Machines, rocks, rocks — everything rotates around the ship's centre of mass.

**Implementation:** The renderer already supports rotation via `Mat4.rotationZ()`. A single `camRot` uniform applied to the camera transform would rotate the entire view.

**Cost:** One additional camera parameter (`camRot`) and one matrix multiplication per frame. Negligible.

**Verdict:** Very cheap visually, but physically misleading — the ship appears to tip over but gravity doesn't change.

---

## 7. Hybrid approach (recommended)

**Rotation angle on `VesselState`:** `val angle: Frac = Frac(0)` — the ship's orientation in the world, stored as a fixed-point angle.

**Gravity is derived, not set:** `VesselState.gravity` is replaced by `VesselState.thrustDirection: Frac2` (a vector the engines produce). The "felt gravity" is computed as:

```
feltGravity = thrustDirection · sin(angle) + platingGravity · cos(angle)
```

Where `platingGravity` is the ship's own gravity (usually `(0, 1)` or `(0, 0)` for freefall).

**Simpler: gravity rotates with the ship:**
```
feltGravity = rotate(platingGravity, angle) - shipAccelerationRotated
```

Where `rotate(v, angle)` applies the 2D rotation matrix.

**Rocks stay at grid positions.** They don't transform. The grid frame is the ship frame. A rock at grid `(10, 20)` is always at `(10, 20)` regardless of ship angle.

**Renderer rotates everything by `-angle`** (counter-rotates the view so the ship appears upright) OR rotates by `+angle` (lets the ship appear to tip over, gravity appears to change direction).

**Angular dynamics:** `VesselState` gains `angVel: Frac` and `angAccel: Frac`. Thrust offset produces torque: `τ = offset.x * thrust.y - offset.y * thrust.x`. `angVel += τ / I * dt`. `angle += angVel * dt`. `I` (moment of inertia) is computed from machine positions.

---

## 8. What doesn't break vs. what does

| Subsystem | Grid rotation (4A) | Visual rotation (4B) |
|---|---|---|
| Rock sweep collision | **No change** (grid frame) | **No change** |
| Fluid drift | **No change** (handles any gravity) | **No change** |
| Buoyancy | **No change** (handles any gravity) | **No change** |
| Heat conduction | **No change** (topological) | **No change** |
| Debris settling | **Minor** (extend `downDirection`) | **No change** |
| Machine placement | **No change** (grid-locked) | **No change** |
| Rock rendering | **No change** (grid position) | **Minor** (rotate view or rocks) |
| Vessel rendering | **Major** (ship appears rotated) | **Major** (ship appears rotated) |
| Gravity direction | **New vector** (any direction) | **Unchanged** (always down) |
| Angular momentum | **New system** | **New system** |

**Key insight:** For outofspace, the grid-based subsystems are **incredibly resilient** to rotation. The fluid solver already handles any gravity direction. Heat conduction is topological. Machine placement is grid-locked. Rocks don't transform — they live in the grid frame.

**The main work is:**
1. Angular momentum system for the vessel (`angVel`, `angAccel`, `I` computation, torque from thrust offset)
2. Gravity vector rotation (deriving `gravity` from vessel angle)
3. Renderer rotation (making the ship appear to tip over, or counter-rotating the view)

---

## 9. Comparison with Scavengers

Scavengers handles rotation with:
- `TransformComponent { pos: Coord2, ang: Coord }` — angle is a `Coord` (torus coordinate)
- `MotionComponent { vel: Coord2, angVel: Coord }` — angular velocity is a `Coord`
- `ImpulseComponent { angVel: Frac }` — angular impulses

Scavengers uses **circles** (`ColliderComponent.radius`). A circle rotated is still a circle — no shape transformation needed.

Outofspace uses **BooleanArray shapes** (`Rock.cells`). A rotated rectangle is a rotated rectangle — the collision shape changes with angle. This is the key difference.

**For outofspace, keeping bodies grid-aligned while the ship rotates is the right call.** Bodies don't need to rotate because they're external to the ship's frame. Only the ship's visual orientation and gravity direction change.

---

## 10. Implementation order (if pursued)

| Phase | What | Days | Dependencies |
|---|---|---|---|
| **0. Angular dynamics** | `angVel`, `angAccel`, `I` computation, torque from offset thrust | 2.0 | — |
| **1. Gravity rotation** | `gravity` derived from vessel angle + plating setting | 1.0 | 0 |
| **2. Renderer rotation** | Ship and entities render at `angle`; counter-rotate view if desired | 1.5 | 0 |
| **3. Debris quantization** | Extend `downDirection` to handle diagonal gravity | 0.5 | 1 |
| **4. Rock collision torque** | `τ = r × J` when rocks hit the hull off-centre | 1.5 | 1 |
| **5. Visual polish** | Rocks, debris, machines visually rotated; particle effects | 2.0 | 2 |

**Total: ~8 days**

---

## 11. Risks

1. **Performance:** `cos(θ)` and `sin(θ)` per tick are cheap, but `Math.cos`/`Math.sin` in Kotlin/JS may be slower than expected. Use a fixed-point or Taylor-series approximation if needed.

2. **Game feel:** Diagonal gravity is disorienting. Players expect gravity to point "down" on screen. The gravity quantization in `downDirection` exists to prevent confusion. Consider clamping gravity to axis-aligned when the angle is near 0°, 90°, 180°, or 270°.

3. **Fluid edge cases:** The Eulerian solver handles any gravity direction, but edge cases (gravity exactly diagonal, very low gravity with lateral thrust) need testing. The `scaleByGravity` function has known issues at low gravity (< 0.3g).

4. **Save format:** Adding `angle`, `angVel`, and changing `gravity` from a setting to a derived value requires save migration.

5. **Grid rotation vs. visual rotation mismatch:** If the grid doesn't rotate but the renderer does, there's a disconnect. The renderer would need to know about the ship's angle to rotate the view. If the player expects the ship to tip over, they'll see it visually — but rocks won't "fall" in the new direction unless gravity is also rotated.

---

## 12. Recommendation

**Defer vessel rotation until after rigid body unification.** Here's why:

1. **Rotation is a gameplay feature, not a physics necessity.** The current axis-aligned gravity works fine. Players can fly around with offset thrusters (already supported) without needing the ship to visually rotate.

2. **The angular momentum system is a large new subsystem** that's orthogonal to rigid body unification. It requires:
   - Moment of inertia computation (changes as machines move)
   - Torque from thrust offset
   - Torque from rock collisions
   - Angular integration (already in Scavengers engine, but needs outofspace-specific implementation)

3. **The renderer work is significant.** Making the ship visually rotate while keeping the grid fixed requires:
   - A camera rotation transform (already in Scavengers' `Mat4.rotationZ()`)
   - Counter-rotated rendering for grid entities (machines, tiles)
   - Visual rotation for free entities (rocks, debris)
   - UI rotation (HUD elements, panels)

4. **The most impactful use of rotation (rock collision torque)** depends on rocks being unified as `RigidBody` — then you can have rocks exert torque on the ship when they collide off-centre.

5. **Visual-only rotation (4B) is cheap and effective** if you just want the ship to tip over. But it's a visual gimmick without the physics backing (rotating gravity), which makes it feel fake.

**If you do it now:** Start with visual-only rotation (4B). It's cheap, it's a visual polish feature, and it doesn't touch physics. The angular momentum system (phase 0 above) is the real work and should wait until after `RigidBody` unification.

---

## 13. What we would know it worked

- The ship visually rotates when thrusters are applied off-centre (or manually set `angle`).
- Gravity in the grid frame rotates with the ship: rocks "fall" sideways when the ship is rotated 90°.
- Fluid simulation runs correctly with diagonal gravity — no artifacts, no crashes.
- Debris piles settle in the new gravity direction.
- Heat conduction is unchanged (topological, independent of gravity).
- Rocks don't need coordinate transforms — they stay at their grid positions.
- `angVel` is conserved in freefall (no damping in vacuum).
- A ship with no machines has `I = M · R²` (simple rotation about centre).
- A ship with machines has correct `I` computed from mass distribution.
- Save/load preserves `angle` and `angVel`.
- Determinism: same thrust input → same `angle` every run.
