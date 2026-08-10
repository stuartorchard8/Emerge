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

### A merge is not a second source, it is an inverted gradient (2026-08-01)

Third save, same day, and the one that showed the previous entry's last paragraph was drawn too
narrowly. Two storages, each recirculating on its own loop of track, identical but for one thing: a
bridge dropped material onto one of them. The bridged loop **stopped dead** — its storage's own
material sat on the tile outside the output port and never moved — while the untouched loop ran
perfectly. Exactly backwards from what you would guess, since the interference came from the loop
that was getting *more* material.

Depth is a single breadth-first sweep from *every* output port at once, so a producer joining partway
along a run does not merely add material to it. It resets depth to zero where it lands and
**inverts** the gradient over everything upstream. The two waves meet at a tile whose neighbours are
both no deeper than it is, and a tile with nothing deeper beside it has no forward at all. Then the
dead-end reachability rule — correct, and doing its job — sees a branch leading nowhere and refuses
to enter it, so the emptiness propagates back up the line until the original producer is walled in.
Half a factory goes quiet because somebody bridged onto its belt.

There is no single potential field that avoids this. Merging two feeds into one line is completely
ordinary; it is what a bridge dropping onto a main run *is*. **So forward cannot be the only rule.**
Where a tile's forward leads nowhere useful, material now falls back to the old downhill rule. The
"material nobody is feeding" case above turns out to be the *special* case of that, not the general
one: an orphan is simply a tile whose forward is empty because there was never a source at all.

Two things this cost, both already-learned lessons recurring in new clothes:

- **The traversal order splits with the rule.** A tile ranked by depth but moved by distance gets
  visited after the packet it has just received, and moves it twice — the same multi-tile leap as
  before, arriving by a different route. Tiles moving downhill now sort ahead of tiles moving
  forward, each keyed by its own measure.
- **"Never step back upstream" is a plausible discriminator and a wrong one.** It would separate a
  watershed from a dead end if the watershed's downhill neighbour sat at equal depth, which it does
  in the save that prompted this and does not in general. Tried, tested, reverted.

What survived instead is that a dead branch now **drains**. Nothing enters it — that is the property
that matters, and it is still enforced at the fork — but material stranded on one finds its way back
out rather than sitting there forever. `isFed` says yes for such a tile now.

A cycle is still impossible for tiles moving forward, since depth strictly increases, and for tiles
moving downhill, since distance strictly decreases. A path alternating between the two rules is not
excluded by construction the way the pure DAG was; it is ruled out empirically by the conservation
ledgers and the run-on determinism test rather than by argument, which is worth knowing if this area
misbehaves again.

### One step per advance is a fact about the packet, not about the walk (2026-08-01)

The sequel to the previous entry's first bullet, and proof that ranking each tile by the measure it
moves by was not enough. Sorting downhill tiles ahead of forward tiles fixes the leap *within* a run;
it cannot fix it at the **boundary between the two**, which is exactly where such a run has one.

Found in a save of Stu's: a miner feeding a line that forks to two vents, with a bridge's output port
sitting on the fork tile. That port makes the fork a source, so depth there is zero, so every tile
between the miner and the fork has no forward at all and moves downhill — while the fork itself moves
forward. Downhill sorts first, so the tile feeding the fork is walked *before* the fork, hands its
packet over, and the fork then moves the same packet on in the same pass. The fork tile was never
occupied in any single frame: material appeared to skip straight over the port.

No ordering can be relied on to prevent this, because "most downstream first" is a total order over
one potential and this run has two. So the guarantee moved to where it can actually be enforced:
`advanceSegments` records the tiles that took delivery this pass and does not move them again. The
order stays exactly as it was — it is what makes a packed run shuffle along in one pass, which is a
real property worth keeping — but it is now an *optimisation* rather than the thing correctness rests
on. Where it cannot be achieved, a run costs a tick of latency at the boundary instead of teleporting.

### A bridge is three tiles, so it holds three packets (2026-08-01)

It held one, and that was two mistakes wearing one coat. A three-tile span with one slot has a third
of the capacity of the track it replaces, so every bridged line ran at a third speed for no reason a
player could see — a bottleneck dressed as a detour. And material inside it was *nowhere*: it left
one end of the span and appeared at the other, which is the wormhole reading the whole latency
argument existed to avoid.

Now `entry`, `middle` and `exit`, shifted one place per conduit step, in the same pass as the track
and before it — so the slot a packet leaves is free for the one behind it, the same rule and the same
reason as `FlowField.order`. Three steps of latency, one packet per step, which is precisely what
three tiles of ordinary rail cost. The renderer draws each slot at the tile it is on, so a bridge now
shows its traffic.

Worth noting for the test that measured it: the `exit` slot is empty at the end of nearly every step,
because it is put down on the track in the same step it reaches the end — the same thing the last
tile of any run does. Occupancy is the wrong thing to assert; throughput, and the backed-up case, are
the right ones.

### Dropping the tick rate was a test of whether the tick rate means anything (2026-08-01)

Moving to 4Hz broke eight tests at once, and the eight failures looked like eight unrelated bugs: a
miner had dug fifteen times too much, a gauge's line was empty when looked at, a tank had hit its cap
so a fork read as lopsided, a room would not equalise, a processor's concentrate came out at 66%
instead of 75%. Three causes, and only one of them was in the tests.

**Four were the test clock.** "Run for thirty seconds" was written `60 * 30`, with the 60 a literal
rather than a reading of `ticksPerSecond`, so every one of them silently became 450 seconds. Now
`seconds(n)` in `TestClock.kt`. Ticks are still right where the tick is the thing under test — a
`STEP_TICKS` multiple, or `Rate` arithmetic that passes its own rate in.

**The atmosphere was past its stability limit.** Flux was capped at *half* the gradient, which is the
right limit for a pair of tiles and the wrong one for a lattice: every edge is computed against one
snapshot and applied together, so a tile surrounded by four emptier ones gives away half a gradient
four times over. High tiles and low tiles swap places every tick and the room sits in a permanent
checkerboard — a gap of 1467 grams that never moved, at any duration. It never showed at 60Hz because
`FLOW_PER_SECOND / 60` is a tenth of the gap and the half-gap cap never bound. At 4Hz the raw flux is
one and a half *times* the gap, so the cap bound on every edge at once.

Two changes. The cap is now `STABLE_SHARE` — a tenth, a statement about a four-neighbour grid rather
than about the tick rate. (An eighth is the theoretical limit and is *marginally* stable: it stops
diverging but rings, resting in a ±6 shimmer instead of settling. A tenth damps, and is what 60Hz was
accidentally running at all along.) And the flow pass is now **sub-stepped** — run as many times per
tick as it takes to deliver the second's worth of movement in stable increments. Work per second is
unchanged, since the passes go up exactly as the ticks come down, and 4Hz and 60Hz now equalise the
same room to the same ±1 in the same ten seconds. Worth knowing: `stratifyColumns` has the same
shape and has **not** been sub-stepped, so stratification is still tick-rate dependent.

**A processor's concentration depends on the tick rate, and still does.** Same ore, same machine:
65% at 1Hz, 66% at 4Hz, 72% at 30Hz, 75% at 60Hz, 79% at 120Hz. `process` floors its impurity split
and `Mixture.take` rounds the chunk's own composition, so a smaller chunk-per-tick concentrates
harder — the machine is manufacturing purity out of integer flooring. Rounding the split to nearest
instead of down was tried and merely inverts the bias (50% at 120Hz); the honest fix is a rounding
carry held on the machine, the way `Rate` already holds one for grams. **Not fixed.** The two tests
that were pinned to the 60Hz figures now assert the property they are named for — each stage cleaner
than the last, concentrate well above the 41% feed — rather than three numbers that were never about
refining.

The general lesson drawn at the time — a tick is an implementation detail, so every rate in the sim
has to be stated per *second* — turned out to be the wrong one, and the next section is why.

### The tick is the unit, and seconds belong to the renderer (2026-08-01)

Everything above is the cost of one assumption: that the world should come out the same per second
whatever the tick rate. Paying it bought a fractional carry on every machine, a sub-stepping loop in
the atmosphere, a stability constant, a test-clock helper — and it *still* leaked, because processor
purity is a function of the chunk size and the chunk size is a chunk per tick. Three sessions running,
the bug of the day was a tick-rate bug.

So the assumption is gone. **Every rate in the sim is now stated per tick**, and `ticksPerSecond` is
read in exactly one place: the controller's frame accumulator, which is the one thing that is honestly
about real time. Raising it makes the factory run faster, the way a speed dial does, with identical
results per tick.

What that is worth, concretely:

- **The purity defect is gone**, without the rounding carry it was going to need. It was never about
  chemistry. `process` floors its impurity split once per chunk, so the bias was a function of how
  big a chunk is; make the chunk a constant and the bias is a constant. Nothing in `Chemistry.kt`
  changed.
- **`stratifyColumns` is fixed too**, the one still-dependent subsystem from the previous section. It
  needed no sub-stepping — just its own fraction, `STRATIFY / STRATIFY_PER`.
- **The atmosphere's sub-stepping stays, and now says what it means.** `FLOW_PASSES = 15` — a tick is
  fifteen stability-limited relaxation passes. The old code derived that number from a per-second flow
  rate and the tick rate; the arithmetic came out at exactly fifteen, so all of it was computing a
  constant. `STABLE_SHARE` stays untouched: it was always a statement about a four-neighbour lattice,
  and it is the one constant in this whole story that was right the first time.
- **`TestClock.kt` is deleted.** Tests count ticks, because ticks are what there is.
- **`Rate` survives, doing a smaller and truer job.** The clock was never the only fraction — a
  *throttle* is one, and 45% of 125 g/tick is 56.25 g. So the carry still exists and still serialises,
  but it now carries the throttle's remainder rather than the clock's, and an unthrottled machine
  never touches it.

Save version 2. The `rate` field changed units, so version 1 files are migrated by dividing by the
four ticks a second they were written at — a v1 factory keeps the throughput it was built with instead
of quietly running four times too fast, which is the sort of "it loaded fine" that is worse than a
refusal.

The test that pins all of this is `the tick rate changes how fast you watch, not what happens`: play
200 ticks at 1Hz, 4Hz and 60Hz and compare the entire save text. It is deliberately blunt — a gram, a
carry, a joule, a diverter cursor, anything at all that comes out different fails it. It is also the
test the previous design could not have passed, which is the clearest statement of the difference.

The real lesson, then, is not about ticks or seconds. It is that the sim had **two** units for time
and spent all its effort keeping them agreeing. One unit cannot disagree with itself.

### Drawing the tick happening, not the tick having happened (2026-08-01)

At four ticks a second a packet crossing a whole tile per tick reads as teleporting, so packets now
interpolate from where they were to where they are. The interesting part is not the lerp, it is that
**the renderer cannot work out where a packet was**. Given two consecutive snapshots, a lump on a
tile with three joined neighbours might have come from any of them, or from a machine's port, or have
sat still while the tile behind it was refilled. The mover knows and the observer is guessing, so the
mover writes it down: `Motion`, built during the tick and carried in the snapshot, presentation-only,
never read by the sim and never saved. A freshly loaded world is simply still for one tick.

Size and position are animated separately, and that split does the awkward cases for free. Mass
interpolates from what the tile held at the start of the tick, which covers a packet being drawn into
a machine, topped up from one, or squashed into by the packet behind it — all three change a lump's
size without moving it, and all three pop without it. A separate `scale` factor, which really does go
to zero, does the appearing and disappearing. Keeping them apart is what lets a half-full packet
shrink away *from half size* rather than jumping to full first.

**The animation immediately exposed a modelling bug rather than a drawing one**, which is the best
argument for having built it. Packets crossed a bridge smoothly to the middle and then teleported to
the far end and pulsed. Two separate causes, both invisible while everything moved a tile at a time:

- **The exit slot never survived a tick.** Machine ejection runs after the conduits advance, so the
  shift filled the exit and the same tick's `pushOut` emptied it. A bridge was three slots of which
  one was imaginary. Draining is now the *first* thing the conduit step does, before the shift.
  Ordering matters more than it looks: draining after the shift also gives every slot a full tick,
  but leaves the exit occupied when the shift wants it, so each slot idles a step waiting for the one
  ahead and the bridge quietly runs at half speed. That version is correct tile by tile, and only a
  throughput test can see it.
- **Getting on and off a bridge is not a movement.** A bridge's ports sit at ±1 from its centre —
  exactly where the entry and exit slots are drawn — so a packet handed between the track and the
  span changes *layer* without changing place. The first attempt grew it in on the span and shrank it
  off the track, which draws two lumps at one tile and pulses. Left silent, a crossing is one
  unbroken slide from the track, along the span, and back onto the track.

Both are the same lesson as the `arrived` rule for track: one step per advance has to be a fact about
the packet, not about the order the passes happen to run in. Animation is a good way to find out that
it is not, because the eye catches a discontinuity that a tile-by-tile assertion is happy with.

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

## 5b. The fluid field (2026-08-04)

Heat conduction and radiation were switched off before this was written. They were not wrong so much
as inert: heat had no way to *move* anything, so it read as a number painted on the world rather than
a thing happening in it. Convection is the missing half, and convection is a fluid problem. So the
fluid sim comes first and heat comes back on top of it.

The structural source material is Sebastian Lague's grid smoke sim (`~/seb-smoke`, CPU version in
`Assets/SmokeCPU/FluidGrid.cs`): a staggered MAC grid, a relaxation pressure solve, and
semi-Lagrangian advection. What follows takes the skeleton and rejects two of the three numerical
choices, for reasons that are specific to this game rather than to fluids in general.

### The end state, so the increments point somewhere

Written down first because every decision below is only justified by where it is going, and because
the increments are meant to be individually shippable without quietly foreclosing on this.

1. Gas moves in the vessel with momentum — draughts, updraughts, inertia. A breach is a blowout.
2. Liquids follow, on the same field, incompressible being a *parameter* rather than a second solver.
3. Pipes are not a third network. A pipe is a sealed region running the same solver at a narrower
   aperture and a smaller volume — the vessel in microcosm. A burst pipe and a hull breach become the
   same event.
4. **Gas leaving the grid imparts momentum on the vessel.** Mix combustible products, ignite, eject
   the exhaust, and the thing moves — off-centre, and it rotates. A chemical rocket that nobody
   implemented, that falls out of conservation laws being kept honestly.

Point 4 is the acceptance test for the whole enterprise. It is also the thing that dictates the
numerics, because it is the one goal that cannot survive an approximate scheme.

### Store momentum, not velocity

The decision the rest hangs on.

Semi-Lagrangian advection — Lague's step, and the standard one — traces backwards and bilinearly
samples. It is stable, cheap, and **conserves nothing**. For smoke that is a non-issue. Here it takes
out both of the ledgers the game is built on: `Mixture`'s promise that there is no arithmetic path
which loses a gram, and the thrust sum, which is only meaningful if momentum is exact. A rocket built
on a lossy momentum field is a rocket whose thrust is an artefact of the discretisation, and no
amount of tuning rescues that.

So both mass and momentum move in **flux form**: across each edge, an amount leaves one cell and
arrives in the other, and the two are the same number by construction. That is exactly what
`transferGas` already does — only the line computing the amount changes, from a pressure gradient to
a velocity. `apportion` splits the flux across species unchanged.

Momentum is therefore stored as an integer `Long` (gram·cells per tick), one per edge, and **velocity
is derived** as momentum over the mass it is carried by. This inverts the usual arrangement and it is
the right way round here: velocity is a convenience, momentum is the conserved thing, and the
conserved thing is what gets a home in state. It also sidesteps `Frac`'s ~[-2,2] range, which a fast
exhaust would otherwise overrun — `Frac` appears only where a velocity is momentarily needed, and CFL
already requires that to be under a cell per tick.

The invariant this buys, and the one to test hardest: **a sealed vessel produces exactly zero net
thrust, forever.** Internal pressure forces are equal and opposite and must cancel to the last unit.
Anything else is a leak, and a leak is indistinguishable from free energy.

### The pressure solve stays, and takes a target divergence

The first scoping pass argued gas does not need a projection, since gas is compressible and pressure
is just mass. That was wrong about what the projection is *for*. Its value is not incompressibility,
it is **ellipticity**: the solve couples every cell to every other cell within a single tick. Without
it, information crosses one cell per tick, and a breach at one end of a ninety-six cell corridor is
not felt at the other for ninety-six ticks. Relaxation-only diffusion is exactly that, and it is why
the current atmosphere equalises without ever producing a draught.

Compressibility is recovered by solving toward a **target divergence** rather than toward zero:

```
∇²p = ∇·v − d_target
```

where `d_target` comes from the equation of state — an over-dense or hot cell wants to expand, a
sparse one to contract. One extra term, the same solver. It also deletes the gas/liquid fork before
it is built: **a liquid is the case where `d_target` is zero.** Combustion raising temperature raises
the target divergence, which is what makes the projection itself generate the outward acceleration
that becomes thrust.

**Jacobi, not Gauss-Seidel.** Lague's `PressureSolve` reads and writes the same array in one sweep.
It is deterministic, but it is order-*dependent*, which breaks the snapshot-then-apply discipline
every other pass in `Atmosphere.kt` keeps, and it cannot be parallelised. Jacobi converges more slowly
per iteration and buys back both. Iteration count is the tuning dial, and at 96×60 there is room.

### The edge is the primitive, and every edge has an aperture

The cheap-now, expensive-later decision, and the one that makes pipes-as-microcosm nearly free.

The solver must not be written as `for x, for y` over a rectangle, because point 3 needs it to run on
a sub-region with different connectivity. But an abstract graph is equally wrong, because momentum is
a **vector** and a graph has no geometry to give it a direction.

What satisfies both: keep the lattice, and make the unit of iteration the **edge between two tiles**,
carrying an **aperture** — an open area, not a boolean. A hull edge has aperture zero. Open air has
aperture one. A pipe has aperture along its run and zero across it. `StructureMap.isImpermeable`
stops being the solid mask and becomes one contributor to an aperture field.

Pipes then need no new solver, no new network and no new packet type: they are the same sweep at a
narrow aperture over a small volume. This costs nothing to adopt now and is a rewrite to retrofit.

### What this replaces

`stepAir`'s fifteen relaxation passes are diffusion, not transport. Diffusion survives at most as a
small sub-grid mixing term; the transport is advection now.

`stratifyColumns` goes. It is the function §3 flags as *the one function permitted to assume gravity
is axis-aligned*, and it does not get ported — it becomes a **buoyancy force** on the edge momenta,
`F ∝ (ρ_cell − ρ_ambient) · gravity`, with density already available from `molarMass` and the
per-species grams. That form takes gravity as an arbitrary vector natively, so the axis-aligned
special case is deleted rather than carried forward, and convection *emerges* instead of being a
scripted swap of the wrong-way-up gas.

Two things carry over from the existing atmosphere untouched, and they are the reason this is a
smaller job than it looks: the solid map is `StructureMap`, already derived every tick and already
handling machine footprints; and `Structure.Vacuum`, from the flood fill, is a free-outflow boundary
— a Dirichlet `p = 0` — which is what turns venting from `grams = 0` into a pressure-driven blowout.

### Increments

Each is meant to be shippable and committed on its own.

- **A. `VelocityField`** — staggered MAC layout, integer edge momenta, the aperture field, solid-aware
  accessors. Pure structure, tested alone, moves nothing.
- **B. Conservative advection** — mass and momentum in flux form across apertures. The existing
  conservation tests are the acceptance criterion and must pass unchanged.
- **C. Pressure projection** — Jacobi, target divergence from the EOS. Plus buoyancy and gravity as
  forces; `stratifyColumns` deleted here.
- **D. Boundary** — vacuum as `p = 0`, breach as blowout, and the thrust/torque sum. Heat comes back
  on at this point, because now it has something to push. Sealed-vessel-zero-thrust is the gate.
  *Temperature landed here (2026-08-04): `tilePressure` is `n × T / T_ambient`, and the gas's thermal
  energy advects on the same fluxes as momentum, so convection emerges from `applyBuoyancy` without
  a line of code mentioning convection. Three things it taught, all of them about representation
  rather than fluids:*
  - *The gas's heat **must live inside `AirField`**, not beside it in `VesselState`. With capacity
    derived from mass and energy as the stored quantity, `copy(air = …)` reinterprets old joules
    against a new capacity — ten kilos of oxygen read as 57K. `copy` is precisely the operation that
    desynchronises parallel fields, so the two have to be one value.*
  - *Capacity is carried in **millijoules per kelvin**, and the gas's energy in millijoules to match.
    Dividing the thousand out early floors a sub-gram tile to zero capacity and a two-gram tile to
    one — a cliff that lands in the thin edge of a plume and showed up as trace species leaning 20%.*
  - *Clamping a transfer against the live array instead of the snapshot is a Gauss-Seidel sweep in
    disguise. Same fault `applySpeciesDrift` had, same detector (`BreachSymmetryTest`).*

  *Still open in D: torque, and coupling the fabric's heat to the air's — until that exists a smelter
  warms the deck plating and not the room, so there is no in-game way to heat gas.*
- **E. Liquids** — `d_target = 0` and a free surface. Only after A–D behave.
- **F. Pipes** — sealed sub-regions at a narrow aperture. Should be mostly configuration if the
  aperture decision above held. *This is the increment that overran — see §5d. It was built, it
  works, and it is deliberately not wired into anything while the slice below is built. The
  "mostly configuration" estimate was wrong for the reason recorded in `PipeField.kt`: a tile can
  hold both a room and a pipe, so the sealed-sub-region form cannot represent it.*

### The known wall

A genuinely fast exhaust exceeds one cell per tick, which breaks the CFL condition the whole explicit
scheme rests on. Storing momentum as `Long` dodges the *range* problem but not the *stability* one.
The answer is sub-stepping where velocities are high, and it is deliberately not designed here —
it wants to be looked at with a working nozzle in front of it rather than guessed at now.

---

## 5c. The body model (2026-08-04)

Heat came back on, and the first thing it needed was somewhere to be.

The field it replaces gave every tile one temperature and one heat capacity, worked out from what
kind of tile it was: interior, hull, or interior-plus-a-machine. That is a fair approximation right
up to the moment a tile holds more than one thing — which in this game is the ordinary case, because
conduits are **layers** and layers exist precisely so a rail, a pipe and a machine can share the
floor. Averaging an iron rail, a copper pipe and a titanium furnace shell into one lump denies all
three of them the only thing that makes materials interesting: they warm at different rates and pass
heat at different rates. Routing a cable through a hot room could never cost or save anything,
because the cable had no temperature of its own to lose.

### The unit is the object, and the object owns its energy

`Body` is one solid thing with one temperature: a wall, a five-tile furnace, a tile of track. Its
capacity is `Material.capacityPerTile × thermalTiles`, so a furnace is twenty-five tiles of firebrick
at one temperature — which is what a furnace is — while a run of track is one body per tile, which is
what track is.

The energy lives **on the machine and on the segment**, not in a field beside them. This is the same
decision, for the same reason, as the gas's heat living inside `AirField`: when energy is stored and
temperature derived, a parallel array keyed by tile is desynchronised by exactly one operation,
`copy(machines = …)`, and that operation is every save load, every fixture and every player edit.
Pull a smelter, drop a rail on the tile, and the rail inherits a furnace's joules and reads at four
thousand kelvin. There is no discipline that survives that; there is only a shape that makes it
unreachable. It costs a field on eight data classes.

### Touching is a graph, not a stencil

`stepSolidHeat` builds a contact list rather than sweeping a neighbourhood:

| contact | rule |
| --- | --- |
| same tile | anything sharing a tile touches, whatever layer it is on |
| impermeable ↔ impermeable | across tile faces, counted **per face** so a five-tile joint is five times a one-tile one |
| impermeable ↔ air | the air in each neighbouring permeable tile — the only way heat reaches a room |
| permeable ↔ anything | its own tile only: what shares it, and the air in it |
| fitting ↔ fitting | only where the player **drew** a join — see `Segment.links` |

The last row is the one that makes routing a decision. Two runs of track lying side by side are not
connected, so heat does not cross between them; a long copper run is a thermal short circuit along
its length and nothing at all across it, which is what a cable is. A bridge needs no case of its own:
it spans three tiles and the segments it joins to sit on the two ends, so the shared-tile rule
already connects it.

The shared-tile rule is not physically grounded — three objects are not really in one cubic metre —
and it is kept anyway, because a tile is a bookkeeping unit rather than a volume and the behaviour it
gives is the right one: things built on top of each other share their heat.

### What it cost the ledgers

Two new terms, both honest traffic rather than fudge factors:

- `constructionJoules` — a body carries its energy with it, so building a wall brings a wall's worth
  of room-temperature heat into the world and scrapping one takes it away. The old field hid this by
  charging every tile a capacity whether or not anything stood on it.
- `solidToAirJoules` — the fabric and the atmosphere exchange heat now, so what one ledger loses the
  other gains. Booked once, read by both with opposite signs, which is what keeps each of them
  closing independently. A transfer that is not counted is a leak in one and a mint in the other.

Both hold exactly over four hundred ticks of a working refinery.

### Convection, for free, again

Nothing in the heat pass knows which way is up. A furnace warms itself, conducts through firebrick
into the air beside it, `P = nT` makes that gas light for its pressure, buoyancy lifts it and
`advectHeat` carries the warmth up with the parcel. Measured at tick 400 of the starter vessel: the
furnace at 401 K, and the hottest air in the ship — 334 K — directly **above** it, with the gas
below it barely warm.

### ⚠️ Conduction runs before the fluid

A wall warms its parcel, and buoyancy lifts that parcel, in the same tick. The other order works and
is subtly worse: every parcel would rise one tick after it was warmed, so the circulation always
trails its own cause.

### ⚠️ What this uncovered and did not cause

`BreachSymmetryTest` ran on the starter vessel, which is fine while the air is isothermal — the only
thing the gas can feel is the hull, and the hull is symmetric whatever stands inside it. Coupling
ended that: a refinery line is not mirror-symmetric, so one half of the ship is now genuinely warmer,
and a warmer plume is a faster plume. The test moved to a bare hull box, where the world really is
symmetric.

The box leans 11–13% at ±5 and ±12 — **and it leans by exactly the same amounts on `1d7c8e1e`, the
commit before any of this.** So there is a real, pre-existing asymmetry in the transport that the
starter vessel was masking, and it is worth chasing on its own. What the test still says with full
sharpness is that the body model adds *nothing* to it: with the machines out, the numbers before and
after are identical to the gram.

---

## 5d. The vertical slice (2026-08-05)

The plumbing got ahead of the game. Increment F built a second fluid layer, a valve, a pump and an
interlayer crossing, and the result was good enough to keep and not good enough to trust — so the
question "what should pipes be?" was about to consume the next increment as well. It is worth
answering, and it is not worth answering *now*, because of what a look at the tree turned up:

**Thrust is measured every tick and connects to nothing.** `advectMomentum` books the momentum that
leaves through the rim and the comment says outright that "equal and opposite, it is thrust". And
`Vessel.gravity` is a `Frac2` — a vector, already threaded through `applyBuoyancy`, and
`applySpeciesDrift` — that has only ever held a constant. Two wires, both live, never joined. 
The vessel has no position and no velocity, so nothing in the game can move.

That is the loose end, and it exists today. Everything else on the list adds to it.

So the slice comes first: **a vessel that thrusts, travels, captures rock, refines it into fuel, and
burns that fuel to thrust again.** Nothing in that loop needs a pipe.

### What was settled about pipes, so it is not re-litigated

- **Pipes are not packets.** The tempting simplification — ONI's model, reusing the proven transport
  layer — was rejected on one argument: a packet is a sealed quantity of one thing, so a packet of
  water that boils has nowhere to put the gas. Phase change in a pipe becomes a special case, and
  then so does bursting, and so does a pipe half full of vapour. That is the whole point of putting
  fluid in pipes, and packets make it inexpressible.
- **ONI is not evidence either way.** Its *pipes* are gravity-agnostic; its *rooms* assume gravity.
  But a packet does bake in "the liquid is a coherent slug that stays a slug", and in a vessel whose
  gravity can be switched off that is a gravity assumption in disguise.
- **The volume term did not destabilise the room sim.** `tilePressure` multiplies before it divides,
  so a cell at `VolumeField.FULL` lands on bit-identical values to before the parameter existed, and
  it reaches exactly two call sites. What it *does* do is make a pipe cell the stiffest cell in the
  world — an eighth the mass at eight times the pressure, against CFL and pressure-force caps that
  are both mass-based. ⚠️ **It was never A/B'd against `PIPE_VOLUME = FULL`**, and the pipe suite
  cannot detect the difference: `ValveTest` asserts against `room * PIPE_VOLUME / FULL`
  symbolically, so every test passes whatever the dial says. A one-line experiment nobody ran.
- **The simpler fluid pipe exists, if the overlap is dropped.** A pipe tile that *is* a pipe — full
  volume, ordinary cells, connectivity from drawn links — deletes `Interlayer.kt`, the volume term
  and the momentum-free pump, keeps phase change and gas-or-liquid, and costs pipes running under
  corridors and pipes crossing each other. If the overlap is kept instead, the honest form is a
  genuine interlayer **axis** (a face between the layers, so momentum crosses by ordinary
  advection), not a relaxation approximating one. That is the choice to make in §5e, with phase
  change in front of it.

**The existing pipe code stays in the tree, unused.** It is tested and it costs nothing idle;
stripping it would be work spent on a question that is still open.

### Water: defer the transition, not the phase

`Species.Water` is already `Phase.Liquid`, and `Species.kt` already says phase is "what this normally
is" with `(species, temperature, pressure)` flagged for later. Calling it ice would be a regression
dressed as a simplification.

What gets deferred is the **transition**. Extraction yields water into a `Mixture` at a rate; the
mass ledger does not care what phase it is. ⚠️ And it cannot care yet, because the fluid solver
iterates `Species.GASES` — liquid water has no field to exist in, so it can only be stockpiled
cargo. That is the real reason "heat the asteroid and let the water melt off it" is not the
mechanism today: the melt has nowhere to go. It is a good thing to want, and it arrives with phase
change, and the shape below is chosen so that it can arrive without the surrounding structure
changing.

### The cargo hold is a food vacuole

A captured rock is a `Body` — it already has a material, a mass and its own joules — sitting in a
room that can be pressurised like any other. The hold is a region with an **extraction rate** that
leeches mass off the bodies in it and produces dirty ore from their average composition, which then
runs through the refining stages that already exist.

Average composition rather than geometry is the load-bearing simplification: no per-rock shape, no
erosion model, and the output is a `Mixture` with real proportions, which is what makes refining a
decision rather than a lookup. It also degrades in the right direction — when phase change exists,
the rate stops being a constant and becomes a function of the body's temperature, and heating the
rock becomes the mechanism without anything above it moving.

### Thrust is experienced gravity

`a = F/m` from the thrust already being measured, written into `Vessel.gravity`, which every
consumer already reads. Convection under acceleration, a heavy gas pooling against the direction 
of travel — all of it falls out of passes that are already written.

⚠️ **The first engine is axis-aligned, on purpose.** `applyBuoyancy` documents itself as the one
function permitted to assume gravity is axis-aligned, and a vector gravity has never been exercised
off-axis. Diagonal thrust is a scheduled follow-up, not a thing to discover as a bug with three
subsystems stacked on top of it.

The CFL wall in §5b is the other thing this walks toward: a genuinely fast exhaust exceeds a cell per
tick. It stays undesigned, as planned, until there is a working nozzle to look at.

### Increments

- **G. Motion** — ✅ **BUILT 2026-08-05.** See §5e.
- **H. Capture and the hold** — an asteroid field to fly in, rocks as bodies, capture into a hold,
  extraction at a rate into dirty ore.
- **I. Refining to fuel** — the existing stages, ending in propellant the engine consumes. Plumbing
  stays crude: a tank bolted to the engine, no run of pipe between them. This is the increment where
  the gameplay loop actually closes.
- **J. Transport, revisited** — with phase change as the driver and §5d's two candidate shapes on
  the table. Earned rather than guessed.

---

## 5e. Motion, and the constant that turned out to be load-bearing (2026-08-05)

Increment G is built. A vessel has a velocity and a position, thrust drives both, and the thrust it
is driven by is the same `vesselImpulse` the momentum ledger has been keeping since §5b.

### The shape it took

Almost nothing is stored. The ship's momentum is `vesselImpulse` — already there — its mass is
`vesselMassGrams`, and velocity is the one over the other, derived, so there is no integrated
quantity to drift and nothing to be wrong about across a save. Only **position** is new state,
because a position is a history and cannot be recomputed from anything.

"The ship" is the fabric and what it carries, and **not** the atmosphere. The gas has its own
momentum on the faces; counting its mass against the ship's momentum would mix the two halves of one
ledger and give a velocity belonging to neither. The price is stated where it is paid: spending
propellant does not lighten the vessel, so there is no rocket equation until fuel is cargo in a tank,
which is increment I.

Felt gravity is `plating − acceleration`, handed to the fluid and the drift in place of
the constant they used to read. `VesselState.gravity` stays a *setting*; `feltGravity` is the
reading. Keeping those apart is what lets a fixture say `copy(gravity = sideways)` and still mean it.

Stu's model held up in both halves. The ship is pushed on the tick the hull opens, before a single
gram reaches the rim — pinned by a test — and the ledger identity is exactly his "the fluid receives
zero net force relative to the world".

### ⚠️ The actual finding: the sim had never left `gravity == 1`

The feedback loop everyone was watching for — thrust → gravity → gas piles at the hole → more thrust
— **converges** and is undramatic: about a six-hundredth of a g on a bare breached hull, and
`undelivered` sits flat at −163 over three hundred ticks rather than growing as predicted. That was
not the problem.

The problem was that several passes scale a quantity by `q * g / SPEED_LIMIT_RAW`, which at exactly
one g is the identity and at **anything else** truncates toward zero. That kills the ones and twos a
thin plume is made of, and kills them asymmetrically, because truncation toward zero rounds `+7` down
and `−7` up. `BreachSymmetryTest` went from even to a **nine per cent lean** the moment gravity moved
off one — and the tell was that the lean was bit-identical at 0.9999 g and at 0.99 g. Not a
sensitivity to how much gravity there is: a cliff at the one value that had ever been used.

`scaleByGravity` rounds to nearest, symmetrically about zero, and is the identity at one g, so
nothing measured before it moves. The plume is then even under every gravity tried.

⚠️ **The general lesson, and it is not confined to gravity: anything only ever exercised at one value
has not been exercised.** A first wrong theory was built and measured before the right one was found —
that the cross-axis thrust term was noise being amplified — and it had evidence, in that suppressing
it made the symptom go away. Suppressing a symptom two layers above the cause is exactly what a
plausible wrong theory buys.

`downDirection` also had to grow up: it answered `null` to anything not *exactly* on an axis, which
is the same rule as "dominant axis" for as long as gravity is a constant somebody typed, and is not
the same rule at all once an engine writes it. A lateral engine under active plating gives a
permanently diagonal pull, and every pile aboard froze the moment it lit. It now rounds to the axis it
leans toward, and keeps `null` for the two cases with no answer: no gravity, and an exact tie.

### What is not done

Open question 4 stands, narrowed. Off-axis gravity is now *run* constantly, and the fluid handles it
per-axis; what remains undecided is what a **pile** does under a genuinely diagonal pull — a
staircase, presumably. It rounds to an axis today. Diagonal settling and diagonal thrust arrive
together or not at all.

---

## 5f. Rocks, and the shortcuts taken on purpose (2026-08-05)

Increment H is "capture and the hold", and a look at the tree before starting it turned up four
things the design above does not say, all of which are about the same absence: **there is nowhere for
a free-floating solid to be.**

- `Body` is not stored. `bodiesOf` *derives* it every tick from `machines`, `conduits` and `bridges`,
  which is exactly why the heat model cannot desynchronise — and it means a rock outside those three
  lists does not exist. Whatever home a rock gets has to answer to `solidJoules`/`baselineJoules` on
  the tick it appears or the thermal ledger breaks immediately.
- **The grid is the vessel's frame and the asteroid field is not.** The ship's position is in open
  space; the grid travels with it and nothing on it moves because the ship does. "Flying to a rock" is
  the ship's position changing while the grid stands still, and the rock is *placed into* the grid as
  the ship reaches it. That is open question 1 arriving with something at stake.
- **A rock is new mass in a closed world.** The balance is `mined == inTransitGrams + vented`. Matter
  arriving from outside needs a named term, and it must not be `minedGrams`, because the miner is a
  stand-in the hold is meant to *replace* (open question 5). Don't build the hold on it.

### The two simplifications, chosen rather than discovered

**Rocks do not rotate.** A rock has its own grid and its own shape, axis-aligned with the vessel's,
offset by a sub-tile amount. Overlap is then an integer box test plus a fraction, and a rock carries
momentum and no angular momentum. Rotation is a rigid-body engine — arbitrary-angle grid overlap,
torque from off-centre contact, and tiles that no longer line up with the fluid cells they sit in —
landing on a momentum ledger that only just closed at residual zero. Same move, and the same reason,
as "the first engine is axis-aligned, on purpose": a tumbling rock is a good thing to want and it is
not what makes the extractor interesting.

**A rock is permeable to air.** It reads the pressure field and does not write to it: air flows
through the tiles it occupies, it displaces nothing, it blocks nothing. It samples the pressure
gradient over its footprint, turns that into force on its own momentum, and the equal-and-opposite
goes back to the faces it was read from.

The alternative is a **moving boundary** — an aperture map changing every tick, cells opening and
closing as the rock passes, and a pressure solve whose domain moved since it last ran. That is the
genuinely hard version of solid-fluid coupling and it is out of proportion to what it buys. Permeable
still gives the readable behaviour: a rock drifting toward a breach, a rock pinned against the stern
under thrust. Making it impermeable later is *additive*, so this degrades in the right direction, and
the moment to spend the budget is when there is something on screen that reads wrong.

### The debug thruster, and the rule it has to obey

Building a real engine — a nozzle, high-pressure exhaust, a CFL wall — before the loop closes is
tuning a subsystem that every later change will detune again. So the ship gets a **debug thruster**:
a key that puts impulse straight into the vessel, as if a rocket had fired, with no rocket.

⚠️ **It must book what it mints.** `momentumBalance` is the instrument that caught the truncation bug
in §5e, and a key that creates momentum from nowhere makes that number non-zero forever — at which
point the instrument is dead, because the reading has become one you have learned to ignore. So
`debugImpulse` is a **fifth named store** beside `undelivered`, and the identity becomes

```
vesselImpulse + momentum + pipeMomentum + exhaust + undelivered − debugImpulse == 0
```

which reduces to exactly the old one when nothing has cheated. Same walk as `ventedGrams`: it is not
a leak if it is counted.

That also gives the shortcut a clean death. When the real engine lands in increment I, the check is
that `debugImpulse` returns to zero and the ship still moves — the stand-in removes itself provably
instead of lingering as a suspicious extra term.

The general form is worth stating, because the miner was the same thing and earned its keep for
months: **a stand-in that closes the loop beats a real subsystem that doesn't.** The miner's problem
was never that it was fake. It was that it minted mass silently.

### The nav view is not a convenience

Zooming out does not show you space. The grid *is* the vessel's frame and the void tiles around the
hull are part of it, so a far zoom-out shows a small ship interior in an empty box. **Open space has
no representation at all**, and until it has one the ship's position, its velocity and everything
outside the hull are invisible.

So a nav panel is the instrument that makes the two frames legible instead of baffling — including
the transition where a rock crosses out of open space and is placed into the grid, which is the thing
in H most likely to read as janky. Ship fixed at the centre with the world sliding under it, because
that is the honest frame and it is how the grid already behaves. Velocity as a vector. Rocks as dots.
A range dial, with the default deferred until there is something to look at: how far apart rocks want
to be and how fast the ship actually goes are not known until H1 flies.

No fog of war and no detection mechanic. This is an instrument, and if scanning ever becomes a
mechanic it works by taking something away from a view that already works.

### The increments, sized to be watched

Increment G was sized as a *mechanism* and was therefore dark until it was finished. These are sized
so that each one ends with something to look at, and each commit carries **all four** of:

1. Something you can do in the running game with a key or the mouse. Not a test that passes.
2. A named script in `apps/outofspace/agent-scripts/` that drives exactly that, with `expect`s, so the
   claim can be re-run rather than believed.
3. A screenshot that has actually been opened and looked at before the change is called working.
4. A paragraph in the handoff saying what to go and look at.

- **H0. The debug thruster and the nav view** — ✅ **BUILT 2026-08-05.** Arrow keys (not WASD: `W` is
  the tool toggle), `debugImpulse` as the ledger's fifth store, a nav panel with two scales.
- **H1. A rock** — ✅ **BUILT 2026-08-05.** Own grid, no rotation, momentum, its own ledger. `F6`
  drops one; `agent-scripts/rocks.txt` drives it.

  Two things it settled that the design above did not say. **The plating stops where the vessel
  does**: `feltBy` gives a rock the deck's artificial gravity only while it is over the grid, and the
  frame's acceleration *always*, because those are not the same kind of thing — one is a field the
  ship makes and the other is the price of writing the world in an accelerating frame. And the rock
  ledger is `rockGrams == baselineRockGrams + capturedGrams`, deliberately **not** a term in
  `minedGrams`, so the extractor is not built on the miner it exists to delete.

  ⚠️ A test asserted that a rock in open space "does not move at all" and failed by eight hundredths
  of a tile — correctly. A sealed hull's atmosphere rings, the ship recoils from it, and a ship with a
  non-zero acceleration gives every free rock in the universe an equal and opposite apparent one. The
  claim is *no plating out there*, not *no motion*; the two only coincide when the ship is not
  accelerating, so the fixture is now a vacuum one. Same shape of error as §5e's: **a stronger
  assertion than the model makes is a test that will fail on correct behaviour.**
- **H1b. Freefall** — ✅ **BUILT 2026-08-05.** The deck plating is gone. See §5g.
- **H2. Collision** — ✅ **BUILT 2026-08-05.** See §5h. Grid/grid overlap against hull and deck, **with restitution**: a rock that hits
  the ship ricochets rather than sticking. `e = 0.5`, tuned for legibility rather than measured (rock
  on steel is really nearer 0.2–0.4, and a ricochet you cannot see is not worth having). Normal
  impulse only — frictionless, so a rock sliding along a wall keeps sliding — plus a resting
  threshold, below which the bounce is dropped, because otherwise a rock on the deck buzzes forever.
  The exchange is `+J` to the rock and `−J` to the ship, so it conserves momentum *by construction*
  and needs no ledger term at all, unlike the debug engine.

  ⚠️ **Do H2a first: move the rock's momentum into the world frame.** It is in the *vessel* frame
  today, which was right while nothing exchanged. The moment something does, an impulse on the ship
  changes the frame every other rock's velocity is measured against, and the reduced-mass term goes
  missing silently. In the world frame there is no pseudo-force at all: a free rock has constant
  momentum, the plating is an ordinary force where it reaches, and the astern drift falls out of
  position being relative. It should be bit-identical to H1, which is the check. It lands and stays landed, and
  `momentumBalance` stays zero because the ship gets what the rock loses.
- **H3. The extractor** — ✅ **BUILT 2026-08-05.** 5×5 permeable plate, leeching mass off the rocks
  lying on it into the existing refining stages. **The miner is deleted.** See §5i.
- **H4. The rock field** — ✅ **BUILT 2026-08-05.** A starting world scatters rocks through the space
  around the vessel, for the player to find and line a plate up under. See [RockField].

  **This is the whole increment, and the narrowing is deliberate.** What the extractor needed was not
  a capture mechanism but *something to capture*: `F6` was a debug key standing in for a world with
  ore in it, and a key is a poor substitute for a place. Twelve rocks of three sizes, rejection-sampled
  clear of the vessel and of each other, seeded so that two starter vessels are the same world — every
  determinism check in the suite builds two and compares them.

  They are **baseline mass, not captured mass**: a rock that was here when the world was made did not
  arrive from outside it, so handing them to the constructor puts them in `baselineRockGrams` and
  `baselineJoules` and both ledgers read zero on tick one. Dropping the same rocks in through the edit
  would be a world that began by admitting twelve rocks it had had all along.

  No drift, no despawn, no replenishment, and no hold. Rates and lifetimes are a better question to
  ask of a world that already has rocks in it, and the stranded tail of §5i is gameplay.

  ⚠️ `starterVessel` takes a **count**, so a fixture that plants and weighs its own rocks says
  `rocks = 0` and gets an empty sky — as does `new 0` in the harness. A test that inherited a field it
  never mentioned would be measuring something it did not choose.
- **H5. Pressure on rocks** — the permeable coupling above. Last, so it can be cut.

### Editor tools (2026-08-05)

Not an increment — the affordances needed to design a vessel by hand rather than in `StarterVessel`.

- ✅ **Aimed delete.** A tile holds a bridge, a pipe, a rail and a machine at once, and the only way
  to reach the track under a smelter was to click repeatedly. `Edit.Remove` takes a `DeleteLayer`;
  `TOP` is the old blind one-layer-per-click and is still the default, so nothing that predates it
  changed meaning.
- ✅ **Right-drag and WASD pan.** Deleting used to own the right button. `W` moved off the tool
  toggle, which cycles on `Q`; the debug engine keeps the arrows.
- ✅ **The debug bellows** (`Edit.Inject`) — 1 kg of room-temperature air a tick into a permeable
  tile, held. It mints matter and **admits it**: `atmosphere + vented − injected == baseline`, booked
  exactly as `debugImpulseX` books the debug engine, and it dies the same provable death when air
  comes from a tank instead. ⚠️ The air *energy* identity carries a term the mass one does not —
  `solidToAirJoules` — and leaving it out reads a warm room as a leak.
- 📋 **The grid fits the vessel** — scoped, not built. See `apps/outofspace/PLAN_dynamic_grid.md`.
  ~3.5 days. The box covers built tiles only — rocks live outside the grid by design, and
  `overlapsHull` says so.

---

## 5g. Freefall, and a constant that was an off switch (2026-08-05)

**The deck plating is gone.** A vessel has no gravity of its own; "down" is something it earns by
burning and loses the moment the engine stops. That is where §1 and §3 were always pointing —
"parameterised, acceleration-derived later" — and increment G is what made the second half available.

The field stayed rather than becoming a constant zero. `VesselState.gravity` defaults to `FREEFALL`,
and `PLATING_ONE_G` is kept as a **value** that a fixture sets when it means to exercise buoyancy or
settling. §5e's lesson forbids the alternative: a term only ever run at one value has not been run,
and zero is the worst value to be stuck at, because every gravity-scaled quantity goes identically to
zero and a whole class of bug stops being merely unlikely and becomes invisible. Nine tests were
silently inheriting plating and now say so, which is an improvement on not knowing.

### ⚠️ What it uncovered: gravity below 0.3 g did nothing at all

Sweeping the plume lean against gravity, rather than theorising about it:

```
  g     1.0   0.5   0.45  0.4   0.3   0.25  0.2   0.1   0.02  0.0
  lean  0%    12%   12%   12%   87%   31%   31%   31%   31%   31%
              └─ bit-identical ─┘      └──── bit-identical ────┘
```

Plateaus of **bit-identical** results — the same tell as §5e, and the same cause one line further
down. `pull` was a *rounded* gravity multiply followed immediately by a **truncating** integer divide
by the settling rate, so a quantity needed `q × g ≥ 4` to survive. Everything below about 0.3 g was
zero gravity. The debug engine was worth 0.02 g, so thrust-derived gravity would have moved the ship
and left the atmosphere inside it untouched — implemented, and inert.

The whole scaling is now one rounded operation, `q × g × num / (LIMIT × den)`. The general form is
worth having: **rounding correctly is a property of the chain, not of an expression.** A rounded
multiply followed by a truncating divide is a truncating chain.

The debug engine is 0.25 g, because with the plating gone it is the only gravity a vessel has.

### ⚠️ Parked: the plume leans, and `BreachSymmetryTest` is `@Ignore`d

Taking the double-damping off roughly **doubles** buoyancy on the small quantities a plume is made
of, and `pull` predicts what happens — "a strong pull overshoots and the layer bounces". The 1 g
symmetry cases went to 9–12% against a 5% tolerance. The scaling function is provably antisymmetric
in isolation (`−9 → −2`, `+9 → +2`), so this is not a new asymmetry; it is the settling rate now
being applied once instead of one-and-a-bit times, and wanting retuning. Two denominators were tried
and neither was the answer.

Separately, and unrelated to any of it: a breach **in freefall** leans 7–18% with buoyancy not even
called, so there is an asymmetry in the pressure/advection path that gravity was masking. Recorded by
a test rather than fixed.

Both are parked deliberately. **The gameplay loop comes first**; this is a fluid-tuning session and
it should be one, not a detour inside an increment about rocks.

---

## 5h. Collision, and two things that balanced perfectly while being wrong (2026-08-05)

Increment H2, in two commits. **H2a** moved a rock's momentum into the world frame, which the plan
insisted on first and was right to: the position stays on the grid, because a position means *which
wall*, and the momentum must not, because the vessel's frame accelerates and a reduced-mass term
computed against a moving ruler goes missing without anything failing. What it bought immediately was
sharper tests — the astern drift is now the ship's own travel *exactly*, same number, same tick,
opposite sign, where before it was a pseudo-force checked to within a few per cent.

**H2b/H2c** are the sweep and the exchange. Sub-stepped at half a tile so a fast approach cannot step
over a bulkhead; the contact normal recovered by asking *would x alone have hit? would y alone?*,
which gives the corner an honest answer rather than a preference; `e = 1/2`, frictionless, `+J` to the
rock and `−J` to the ship.

Both findings below had the same shape, and it is worth naming: **a conserved quantity that is exactly
zero is not evidence that the model is right.** Both of these balanced perfectly the entire time.

### ⚠️ A rock lying on the floor was a thruster

Charge the ship for the *contact* and not for the *plating* and you get a momentum pump you could fly
on: the field pushes a resting rock down for free, the deck pushes it back up with a reaction, and the
ship climbs forever. `momentumBalance` stays at zero throughout, because the free half never enters
the ledger at all — so the instrument that exists to catch exactly this cannot see it.

The rule that fixes it is worth stating generally: **a field the vessel makes is a force the vessel
exerts.** `rockImpulse` is therefore everything the ship hands a rock, by any means, and the ship pays
for all of it. Zero under freefall, which is every ship — it is the 1 g fixtures and H4's capture that
would have found out, later and less comfortably.

### ⚠️ The resting threshold is `a / e`, not `a`

A landed rock has to stop bouncing or it buzzes on the deck forever, and the threshold below which
the bounce is dropped is *derived*: a bounce is worth having when the rock actually leaves, it departs
at `e·v`, gravity pulls it back at `a` per tick, so it is airborne for at least one tick only if
`v > a / e`. Set at `a` — the speed a resting rock *arrives* at, which is the plausible-looking wrong
answer — the rock settles into a **perfect limit cycle**: two velocities, two heights a third of a
tile apart, alternating forever, with every conserved quantity exactly right. Nothing but printing the
position over sixty ticks would have found it, which is what found it.

The general form is §5e's and §5g's, one more time: the interesting bugs here are all in *chains* and
*factors*, not in expressions, and the instrument that finds them is a column of numbers over time.

### What to go and look at

`agent-scripts/collision.txt`, and `F6` in the running game. Two scenes: a rock dropped over the deck
with the plating on, which falls, bounces and **stops**, in the same place forty ticks later; and the
game's actual regime — plating off, the ship burning into a rock hanging below the keel, which is
knocked clear while the ship visibly recoils. `momentumBalance` is zero in every frame of both.

---

## 5i. The extractor, and a stand-in that removed itself (2026-08-05)

**The miner is deleted.** Ore is no longer created; it is taken off a rock, and the world's ore
ledger is now hinged to its rock ledger by a single shared term. `minedGrams` is `extractedGrams`,
and it appears in both identities at once:

```
massBalance   extracted == aboard + vented
rockBalance   rockGrams == baselineRockGrams + captured − extracted
```

Add them and everything but the baseline and the capture cancels, which is what makes the pair a
proof rather than two hopeful sums: mass arrives from outside, sits in a rock, and leaves the rock
only by becoming ore, so a gram invented in the crossing has nowhere to hide. The miner's problem was
never that it was fake — see §5f — it was that it minted mass with no term admitting it.

### The two choices that carry the machine

**It is permeable**, and that is not a detail. A deck machine is solid: `StructureMap` marks its
tiles `Machine`, air cannot be in them, and `overlapsHull` bounces a rock off them. An impermeable
extractor is therefore a *wall a rock can never get on top of* — it could not do the one thing it
exists to do. So `MachineKind.isPermeable` skips it in the flood fill entirely and the tile is
whatever the fill would have made it. Nothing downstream needed a case: air, heat and rock contact
all read that map and all three then treat the plate as the empty floor it is.

**It eats whole cells.** A rock's mass is `filled × gramsPerTile` and nothing else, so there is no
such thing as a rock that is 40% eaten; the only exact way to take mass off one is to remove a cell.
That meets a rate in grams by giving the machine an `input` buffer holding **one 3 kg cell**, which
it grinds into its output at `gramsPerTick` exactly as a processor works a lump. So the extractor
reads like every other machine in the game — input, rate, output — the rock is never half-eaten, and
the belt still fills in a smooth trickle rather than in 3 kg lurches. On screen you get both halves:
the rock visibly pits away a cell at a time while the line runs steadily.

⚠️ **Three things leave a rock in the same instant and all three have to be booked.** Only the mass
is obvious. The heat goes into the casing and is a **transfer**, so it is `absorb`ed rather than
`heat`ed — putting it through the generated-joules term would mint energy that was already in the
world. And the momentum goes to the *ship*, because the ore is aboard now and moving with it, which
means the ship hands the rock the negative of it through `rockImpulse`. Each share is taken as the
remainder of one truncating divide (`whole − whole × (n−1) / n`) rather than as a multiply of its
own, so the two halves add back to the original exactly — §5g's lesson applied before it could bite.

### What it uncovered: nothing holds a rock down

A vessel has no gravity (§5g), so a rock lying on a plate is not resting on anything. It keeps its
world-frame momentum while the ship has a residual velocity of a couple of thousandths of a tile a
tick — its own air and machinery are enough — and the plate is exactly a rock across. Measured: a
63 kg rock is eaten to about 9 kg by tick 400 and stays there. The far column of cells has slid past
the plate's edge and is out of reach for good.

That is **gameplay, not a defect**: keeping a rock on the plate is something the player does, and
holding one still is what H4's hold is for. Every ledger is zero the whole time; the stranded 9 kg is
still counted as rock.

### The wrong turn, which is the one worth writing down

The first version of this made the extractor **grip** its rock — match its velocity to the ship's,
booked through `rockImpulse` — because without it the starter vessel ate 12 kg and stopped. That is
functionality nobody asked for, and it was cut. It then turned out the 12 kg stall had a *completely
different cause*: the starter vessel's belt started one tile past the new 5-wide plate's output port,
so the extractor filled its buffer and stopped for want of anywhere to put anything. The grip made a
symptom go away and the symptom was two layers above the cause — §5e's exact mistake, in a plan that
has the warning written into it. **A fix that works is not evidence of a diagnosis that is right.**

### What is not pinned any more

`ProcessorChainTest` used to assert exact purities. It has read `75/100/100`, then `66/88/100`, and
now wobbles between 66 and 64 on the first stage depending where in a bite it is sampled — ore is
apportioned once per 3 kg cell where the miner apportioned it afresh every tick, so what is *standing
in* a buffer moves about even though what is separated does not. Every one of those figures was a
constant re-pinned by whatever changed upstream, which makes the test a record of its own history. It
now asserts the claim: each stage cleaner than the last, the far end pure.

### What to go and look at

`agent-scripts/extractor.txt`, and `F6` in the running game. The starting world is now a complete,
fully wired refinery that produces **nothing**, because the thing at the head of the line no longer
invents ore — that is the increment in one screen. Drop a rock on the plate and the whole line comes
alive; leave it and the rock pits away until what is left of it has slid off the plate and the line
goes quiet with a sliver of rock stranded an inch outside the machine that wanted it.

---

## 6. Open questions

1. **What is outside the hull?** Vacuum as a special tile, or genuinely absent tiles? This decides
   what a breach means, and the atmosphere solver wants to know early. Bundled with defining
   structure at the start of Phase 4.
2. **How does a pump differ from a compressor?** Both push fluid down the same pipe, but a liquid
   pump works against gravitational head while a gas compressor works against pressure. That
   difference is the interesting part of fluid machinery and it wants designing alongside the
   atmosphere model in Phase 4, not before — noted here so it does not get quietly forgotten.
   *Partly answered by the pump built in increment F: it works against pressure, stalls at a ratio,
   and does not model compression heating — which is exactly the term that would make it a
   compressor. Reopens with §5d increment J.*
3. **Should the grammar get a comparison?** `WHEN RED > 900` would buy digital control — latches,
   hysteresis, "top up only when nearly empty" — alongside the proportional behaviour it already
   has. It is the obvious next expressive step, and the obvious risk is turning a small language
   into a big one.
4. **Off-axis gravity has never been run.** `applyBuoyancy` is the one function permitted to assume
   gravity is axis-aligned, and until §5d increment G nothing ever gave it a reason not to be.
   Diagonal thrust is the first thing that will, and it is scheduled rather than assumed to work.
5. ~~**Miners are a stand-in** and should not grow depth.~~ *Answered by increment H3: the miner is
   gone, and ore now comes off a rock that had to arrive from outside. What is still a stand-in is
   how the rock gets here — `F6` — which is H4's question, not a machine to elaborate now.*

*Settled:* the grid is a **fixed generous bound** with the vessel built inside it (much simpler for
the Phase 4 atmosphere solver, and it still allows expansion); belts have **slots**; solids and
fluids are the two networks; packets are 1 kg; quantity is mass with a per-phase route to volume.
