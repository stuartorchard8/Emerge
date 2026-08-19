# Self-building rails

Status: **increments 1–4 built and green** (2026-08-19). Creative mode is still on by default —
flipping `VesselState.creative` is a one-line change and Stu's call.

Replace creative-mode placement with a closed construction loop. ONI does this with agents that
walk to a ghost carrying materials; we do it with the rail network itself. No pathfinding, no
gravity direction, no duplicants.

> The player draws a rail. That creates **ghosts**. A ghost is track with a representation but no
> mass. It gives itself an input port, refuses anything it cannot be built from, and skims what it
> needs off the material passing over it. When it has its full bill it is simply a rail. Mark a rail
> for deconstruction and it grows an output port, dribbles its own metal back onto the network, and
> when it holds nothing — in its structure *or* on its track — it ceases to be.

Matter and energy are conserved throughout. A rail segment can be made to walk across the grid by
drawing ghosts ahead of it and deconstructing behind it, with the same atoms arriving at the far end.

## The inversion at the centre

`Conduits.reconciled()` currently enforces **segment exists ⟺ segment has its metal**. Laying
conjures a full `conduitBillOfMaterials` at ambient via `TrackLayers.lay`, and `Work.built` books
that mass and heat as *arriving from off-world*. The class doc states it outright: "a segment's
existence and a segment's matter are one fact at one address."

A ghost is the deliberate breaking of that identity. Everything else here is additive on top.

Consequences:

- `reconciled()` stops laying and stops clearing.
- `TrackLayers.lay` becomes a **target** (a bill to reach), not an act.
- **`Work.built` loses its conduit term.** The rail network's mass and energy become closed. This
  feature makes the ledger *stricter*, not looser — which is the strongest evidence the design is
  right, and the reason increment 1 goes first.

## Decisions taken (Stu, 2026-08-18)

1. **Mixed packets.** A packet is admitted if it is **≥95% target species by mass**. It is admitted
   *whole* — the extra ≤5% is baked into the tile's structure composition. `StuffLayer` already
   stores arbitrary species per tile, so this costs nothing. No downside modelled today.
2. **Form is ignored for construction.** Powdered iron builds a rail exactly as an ingot does. (Stu's
   attachment to the smelter is wearing off in favour of the thermal decomposer; forcing a form here
   would be propping up a machine that may not survive.)
3. **Bricking is a known limitation.** A network saturated with non-iron packets and no source of
   iron has no way out, and a player who deconstructs their last rail is stuck. Accepted, not solved.
   Stu will stress-test once built and come back with suggestions. **Do not invent a rescue
   mechanism.**
4. **`deconstructing` is a real bit on `Segment`.** Construction and deconstruction will be part of
   every building's lifecycle in the end state, so this earns its place even though increment 1 does
   not read it. Note this partly reverses the recent "Segment is conduit + links, nothing else"
   cleanup — deliberately.
5. **A ghost marked for deconstruction gets no special treatment.** It is exactly a
   partially-deconstructed rail: it dumps what it has and ceases to be.
6. **A deconstructing rail still carries traffic.** It is still track. Clever players may exploit
   this; executing it is messy enough (each segment gets its own output and its own contribution to
   the flow graph) that it is not worth pre-empting.
7. **Rails and pipes stop being mutually exclusive.** See "Pipes" below. This reverses `b29ae1e6`.

## Why ghost-ness needs no new state

`Segment` holds conduit + links. Ghost-ness is `tracks[Rail]` at that tile falling short of the bill
— derived, not stored. It saves for free with the existing `TrackLayers` serialisation and needs no
save version bump. Only the `deconstructing` bit (decision 4) is new on disk.

**Completeness is per-species, not total mass.** With ≤5% junk baked in, a tile can exceed the bill's
total mass while still short of its iron. The test is: every species in the bill is present at or
above its billed mass.

## Flow

A ghost is a **sink**. A deconstructing rail is a **source**. `FlowGraph` already decides flow from
sources and sinks rather than from segment direction, so routing needs no new concept: material is
pulled from deconstructing rails toward ghosts and toward whatever else is drawing.

**Ghosts fill nearest-first, but do not starve the far end.** Valid material traverses a ghost and is
skimmed on the way past, so a long drawn run builds from the source outward and then keeps feeding.
Invalid material is refused entry entirely — that refusal is the whole anti-exploit, because a ghost
that let anything through would be a free rail.

Absorption is proportional across the packet's species, so the junk fraction is skimmed at the same
rate as the target. A ghost takes only what it still needs; the remainder rides on, over what is now
real track.

**Heat is carried, not conjured.** The arriving packet's energy lands on the structure layer. A rail
built from cold iron is a cold rail. `SolidHeat` already skips nodes with `capacity <= 0`, so a
zero-mass ghost is thermally inert with no new guard. `Flight` already weighs track off the layer,
so a ghost line correctly contributes nothing to the ship's mass.

## Ports — resolved: machines lock the track beneath them

A machine does **not** own the rail at its port tile. Nothing is auto-laid; the player draws every
length of track, so the loop stays closed. Instead, **track under a deck machine's port is locked
from deconstruction while that machine stands.** Remove the machine and the track is reclaimable
again.

This was chosen over the three-way port priority it replaces, and over having machines own their
track. It does not merely pick a winner among the priority rules — it deletes two of them:

- A locked rail can never be *deconstructing*, so "ghost output beats a real output" and "a real
  input beats a ghost output" describe states that cannot occur.
- The only surviving case is a **ghost input on the same tile as a machine input**, and it resolves
  itself: the ghost wins, and the machine is simply disconnected until its feed finishes building.
  One rule, with a consequence the player can see.

It also gives a clean order of operations for free — track before the machine, machine off before the
track comes back — and reduces bricking, since a player cannot strand a machine by deconstructing
what feeds it.

`Port`'s existing rule still holds and is what makes a ghost's port expressible at all: **two ports
may share a tile only if the conduit differs.**

## Creative mode

Free construction does not go away; it stops being the only mode. A **creative** flag lets the
player shove mass and energy into the world by placing machines outright, exactly as today — the
world gains matter from off-world and `Work.built` books it, which is precisely the term increment 1
removes from the normal path.

- A **switch in the code** for now. Not a UI, not a save field.
- Expected to become a **world setting** further down the track, chosen at world creation.
- ⚠️ The ledger must stay honest in both modes. Creative placement is an *insertion* and has to be
  booked as one; it must never look like the world spontaneously gaining mass. The existing
  `Work.built` / `Work.scrapped` pair is the right home — keep it, and keep it off the normal path.

## Pipes, and why rails-first is a real runway

Plumbing cannot carry ingots, so a pipe ghost cannot be fed by pipes. It is fed by a **rail port on
its own tile** — legal precisely because rail and pipe are different conduits. The player runs
temporary track to build the plumbing, then deconstructs the track: the same walking-rail trick,
applied to a different layer.

This requires dropping rail/pipe exclusion (decision 7). The temporary rail cannot be removed
mid-build, so the completed pipe and the rail that built it necessarily coexist for at least a tick,
and forcing the rail into deconstruction on completion would be coercive. `Conduits.checkExclusion`
goes.

⚠️ That check was the game's namesake constraint — matter transport competing for floor space. Losing
it is a genuine loss of design intent, and it is worth deciding later whether something replaces it.
It is *not* in scope here.

Keeping one layer per conduit type rather than merging them is what makes this possible at all.

## Increments — all four built

**1. Break the identity ✅** `9da586d9`, `cfcbe610`, `2fea0f6b`. `reconciled` split into `swept`
(clears orphans) and `finished` (only a *stated* world says it). `trackstuff` added so a partly-built
segment survives a save. `VesselState.creative` added, on by default.

**2. Ghost port and admission ✅** `fbf4c89a`, `62647892`, `e1f0eb53`. A ghost is a sink without
owning a port; refuses anything under 95% of what it is made of; absorbs proportionally, junk and
all, onto the structure layer. `VesselState.builtMass` books the crossing from cargo to fabric.

**3. Deconstruction ✅** `4cb7424d`. `Segment.deconstructing`, saved as `scrapping=1`. Outside
creative, delete marks rather than removes. A marked rail is a source, carries traffic to the end,
and ceases to be when both its structure and its tile are empty. Track under a deck machine's port
is locked. **A rail walks**, pinned to the unit by `GhostTest`.

**4. Starter vessel and rendering ✅** `e8662a02`, `1e9e3035`. Half a tank of iron;
`baselineCargoMass`; ghosts fade up from a dim slate, marked track goes warm; `agent-scripts/ghosts.txt`.

Later, out of scope: pipes and wires by the same mechanism, then deck machines, then the fancier
things a walking rail makes possible.

## What building it turned up

**Three latent bugs the work exposed, all fixed:**

1. The grid remap copied a segment's *heat* by hand and let `with` re-derive its *mass* from the
   kind's bill. A length of track whose composition had been altered came back pristine one grid
   over and no ledger could see it. It copies the matter now.
2. `writeMixture` never wrote energy, though `readMixture` has always read it. Every gram of ore in
   a tank or on a belt came back from a save at whatever the reader defaulted to. Invisible while
   everything stored happened to be at ambient.
3. The mass ledger assumed everything solid aboard had been dug up. A ship that starts with a stock
   cannot say that — hence `baselineCargoMass`.

**One design collision, fixed:** a segment handing its metal back is always short of its bill, so it
read as a ghost and absorbed its own metal straight off the belt. Stable, stationary, and
indistinguishable from deconstruction doing nothing. Being told to go now overrides being short.

## ⛔ Open — for Stu

**Deconstruction deadlocks on an occupied tile.** A marked rail cannot hand its metal back while a
lump is standing on it, and if nothing downstream is drawing, the lump never leaves. Draw a run, let
the tank overfill it, change your mind, and the tiles sit marked for ever. Recorded by
`agent-scripts/ghosts.txt`, which asserts the jam rather than papering over it.

Same family as the accepted bricking limitation, but reachable by ordinary play rather than by
saturating a network with the wrong material. Not fixed, and deliberately so — the plan says not to
invent a rescue mechanism. Worth a decision.

**`massBalance` cannot see conjured fabric.** It counts cargo, and conduit structure is not cargo, so
a bug that minted metal directly onto the structure layer would not move it. `builtMass` is the only
witness. Worth knowing when reading a green ledger.

**The energy ledger still does not follow the mass.** The transport layer's energy was never in
`storedEnergy`, so heat riding in with absorbed material is not booked. Older than this work, and the
energy ledger is parked.

## Coordination

Increment 1 touches `Conduits`, `Save` and `OutofspaceSim`'s work ledger. Gauge and valve work was in
flight in `Save`, `Port` and `Segment` when this was written — it landed in `a52cd884`/`b5b0f3fd`
before any of the above.

---

# Increment 5 — deck machines build themselves

Scoped 2026-08-19, from Stu's design. Chosen ahead of pipes: a machine is the thing creative mode
was actually for, so it is the case that decides whether the loop can replace creative placement at
all. Pipes and wires stay where the plan left them.

The rails are the runway and almost everything below is the same mechanism at a different address.
What is new is that a machine *does something*, so it has a state a rail never had: **placed, and
not yet a machine.**

## Ghost-ness derives, again

`DeckArray.stuff` already holds each machine's casing as real matter, laid from
`tileBillOfMaterials` when it is placed. So a machine ghost is the same fact as a rail ghost — the
deck layer short of the bill — and needs **no new save state**. Only the deconstruction mark is new
on disk, exactly as `scrapping` was for a segment.

The bill is the whole footprint: `tileBillOfMaterials(kind)` once per covered tile. A 3×3 machine
costs nine tiles of casing and a 1×1 costs one, which is what it already weighs.

## The construction port sits on the centre tile

**One port, at local (0,0).** `localPorts` already speaks the machine's own frame at ±`reach`, so
this is one entry that needs no knowledge of how wide the machine is — and that is the whole reason
for choosing the centre. A port at an edge would have to be placed per kind, per footprint, and
would clash with the real ports of a machine that already uses that side.

For a **1×1** machine `reach` is 0, so the centre *is* where its input port already sits. There is
no clash to resolve because the construction port **overrides** it: while the machine is a ghost the
port at (0,0) accepts casing and nothing else. A machine with no ports at all — hull, airlock,
button — gains one for the first time in its life, which is what makes a hull buildable.

The port is on **`Conduit.Rail`** whatever the machine is for: casing is solid, so a pump and a vent
are both built by track, and the plumbing they exist to serve has nothing to do with it.

It is a port like any other, so `lockedByMachine` already applies: the rail under a ghost's
construction port cannot be deconstructed while the ghost stands.

## A ghost machine is inert and permeable — Stu, 2026-08-19

It does not run: it is skipped at the tick's single machine-dispatch site, so a smelter smelts
nothing until it is paid for. That is the anti-exploit, and it is the machine's version of "a ghost
refuses material it cannot be built from".

It is also **not there** as far as air is concerned. `StructureMap.derive` treats a ghost's
footprint as the empty floor it is, and placing one displaces no air. A frame with no metal in it
does not hold pressure.

⚠️ **Consequence, accepted:** outside creative you cannot pressurise a room until the *last* hull
tile of it finishes. A room under construction is open to space and everything that follows from
that. This is the honest reading of a massless ghost and it is not to be softened.

Its real ports are suppressed while it is a ghost — there is nothing to feed and nothing to collect.

## Filling: spread evenly over the footprint — Stu, 2026-08-19

Every absorbed packet is apportioned across all of the machine's tiles, so it grows uniformly and
each tile's heat capacity stays proportional to the metal actually in it. `holdsFullBill` asks the
whole footprint.

Rejected: filling the centre outward (a half-built machine with a lopsided mass and heat
distribution across its own footprint), and pooling on the centre tile (one tile briefly holding
nine tiles of iron, which every per-tile reader — `SolidHeat`, `Flight`, chemistry — would see and
believe).

Absorption is otherwise exactly `absorbIntoGhost`: admitted at ≥95% of the bill by mass, admitted
whole, junk baked in, proportional across species, heat carried not conjured, remainder rides on.

## Deconstruction — Stu's ordering

Mark a machine and it comes apart in a stated order, and the order is the design:

1. **Its output ports drain as normal.** It is still a machine's worth of product and it leaves the
   way it always did.
2. **Its input port becomes an output** and hands back whatever is sitting in the input buffer, onto
   the belt that fed it.
3. **Each of these modified ports vanishes once its own buffer is clear.** A port with nothing left
   behind it is not a port.
4. **Only when every other buffer is clear** does the centre port start dribbling the **casing**
   back onto the network — the mirror of construction, a packet at a time.
5. The machine ceases to be when its casing and its buffers are both empty.

The ordering is what stops a machine's contents being destroyed by its own demolition, and it is the
reason the casing goes last rather than first: casing is the only thing whose removal is
irreversible, so it is the last thing done.

⚠️ The rails' lesson applies unchanged: **nothing leaves the deck layer until the belt has taken
it** (`4e8d37a6`). Refused, the machine hands nothing back this tick and stays marked.

## The mark

`Segment` took a real `deconstructing` bit. A machine will not: `DeckMachine` is a sealed interface
with eighteen implementations and the bit would have to be threaded through every `copy`. It lives
instead as a set of marked **centre tiles** on the vessel, which is how machines are addressed
everywhere else.

⚠️ That is the parallel-array footgun `DeckArray`'s own doc warns about — a mark keyed by tile
outlives the machine that earned it. Clearing it belongs with `removeMachine` and with `DeckArray`'s
`-=`, and nowhere else.

## Increments

**5a. A bill is a bill.** Generalise `buildableFrom` and the completeness questions off `Conduit`
and onto a `Mixture` bill, so a machine and a length of track ask the same code. Shared plumbing,
no behaviour change, and it goes first only because it is genuinely shared and genuinely concrete.

**5b. A placed machine is a ghost.** Outside creative, `placeDeckBuilding` puts the machine down
with no casing, books nothing through `Work.built`, and displaces no air. Inert at the dispatch
site, absent from `StructureMap`, real ports suppressed. This is the identity break and it decides
the rest — a 3×3 smelter is the case to write first, not a hull.

**5c. The construction port.** Centre port on the rail layer, overriding a 1×1's input; admission at
95%; absorption spread over the footprint; and the moment it holds its full bill it is simply a
machine — ports back, runs, solid.

### What 5c turned up: an alloy is not one species

The rails never met this, because a rail is made of **iron** and nothing else. A hull is **steel** and
a smelter is **firebrick**, and the moment a bill names two species three things surface that the
single-species case hid:

1. **A machine cannot be built from one of its components.** Pure iron is admitted for a steel hull —
   it is 100% target species, so the 95% rule waves it through — and the hull then never finishes,
   because its carbon never arrives. Worse than a refusal: it went on accepting iron for ever, past
   its own billed mass, so a tank of iron drained into a machine that could never complete. A matter
   sink reachable by ordinary play. **RESOLVED — see below.**
2. **A share per species rounds every share down**, so the machine lands a gram short of everything
   at once and the next delivery is asked for a shortfall too small to divide — stuck at 999 per
   mille with a loaded belt on it.
3. **A share proportional to the delivery cannot close the last gram.** Even apportioned exactly, the
   lump's ratio is not the shortfall's ratio, so the last unit goes to whichever species the rounding
   favours and the other parks one gram short for ever.

(2) and (3) are answered together by reading the plan's own sentence — *a ghost takes only what it
still needs* — **per species**: the final top-up takes `min(what is here, what is missing)` of each.
Junk still gets baked in, by the branch above it: while a ghost is hungrier than the lump is big it
swallows the lump whole, junk and all. So "proportional, junk included" describes the swallow and
"only what it needs" describes the top-up, and the two together terminate.

### The alloy rule — Stu, 2026-08-19

**The purity test is asked of every species in the bill separately, against its own share of the
recipe.** Steel is 990:10, so a delivery must be at least 94.05% iron *and* at least 0.95% carbon.
Pure iron fails the second and is turned away at the tile, so the sink is closed at admission.

Two properties make this a generalisation rather than a second rule:

- For a **single-species** bill it *is* the old test — a rail's threshold is 95% of 100% — so nothing
  about track changes, and the existing purity tests pass untouched.
- The thresholds **sum to 95%**, so anything that passes is automatically ≥95% bill species by mass.
  The aggregate test is implied, not discarded.

⚠️ The tolerance is **proportional**, so it is generous for a trace component and tight for a
balanced mix: steel's carbon may be anywhere in 0.95%–5.95%, a six-fold window, while firebrick's
550:450 pins each species to about ±2.5 points. That unevenness is intended pressure.

### Where the mixing happens — Stu, 2026-08-19

**Not at the construction site, and not in a smelter.** Form is ignored for construction (decision 2),
so raw powdered ore builds a casing exactly as an ingot does, and the site's job is *compaction into
the shape required* — a real compaction-and-sintering analogue, with the metallurgy skipped.

Mixing is therefore a **logistics** problem:

- **On the belt**, by packets merging as they are loaded onto one tile — hard and unintuitive, and
  possibly the ideal late-game setup.
- **In a storage**, which is the controlled and forgiving method. `takePacket` slices a buffer
  proportionally, so a tank held at >94.05% iron and >0.95% carbon emits packets that satisfy a steel
  machine exactly. **Holding those concentrations while replenishing is the game.**

⚠️ This is why the tolerance matters more than the exact recipe: a player maintains a *ratio*, not a
number.

Consequence worth recording: nothing else in the game mixes. `smelt` returns a single species by
construction and the processor concentrates toward one; every species of every material — iron,
carbon, quartz, aluminium — is already producible on its own. Stu is leaning toward **deleting the
smelter**; that is not in scope here.

**5d. Deconstruction ✅** The mark, the five-step ordering above, ceasing to be.

The mark is `VesselState.scrapping`, a set of **centre tiles**, saved as `scrapping=1` on the
machine's own record — the same spelling a segment uses, absent reads as unmarked, no version bump.
It is cleared by `dropMachine` and nowhere else.

A marked machine **does not run**, and that is forced rather than chosen: one that kept running would
refill the very stores deconstruction is waiting on. (A rail being taken apart still carries traffic,
because carrying is not producing.)

### Where each store hands itself back — Stu, 2026-08-19

- **A processing buffer goes back out through the input port.** A `Processor` or `ThermalDecomposer`
  holds a lump *in the middle of being worked*, and that is not finished goods — it has no business
  leaving by an output. The way it came in is the honest way back out. ⚠️ The store itself sits at
  the machine's **centre**, so this is the one place where where a store *is* and where its contents
  are handed back deliberately differ.
- **A storage uses its normal output.** Its `Inside` is what its own output port drains, so a tank
  empties by its natural exit and deconstruction only waits.
- **An `Extractor`** has a working store and no input port at all — what it is chewing came off a
  rock, not off a belt — so it falls back to its own tile, where the deconstruction port stands.
- **A bridge's slots** are shuffled out of the far end by `advanceBridges`, which is part of the
  conduit step rather than of running a machine and therefore keeps going while the bridge is marked.

⚠️ **A store a surviving output port still drains is left to that port.** A `Storage` keeps only an
`Inside` store and that *is* what its output port drains, so two mouths would empty one tank in a
tick — harmless for the ledger, but it comes apart at twice the rate it appears to and puts the same
cargo down twice.

⚠️ **The rails' collision, again, and in a second place.** A machine handing its casing back is short
of its bill from the first load, so it reads as a ghost. Guarding `machineGhosts` was not enough:
`portsByTile` asked `isGhost` *before* the mark, so the machine grew a **construction** port, turned
into a sink and drew its own metal straight back off the belt. Silent, stable, and indistinguishable
from deconstruction doing nothing. **Being told to go overrides being short — in every place that
asks.**

**5e. Rendering, starter vessel, harness.** A ghost machine reads as one, a marked one reads as
marked, and `agent-scripts/` watches a machine build itself.
