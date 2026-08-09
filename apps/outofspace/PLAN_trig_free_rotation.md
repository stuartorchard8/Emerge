# Trigonometry-Free Rotation Plan

*Draft, 2026-08-09. Nothing built. Replaces the angle-centric approach in PLAN_vessel_rotation.md
with a direction-vector approach that avoids sin()/cos()/atan2() in hot paths.*

---

## 1. The core idea

Store rotation as a **direction vector** instead of an angle:

```
// Instead of:
val angle: Frac  // radians, requires cos(angle), sin(angle) every time you need a direction

// Store:
val forward: Norm  // already a (cos, sin) pair — zero trig needed
```

The `Norm` type already exists in the codebase (`engine/sim/core/physics/primitives/Norm.kt`)
and is exactly this: a normalized (x, y) direction vector.

**Why this matters for outofspace:**
- `Norm.fromAngle()` and `Norm.asAngle` currently use `cos`/`sin`/`atan2` (marked TODO)
- `Frac2.rotateByAngle()` calls `Norm.fromAngle()` every time
- These are the trig hot-spots for vessel rotation
- The Reddit approach eliminates them from the per-tick path

---

## 2. Direction vector rotation mechanics

### 2.1 Storing rotation state

```kotlin
// Forward direction unit vector (cos, sin pair)
// Initialized to (1, 0) — ship faces along +x (right)
val forward: Norm = Norm(Frac(1), Frac(0))
```

This is `Frac2` normalized — the `cosθ, sinθ` pair without ever computing an angle.

### 2.2 Turning (rotating the direction vector)

Use the 2D rotation matrix multiplication directly, without `sin/cos`:

```kotlin
// To rotate 'forward' clockwise by rotationSpeed per tick:
val nextX = forward.x - rotationSpeed * forward.y
val nextY = forward.y + rotationSpeed * forward.x

// Re-normalize to prevent floating-point drift
val nextLen = Frac2(nextX, nextY).len
val newForward = Frac2(nextX, nextY) / nextLen  // = Norm via /
```

This is the **complex number rotation** — identical to the rotation matrix but expressed
as vector arithmetic. No trig.

For **Frac2 rotation** (used in `Frac2.rotateByAngle()`), this is the replacement:

```kotlin
// OLD (uses Norm.fromAngle → cos/sin):
fun Frac2.rotateByAngle(angle: Coord): Frac2 {
    val rotation = Norm.fromAngle(angle)
    return Frac2(x * rotation.x - y * rotation.y,
                 x * rotation.y + y * rotation.x)
}

// NEW (pure vector, no trig):
fun Frac2.rotateByForward(forward: Norm, up: Norm): Frac2 {
    // up = perpendicular to forward (counter-clockwise 90°): (-forward.y, forward.x)
    // rotation: x' = x·cos - y·sin = x·forward.x - y·forward.y
    //           y' = x·sin + y·cos = x·forward.y + y·forward.x
    return Frac2(
        x * forward.x - y * forward.y,
        x * forward.y + y * forward.x
    )
}
```

Wait — this is just a dot-product-style computation. The rotation matrix IS:
```
[ cos  -sin ]   [ forward.x  -forward.y ]
[ sin   cos ] = [ forward.y   forward.x ]
```

So `Frac2.rotateBy(forward)` is already what we'd get from `Norm.fromAngle()`, just without the
`atan2 → cos/sin` conversion. **We're removing the angle as an intermediate step entirely.**

### 2.3 Renormalization

The rotation step preserves length mathematically, but floating-point arithmetic accumulates
error. Renormalize periodically:

```kotlin
// Every tick (cheap — Frac2.len uses longISqrt, already optimized)
val currentForward = Frac2(nextX, nextY)
val newForward = if (currentForward.lenSq > Frac(0)) {
    Norm(currentForward.x / currentForward.len, currentForward.y / currentForward.len)
} else {
    Norm(Frac(1), Frac(0))  // degenerate case
}
```

---

## 3. Impact on outofspace components

### 3.1 VesselState — the main change

**Add:**
```kotlin
val angle: Frac = Frac(0)              // keep for UI/debug (derived, not authoritative)
val angVel: Frac = Frac(0)             // angular velocity (radians/tick → direction-vector delta)
```

Actually — if we use direction vectors, we don't store `angVel` as a scalar. We update the
`forward` vector directly. But for angular momentum conservation, we still need an angular
state. The question is: **what do we store?**

Two options:

**Option A: Store angle + derive direction**
```kotlin
val angle: Frac      // the "real" state
val angVel: Frac     // angular velocity
val forward: Norm    // derived: Norm.fromAngle(angle) — but this uses trig!
```

This doesn't help — we still convert angle → direction with trig.

**Option B: Store direction vector + derive angle**
```kotlin
val forward: Norm    // the "real" state (the direction vector)
val angVel: Frac     // scalar — rate of change of heading
```

Update rule:
```kotlin
// On each tick:
// 1. Compute angular acceleration from torque
angAccel = torque / momentOfInertia
angVel += angAccel

// 2. Rotate the forward vector using rotation matrix
val headingAngle = forward.asAngle  // only for computing rotation delta?
// No — we need the rotation speed as an angle to use in the matrix...
```

Hmm, this is the trickier part. The rotation matrix needs `cos(ω·dt)` and `sin(ω·dt)` where
`ω` is angular velocity. If `ω` is small (per-tick), we can approximate:

```
cos(ω) ≈ 1
sin(ω) ≈ ω
```

So for small angular steps:
```kotlin
val ω = angVel.toFrac()  // scalar angular velocity as Frac
val newForwardX = forward.x - ω * forward.y
val newForwardY = forward.y + ω * forward.x
val newForwardLen = Frac2(newForwardX, newForwardY).len
forward = Norm(newForwardX / newForwardLen, newForwardY / newForwardLen)
```

This is the **small-angle approximation** — it's exactly what the Reddit post shows as the
"matrix-free rotation." The renormalization step absorbs the small errors.

**Verdict: Option B (store direction vector, angVel as scalar for torque integration).**

### 3.2 Felt gravity (rotation's main effect)

**Current** (from VesselState):
```kotlin
val feltGravity: Frac2 get() = experiencedGravity(gravity, netImpulseX, netImpulseY, massGrams)

fun experiencedGravity(deckGravity: Frac2, netImpulseX: Long, netImpulseY: Long, massGrams: Long): Frac2 {
    val a = frameAcceleration(netImpulseX, netImpulseY, massGrams)
    return Frac2(Frac(deckGravity.x.raw - a.x.raw), Frac(deckGravity.y.raw - a.y.raw))
}
```

**With rotation**, gravity is derived from the ship's orientation:
```kotlin
// The "down" direction in world frame is the ship's backward/upward direction.
// If forward = (cos θ, sin θ), then down = (-sin θ, cos θ) = (-forward.y, forward.x)

val shipDown: Norm get() = Norm(-forward.y, forward.x)

val feltGravity: Frac2 get() {
    val deckGravity = platingGravity  // user setting: (0, 1) for 1g down, (0, 0) for freefall
    val shipAccel = frameAcceleration(netImpulseX, netImpulseY, massGrams)
    
    // Rotate deck gravity by ship's orientation
    val rotatedDeck = deckGravity.rotateBy(forward)  // see new Frac2.rotateBy(Norm) below
    
    return Frac2(
        Frac(rotatedDeck.x.raw - shipAccel.x.raw),
        Frac(rotatedDeck.y.raw - shipAccel.y.raw)
    )
}
```

Wait, but `deckGravity` is `(0, 1)` for 1g (straight down on screen). If the ship rotates 90°,
the deck gravity should still be `(0, 1)` — it's a setting. What rotates is how the crew feels it.

Actually, looking at the PLAN_vessel_rotation.md more carefully:

> feltGravity = rotate(platingGravity, angle) - shipAccelerationRotated

The idea is: when the ship rotates, the gravity the crew feels rotates with it. If the ship
orients so its +x edge is "down," then gravity in grid coordinates becomes `(1, 0)`.

So `platingGravity` is the **setting** and `feltGravity` is **derived from orientation**:

```kotlin
// Plating gravity as a setting (what the ship WOULD feel with no acceleration)
val platingGravity: Frac2 = Frac2(Frac(0), Frac(1))  // or FREEFALL

// With ship orientation:
// If forward = (1, 0) (facing right), and platingGravity = (0, 1) (screen-down),
// then felt = (0, 1) — normal, ship upright.
// If forward = (0, 1) (facing down), platingGravity = (0, 1),
// then felt = (1, 0) — gravity pulls along +x (ship is now "sideways").

// The felt direction is: rotate platingGravity by the ship's orientation.
// This is the same as dot product with the rotation basis:
//   felt.x = platingGravity · forward      (projection onto forward axis)
//   felt.y = platingGravity · up           (projection onto perpendicular axis)
// where up = (-forward.y, forward.x)
val feltGravity: Frac2 get() {
    val up = Norm(-forward.y, forward.x)
    val projected = Frac2(
        platingGravity.x * forward.x + platingGravity.y * forward.y,
        platingGravity.x * up.x + platingGravity.y * up.y
    )
    return Frac2(
        Frac(projected.x.raw - frameAcceleration.x.raw),
        Frac(projected.y.raw - frameAcceleration.y.raw)
    )
}
```

Actually, let me think about this more carefully. The `feltGravity` is a **Frac2 vector** —
it has x and y components in grid coordinates. The ship's orientation determines which grid
direction is "down" relative to the ship.

Simpler approach:

```kotlin
// Ship's "down" in grid coordinates: perpendicular to forward, pointing outward from hull.
// If ship faces right (forward = (1,0)), down = (0,1) — screen-down.
// If ship faces down (forward = (0,1)), down = (1,0) — right is down.
val gridDown: Frac2
    get() = platingGravity.rotateBy(forward)

// rotateBy for Frac2: apply the rotation matrix defined by forward
fun Frac2.rotateBy(forward: Norm): Frac2 {
    val upX = -forward.y
    val upY = forward.x
    return Frac2(
        this.x * forward.x + this.y * upX,
        this.x * forward.y + this.y * upY
    )
}
```

Hmm, this is getting complicated. Let me reconsider. The key insight is that `Frac2` already
has `rotateByAngle`. What we want is `rotateBy(Norm)` — the same thing but using a direction
vector instead of an angle:

```kotlin
// Frac2.kt addition:
fun Frac2.rotateBy(forward: Norm): Frac2 {
    // Rotation matrix: [cos  -sin] = [forward.x  -forward.y]
    //                   [sin   cos ]   [forward.y   forward.x]
    return Frac2(
        x * forward.x - y * forward.y,
        x * forward.y + y * forward.x
    )
}
```

This is **identical** to the current `rotateByAngle` but avoids the `Norm.fromAngle(angle)` call
which does `atan2 → cos/sin`. Instead it uses `forward` which is already `(cos, sin)`.

### 3.3 Torque and angular dynamics

```kotlin
// Torque from offset thrust:
// τ = r × F (cross product of lever arm and thrust force)
fun torqueFromThrust(offsetX: Long, offsetY: Long, thrustX: Long, thrustY: Long): Long {
    // 2D cross product: τ = r.x * F.y - r.y * F.x
    return offsetX * thrustY - offsetY * thrustX
}

// Angular acceleration:
val angAccel: Frac = Frac(torque / momentOfInertia)

// Update angular velocity and forward vector:
angVel += angAccel * dt
val ω = angVel * dt  // small angle per tick

// Rotate forward vector (small-angle approximation):
val newForwardX = forward.x - ω * forward.y
val newForwardY = forward.y + ω * forward.x
val newForwardLen = Frac2(newForwardX, newForwardY).len
forward = if (newForwardLen.raw > 0) {
    Norm(newForwardX / newForwardLen, newForwardY / newForwardLen)
} else {
    Norm(Frac(1), Frac(0))
}
```

### 3.4 Moment of inertia

The ship's moment of inertia `I` depends on mass distribution:

```kotlin
// I = Σ m_i · r_i²
// where r_i is the distance of mass element i from the ship's center of mass
val momentOfInertia: Long
    get() {
        // For a grid of machines, compute Σ m · r² from the center of mass
        val (cx, cy) = centerOfMass
        return machines.sumOf { machine ->
            val m = machine?.mass ?: 0L
            if (m == 0L) 0L else {
                val (mx, my) = machineCenter
                m * ((mx - cx) * (mx - cx) + (my - cy) * (my - cy))
            }
        }
    }
```

This is the same regardless of angle representation.

### 3.5 Rock collision torque

When a rock collides with the hull off-center:

```kotlin
// Rock impact torque: τ = r × J
// r = vector from ship center of mass to impact point
// J = impulse delivered by rock
fun rockTorque(rock: Rock, impactX: Long, impactY: Long): Long {
    val (cx, cy) = centerOfMass
    val dx = impactX - cx
    val dy = impactY - cy
    return dx * rock.impulseY - dy * rock.impulseX
}
```

Same as before — no change. The torque is a scalar in 2D.

### 3.6 Renderer — drawing rotated entities

The renderer needs to draw the ship at its current orientation. Currently it draws axis-aligned
rectangles only. With direction-vector rotation:

```kotlin
// For each entity that needs rotation:
// Compute its world-space vertices from local-space using the direction vector

// A machine at grid tile (tx, ty) with size (w, h):
// Its local corners (relative to tile origin): (0,0), (w,0), (w,h), (0,h)
// Its world-space corners (rotated by forward):
for (corner in corners) {
    val wx = corner.x * forward.x - corner.y * forward.y
    val wy = corner.x * forward.y + corner.y * forward.x
    drawRect(worldX + wx, worldY + wy, ...)
}
```

For a full screen rotation (the ship stays centered, everything else rotates around it):

```kotlin
// Apply camera rotation — same as PLAN_vessel_rotation.md §6.2:
// mat4 = translate(-center) × rotateZ(angle) × translate(center)
// But: we don't have the angle. We have the forward vector.
// Angle = atan2(forward.y, forward.x) — ONE call per frame, not per entity.
val screenAngle = forward.asAngle  // ONE trig call per frame, acceptable
```

This is the key win: **one `atan2` per frame instead of per-body-per-tick**.

### 3.7 Debris settling with rotated gravity

From PLAN_vessel_rotation.md §5.1:

> `downDirection(gravity)` quantizes to N/S/E/W. With diagonal gravity, a debris pile would
> freeze or jitter. This is already documented — "a gravity that leans 99 parts down and 1 part
> right falls down; nothing else is a defensible rule."

**With direction-vector rotation**, gravity can point in any direction. The `downDirection`
function needs to handle this. Two approaches:

1. **Keep 4-direction quantization** (simplest, already works):
   ```kotlin
   // downDirection already handles this by comparing |x| vs |y|
   ```

2. **Extend to 8 directions** (NE, NW, SE, SW):
   ```kotlin
   // When gravity leans equally on both axes:
   if (abs(x) > threshold && abs(y) > threshold) {
       // Use sign of x and sign of y to determine diagonal direction
       return when {
           x > 0 && y > 0 -> Direction.SE
           x < 0 && y > 0 -> Direction.NE
           x < 0 && y < 0 -> Direction.NW
           x > 0 && y < 0 -> Direction.SW
           else -> null
       }
   }
   ```

Verdict: keep 4-direction for now. Diagonal gravity can quantize to nearest axis.

### 3.8 Angular momentum conservation

The key invariant: **angular momentum is conserved in the absence of external torque.**

```kotlin
// Before collision:
val L_before = ship.I * ship.angVel + rock.angularMomentumAboutCOM

// After collision:
val L_after = ship.I * (ship.angVel + angVelDelta) + rock.angularMomentumAboutCOM

// L_before == L_after (in absence of external torque)
```

This is the same regardless of how we represent the ship's orientation.

---

## 4. What changes vs. the angle-centric plan

| Aspect | Angle-centric (PLAN_vessel_rotation.md) | Direction-vector |
|--------|------------------------------------------|------------------|
| Ship state | `angle: Frac`, `angVel: Frac` | `forward: Norm`, `angVel: Frac` |
| Update angle | `angle += angVel * dt` | `forward = rotate(forward, ω)` |
| Rotate vector | `vec.rotateByAngle(angle)` | `vec.rotateBy(forward)` |
| Renderer angle | `angle` directly | `forward.asAngle` (ONE call) |
| Per-tick trig | `cos(angle)`, `sin(angle)` per body | **None** |
| Per-frame trig | None | `atan2` for renderer angle |
| Angular dynamics | Same (torque → angAccel → angVel) | Same |
| Gravity rotation | `rotate(platingGravity, angle)` | `platingGravity.rotateBy(forward)` |
| Moment of inertia | Same | Same |

---

## 5. Incremental implementation plan

### Phase 0: Add `Frac2.rotateBy(Norm)` to engine
**Files:** `engine/sim/core/src/commonMain/kotlin/.../Frac2.kt`
**Cost:** ~0.5 days

Add a new method:
```kotlin
fun Frac2.rotateBy(forward: Norm): Frac2 {
    return Frac2(
        x * forward.x - y * forward.y,
        x * forward.y + y * forward.x
    )
}
```

This is a drop-in replacement for `rotateByAngle` that doesn't use trig. No behavior change
until callers switch.

### Phase 1: Add direction-vector rotation to vessel state
**Files:** `apps/outofspace/core/src/commonMain/kotlin/.../Vessel.kt`
**Cost:** ~1 day

Add to `VesselState`:
```kotlin
val shipAngle: Frac = Frac(0),       // for UI/debug (derived from forward)
val shipAngVel: Frac = Frac(0),      // angular velocity
val shipForward: Norm = Norm(Frac(1), Frac(0)),  // ship orientation
```

Add angular dynamics to the flight/reducer:
```kotlin
// Compute torque from offset thrust
val torque = torqueFromThrust(offsetX, offsetY, thrustX, thrustY)
val angAccel = Frac(torque / shipMomentOfInertia)

// Update
shipAngVel += angAccel
val ω = shipAngVel * dt
val newFx = shipForward.x - ω * shipForward.y
val newFy = shipForward.y + ω * shipForward.x
val newLen = Frac2(newFx, newFy).len
newForward = if (newLen.raw > 0) Norm(newFx / newLen, newFy / newLen) else Norm(Frac(1), Frac(0))
```

Derive `shipAngle` for display:
```kotlin
// Only for UI — one atan2 per frame is acceptable
val shipAngle: Frac = shipForward.asAngle.raw.toFrac()
```

### Phase 2: Derived felt gravity
**Files:** `apps/outofspace/core/src/commonMain/kotlin/.../Vessel.kt`, `Flight.kt`
**Cost:** ~0.5 days

Change `experiencedGravity` to use `shipForward`:
```kotlin
val feltGravity: Frac2 get() {
    val rotatedDeck = platingGravity.rotateBy(shipForward)
    val shipAccel = frameAcceleration(netImpulseX, netImpulseY, massGrams)
    return Frac2(
        Frac(rotatedDeck.x.raw - shipAccel.x.raw),
        Frac(rotatedDeck.y.raw - shipAccel.y.raw)
    )
}
```

### Phase 3: Renderer rotation
**Files:** `apps/outofspace/core/src/commonMain/kotlin/.../OutofspaceRenderer.kt`
**Cost:** ~1.5 days

Use `shipForward.asAngle` for camera rotation (ONE trig call per frame).
Draw ship entities at their rotated orientation.

### Phase 4: Rock collision torque
**Files:** `apps/outofspace/core/src/commonMain/kotlin/.../RockContact.kt`
**Cost:** ~1 day

Add off-center collision torque:
```kotlin
// When rock collides with hull:
val torque = rockTorque(rock, impactX, impactY)
// Apply to ship's angVel in the reducer
```

### Phase 5: Debris quantization
**Files:** `apps/outofspace/core/src/commonMain/kotlin/.../Debris.kt`
**Cost:** ~0.5 days

Extend `downDirection` to handle diagonal gravity if desired.

### Phase 6: Save/load migration
**Files:** `apps/outofspace/core/src/commonMain/kotlin/.../Save.kt`
**Cost:** ~0.5 days

Store `shipForward` instead of `shipAngle` (or derive `forward` from stored angle).
Version the save format.

**Total: ~4.5 days** (vs ~8 days for the angle-centric plan)

---

## 6. What stays the same (no changes needed)

- **Fluid solver** — already handles any gravity direction (Drift.kt, Buoyancy.kt)
- **Heat conduction** — topological, grid-adjacent
- **Machine placement** — grid-locked
- **Moment of inertia calculation** — mass distribution, independent of orientation
- **Angular momentum conservation** — same math, just different state representation
- **Rock movement** — rocks stay in grid frame, don't need rotation
- **Sweep collision** — grid-based, axis-aligned

---

## 7. What the Reddit approach gives us

1. **Zero trig in the per-tick path** — `cos`, `sin`, `atan2` eliminated from hot loops
2. **Cleaner math** — rotation is just a rotation matrix multiplication
3. **Already normalized** — `Norm` is always unit length (after renormalization)
4. **Consistent with engine** — `Frac2.len`, `Frac2.norm`, `Norm.dot` all already exist
5. **Simpler renderer** — one `atan2` per frame instead of per-body trig
6. **Direction-based API** — `rotateBy(forward)` is clearer than `rotateByAngle(angle)`

## 8. What it costs

1. **`Norm.asAngle` still uses `atan2`** — but only called once per frame for renderer
2. **Renormalization adds a `sqrt` per tick** — but `Frac2.len` uses optimized `longISqrt`
3. **Direction vector is less intuitive for humans** — but the angle is derived for display anyway

---

## 9. Open questions

1. **Should `Frac2.rotateBy(Norm)` replace `Frac2.rotateByAngle(Coord)` entirely?**
   - Yes — `rotateByAngle` can delegate to `rotateBy(Norm.fromAngle(angle))` for backward compat
   - Eventually deprecate `rotateByAngle`

2. **Should rocks get rotation?**
   - Currently no: rocks are `BooleanArray` shapes (axis-aligned)
   - Adding rotation to rocks would require either:
     a. Pre-computed rotation states (limited orientations)
     b. Runtime vertex transformation (expensive per tick)
     c. SAT collision with rotated polygons (complex)
   - Defer to after direction-vector rotation is working

3. **Should the renderer draw rotated hulls?**
   - Currently: hulls are axis-aligned rectangles (grid tiles)
   - With direction-vector: hulls can be drawn at ship's orientation
   - The renderer would need to transform each hull tile's vertices

4. **What about `Norm.fromAngle` and `Norm.asAngle` — should they stay?**
   - Keep for boundary conditions (save/load, mouse input, UI display)
   - Mark as "expensive — avoid in hot paths"
