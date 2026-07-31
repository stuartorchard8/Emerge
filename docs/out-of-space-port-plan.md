# Porting Out of Space to Emerge

A plan for bringing `~/out-of-space` (Godot 4.5, ~8.9k lines of GDScript) onto the Emerge engine —
written against the actual source, not a summary of it.

The stated goal is not "ship Out of Space on Emerge". It is to consolidate its concepts into Emerge
so that a stripped-down version becomes the vehicle for general physics/chemistry work: **heat,
atmosphere contents, fluid dynamics, mechanical and biological systems inside a vessel**. The plan is
built around that goal, and the recommendation below follows from it.

---

## 1. What is actually in the Godot project

| Subsystem | Where | What it does | Lines |
| --- | --- | --- | --- |
| Planet | `objects/planet.gd`, `generation/geometry.gd` | Hexasphere (subdivided icosahedron → Goldberg polyhedron, 5 subdivisions). Per-**face** resources, per-face heat, face adjacency graph. Point gravity. | ~710 |
| Resources | `systems/resource_system.gd`, `crafting_system.gd` | A resource is `{name, extra_data}` where `extra_data` is a **mass-per-mineral map**. Crafting = two inputs → one output with summed composition. Smelting/mineral processing splits a mixture by purity into product + tailings. | ~620 |
| Vessels | `objects/vessel/**` | A `RigidBody3D` whose children are components snapped onto a **truncated-octahedron honeycomb** (8 hex faces + 6 square faces per cell). Mass, centre of mass and inertia tensor are recomputed from the component set. | ~1.5k |
| Components | `vessel/components/*.gd` | thruster, reaction wheel, conveyor, smelter, mineral processor, fabricator, distance sensor, flight seat, structural frame, floors, ladder. Each exposes named *actions*; a per-component `action_triggers` map wires vessel state keys to those actions with a **weight in [-1,1]**. | ~1.2k |
| Agent | `objects/agent.gd`, `agent/grip.gd` | First-person rigid-body character with grips, jumping, surface detection, seat locking. | ~415 |
| Networking | `systems/network_manager.gd`, `objects/physics_sync.gd` | Godot ENet. Server-authoritative RPCs addressed by `NodePath`; `MultiplayerSynchronizer` streams transform/velocity snapshots. | ~800 |
| Save | `systems/save_system.gd` | JSON dictionary of the whole world. | ~700 |
| UI | `ui/**` | Vessel config window (the action-trigger editor), navball, telemetry, altitude graph, hand/held-item. | ~2.4k |

Two structural facts matter more than the rest, because they decide the shape of the port:

**The interesting simulation is already discrete and graph-shaped, not volumetric.** Planet
resources and heat live on a face index with an adjacency list. Vessel components live on a lattice
and connect through matching attachment points. Conveyor and smelter contents are positions along a
1D belt. Resource composition is a dimensionless map of mineral → mass. Not one of these needs three
dimensions; they need *neighbours*.

**The 3D-ness is spent on three things only**: rigid-body flight around a planet, the first-person
agent, and the look. None of those are where heat, atmosphere or fluids would live.

---

## 2. Recommendation: rebuild in 2D

**Cut to 2D.** The case, in order of weight:

1. **You would be building 3D to host a simulation that isn't 3D.** Everything you named — heat,
   atmosphere contents, fluid dynamics, mechanical and biological systems — is a *transport problem
   on a network of connected cells*. Out of Space already models it that way (`face_heat` +
   `face_adjacencies`, components + attachment points). A 2D hex lattice expresses that grammar
   exactly; the third dimension adds cells and camera problems, not physics.

2. **Emerge has no 3D and the gap is not small.** The engine is 2D to the primitives: `Coord`,
   `Coord2`, `Frac2`, `Vec2`, and eleven 2D physics systems. `Mat4` is a 4×4 matrix with only 2D
   helpers; there is no perspective camera, no depth buffer, no mesh pipeline, no quaternion, no 3D
   broadphase, no inertia tensor. Adding all of that is a project in itself, and it would be
   *engine* work — shared boilerplate that four existing 2D apps would carry the maintenance of
   without using.

3. **Complex interacting systems have to be visible.** This is the lesson Cyto keeps re-teaching: if
   a step says "watch the heat move", the heat has to be visible in-world, not in a data panel. A
   2D cutaway of a vessel shows gas composition, temperature and flow *everywhere at once*. Inside a
   3D hull you cannot see your own ship. Oxygen Not Included, Barotrauma and Space Station 13 are
   all 2D, and all three are exactly this genre.

4. **The reuse is already sitting in Cyto.** Cyto has a dense per-species column grid, integer
   edge-flux diffusion at ~0.10% of tick cost, conservation checking (`checkCytoConservation`), and
   a golden-digest tripwire. That is 80% of an atmosphere model, built and hardened, in 2D.

**What you give up**: orbital flight becomes planar (fine — and arguably more legible), the
truncated-octahedron honeycomb becomes a hex tiling (its natural 2D analogue: 6 neighbours,
same face-matching grammar), and the first-person agent becomes a side-on or top-down character.
The crafting tree, the composition model, the component/trigger grammar and the whole refinement
chain port unchanged — they were never 3D to begin with.

Section 6 states what changes if you overrule this.

---

## 3. Port map

### Ports nearly as-is (data and rules, no 3D content)

- **The crafting tree.** `RECIPES`, `SMELT_RESULTS`, `BASE_RESOURCES` are pure data — a Kotlin
  table, one afternoon. `crafting_tree.txt` is the design doc for it.
- **Composition arithmetic.** `get_recipe_output` / `try_merge` / `take_at_most` /
  `process_mineral` / `smelt` are pure functions over a mass map with no engine coupling. They are
  the natural first tests in `commonTest`.
- **The component/trigger grammar.** `action_triggers: {action → [{input, weight}]}` evaluated as
  `Σ(state[input].current × weight)`, clamped to [-1,1], then dispatched. This is a small, complete
  dataflow language and it should survive verbatim.
- **Clumped resource generation** (`generate_clumped_resources`) — a flood-fill over an adjacency
  list. Works on any neighbour graph, including a hex grid.

### Must be rebuilt wholesale

- **Networking.** Confirmed — your instinct is right, but the reason is worth stating precisely.
  The Godot design is *snapshot replication of scene nodes*: `MultiplayerSynchronizer` streams
  transform/velocity per body, and every action is an RPC addressed by `NodePath` that mutates
  server state and then mutates client state by a second RPC. There are ~20 such RPC pairs and each
  is its own little consistency problem (see `receive_left_click`, which is 150 lines of
  hit-test-then-mirror). Emerge inverts this: the world is a pure reducer, the wire carries
  *inputs*, and every peer arrives at the same state by running the same code. Every `req_*` RPC
  becomes a value in an input enum; `peer_held` stops being a server-side dictionary and becomes
  ordinary state in the snapshot. This is a deletion, not a translation — expect the replacement to
  be a fraction of the size.
  - ⚠️ Known engine issue to fix first: `LockstepHost` orders inputs by arrival rather than by
    `PlayerId`, which is a latent desync. It already affects Scavengers.
- **Save.** JSON dictionaries → a versioned binary codec (`engine/sim/codecs/ecs`, and Cyto's
  save-version migration chain as the model). Snapshot-of-state, so it is nearly free once the state
  is a proper type.
- **Rigid-body flight.** Godot's `RigidBody3D` + `_integrate_forces` → Emerge's fixed-point 2D
  integration. Mass/CoM/inertia recomputation from the component set (`refresh_component_setup`)
  ports directly; in 2D the inertia tensor collapses to a scalar, which simplifies it.
- **The UI.** ~2.4k lines of Godot `Control` nodes → the immediate-mode toolkit. The vessel config
  window is the big one, and see the note in §4 Phase 3.

### Genuinely new engine work (2D path)

- **A lattice/assembly substrate.** "Entities made of parts on a grid, where parts connect through
  matching faces, and the assembly has aggregate mass/CoM/inertia." Neither Cyto (springs) nor
  Scavengers has this. It is the one real piece of new shared machinery, and it is worth building
  game-agnostically since it is also what a vessel-interior atmosphere network is defined on.
- **Non-wrapping large-scale space.** ⚠️ `Coord` is *inherently* toroidal — the wrap is 32-bit
  two's-complement overflow, not a policy you can switch off. For a planet-and-orbit world you
  either make the world large enough that you never reach the seam, or introduce a non-wrapping
  coordinate. Worth deciding early. Note the upside: fixed-point coordinates make Out of Space's
  floating-origin hack (`relative_particles.center_on`, which reparents every body to keep floats
  precise near the origin) **unnecessary** — precision is uniform across the world. At a 100,000 km
  world that is ~5 cm resolution everywhere.
- ⚠️ `Frac` holds roughly ±2. Out of Space's constants (gravity `2e6`, thrust `500`, planet radius
  `500`) cannot be represented directly and must be reduced to O(1) coefficients. This bites early
  and silently; do the unit design before the physics.

### The reuse that is not obvious

- **`extra_data` is Cyto's species map.** A resource is `{name, mineral → mass}`; a Cyto cell is a
  species → count map with a `SpeciesRegistry`. Same shape, same conservation requirement, same
  display problem ("what is in this thing?"). One shared **mixture** type could serve ore
  composition, atmosphere contents, and Cyto's cytoplasm — and it is the natural home for the
  chemistry work you actually want to do.
- **The vessel config window is a gene editor.** `WHEN <input> DO <action> AT <weight>` is
  structurally the same sentence as Cyto's `WHEN <condition> DO <action>`. Cyto's
  progressive-disclosure "a gene is a sentence" UI, its spotlight coach, and its snippet/paste
  banking should carry across nearly wholesale. This is the single largest UI saving available, and
  it also means the vessel-programming mechanic arrives already taught.
- **Per-face heat + adjacency is Cyto's diffusion.** `process_heat_and_smelting` is a decay term
  plus a threshold reaction on a cell graph. Cyto's integer edge-flux diffusion is the same
  operation, already conserved and already fast.

---

## 4. Phases

Each phase ends in something runnable and verified. Nothing here needs the phase after it to exist.

**Phase 0 — Decide the fork and the units. (small)**
Settle 2D vs 3D (§2 vs §6). Then do the unit design: pick the world size, the length/mass/time
units, and check every constant in the Godot source lands inside `Frac`'s ±2 range. Write it down as
a table. This is unglamorous and it prevents the most expensive class of rework.

**Phase 1 — The chemistry, headless. (small–medium)**
`tools/new-app.sh outofspace`, then port `resource_system` + `crafting_system` into the core as pure
functions over a mixture type, with the recipe table as data. Tests only: refine an ore, merge two
piles, craft up the tree, assert mass is conserved through every path. No renderer, no vessel, no
world. This is the piece you most want for the general chemistry work and it is worth having on its
own.

**Phase 2 — A world of one vessel on a lattice. (medium)**
The hex-lattice assembly substrate: place components, match faces, recompute mass/CoM/inertia, break
an assembly apart. Flight physics against a planet with point gravity. Thruster + reaction wheel
only. Exit criterion: fly a hand-authored ship, land it, and have a golden digest that fails when
the trajectory changes.

**Phase 3 — Components and the trigger grammar. (medium)**
Conveyor, smelter, mineral processor, fabricator. The `action_triggers` evaluator. Then the wiring
UI — built by adapting Cyto's gene-sentence editor rather than by designing a new one. Exit
criterion: a player-built refinery chain converts ore to a component without the player touching a
config file.

**Phase 4 — The systems layer. (the actual goal — open-ended)**
Now the vessel interior is a cell network and every prior phase was scaffolding for it. Heat
conduction between adjacent components; atmosphere as a mixture per cell with pressure-driven flow;
fluids in pipes; power and mechanical linkage; and biological load (crew consuming O₂, producing
CO₂ and heat). Cyto's diffusion, conservation checker and golden gate are the tools. Take these one
at a time — each is a research project with its own "is this legible to a player" question.

**Phase 5 — Multiplayer. (medium, deliberately last)**
Because the reducer is pure, this is mostly plumbing by the time you reach it: input encoding,
transport selection, and the join/resync path. Fix the `LockstepHost` ordering bug before relying on
it. Cyto's `MULTIPLAYER_PLAN.md` already worked out the client-topology trade-offs; Out of Space's
cost profile (many small stateful machines, modest entity count) most likely suits plain lockstep,
unlike Cyto.

**Not in the plan, on purpose**: the first-person agent, the navball, the skybox, the blend meshes,
orbital transfer planning. All of it is real work with no bearing on the systems simulation, and
none of it is load-bearing for deciding whether the direction is right.

---

## 5. What to salvage from the Godot repo

Keep as reference, not as code: `crafting_tree.txt` (the design of the whole economy),
`resource_system.gd` + `crafting_system.gd` (the only files worth reading line by line while
porting), `mesh_attachments.gd` (the lattice grammar — the face counts and angles tell you what the
2D reduction has to preserve), and `save_data.json` (a real world's worth of shape).

The `.blend` meshes and shaders do not survive a 2D port. That is the largest single thing thrown
away and it is worth being honest about: it is most of the visual identity built so far.

---

## 6. If you keep 3D instead

Not unreasonable — the lattice construction genuinely reads better in 3D, and it is what exists
today. The honest cost, before any Out of Space feature is written:

- **Primitives**: `Coord3`/`Frac3`, an orientation representation (quaternion or basis) in
  fixed-point, and the determinism tests for both.
- **Physics**: 3D integration, a 3×3 inertia tensor with off-diagonal terms, 3D broadphase, 3D
  contacts and constraint solving. This is the big one — Emerge's 2D contact/spring solver does not
  generalise for free.
- **Rendering**: perspective camera, depth buffer, mesh + material pipeline, a mesh format and
  importer, lighting. The current `CircleShader`/`SpriteShader`/`TileShader` set is all 2D
  instanced quads.
- **Ongoing**: every engine module grows a dimension it must keep working in, maintained by four
  2D apps that will never use it.

Realistically that is the majority of a year of engine work before the heat model starts, and it
delays the thing you said you actually want. If the 3D construction feel is what you are protecting,
the cheaper experiment is to build the 2D version first and see whether the hex lattice loses
anything you miss — Phase 1 and 2 are small enough to answer that question honestly.

---

## 7. Open questions

1. **World topology** — do you want a real orbital world (needs the non-wrapping decision), or is a
   single planet surface plus "space above it" enough for the systems work? The second is much
   cheaper and probably sufficient for Phase 4.
2. **How much game?** A stripped-down consolidation could stop at "a vessel you can build and keep
   alive", with no planet, no flight and no economy. That would skip most of Phase 2 and reach
   Phase 4 far sooner. Worth deciding before Phase 2, not during.
3. **Does the mixture type get shared with Cyto now or later?** Sharing it early is the cleanest
   path to the general chemistry work; it also couples two apps through the engine, which the repo's
   rules permit only for genuinely game-agnostic code. A mixture over a species registry qualifies —
   but it needs to be designed as such rather than lifted from either side.
