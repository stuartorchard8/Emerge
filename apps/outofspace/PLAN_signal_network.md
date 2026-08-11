# Signal networks: from named channels to wires on a layer

**Status:** ✅ **BUILT 2026-08-11.** All six increments landed. Written and implemented the same day.

> **What actually happened, against the plan.** C and D were **implemented together** rather than in
> sequence, and F went in with them. The reason: C's end state leaves `Sensor` driving nothing while
> `Channel` is still alive for the gauge, so the tree spends an increment holding two spellings of the
> same idea — churn in `Save`, the HUD and the renderer, paid twice. Splitting them buys a
> demonstrable intermediate state, which is worth having when the increments are being handed out one
> at a time and is worth nothing when one person does all three. **A and B stayed separate and E
> stayed last**, which is where the value of the split actually was.
>
> Two things the plan got wrong, both worth reading before the next one:
>
> - **§2a said `Trigger(channel, weight)` → `Trigger(source, weight)` and stopped there.** It did not
>   notice that `Signals.FULL` is used across the codebase as a plain permille denominator, by code
>   with no opinion about wiring at all. The class it lived on was being deleted. `FULL` moved to
>   `SignalField` and every call site changed — mechanical, but it is the kind of thing that reads as
>   a surprise mid-increment.
> - **§2b's "one input at the anchor tile" is sharper in practice than it sounds on paper.** A run
>   that stops one tile short of a machine reaches it not at all, and looks exactly like a broken
>   machine. It cost a debugging cycle in `agent-scripts/signal.txt` and it will cost players one
>   too. That is arguably correct — it is what "you can see the connection" means — but it wants a
>   HUD affordance eventually, and the wiring panel now says `WIRE reads 0 — no wire under this tile`
>   for exactly this reason.
>
> Everything else held, including the migration property in §2a, which is asserted directly in
> `SignalWiringTest` and did what it claimed.

**Goal.** Replace the six global named channels with a **signal conduit layer** — physical wire the
player lays, exactly as they lay rail and pipe. A transmitter puts a 0–100% value on the run it sits
on; a receiver reads the run *it* sits on. Two machines share a signal only if a wire actually joins
them.

**Why.** ONI's automation is legible because the wire is the thing you can see. Today's `Channel` is a
global variable with a colour for a name: a sensor on the bow silently drives a machine at the stern,
nothing on screen says so, and six colours is a hard ceiling on how many independent things a vessel
can do. Wire has none of those properties. It also makes the eventual logic machines (multiply,
combine, invert) *obvious* — they are things you put in the line — rather than an extra grammar
bolted onto a name.

---

## 1. The reframing that makes this small

**A channel already is a network.** Today there are exactly six of them, they are named by colour,
and every machine is connected to all six. After this change there are as many networks as the player
has laid separate runs of wire, and they are defined by geometry instead of by name.

Everything else stays:

- Values remain **permille integers, 0..1000**. `Signals.FULL` keeps its meaning.
- A network's value is still the **max** of what is driving it. The reason given in `Signals.kt`
  survives intact — max is associative and order-independent, so the result cannot depend on grid
  iteration order, and "any of these is asking for it" remains the useful reading.
- `Wiring` keeps its shape: a machine's activation is still `Σ(signal × weight)` clamped to ±1000,
  still proportional rather than a switch, still per-`Action`.
- The **sign stays in the weight, not on the wire.** Wires carry 0..1000. A machine that should stop
  when a signal arrives uses a negative weight, exactly as it does today.

So the transition is not a rewrite of the wiring model. It is a change to *where a term gets its
number from* — and one new conduit layer to get it from.

## 2. The decisions

### 2a. A term's source is `Always` or `Wire` — not a channel

`Trigger(channel, weightPermille)` becomes `Trigger(source, weightPermille)` with

```kotlin
enum class SignalSource { Always, Wire }
```

`Always` is today's constant: it reads 1000 forever, needs no wire, and is what a freshly placed
machine is wired to — so placing a machine still just works and wiring is still something you *add*.
`Wire` reads the signal network on the machine's own anchor tile.

**This is what keeps the migration behaviour-preserving.** The starter vessel's extractor is wired
`Always@1000 + Red@−1000`; it becomes `Always@1000 + Wire@−1000`. With no wire laid, a `Wire` term
reads 0, which is precisely what `Red` read when nothing was emitting on it. Every existing vessel
keeps working the day the change lands, and the wire is something the player adds afterwards.

### 2b. One wire input per machine, at its anchor tile

A machine reads the signal network on the tile it is stored at. Conduits already share tiles with the
deck — this is the same fact that lets a rail run underneath a smelter to reach its port — so the
wire goes *under* the building and no new spatial concept is needed.

One input, not four sides and not one per footprint tile. A 1-tile machine (airlock, pump, vent,
valve) has exactly one candidate tile, so the common case is unambiguous by construction. Multiple
independent inputs are what **logic machines** are for, and they are out of scope here (§6).

⛔ **Do not** add signal ports to `Port.kt`. Ports exist because material has to enter and leave a
machine at a particular place; a signal does not move mass and needs no such thing. Adding them would
mean a port-selector in every `Trigger` and a port-routing UI, for no behaviour anyone has asked for.

### 2c. A network is instantaneous and undirected

One connected component of wire = one value, everywhere on it, in the tick it is set. No travel time,
no per-tile propagation, no direction.

⛔ **Do not reuse `FlowGraph`.** Rail flow is a *one-way permission graph* with leading routes and
merge cursors, because packets are physical objects that must not be duplicated and must take turns.
A signal is a reading, it is duplicated freely by definition, and nothing takes turns. It is a plain
undirected connected-components sweep and must stay one.

⛔ **Do not** add a propagation delay "for realism". ONI has a one-tick building delay; it exists to
make certain oscillator circuits possible and it costs a great deal of confusion. If it is ever
wanted it is a separate increment with its own justification.

### 2d. Network identity must be deterministic

Components are numbered by their **lowest tile index**, and the list is ordered by that number. Not
by discovery order. The reason is the same one the thermal ledger's fixed traversal order has: a
save/load must not renumber networks, and a numbering that depended on iteration order would make a
world disagree with itself across a round trip.

---

## 3. The increments

Each one leaves the build green, the suite passing and the game playable. **Acceptance tests are
written first, by Claude, and fail before the work starts** — with a `TODO()` stub where the new
function goes, so the module still compiles.

> ⚠️ For whoever implements this: if an assertion in one of these tests looks wrong, **say so and
> stop**. Do not build machinery to satisfy it. A test here encodes a decision from §2; if the test
> and the decision disagree, the test is the thing that is broken.

### Increment A — wire that connects to nothing ✅

`MachineKind.Wire("WIRE", Conduit.Signal)`. That is very nearly the whole of it: `Edit.Lay`/`Edit.Cut`
are already generic over `Conduit`, the drag gesture already reads `brush.conduit`, `Save` already
keys segment lines by conduit name, and `Conduits` already carries four layers with the signal one
sitting empty. Wire is copper (`Conduit.material` already says so), so it joins the thermal ledger for
free.

Rendering: draw the signal layer under the pipe layer, thinner. Colour by value comes in C.

**Nothing reads it. No signal semantics whatsoever.**

Acceptance (`SignalWireTest`):
- A dragged run joins tile-to-tile; two runs that merely touch are **not** joined (explicit links, the
  rail rule).
- Save/load round-trips a laid network, links included.
- Wire does not displace air and does not change `StructureMap` — assert against the same world built
  without it rather than against remembered figures.
- The existing suite is untouched and green.

### Increment B — networks, derived and read by nobody ✅

`SignalNetworks.derive(grid, conduits): SignalNetworks` — connected components over `Conduit.Signal`
links, per-tile network id, `-1` where no wire. A pure function beside `StructureMap`, derived every
tick for the same reason that one is (cheap; caching plus invalidation is a bug class for no gain).

Acceptance (`SignalNetworkTest`):
- Two disjoint runs are two networks; joining them makes one; cutting one makes two.
- **Touching is not joining** — the case most likely to be got wrong.
- Ids are stable under a save/load round trip, and are assigned by lowest tile index. Derive the
  expected id from the fixture's own geometry, never a literal.
- A single isolated wire tile is a network of one.

### Increment C — transmitters, and a wire you can see working ✅ *(landed with D)*

`SignalField`: one value per network, `max` over its transmitters. First transmitter is the **existing
`Sensor`**, which stops emitting on a colour and starts emitting onto the network under its own tile.
A like-for-like swap needing no new input plumbing.

Renderer: **tint the wire by its value.** This is the whole readability argument for the feature and
it should land with the first transmitter, not after.

Receivers still read channels. Sensors therefore drive nothing this increment — that is expected and
temporary, and it is why C and D are separated: C proves values get onto wire, D proves they come off.

Acceptance (`SignalFieldTest`):
- A sensor puts its `fullness` reading onto its own network and onto no other.
- Two sensors on one network yield the max — computed in the test from the two fixture readings, not
  pinned.
- A sensor on no wire is not an error and produces nothing.

### Increment D — receivers, and the end of `Channel` in wiring ✅

The load-bearing one.

- `Trigger.channel` → `Trigger.source: SignalSource` (§2a).
- `Wiring.activation(action, wireValue: Int)` — the global `Signals` argument goes away.
- `VesselState.signals` becomes the `SignalField`.
- **Save version 11**, with a migration: a v≤10 `Trigger` on `ALWAYS` becomes `Always`; on any colour
  becomes `Wire`. Lossy on purpose — six colours cannot survive into a world with no colours — and
  behaviour-preserving in the only case that matters, because an unwired `Wire` term reads 0 exactly
  as an unemitted channel did.
- `StarterVessel`'s `STOP_WHEN_RED` becomes `Always@1000 + Wire@−1000`, **and the starter vessel gains
  an actual wire** from its sensor to its extractor, so the shipped world demonstrates the feature.
- HUD wiring panel: the source cycles `Always`/`Wire` instead of cycling colours.

Acceptance (`SignalWiringTest`):
- A sensor wired by an actual run throttles the machine at the far end; **cut the wire and it stops
  throttling.** That single pair is the point of the entire plan.
- A machine with no wire under it and an `Always@1000 + Wire@−1000` wiring runs at full — the
  migration-safety property, asserted directly.
- A v10 save loads, and the machine it describes has the same activation it had before the change.
  Compare against a world built directly in the new model; do not pin the number.
- Negative weights still invert, fractional weights still throttle proportionally.

### Increment E — the key input machine ✅

Only now does the player get a hand on the wire. A transmitter bound to one key: 1000 while held, 0
otherwise.

This is where the **flight-vs-build mode** lands, because it is the first time a keypress must reach
the sim rather than the camera or the build brush. `OutofspaceInput` carries only `edits` today —
there is no held-key state reaching the reducer at all — so this increment is as much input plumbing
as it is a machine.

Deliberately last of the core four: A–D are a refactor with a demonstrable end state, and this is new
gameplay. Keep them separable.

Acceptance (`SignalInputTest`): held key → 1000 on that machine's network, released → 0; a key bound
to one machine does not drive another; the value survives the same tick it is pressed in.

### Increment F — delete `Channel` ✅ *(landed with D)*

- A gauge becomes a transmitter like any other: `Segment.channel: Channel?` → `Segment.isGauge:
  Boolean`, and it puts `lastPurity` onto the **signal network under its own tile** (layers share
  tiles, so a gauge on rail and a wire on signal coexist on one tile without ceremony).
- `Edit.SetChannel`, `Channel`, and the HUD's channel-readout panel all go.
- Save version 12.

Do not attempt this before D. A dead enum is harmless; a half-migrated one is not.

---

## 4. What this does *not* change

Worth stating, because each is a plausible-looking place to wander into:

- **Rail, pipe, `FlowGraph`, `FlowCursors`** — untouched. See §2c.
- **The `Power` conduit** — still reserved and still empty. Signal and power are different problems
  and bundling them would make both harder to land.
- **`Action`** — still just `Run`. Multiple actions per machine is a separate idea.
- **Air, heat, structure** — wire is a fitting, like pipe. It shares its tile, displaces nothing, and
  contributes only its copper to the thermal ledger.
- **The airlock** — it reads `Action.Run` activation and does not care where the number came from. It
  should keep working across every increment with no change, and a test should say so.

## 5. Risks, in the order they are likely to bite

1. **The save migration is where worlds get silently lost.** Version bump plus a fixture file that is
   actually loaded by a test. Not a code path anyone should trust by inspection.
2. **Determinism.** Network numbering (§2d) feeds machine activation, which feeds the sim. A
   nondeterministic id is a desync and a save that does not reproduce.
3. **Derivation order in the tick.** Signal networks come from conduits, which player edits change, so
   they must be derived *after* edits and *before* machine activation — the slot `Signals.build`
   occupies today. Note that slot already sits before `StructureMap.derive`, because the airlock made
   structure depend on signals; that ordering must survive.
4. **Discoverability.** A wire that is invisible until you know to look for it is worse than a global
   channel. The value tint in Increment C is not polish.

## 6. Deferred: the logic machines

Explicitly out of scope, and the reason the model above is shaped the way it is. Once a value is a
thing that travels along a run, each of these is a machine with an input side and an output side:
multiply by a constant, invert, max/min of two inputs, threshold to 0-or-1000, latch. None of them
need anything this plan does not build, which is the test that the plan is the right shape.

The one thing to keep an eye on: a machine with **two** inputs needs a way to say which side is which,
and that is the point at which §2b's "one input at the anchor tile" has to grow. Growing it for logic
machines is fine. Growing it now, for machines that do not exist, is not.


---

## 7. What is actually there, 2026-08-11

**New types.** `SignalNetworks` (connected components of the signal layer, numbered by lowest tile),
`SignalField` (one value per network, max-wins, and the home of `FULL`), `SignalSource`
(`Always`/`Wire`), `InputKey`, `KeyInput`, `Mode` (`Build`/`Flight`).

**Gone.** `Channel`, `Signals`, `Edit.SetChannel`, `Sensor.channel`, `Segment.channel` (now
`Segment.isGauge`), `Controller.cycleSensorChannel` / `cycleTriggerChannel`.

**Save version 11**, migrating v≤10: `ALWAYS` → `Always`, any colour → `Wire`, `channel=` on a
segment → `gauge=1`, `channel=` on a sensor read and discarded.

**Tests.** `SignalWireTest` (6), `SignalNetworkTest` (8), `SignalWiringTest` (7), `SignalInputTest`
(8). The two that carry the argument are `cutting the wire stops the throttling` and `holding a key
vents the ship and drives it the other way` — the second is the flight loop closing end to end.

**Harness.** `wire <x> <y> <ALWAYS|WIRE> <permille>`, `bind <x> <y> <key>`, `hold <key>…`, `release`,
and two new readings, `impulseX`/`impulseY`. Scripted in `agent-scripts/signal.txt`.

**The starter vessel ships a wire.** Its demonstration extractor is `ALWAYS − WIRE` with an actual
run from the sensor beneath the tank to the extractor's anchor tile, so the feature is on screen in
the world the game opens with.

### Loose ends, in the order they are likely to matter

1. **The seat is still a button.** Stu's original ask was a *control seat* routing input into the
   network; what exists is one button per key, which is the same thing decomposed. Whether a seat is
   now wanted at all — a machine that emits several keys onto several wires — is an open design
   question, not a missing piece.
2. **`trend` in the agent harness throws** (`Format specifier '%10s'`) on any world, including a
   clean one. **Pre-existing and unrelated to this work**; found while scripting, left alone.
3. **Rock density is still open** — untouched by this, and still the thing standing between the
   flight loop and it feeling like flying.
4. **§6's logic machines** remain deferred, and §2b's one-input rule is the thing that has to grow
   when they arrive.
