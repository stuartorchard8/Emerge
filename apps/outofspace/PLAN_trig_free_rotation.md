# Rotation Plan — status and design

*Rewritten 2026-08-13. Supersedes the 2026-08-09 draft, which proposed direction-vector rotation for
outofspace. **That draft's central choice was rejected** — see §2 for why. §1 is where the work is.*

---

## 1. STATE OF PLAY — read this first

### ✅ Step 1 is VERIFIED and COMMITTED as `3ea0a1fd` (2026-08-13).

All 8 `TrigTest` assertions pass on JVM, `compileTestKotlinJs` is green (the common-source-set JS
trap), and Scavengers, Drockets, Cyto and Out of Space test suites are green.

Three bugs were caught between writing and passing, all of them mine, all now fixed in the commit:

1. **`atan2` picked its normalisation shift from the larger *signed* value, not the larger
   magnitude.** `vx` is non-negative after the half-plane fold but `vy` is not, so a straight-down
   vector `(0, −n)` gave `m = 0` and an **infinite shift loop**; `(small x, large −y)` instead
   picked a shift that overflowed `vy`. Fixed by taking `max(vx, |vy|)`.
2. **The ±π branch cut was decided by CORDIC noise.** On the `y == 0` axis the true residual is
   exactly zero, but the loop drives `vy` away and back and lands on a residual whose *sign* is
   arbitrary — which at the cut is the difference between +π and −π. `atan2(0, −1)` and
   `atan2(0, −Int.MAX_VALUE)` disagreed despite being the same direction. Fixed by handling `y == 0`
   up front and returning +π, matching `atan2`'s own convention.
3. **Two test bugs of my own.** The axes test asserted one specific neighbour of π/2 after its own
   comment said either was correct, and the round-trip test swept `Int.MIN_VALUE` — see below.

### ⚠️ The one round-trip exception, and why it is not Trig's fault

`asAngle(fromAngle(a)) == a` exactly for every `a` **except `Int.MIN_VALUE`** — measured as 1
mismatch in 200 000 swept angles, and the only one. This is a property of `Coord`, not of `Trig`:
`Coord` scales as `raw / Int.MAX_VALUE` but wraps on `Int` overflow, so its angular **period is
2³² while a full turn is 2·Int.MAX_VALUE = 2³² − 2**. Those disagree by two raw units, and
`Int.MIN_VALUE` is the one raw that falls in the gap — an angle just past −π with no representable
fixed point. Widening the sweep will not find another. Fixing it would mean rescaling every angle in
the engine, which is a separate decision and not this change's business.

### ⚠️ No golden moved, but do not read that as "nothing changed"

I expected a shift and there wasn't one. The reason is worth knowing before trusting these suites
again: **neither `ScavengersDeterminismTest` nor `DrocketsDeterminismTest` pins a trajectory.** Both
run the sim twice and assert the two runs agree — `ScavengersDeterminismTest.kt:43` says so outright
("we assert the two runs agree, not any particular value"). They are reproducibility tests, not
goldens, and they **cannot detect a behaviour change** of this kind by construction. Scavengers and
Drockets trajectories almost certainly *did* move, and nothing in the repo would notice.

`CytoGoldenTest` *is* a stored digest and is unchanged — correctly, because cyto's single call site
(`CytoSoaReducer.kt:365`) is a degenerate fallback for a zero neighbour vector.

### ⚠️ Two traps for whoever picks this up

- **Do not use `--rerun-tasks` on this repo.** It invalidates the up-to-date state of the whole
  multiplatform graph; the rebuild ran 25+ minutes without finishing and cost a whole session.
- **`./gradlew test` currently fails on `:apps:fluidlab:core`, and it is not related to this work.**
  `world/fluid/Drift.kt` has a local `val mass` (a `Long`) colliding with a parameter that should be
  the `LongArray`, so it does not compile — introduced by `c5f7f49a`, the grams/joules bulk rename,
  and exactly the "a bulk rename onto a name that already exists changes meaning silently" hazard.
  It references none of the APIs touched here. Left alone deliberately; **it is a live bug on main.**
  Until it is fixed, run the modules directly:
  ```
  ./gradlew :engine:sim:core:jvmTest :apps:scavengers:core:jvmTest \
            :apps:drockets:core:jvmTest :apps:cyto:core:jvmTest :apps:outofspace:core:jvmTest
  ```

### What was built

| File | Change |
|------|--------|
| `Trig.kt` (new) | Integer CORDIC. No floating point. One `atan` table serves both directions: rotation mode → `cos`/`sin`, vectoring mode → `atan2`. |
| `Norm.kt` | `fromAngle`/`asAngle` call `Trig`. Both `TODO`s and all six `kotlin.math` imports gone. |
| `Frac2.kt` | Added `rotateBy(Norm)`. `rotateByAngle` now delegates to it, so they are one implementation. |
| `TrigTest.kt` (new) | Accuracy, unit length, cardinal exactness, round-trip, `rotateBy`/`rotateByAngle` agreement. |

### Measured in a numerical prototype, not in Kotlin

The algorithm and every constant were validated in Python first
(`scratchpad/kotlin_exact.py`, not in the repo), **emulating Kotlin's exact semantics**: truncating
division, arithmetic `shr`, and a `Long` overflow assertion on every intermediate.

| | old `Float` path | new integer CORDIC |
|---|---|---|
| `fromAngle` max error | **641 raw units** | **0.53** |
| `asAngle` max error | — | **0.50** |
| `fromAngle` → `asAngle` round trip | — | **exact, 0 drift** |
| Cross-platform agreement | not guaranteed | bit-identical by construction |

So the tolerances in `TrigTest` (≤ 1.0 raw) are derived from measurement, not guessed. **If a test
fails by a small margin, the bug is in the Kotlin transcription, not the tolerance** — do not relax
it without re-deriving. That rule earned its keep: every one of the three failures above was a real
defect in the implementation or the test, and not one of them was the tolerance being too tight.

Two portability traps were caught in the prototype and are already handled in `Trig.kt`. Both would
have compiled clean and been wrong:
1. **Python's `%` is non-negative; Kotlin's takes the dividend's sign.** Angle folding needed an
   explicit floored modulo, written out because `Long.floorDiv` is not in the common stdlib.
2. **`v * Int.MAX_VALUE / 2⁴⁰` overflows `Long` at 2⁷².** Fixed by holding the rotating vector at
   `Int.MAX_VALUE shl 9` so the conversion back to a `Frac` is a shift and a round, not a multiply.

---

## 2. Why the original direction-vector plan was rejected

The 2026-08-09 draft wanted to store `forward: Norm` in outofspace specifically to avoid
`Norm.fromAngle`. Three findings killed it:

1. **The engine already has a rotation model, and it is angle-centric.** `TransformComponent.ang:
   Coord`, `MotionComponent.angVel`, `ImpulseComponent.angVel`, integrated in `IntegrationSystem.kt:31`,
   with codecs and SoA columns. Scavengers and Drockets both use it. Outofspace does *not* use the
   engine ECS, but adopting a second, incompatible representation for the same concept is a cost with
   no payer.
2. **`Coord` is the better representation for "every body has rotation".** Int overflow *is* modular
   arithmetic on turns, so it wraps exactly and never drifts; composition is addition, so
   `worldAng = vesselAng + localAng` is exact for a body attached to the vessel. Normalised direction
   vectors requantise on every renormalise (`Frac2.len` quantises to ~4.7e-10) and compound that error
   per body per tick. Saves are one `Int` that round-trips exactly.
3. **The plan's stated motivation was the wrong diagnosis.** It framed the problem as *trig is slow*.
   With one rotating body, one `cos`/`sin` per tick is nothing. The actual defect was that
   `Norm.fromAngle` did a **`Float`** round trip — 24-bit mantissa under a 31-bit fixed-point type,
   throwing away seven bits, with no cross-platform bit-identity guarantee. Fixing the primitive fixes
   it for the whole engine; routing around it fixed it for one app and left the hazard in place.

⚠️ **Latent, pre-existing, and NOT addressed here:** Scavengers calls `Norm.fromAngle` /
`rotateByAngle` from `ShipThrustSystem`, `LandingSystem`, `DamageSystem` and `RespawnSystem` — sim
systems, inside a lockstep multiplayer game, where JVM host and JS/Android client are not guaranteed
to agree on `cos(Float)`. Step 1 removes the float and therefore the hazard, but **nobody has
confirmed this was ever causing an observed desync**, and it is a separate question from the known
`JOIN_IMPULSE` join bug. Do not conflate them.

---

## 3. The remaining steps — agreed scope, nothing built

End state (Stu, 2026-08-13): **every body has rotation like the vessel, with collisions imparting
torque as well as momentum.** Scavengers and Drockets already do this, but only with circle colliders.

### Step 2 — vessel `ang` / `angVel` and torque

**Next up. Nothing built.** Groundwork surveyed 2026-08-13, recorded here so it need not be re-derived:

- **Where the linear impulse is booked:** `OutofspaceSim.kt:326-327`. `netImpulseX/Y` is a sum of
  five contributions — `pushed.vesselX`, `pipePushed.vesselX`, `thrustX`, `−handedX`,
  `−w.exhaustMomentumX`. Angular is the **same five terms crossed with their application point**,
  so the honest change is to give each of those producers a position and book `τ = rₓF_y − r_yF_x`
  alongside, not to bolt torque onto the total afterwards. The total has already lost the positions.
- **Where it lands in state:** `state.copy(...)` at `OutofspaceSim.kt:328` sets `vesselImpulseX/Y`
  (running total) and `netImpulseX/Y` (this tick). `angImpulse`/`netTorque` are the twins to add.
  Position integrates explicitly from start-of-tick velocity (`positionX = newPositionX`); `ang`
  should integrate from start-of-tick `angVel` the same way, for the same reason.
- **Moment of inertia:** extend the **existing** walk in `structureMass()` (`Flight.kt:48`) to
  accumulate `Σ m·r²` about the centre of mass in the same pass — it already visits machines,
  conduits and bridges with their masses. Do **not** write a second walk; that function's KDoc says
  why, and `vesselMass()` is already built by composing it rather than duplicating it.
- **Save:** `Save.kt:55` writes `thrust <x> <y>` and `Save.kt:507` reads it. ⚠️ Memory's warning
  applies directly here — **a string literal in this file is a save keyword**, so add a new keyword
  rather than widening `thrust`, and bump the version with a migration.
- ⚠️ `state.velocityX/Y` is handed to `driftBodies` (`OutofspaceSim.kt:307`) as *the velocity of the
  grid*. Once the grid rotates, a body's grid-frame velocity picks up an `ω × r` term and that call
  is where it has to enter. It is the one place where step 2 leaks into step 4.

Failing test first: an unbalanced thruster layout spins the ship, a balanced one does not — and a
third case worth pinning, since it is the one a wrong `r` still passes: **a single thruster on the
centreline produces zero torque at every throttle**.

### Step 3 — world frame + camera mode
World coordinates **do not exist today**; this step creates them. Camera becomes player-selectable:
**Flight → world-relative, Build → grid-relative** (Stu's call).

Drockets already does exactly this toggle at `WorldRenderer.kt:494`
(`Coord(focusRotationOffset.raw - transform.ang.raw)`) — copy that pattern rather than inventing one.

### Step 4 — `RigidBody` orientation, and collisions imparting torque
`RigidBody` currently has **no orientation at all**: a box plus a row-major solid mask, axis-aligned.
This is where the polygon-vs-circle gap bites and it is the largest step.

⚠️ **Do not start here.** Memory records six standing failures already sitting in the free-body
gravity/contact area (plus `ProcessorChainTest`), pre-dating the unit rescale. Clear or at least
understand those before adding spin to that code, or you will be debugging two things at once.

### Explicitly parked
- **Centrifugal force in the fluid sim.** Stu, 2026-08-13: the fluid sim was dramatically simplified
  and he does not want it touched for a while. Note this is the *only* physically real coupling
  between vessel rotation and felt gravity — see §4.
- **Rotated rocks in the renderer**, and 8-way `downDirection` quantisation. Neither is needed for
  steps 2–3.

---

## 4. A correction to the old draft worth keeping

The 2026-08-09 draft's §3.2 proposed `feltGravity = platingGravity.rotateBy(forward) − frameAcceleration`.
**That is wrong**, and the draft half-noticed ("Wait —", "Hmm, this is getting complicated") without
resolving it.

`VesselState.gravity` defaults to `FREEFALL`, and the KDoc at `Vessel.kt:598-611` is emphatic about
why: there is no deck plating, "down" is earned by burning. So `feltGravity` is in practice just
`−frameAcceleration`, and `frameAcceleration` derives from `netImpulseX/Y`, which is booked by
thrusters **bolted to the grid**. Rotating the ship rotates the thrusters with it. **Felt gravity in
the vessel frame does not change.** Rotating `platingGravity` by the orientation is either a no-op or
it silently redefines `gravity` as an external field, contradicting the field's documented meaning.

The only real coupling is **centrifugal** — ω²r, pointing outward, and *position-dependent*, whereas
every consumer (`applyBuoyancy`, `applySpeciesDrift`, `downDirection`) takes one global gravity vector
for the whole grid. That is a design decision about the fluid pass, not a refactor. Parked per §3.
