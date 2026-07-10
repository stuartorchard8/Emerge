# Emerge — engine/demo modularization plan

The goal is **modularization**, not unification. The two demos (Scavengers, Drockets) staying independent is fine and expected. What matters is that the engine stays game-agnostic and doesn't leak game concepts (rockets, planets, crashes, respawn flow) into modules that demos are supposed to build on top of.

## Big picture finding

`engine/sim/core/physics/` currently mixes three layers in one folder:

1. **Pure math / geometry primitives** — `Vec2`, `Frac`, `Frac2`, `Coord`, `Norm`, `BodyShape`, `Contact`, `RenderShape`. Truly engine.
2. **Generic-feel physics components + systems** — used by both demos. Engine, but co-located with game-specific stuff which is part of why the layer violation isn't obvious.
3. **A baked-in "default game shape"** — `PhysicsState`, `PhysicsConfig`, `PhysicsReducer`, `PhysicsInput`, `HomePlanet*`/`Respawn*`/`Crash*`, plus the closed `registry` in `sim/sync/ecs`. This is Scavengers-flavored and is currently being smuggled around as if it were engine.

Bucket (3) is the source of the spaghetti. Drockets currently inherits Scavengers' shape: it accepts `PhysicsConfig` as the config type of every one of its systems (including `KnightAISystem`, `KnightWalkSystem`, `ReproductionSystem` — none of which care about thrust/turn/respawnTicks). `engine/sim/sync/ecs/ComponentCodec.kt` hardcodes a closed list of codecs including Scavengers-only ones (`HomePlanetCodec`), and `engine/sim/codecs/physics/PhysicsNetCodecs.kt` iterates that list to encode state on the wire — making the engine's default-game shape the on-wire shape, which both demos then have to deal with.

## Per-module verdict

### Clean — no action

- `engine/net/api/*`, `engine/net/transports/*` — pure transports.
- `engine/sim/core/Ids.kt`, `TickStepper.kt`, `SimReducer.kt`.
- `engine/sim/core/ecs/*` — ECS infrastructure (EcsWorld, ComponentStore, Partition, Pipeline, Fork, ParallelExecutor, SpatialGrid…).
- `engine/sim/core/physics/primitives/{Vec2,Frac,Frac2,Coord,Norm,BodyShape,Contact,RenderShape}.kt`.

### Engine, but folder name "physics" is overloaded

Used by both demos. Legitimately engine; consider whether the `physics` package should split into `physics/primitives/` (pure) + something like `physics/sim/` (the generic systems layer) so the boundary with the game-shape layer is visible.

- Components: `TransformComponent`, `MotionComponent`, `ColliderComponent`, `MaterialComponent`, `RenderShapeComponent`, `ImpulseComponent`, `ParticleComponent`, `DamageComponent`, `PlayerOwnedComponent`, `TeamComponent`, `ForceFieldComponent`, `PlanetComponent`, `LandingAttachmentComponent`.
- Systems: `IntegrationSystem`, `ContactSystem`, `BounceSystem`, `RollingResistanceSystem`, `GravitySystem`, `AttachmentSystem`, `ImpulseResetSystem`, `ParticleSystem`, `ShipThrustParticleSystem`, `CrashSystem`.
- `PhysicsBuilder` (with `setContacts`, `nextRandomInt`, `spawnBody`, `spawnParticle`, `queueRespawn`, `clearRespawn`, `updateRespawn`, `removeEntityWithLandingCascade`).

### Misplaced — clearly Scavengers-only, currently in engine

| File | Evidence |
|---|---|
| `engine/sim/core/physics/PhysicsReducer.kt` | KDoc: *"Reducer for the main physics demo"*. Drockets uses `DrocketsReducer`. |
| `engine/sim/core/physics/NoImpulsePhysicsReducer.kt` | Scavengers-only variant. |
| `engine/sim/core/physics/components/HomePlanetComponent.kt` | Drockets doesn't import it. |
| `engine/sim/core/physics/model/CrashImpactAudioEvent.kt` | Scavengers `CrashAudioSystem` is the only consumer. |
| `engine/sim/core/physics/model/RespawnRocketSpec.kt` | Scavengers-only respawn spec. |
| `engine/sim/core/physics/model/PlayerRespawnState.kt` | Lives in `PhysicsState.pendingRespawns`; Scavengers respawn flow. |
| `engine/sim/core/physics/systems/RespawnSystem.kt` | Composed only by `PhysicsReducer`. |
| `engine/sim/core/physics/systems/LandingSystem.kt` | Composed by `PhysicsReducer`; Drockets uses `AttachmentSystem` instead. Verify before moving. |
| `engine/sim/core/physics/systems/DamageSystem.kt` | `PhysicsReducer` composes it; Drockets uses `DrocketAdaptiveDamageSystem`. |
| `engine/sim/core/physics/systems/ForceFieldSystem.kt` | `PhysicsReducer` composes it; Drockets doesn't. |
| `engine/sim/core/physics/systems/ShipThrustSystem.kt` | `PhysicsReducer` composes it; Drockets has its own thrust. |
| `engine/sim/core/physics/model/PhysicsStateTransforms.kt::removePlayerRocket` | Only Scavengers imports it. |
| `engine/sim/sync/ecs/HomePlanetCodec.kt` | Codec for a Scavengers-only component. |

### Misplaced — leaky "engine" structures that bake in Scavengers' shape

| File | What leaks | Direction |
|---|---|---|
| `engine/sim/core/physics/model/PhysicsConfig.kt` | Fields are Scavengers tuning (thrust/turn factors, respawnTicks, maxHealth). Drockets just overrides with `DROCKETS_CONFIG`. | Each demo defines its own `<TConfig>`; engine's `EcsSystem<TConfig, TState, TInput>` is already generic. |
| `engine/sim/core/physics/model/PhysicsState.kt` | Fields `pendingRespawns` + `crashImpactAudioEvents` are Scavengers data. Convenience getters for `homePlanets`/`landings`/`damages`/`planets` hardcode component types. | Trim to `world` + `playerEntities` + `components` + `contacts` + `randomSeed`. Move respawn/audio to Scavengers wrapper state. Drop typed getters; demos write extension properties. |
| `engine/sim/core/physics/primitives/PhysicsInput.kt` | `thrust: Int, turn: Int` is Scavengers' control scheme. Drockets passes it through but ignores. | Engine declares marker `interface SimInput`; each demo defines its own input. |
| `engine/sim/sync/ecs/ComponentCodec.kt` `registry` | Closed list mixing engine + Scavengers codecs. Drockets can't extend it; it bolts its own codec on top via `DrocketsSaveCodec`. | Per-game registry. Engine sync exposes encode/decode mechanism + registration; each demo supplies its own list. |
| `engine/sim/codecs/physics/PhysicsNetCodecs.kt` | Iterates the closed registry; both demos use it. Treats Scavengers' shape as the on-wire shape. | Becomes Scavengers' codec. Drockets builds its own from engine primitives (largely doing this already in `DrocketsSaveCodec`). |
| `engine/render/torus/ScreenRenderer.kt`, `shader/WorldShaderParams.kt` | Take `PhysicsState` directly. KDoc even names "Drockets" as a special case. | Renderer consumes ECS world / component tables, not the game-flavored state shape. Lowest priority. |

## Modularization moves (sequential, smallest blast radius first)

Each move is independent: none depend on the others, so they can land separately. After every stage: run the build, then commit.

### Move 1 — extract Scavengers-only types from engine

Move into `:demos:scavengers`:

- `HomePlanetComponent`
- `HomePlanetCodec` (from `engine/sim/sync/ecs/`)
- `CrashImpactAudioEvent`
- `RespawnRocketSpec`
- `PlayerRespawnState`
- `removePlayerRocket` extension (from `PhysicsStateTransforms.kt`)

Then remove these from `engine/sim/sync/ecs/ComponentCodec.kt`'s `registry`. (Will break the closed-list invariant in the short term — Move 3 fixes properly.)

Verify the engine no longer references any of these names. Update Scavengers imports.

### Move 2 — extract Scavengers-only reducer + systems

Move into `:demos:scavengers`:

- `PhysicsReducer` → `ScavengersReducer`
- `NoImpulsePhysicsReducer` → `NoImpulseScavengersReducer`
- `RespawnSystem`
- `ShipThrustSystem`
- `LandingSystem` (verify Drockets doesn't reach into it first)
- `DamageSystem` (verify Drockets doesn't reach into it first)
- `ForceFieldSystem` (verify Drockets doesn't reach into it first)

Update Scavengers' platform hosts that reference `PhysicsReducer`/`NoImpulsePhysicsReducer` by name.

### Move 3 — open the codec registry

Make `ComponentCodec.registry` per-demo. Engine sync exposes the encode/decode mechanism + registration; each demo supplies its own list. Removes the engine's closed-list problem.

Likely shape: a `CodecRegistry` value type, constructed once per demo. `PhysicsNetCodecs` and `DrocketsSaveCodec` each construct theirs.

### Move 4 — move `PhysicsNetCodecs` into `:demos:scavengers`

It's currently `:engine:sim:codecs:physics`. After Move 3, Drockets owns its own codec registry; Scavengers owns this one. Rename module to `:demos:scavengers:codec` or fold into `:demos:scavengers` directly.

### Move 5 — trim `PhysicsState`

Reduce engine state to: `world`, `playerEntities`, `components`, `contacts`, `randomSeed`. Move `pendingRespawns` + `crashImpactAudioEvents` to a Scavengers wrapper. Drop the typed `homePlanets`/`landings`/`damages`/`planets` getters; replace with per-demo extension properties.

This is the largest single move — touches both demos.

### Move 6 — per-demo config

Each demo declares its own config record. Engine ships only the generic `<TConfig>` slot. Drockets stops carrying Scavengers' field set.

### Move 7 — `PhysicsInput` → marker interface

Engine declares `interface SimInput`. Each demo defines its own input data class. Drockets stops carrying Scavengers' `thrust`/`turn` fields.

### Move 8 — decouple the renderer

`engine/render/torus/ScreenRenderer.kt` + `WorldShaderParams.kt` should consume an ECS world / component tables, not the game-flavored state shape. Removes the last engine→demo-shape coupling. Lowest priority.

## After-state

After Moves 1–2, the engine no longer knows the words "Scavengers", "rocket", "crash", "respawn", or "home planet". After Moves 3–7, each demo is fully self-contained: it brings its own config, input, state extensions, and codec registry. After Move 8, the renderer is game-agnostic too.
