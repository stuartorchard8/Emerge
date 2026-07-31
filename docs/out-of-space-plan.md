# Out of Space

A 2D side-on management/automation game about building and running space vessels, on the Emerge
engine. It reuses the resource model of the Godot game at `~/out-of-space` — which stands on its own
and is not being replaced — and drops everything else about it.

The purpose is not feature parity with the Godot build. It is to get a stripped-down, legible world
in which to do the general physics/chemistry work: **heat, atmosphere contents, fluid dynamics,
mechanical and (later) biological systems inside a vessel**. Every decision below is judged against
that.

---

## 1. The decided design

**Side-on, square grid, no inhabitants.** You build a vessel's interior on a square lattice.
Resources arrive from outside, move through logistics (belts and pipes), are refined by machines
using a *blended composition* chemistry, and when they reach a **central node** they become globally
available for construction anywhere on the vessel. Closest reference point is Factorio: Space Age —
material flow and processing, no agents. ONI is the reference for the *systems* (atmosphere, heat,
pressure) and for side-on readability; Barotrauma for the eventual crewed-vessel feel.

Settled:

| Decision | Choice | Note |
| --- | --- | --- |
| View | 2D side-on | |
| Lattice | Square | Pipes, belts, floors and hull all read unambiguously; "up" is a real direction. |
| Gravity | Vessel-local constant, **parameterised** | Acceleration-derived later — see §3. |
| Inhabitants | **None for now** | Pure management. ONI/Timberborn-style agents come after the base systems work. |
| First playable | One vessel interior, no flight | Ore arrives as if from outside. |
| Name | `out-of-space` | `apps/outofspace` in the repo. |

Deferred on purpose, in likely order: flight and orbits, multiple vessels/fleet, a solar system to
explore, autonomous crew, life support with actual consumers.

Note what "no inhabitants" does to the systems layer: atmosphere and heat stay first-class, but
their consumers are **machines**, not lungs — gas-fed reactions, coolant loops, thermal limits,
pressure vessels. That is a complete design on its own and it keeps Phase 4 honest. Crew arrive
later as another consumer, not as a redesign.

---

## 2. What comes from the Godot game

**Taken, nearly verbatim** — these are pure rules with no engine coupling, and they are why this
project starts ahead:

- **The blended resource model.** A resource is `{form, composition}` where composition is a
  mass-per-mineral map (`extra_data` in `resource_system.gd`). Ore is never "iron ore", it is 41%
  iron / 30% silica / 18% copper / 11% titanium, and every operation is a *mixture* operation.
- **Refinement by purity.** `process_mineral` splits a mixture into product and tailings with an
  efficiency capped by the input's own purity — low-grade ore is genuinely worse to process, with no
  special case. This is the best idea in the Godot codebase.
- **Smelting.** `smelt` takes the dominant mineral, yields a refined form plus slag, and degrades to
  slag entirely when impurities exceed the product.
- **The crafting tree.** `RECIPES` / `SMELT_RESULTS` / `BASE_RESOURCES` and `crafting_tree.txt` —
  ore → ingot → alloy → component → system, as data.
- **The component trigger grammar.** `action_triggers: {action → [{input, weight}]}`, evaluated as
  `Σ(state[input] × weight)` clamped to [-1,1]. A small, complete dataflow language for wiring
  machines to signals. Worth keeping even before there is a ship to fly.

**Left behind**: the hexasphere planet, the truncated-octahedron honeycomb, 3D rigid-body flight,
the first-person agent, the Godot RPC networking, the `.blend` meshes and shaders, the save format.

---

## 3. Gravity, and keeping the door open

Vessel-local constant down now; acceleration-derived later. They are *not* equally hard, and the
difference is worth stating so the door stays cheap to open.

A constant, axis-aligned down means gravity is `(0, +g)` in grid space, which licenses **column-wise
algorithms**: sort a column by density, push pressure along it, let hot gas rise one cell per tick.
Fast, and legible on screen. An arbitrary rotating gravity vector kills that shortcut — buoyancy
becomes a general flux along a direction that changes every tick — and adds a zero-g case where
stratification stops existing at all.

The insurance, which costs nearly nothing now:

- Gravity is a **per-vessel `Frac2` in state**, never a constant and never implied by array order.
- Every fluid/atmosphere/heat function takes it as a parameter.
- Exactly **one** function may assume it is axis-aligned, named so that is obvious
  (`stratifyColumns`), and it is the only thing that has to be replaced.
- A test asserts the general path and the fast path agree for `(0, +g)`.

If it turns out the fast path is where all the behaviour lives, we will have learned that cheaply.

---

## 4. Architecture

### The mixture type — the load-bearing decision

A `Mixture` maps mineral species to mass. **Mass is an integer**, not a float. Reasons: determinism
across platforms, exact conservation, and the fact that every interesting bug in this kind of sim is
"where did the mass go". Cyto already runs on integer species counts with a conservation checker
(`checkCytoConservation`) and integer edge-flux diffusion that conserves by construction; this is
the same problem and deserves the same answer.

The cost is that ratio splits (`process_mineral`'s purity split, `take_at_most`) need explicit
remainder handling rather than float multiplication. That is a solved problem, and it is where the
first tests go.

This type is a strong candidate for eventual promotion into `engine/` and sharing with Cyto — ore
composition, atmosphere contents and cytoplasm are one concept. **Not yet**: it should earn that by
being used here first, and then be *designed* as game-agnostic rather than lifted from either side.
(Repo rule: the engine stays game-agnostic; apps never depend on each other.)

### World state

- **Vessel** = a square tile grid + a gravity vector + aggregate mass/centre of mass (kept from the
  start even without flight, because machines will want acceleration signals later).
- **Tile** = structure (empty / hull / floor / …), an optional machine, and a gas cell: a `Mixture`
  plus a temperature. The gas cell exists on *every* tile including empty interior ones — that is
  what makes atmosphere a field rather than a machine property.
- **Machine** = a type, an orientation, internal `Mixture` buffers, and its `action_triggers`.
- **Global pool** = the construction inventory the central node feeds.

All of it inside a pure `SimReducer`, per the template's contract. Player actions — place machine,
remove machine, set a trigger, queue a build — enter as input values, which is also what makes
multiplayer later mostly plumbing.

### Risks that mostly evaporate at this scope

The two traps from the earlier port plan were about *space*: `Coord` is inherently toroidal (the
wrap is 32-bit overflow, not a switch), which was a problem for orbital flight, and `Frac`'s ±2
range against orbital constants. A vessel-interior game with no flight has neither — tiles are
integer grid indices and the constants are all O(1). **Both return when flight does**, so the unit
design still has to happen before Phase 5; it is just not blocking now.

---

## 5. Phases

Each ends in something runnable and verified, and none needs the next to exist.

**Phase 1 — Chemistry, headless.**
`tools/new-app.sh outofspace`, then the integer `Mixture` type and the port of `resource_system` +
`crafting_system` as pure functions, recipe table as data. Tests only: refine an ore, merge two
piles, craft up the tree, split by purity, and assert **mass is conserved exactly through every
path**. No renderer, no grid, no world. The piece most wanted, and it stands alone.

**Phase 2 — The grid, and things that move on it.**
Square tile grid, structure placement, and logistics: belts carrying discrete stacks, machines with
input/output buffers, the central node absorbing arrivals into the global pool. Enough renderer and
UI to place a machine and watch a stack move. Exit criterion: ore enters at one edge, leaves a
smelter as an ingot, reaches the node, and becomes buildable material.

**Phase 3 — Machines and the trigger grammar.**
Smelter, mineral processor, fabricator, storage, pumps. The `action_triggers` evaluator and its
wiring UI — adapted from Cyto's "a gene is a sentence" editor, which is the same sentence
(`WHEN <signal> DO <action> AT <weight>`) and the largest single UI saving available. Exit
criterion: a player-built refinery chain runs unattended and can be re-wired without a config file.

**Phase 4 — The systems layer. (the actual goal)**
Now the interior is a cell network and everything before was scaffolding. In order, each with its
own "is this legible?" question: heat conduction between adjacent tiles and machines; atmosphere as
a per-tile mixture with pressure-driven flow and gravity stratification; liquids and pipes; power
and mechanical linkage. Cyto's diffusion, conservation checker and golden-digest gate are the tools.
One at a time.

**Phase 5 and beyond — flight, fleet, solar system, crew.**
Needs the unit design deferred in §4. Multiplayer belongs here too; fix `LockstepHost`'s input
ordering (it orders by arrival, not `PlayerId` — a latent desync that already affects Scavengers)
before relying on it.

---

## 6. Open questions

1. **Belt granularity.** Discrete stacks on belts (Factorio) or a continuous flow rate? Discrete is
   more legible and matches the Godot conveyor; continuous composes better with the fluid model
   arriving in Phase 4. They can coexist — items discrete, gas and liquid continuous — which is
   probably the answer, but it should be decided before Phase 2 rather than during.
2. **Is the vessel grid a fixed size?** Fixed bounds are much simpler for the atmosphere solver; a
   growable hull suits the "expand your vessel" fantasy better. A generous fixed bound with the hull
   inside it is the cheap compromise.
3. **What is outside the hull?** Vacuum as a special tile, or genuinely absent tiles? This decides
   what a breach means, and the atmosphere solver wants to know early.
