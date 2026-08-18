# Self-building rails

Status: **planned, not started** (2026-08-18).

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

## Increments

**1. Break the identity.** `reconciled` stops conjuring and stops clearing. Laying makes matter-free
track. `Work.built` drops its conduit term; `TrackLayers.lay` becomes a bill query. Nothing is
playable — the whole network is ghosts — but the ledger tightens and the suite reports the true
scope. This is the hard shape and it cannot be discovered by doing increment 2 first.

**2. Ghost port and the admission rule.** A rail below its bill grows an input port. Only material
≥95% target species may enter its tile. What enters is absorbed onto the structure layer,
proportionally, up to what is still needed. Resolve the port-priority question (above) first.

**3. Deconstruction.** The `deconstructing` bit, the implied output port, the multi-tick dribble
(a tile carries at most one packet and a bill may exceed `Capacity.PACKET_MASS`), and ceasing to be
when structure mass is zero *and* the transport tile is empty.

**4. Starter vessel and rendering.** Real rails and a storage container of iron on the starting ship.
A fill-fraction visual so a half-built ghost is legible, and a deconstruction visual.

Later, out of scope: pipes and wires by the same mechanism, then deck machines, then the fancier
things a walking rail makes possible.

## Coordination

Increment 1 touches `Conduits`, `Save` and `OutofspaceSim`'s work ledger. Gauge and valve work was in
flight in `Save`, `Port` and `Segment` when this was written — check what landed before starting.
