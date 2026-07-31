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

### Logistics: packets, not flow rates

Matter moves as **discrete packets**, ONI-style, capped at **1 kg** each. A packet is a thing you can
see, point at and read the contents of, which is the whole reason this game is side-on; a continuous
throughput number would be easier to simulate and impossible to look at. Packet size and throughput
are separate dials and both are data — 1 kg lumps at 1 kg/s is a starting tune, not physics.

**Solid vs fluid, not solid/liquid/gas.** Solids ride belts; liquids and gases share pipes. The
liquid/gas distinction is real but it lives at the *ends* of a network — lifting a liquid against
gravity and compressing a gas are different machines with different costs — so the transport layer
only needs the two-way split, and `Species.phase` carries the three-way one for when it matters.

Two asymmetries that fell out of this and are worth keeping:

- **A solid packet has a `Form`; a fluid packet does not.** It matters enormously whether a solid is
  an ingot or a structural frame, so a solid packet carries a whole `Resource`. A fluid is only ever
  "whatever was at the source" — an amalgam with no identity beyond its composition — so a fluid
  packet is a bare `Mixture`.
- **Solids merge only within a form; fluids always merge.** You cannot pour an ingot into a
  structural frame, but any two fluids make a third fluid.

Pure ingots and elemental products are single-species, as intended — `smelt` yields only the
dominant species. But **alloys are amalgams too**, not just ore and slag: `SteelAlloy = IronIngot +
CarbonFiber` sums compositions, which is what an alloy is.

**Mass now, volume later — but per-phase.** Volume is the truer measure and everything goes through
`Capacity.quantityOf(packet)` so there is one place to change it. The caveat to record before that
switch: volume works for solids and liquids and is *meaningless for gases*. A gas has no volume of
its own; it fills its container, and "a litre of gas" says nothing without a pressure. ONI is
mass-based for gas packets for exactly this reason. So the likely end state is **volume for solids
and liquids, mass for gases**, which is why `quantityOf` takes a whole packet rather than a mass.

**Rates carry their fraction.** "1 kg/s" at 60 ticks/s is 16.67 g/tick and there is no honest integer
for that. Rounding each tick either leaks mass or runs the belt at the wrong speed, and over an hour
either is a lot. `Rate.tick` keeps the remainder in a `Long` carry that lives in the owning machine's
state, so it serialises with the snapshot and the delivered total is exact over any whole second.

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

**Phase 2 — The grid, and things that move on it. ✅ BUILT**
Square tile grid; belts with **slots**; miner, processor, smelter, node and vent; a renderer and a
build UI. Exit criterion met: ore is dug, concentrated, smelted, banked as iron ingot, and its waste
vented — and the default world opens with that line already running so the loop is visible in the
first second.

Two things settled while building it. **Belts have slots** (four per tile), not a throughput number:
when the line backs up the slots fill from the head and you can count the jam, which is the whole
argument for the packet model. And **structure is deferred** — Phase 2 needs no notion of hull or
floor, and Phase 4 will define it properly when it has to answer "what is inside the vessel".

**Phase 3 — Machines and the trigger grammar. ✅ BUILT**
Fabricator, storage, sensor; the `Σ(signal × weight)` evaluator; and the wiring UI, built on the
toolkit's `clauseRow` — the same three-tappable-token sentence as Cyto's gene editor, which is
exactly the saving that was hoped for. Signals are six colour channels plus a constant `ALWAYS`;
sensors read the fullness of the tile they face; every machine is wired to `ALWAYS` by default, so
placing one just works and wiring is something you *add*.

One property worth knowing, discovered by a failing test rather than by design: **activation is a
throttle, not a switch**, so `ALWAYS − RED` is a *proportional controller*. A miner filling a tank
slows as the tank fills and approaches full asymptotically instead of stopping dead. That is the
better behaviour and it is kept — but it means the grammar cannot express a **threshold**. "Stop
when past 90%" needs a comparison, which is a new kind of term rather than a change to the
arithmetic. Pipes and pumps are deliberately not here; they belong with the fluid model in Phase 4.

**Phase 3.5 — making a world of mixtures readable. ✅ BUILT**
An **inspector** (point at any tile, see every buffer with its full composition) and an **analyzer**
machine (a belt tile that measures what passes through, reports it on the tile and broadcasts its
purity on a channel). Added because the absence of them was actively misleading: a correct refinery
looked wrong, since nothing anywhere said that ore is 40% iron or that the concentrate leaving the
front is 75%. Any simulation of mixtures needs this before it needs more mechanics — a lesson to
carry into Phase 4, where a tile of air is far less legible than a lump of ore.

**Phase 4 — The systems layer. (the actual goal — in progress)**
Now the interior is a cell network and everything before was scaffolding. In order, each with its
own "is this legible?" question. One at a time.

- **Structure and heat. ✅ BUILT.** Structure is *derived*, not painted: the player builds hull, and
  a flood fill inward from the grid edge decides what is enclosed. That answers "what is outside the
  hull" for free and makes a breach mean exactly what it should — remove one hull tile and the fill
  pours in, so the room *becomes* outside. No separate concept of a leak was needed.
  Heat stores **energy** per tile with temperature derived as `joules / capacity`; storing
  temperature and averaging it would create and destroy energy wherever two unlike tiles met.
  Conduction is Jacobi (computed from old temperatures, applied after) so it cannot depend on visit
  order, and each flux is capped at the amount that would equalise the pair, which is what stops a
  coarse timestep oscillating. Machines charge heat **per gram of work done** rather than per second,
  so it needs no clock of its own and a throttled machine warms the room proportionally less.
  Invariant: `stored + radiated − generated == baseline`, every tick.
- **Atmosphere** — per-tile gas mixture, pressure-driven flow, gravity stratification. Next.
- **Liquids and pipes**, then **power and mechanical linkage**.

Two tuning lessons already banked. Radiating heat in vacuum is *hard* — the first `RADIANCE` tried
was 90× too high, which meant a vessel could only ever freeze and heat was never a constraint;
spacecraft struggle to reject heat, and that is the interesting version. And an absolute colour ramp
still has to be scaled to the question: the first heat overlay spanned 220K, across which a real
18K spread was one flat wash of blue.

**Phase 5 and beyond — flight, fleet, solar system, crew.**
Needs the unit design deferred in §4. Multiplayer belongs here too; fix `LockstepHost`'s input
ordering (it orders by arrival, not `PlayerId` — a latent desync that already affects Scavengers)
before relying on it.

---

## 6. Open questions

1. **What is outside the hull?** Vacuum as a special tile, or genuinely absent tiles? This decides
   what a breach means, and the atmosphere solver wants to know early. Bundled with defining
   structure at the start of Phase 4.
2. **How does a pump differ from a compressor?** Both push fluid down the same pipe, but a liquid
   pump works against gravitational head while a gas compressor works against pressure. That
   difference is the interesting part of fluid machinery and it wants designing alongside the
   atmosphere model in Phase 4, not before — noted here so it does not get quietly forgotten.
3. **Should the grammar get a comparison?** `WHEN RED > 900` would buy digital control — latches,
   hysteresis, "top up only when nearly empty" — alongside the proportional behaviour it already
   has. It is the obvious next expressive step, and the obvious risk is turning a small language
   into a big one.
4. **Miners are a stand-in** and should not grow depth. Whatever eventually replaces them — imports
   from outside the vessel, a mining rig on a surface — is a Phase 5 question, not a machine to
   elaborate now.

*Settled:* the grid is a **fixed generous bound** with the vessel built inside it (much simpler for
the Phase 4 atmosphere solver, and it still allows expansion); belts have **slots**; solids and
fluids are the two networks; packets are 1 kg; quantity is mass with a per-phase route to volume.
