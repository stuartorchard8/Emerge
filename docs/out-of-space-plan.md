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
- **Atmosphere. ✅ BUILT.** Grams of each gas per tile, in one flat array. Pressure is simply total
  mass, since every tile is the same volume. Flow is the same conservative edge-flux pattern as heat,
  and gas that moves is a *proportional sample* of its source, so a draught carries the room's actual
  mix. Stratification is a **swap** — equal masses of heavy down and light up — so it rearranges
  composition without disturbing pressure and cannot fight the flow pass. `stratifyColumns` is the
  one function permitted to assume axis-aligned gravity, exactly as §3 promised; with a diagonal
  gravity it declines to sort rather than guessing an axis. Breaching reuses the structure derivation
  with no new code: the tile stops being enclosed, so its air is vented and its heat radiated.
  Invariant: `aboard + vented == baseline`, every tick.
- **The stockpile became a view over the storages** (`Stockpile.of(machines)`), and the central node
  was deleted. Material used to live in one of two mutually exclusive places — on the grid, or banked
  — so the conservation check had to name both. Deriving it removes the seam: there is no act of
  banking, only of storing, and the invariant shortens to `mined == aboard + vented`. A warehouse
  becomes a thing you can lose, which a central bank could never be.
- **Debris** (`world/Debris.kt`). Dismantling a machine spills its contents onto the deck rather than
  deleting them — the mass balance was right to call the old behaviour a leak, and the fix is
  somewhere for the material to go, not an exemption for player edits. A sparse map from tile to
  pile, unlike the dense air and heat fields, because it is touched in a handful of tiles and only on
  dismantle. Piles fall toward gravity, pass *through* machinery, and stop at hull or at a per-tile
  cap. They keep their `Form`s apart: rubble loses its arrangement, not its refinement. Spilling
  outside the hull vents and breaching a room takes its heaps — both free from the structure model.
  `downDirection(gravity)` is shared with `stratifyColumns`, since the two must not be able to
  disagree about which way is down.
- **Footprints and ports** (`world/Footprint.kt`). Conveyors are one tile; miners, processors,
  processors and tanks three; smelters five. Belts carry one packet. This had to precede pipes,
  because **a port is a property of a tile**: on a one-tile machine every port overlaps every other
  and connectivity collapses into "which way is it pointing".
  - Machines anchor at their **centre**, hence odd sizes. Rotating about a centre leaves the covered
    tiles alone, so a rotate is a change of facing and nothing else.
  - Ports are declared **once in the machine's own frame** (facing-Right canonical) and rotated into
    the world, so "in at the back, concentrate out the front, tailings out of the floor" is one
    sentence that holds at every orientation.
  - Delivery checks a port's **facing**, not merely its tile — otherwise a three-by-three is a
    nine-tile sponge that absorbs anything touching it.
  - Which buffer drains through which port is named by `Stream`, not inferred from an angle. The old
    "product by `facing`, waste by `facing.clockwise`" rule only worked while machines were one tile.
  - `Occupancy` is derived every tick beside `StructureMap`; a machine is stored exactly once, so
    twenty-five tiles of furnace cannot come to disagree about one furnace's contents.
  - Thermal mass scales with footprint.
  - Ports render in ONI's language: **white in, green out**.
- **Liquids and pipes** next, then **power and mechanical linkage**.

### The transport layer, as scoped

Settled with Stu after the footprint work, and worth recording because one premise is a common
misreading of the reference game. **ONI's pipes are not a balanced graph.** Its *wires* are — power
solves a whole connected component against total supply and demand — but pipes and rails move
discrete packets with order-dependent junctions, which is why bridge-priority tricks are a player
skill there. That is an artifact, not a design, and it is not worth inheriting.

So the transport layer is two mechanisms, chosen per network rather than one forced everywhere:

- **Packets** — things with a position, visible jams, real latency. Solids stay here.
- **Circuit** — no position; solve the connected component in one pass. Right for power, probably for
  signals.

Fluids get segment-held mixtures with **balanced junctions**, since a three-way split of N packets is
exactly largest-remainder apportionment and `apportion()` is already exact.

The design decision that makes **bridges** free: *connectivity is a property of ports, not of tiles*.
A normal segment has ports on four sides; a bridge has two opposite ports and none on the others.
Two runs crossing in the same layer simply share no port, so the network derivation never needs to
know the special case exists. Four layers — Deck, Conduit, Power, Signal — one occupant each per
tile; structure, heat and air only ever read the Deck.

### Two corrections from playtesting (2026-08-01)

Both from Stu playing the built version, and both changed the model rather than tuning it.

**Track connects by being drawn, not by touching.** Segments used to join every adjacent segment.
That is what ONI does, but it makes two parallel lines need a tile of clearance between them, and it
made bridges nearly pointless — a bridge's ports had to sit *two* tiles out, flanking its span, or
the track at its own ends would merge with the line it was hopping. Segments now carry an explicit
four-bit link mask, set only by a drag (`Edit.Lay`), and always symmetric by construction. Bridge
ports moved back to the bridge's own two ends, where they always belonged. A single click now lays an
unconnected stub, which the renderer draws dimmer so it is visible as a mistake.

**Material is pulled toward consumers, not pushed away from producers.** The flow field BFSes out
from *input* ports, and material runs downhill toward the nearest one. This is the fix for
"resources are never pushed onto dead ends": a branch with nothing on the end of it has no distance
to a sink, so nothing is ever offered to it — no rule required. It also deleted the `stranded`
special case, which existed only to un-freeze material a source could no longer reach.

Worth recording how the *symptoms* changed, because they are more legible now:

| | pushed | pulled |
|---|---|---|
| run with no consumer | packs solid with stock to dig out | stays empty; backlog visible in the machine |
| two lines wrongly merged | material dribbles into both tanks — looks like it works | the nearer tank takes everything, the far one starves |
| dead-end branch | fills up and stays full | never receives anything |

A jam is now *a destination that has stopped accepting* rather than *an absent destination*, which is
both truer to a factory and a better thing to be able to point at.

One trap worth naming, since it cost real time: a fixture that lays track **over** existing track
must preserve its links. Replacing the segment wipes that tile's links while its neighbours keep
theirs, giving a join that exists in one direction only — a network connected one way and not the
other. Every crossing drawn by the first version of the test helper was silently cut this way.

The **fabricator** was dropped in the same pass — a throwback to an earlier shape of the game,
and the only machine reading the binary recipe tree. The tree itself stays: construction costs are
the question it answers next.

Storage also lost its second input port. Two lines arriving at one tank is a merge, and a merge
should be something built out of track where the player can see it.

### A full consumer is traffic to drive round, not a wall (2026-08-01)

Found from a save Stu sent — the first thing the save format was used for, and it paid for itself
immediately. A line jammed solid two tiles from a vent that would have taken everything on it.

The cause was a piece of reasoning that looked principled and was not. Pulling gave every tile a
distance to the nearest input port, and a tile *at* a port got distance zero and **no successors** —
justified in the comment as "what stops material walking through a consumer and out the far side".
But arriving somewhere and being *taken* there are different events. A packet the consumer refused
was pinned to that tile permanently, and everything behind it queued forever. A belt has to let
material past a machine that does not want it.

The fix is two rules, and it needed both:

1. **Sinks are filtered by room.** An input with nothing free does not pull, so the field routes
   traffic past it to the next consumer that will have it.
2. **A tile at a sink still has successors** — its linked neighbours, nearest first, minus any whose
   own nearest sink is this very tile (those would hand the packet straight back). This covers the
   case rule 1 cannot: a machine with room that refuses *this* particular form.

Rule 1 alone was tried first and was wrong in an instructive way: it fixed the reported bug and
**emptied every jammed line**. With no accepting consumer anywhere, a run has no field at all, so
nothing moves and the backlog hides inside the machine feeding the belt — while the belt itself goes
bare. A jam should be the most visible thing on the deck. So a full consumer is **demoted, not
deleted**: it is seeded into the BFS at `FULL_PENALTY`, heavier than any real path, in a second pass
after the accepting ones. An accepting consumer anywhere on the run beats a full one next door; with
nothing better available, material still travels toward the blockage and packs in behind it.

One test changed meaning rather than being retuned. `drawn straight through, the two lines really are
one network` asserted the starved tank got *nothing*; it now gets everything the nearer tank cannot
hold. The merge is still real, so the test now asserts the ordering — the far tank receives nothing
until the near one is at capacity — which is the honest statement of what merging costs.

### A fork needs to know where material came in (2026-08-01)

Second save from Stu, same afternoon: a line splitting to a vent two tiles away and a tank three
tiles away sent **everything** down the vent.

Nothing chose the vent. Under pure pulling, material moves to whichever neighbour is closest to a
consumer, and one branch simply happened to be shorter — so the junction produced a single
successor. `Diverters`, which has existed since the transport layer was built specifically to
alternate at such a junction, had almost nothing to alternate between: a fork only ever yielded two
successors when the branches were *exactly* the same length. It was very nearly dead code.

**A shortest-path rule cannot tell a fork from a shortcut**, and no amount of tuning the sink side
fixes that, because the consumers are symmetric — the asymmetry is which way the material came in.
So the source sweep is back, and the model is now two fields:

- **Depth**, from every *output* port: how far a tile is from where material enters. A step is legal
  only if it increases depth by one. That is what "forward" means, it makes the flow a DAG, and it
  is why nothing can circle.
- **Distance**, from every *input* port: what makes a step worth taking, with the accepting/full
  tiering from the previous correction.

Both arms of a real fork are one step further from the source, so both are legal and the diverter
finally gets its choice.

Two things fell out that are worth recording because each looked fine and was not:

- **"Leads somewhere useful" has to be asked along the forward graph, not the undirected one.** A
  dead-end spur can always reach a consumer by turning round and going back out the way it came in,
  so a plain `distance >= 0` test sends material down it — the exact dead-end filling that pulling
  was introduced to prevent. It needs real reachability over the DAG, computed deepest-first.
- **The traversal order has to be measured the same way movement is.** Walking a source-fed run in
  nearest-to-a-sink order visits a fork *before* the tile it has just moved a packet into, and moves
  the same packet again — several tiles in one pass. Fed tiles are now ordered by distance from the
  source; orphans, which still move by the old rule, by closeness to a consumer.

`isFed` changed meaning with it, for the same reason: "has a distance to some consumer" answers yes
for precisely the dead ends the network should leave alone. It now means the tile has a successor or
is somewhere material can be taken.

**Material nobody is feeding** — a belt whose miner was just torn out — has no forward, and falls
back to the old downhill-to-the-nearest-consumer rule. This is the one place the two models coexist,
and it is deliberate rather than a leftover: "material with nothing upstream drains to whatever will
have it" is a different situation with a different right answer.

### Saving is a text file, and that is the point (2026-08-01)

Built before liquids, ahead of the other suggestions, for one reason: the loop it shortens is the
loop everything else runs inside. Finding something wrong, describing it, and having it
reconstructed from the description is lossy and slow, and it was how three rounds of the previous
session went. A file removes that step entirely.

That makes **legibility the format's job**, not compactness. It is line-oriented, one entry per
line, `#` comments, and every placed thing carries its coordinates:

```
machine 485 Miner facing=Right ore=Iron=410,Copper=180,Titanium=110,Silica=300 buffer=Ore/- carry=0 rate=1000   # (5, 12)
rail 489 Rail links=5 channel=Amber   # (9, 12) R-L-
```

`links=5` is what the sim stores; `R-L-` is what a person reads. A whole vessel can be typed by
hand, which is what makes "try this layout" a thing that can be sent rather than described.

**Only what cannot be re-derived is written.** Structure, occupancy and signals are recomputed every
tick, so writing them would be writing a cache — and a cache in a save file is a cache that can
disagree with the world. The **ledgers are written**, baselines included, precisely because they are
*not* derivable: `mined == aboard + vented` is a claim about this world's history, and a load that
reset them would forgive every leak that happened before the save.

The test that matters is not a round trip. It is two copies of the same vessel, one of which went
through a file, still agreeing **after another few hundred ticks**. A state comparison passes while
ignoring a field the format forgot; running on does not. Confirmed by deliberately dropping a
miner's fractional `carry` from the writer — invisible in any snapshot, and the divergence test
caught it immediately.

#### Parked: liquids and gases might not want packets at all

Raised while scoping the transport layer, and deliberately *not* being built now — packets carry all
three phases for the moment. Recorded because it is a better model than the one being built, and
because the cost is much lower than it looks.

The observation is that packets suit solids because a solid genuinely *is* a discrete object, and
that liquids and gases are only packets by convention. Two alternatives that follow their physics
instead:

- **Liquids fill a pipe only by being pushed from a source.** Incompressible, so no push from behind
  means the flow stops — the pipe is a column of fluid, not a queue of lumps. That single rule is
  most of what makes plumbing feel different from a conveyor.
- **Gases diffuse along a pipe exactly as they do in the open hull**, with no push-or-pull direction
  at all. A building *compresses* gas into the segment when it outputs, and *decompresses* when it
  takes — so pressure, not routing, is what moves it.

The reason to write this down rather than dismiss it: **the gas half is nearly free.** `stepAir`
already diffuses an integer mixture over a graph of cells; a pipe network is just a different
topology for the same sweep, with a higher per-cell capacity standing in for compression. The
apparent scope creep is mostly a matter of pointing existing machinery at a different adjacency.

It also collapses a distinction that currently has to be maintained by hand. Right now a pipe segment
and an air tile are two different things holding the same `Mixture` for two different reasons; under
this model they are the same thing at different pressures, and `FluidPacket` largely stops existing.

What it would cost: the port model survives intact (a port is still where a building meets a layer),
but `advanceSegments` stops applying to two of the three networks, and pumps versus compressors stop
being a naming question and become genuinely different machines — which was already an open question
in §6.

Explicit isometric 3D was considered and set aside: the honest version makes the *simulation* 3D
(heat, air and fluid fields all gain a depth axis), which would undo the 2D cut this whole version
rests on.

Deliberately deferred, and worth stating so they are not mistaken for oversights: **pressure is not
yet coupled to temperature**. `P ∝ mT` is what produces convection, and it is a pass of its own
rather than something to smuggle in with the plumbing. And there is **no way to make air** — a
breached room stays empty, which is precisely the hook life support hangs on.

A third integer lesson, after apportionment and the heat ramp: a flux of `rate × gap / ticks` with a
rate below the tick rate **floors to zero**, so every gradient under ten grams froze permanently and
rooms stopped equalising with a visible staircase across them. Fluxes need a minimum of one unit.
Heat escaped this only by accident, its coefficient being larger than the tick rate.

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
