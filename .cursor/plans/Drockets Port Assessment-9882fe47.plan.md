<!-- 9882fe47-bbbf-49de-b785-5fad2d54d517 -->
---
todos:
  - id: "create-module"
    content: "Create demos/drockets/ Gradle module with build.gradle.kts, mirroring demos/physics/ structure"
    status: pending
  - id: "drocket-state-component"
    content: "Add DrocketStateComponent (grounded/launching/flying/landing) and DrocketAISystem to drive the autonomous lifecycle"
    status: pending
  - id: "walk-system"
    content: "Add WalkSystem that moves landed drockets along planet surface by updating LandingAttachmentComponent.relativePos"
    status: pending
  - id: "drockets-defaults"
    content: "Create DrocketsDefaults.kt to spawn 1 planet + 3 drockets with initial state, plus PhysicsConfig tuned for Drockets gravity/scale"
    status: pending
  - id: "platform-wiring"
    content: "Wire desktop-app entry point to launch Drockets demo (add launch option or separate main)"
    status: pending
  - id: "atmospheric-drag"
    content: "Add AtmosphereDragSystem for velocity-squared drag inside atmosphere radius (post-MVP but straightforward)"
    status: pending
  - id: "camera-follow"
    content: "Make ScreenRenderer view focus configurable to follow arbitrary entities, not just the local player"
    status: pending
  - id: "sprite-rendering"
    content: "Add SpriteShader and texture atlas support to render textured sprites instead of procedural shapes"
    status: pending
  - id: "port-shaders"
    content: "Port Godot shaders (planet, starscape, drocket glow, conic) to Emerge GLSL via ShaderFactory"
    status: pending
  - id: "animation-system"
    content: "Build frame-based sprite animation (UV cycling per tick based on entity state)"
    status: pending
isProject: false
---
# Drockets Port to Emerge Engine -- Feasibility Assessment

## Where Drockets Would Live

The existing engine convention is `demos/<name>/`. The new project would be:

- `demos/drockets/` -- game logic (common multiplatform)
- Platform entry points would be added alongside the existing ones in `platform/desktop-app/`, `platform/android-app/`, `platform/web-app/`

This mirrors the structure of `demos/physics/` (the existing space-sim demo).

---

## What Emerge Already Supports

These Drockets features map directly onto existing engine capabilities:

| Drockets Feature | Emerge Equivalent |
|---|---|
| Inverse-square gravity | `GravitySystem` in `engine/sim/core` -- already does per-body gravity with configurable `gravityNumerator` |
| RigidBody physics (forces, velocity integration) | `IntegrationSystem`, `MotionComponent`, `ImpulseComponent` |
| Circle collision (planet) | `ColliderComponent` with `BodyShape.CIRCLE`, `CollisionSystem` |
| Bounce / friction (PhysicsMaterial) | `MaterialComponent` (mass, bounce, rough) |
| Landing detection (rocket on planet surface) | `LandingAttachmentComponent` + `AttachmentSystem` + `CollisionSystem` landing logic |
| Particle exhaust | `ParticleComponent` + `ShipThrustParticleSystem` + fading alpha in renderer |
| Entity spawning / lifecycle | `EcsWorld.createEntity()`, `PhysicsState.spawnBody()`, `spawnParticle()` |
| Camera zoom / rotation | `ScreenRenderer.zoomIn/Out/ByFactor`, `rotateLeft/Right/By` |
| Cross-platform (desktop, Android, web) | Full Kotlin Multiplatform with GLFW, Android, and browser targets |
| Deterministic fixed-point math | `Frac`, `Frac2`, `Coord`, `Coord2` -- exceeds Godot's float precision |

**The core orbital-mechanics gameplay loop (gravity, thrust, orbit, land) is already proven by the existing physics demo.** Drockets and the existing demo are remarkably similar in concept -- both are 2D space games with rockets, planets, gravity, and thrust.

---

## What Would Need Re-implementing

### Tier 1 -- Required for MVP (must build)

1. **Autonomous AI / State Machine for Drockets**
   - Godot: `drocket.gd` has a walk/spin/launch/orbit/land state machine
   - Emerge: The existing demo only has player-controlled ships. You'd need a new `DrocketAISystem` ECS system that drives non-player entities through the walk-spin-launch-coast-land lifecycle
   - Approach: Add a new `DrocketStateComponent` (grounded/launching/flying/landing) and a system that transitions between states and applies appropriate forces/impulses

2. **Surface Walking**
   - Godot: Drockets walk along the planet surface by incrementing a `bearing` angle
   - Emerge: `LandingAttachmentComponent` already attaches entities to planets at a relative position. Walking would mean updating `relativePos` each tick along the surface arc. A `WalkSystem` could do this
   - Largely a thin layer on top of the existing attachment system

3. **Capsule Collision Shape (or approximation)**
   - Godot: Drockets use `CapsuleShape2D` (54x130)
   - Emerge: Only `CIRCLE` and `TRIANGLE` shapes exist. For MVP, use `CIRCLE` with an appropriate radius. The collision math only does circle-circle anyway

### Tier 2 -- Important for visual fidelity

4. **Sprite / Texture Rendering**
   - Godot: `AnimatedSprite2D` with 4 animations (idle, walk, fire, rawr), PNG textures
   - Emerge: No sprite rendering -- only procedural circles and triangles via `CircleShader`
   - Options:
     - a) **MVP cut**: Render drockets as colored triangles (like the existing demo ships). Visually simple but functional
     - b) **Build sprite support**: Add a new `SpriteShader` that samples a texture atlas, with per-instance UV offset for animation frames. The `GPU` abstraction already supports textures (`createTexture`, `uploadTexture`). This is moderate effort

5. **Custom Shaders (planet, drocket glow, conic orbits, starfield)**
   - Godot: 5 custom GLSL shaders for rich procedural visuals
   - Emerge: 3 hardcoded shaders with no material system
   - The `WorldShader` already does a procedural noise background, so a starscape replacement is plausible
   - For MVP: use the existing `WorldShader` background and `CircleShader` for the planet. Skip drocket glow and conic orbit rendering
   - Post-MVP: port each shader individually. The `ShaderFactory` and `GPU` abstraction provide the raw tools; you just need to write the GLSL and integrate into `ScreenRenderer`

6. **Sprite Animation**
   - Godot: Frame-based sprite animation (walk cycle, idle, fire, rawr)
   - Emerge: No animation system at all
   - For MVP: skip animation entirely -- static shapes
   - Post-MVP: if sprite rendering is added, animation is just cycling the UV offset per tick based on a state timer

### Tier 3 -- Nice to have, can defer

7. **Camera Follow Mode (track a specific drocket)**
   - Godot: Camera can orbit planet or follow a drocket, with smooth rotation alignment
   - Emerge: Camera always follows the local player entity. No "follow arbitrary entity" or smooth interpolation
   - For MVP: Camera focuses on the planet center (fixed). Could add entity-follow later
   - Low effort if the `ScreenRenderer` view focus is made configurable

8. **Conic Orbit Prediction / Visualization**
   - Godot: `conic.gd` computes Keplerian orbital elements and renders ellipses via shader
   - Emerge: No equivalent
   - For MVP: skip entirely. The orbits are still *happening* via physics; you just don't see the predicted path
   - Post-MVP: add a `ConicPredictionSystem` and a new `ConicShader`

9. **Atmospheric Drag**
   - Godot: Velocity-squared drag inside an atmosphere radius
   - Emerge: No drag system. `ForceFieldSystem` applies a repulsive force around home planets, which is different
   - For MVP: either skip (drockets never slow down from atmosphere) or add a simple `AtmosphereDragSystem` that checks distance from planet and applies drag. This is straightforward math

10. **Touch Input (camera orbit, pinch zoom)**
    - Godot: Touch drag for camera orbit, two-finger pinch for zoom
    - Emerge: Android already has `TouchInputMapper`, but it maps to thrust/turn, not camera. Desktop/web have keyboard-only camera control
    - For MVP: use keyboard camera controls on desktop/web. On Android, repurpose or extend the existing touch mapper
    - Post-MVP: build proper touch-to-camera input

11. **UI (Switch Button)**
    - Godot: A single "Switch" button to toggle camera mode
    - Emerge: No UI framework. Only a shader-based GUI strip
    - For MVP: skip entirely, or use a keyboard shortcut instead
    - Post-MVP: would require building basic UI (or using platform-native overlays)

12. **Audio**
    - Godot: No audio in Drockets (no sound files in the project)
    - Emerge: Audio system exists for crash sounds (demo-level). Not needed for Drockets

---

## Corners Cut for MVP (Record)

Summary of what would be deferred to get something running:

| Feature | MVP Approach | Full Version |
|---|---|---|
| Drocket visuals | Colored triangles (existing renderer) | Textured animated sprites with reentry glow shader |
| Planet visuals | Large circle via CircleShader | Procedural planet shader (port from Godot) |
| Background | Existing WorldShader noise | Port volumetric starscape shader |
| Conic orbit display | Omitted | Keplerian orbit prediction + ellipse shader |
| Atmospheric drag | Omitted | New AtmosphereDragSystem |
| Camera follow drocket | Fixed on planet center | Smooth follow with bearing alignment |
| Sprite animation | None (static shapes) | Frame-based sprite animation system |
| Touch camera controls | Keyboard only | Touch orbit + pinch zoom |
| UI switch button | Keyboard shortcut (e.g. Tab) | Proper UI button overlay |
| Capsule collision | Circle approximation | Dedicated capsule shape (or keep circle) |

---

## Implementation Order (Suggested)

1. **Create `demos/drockets/` module** with `DrocketsDefaults.kt` (spawn planet + 3 drocket entities)
2. **Add `DrocketStateComponent`** and **`DrocketAISystem`** for the walk/launch/fly/land state machine
3. **Add `WalkSystem`** to move landed drockets along planet surface (modify `LandingAttachmentComponent.relativePos`)
4. **Wire up platform entry points** (start with desktop) -- reuse existing GLFW/GL scaffolding
5. **Verify core loop**: drockets walk, launch, orbit, land using existing physics
6. **Iterate visuals**: replace background, add planet shader, add sprite rendering, etc.

---

## Networking Consideration

The existing demo is multiplayer with lockstep networking. Drockets is single-player with autonomous AI. You could:
- Use the local-only path (`LockstepHost` with no network, or just run `PhysicsReducer` directly)
- Or keep the networking infrastructure in place so you could later add multiplayer Drockets (e.g., each player controls one drocket, or watches the simulation together)

The `SimReducer`/`LockstepHost` architecture is generic over state/input types, so Drockets can define its own `DrocketsInput` (possibly just camera commands) and `DrocketsState` wrapping `PhysicsSnapshot` with AI state.
