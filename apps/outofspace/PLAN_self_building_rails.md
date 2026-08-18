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
