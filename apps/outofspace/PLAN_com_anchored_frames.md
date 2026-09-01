# COM-anchored frames

A body stores **where its mass is**. The grid hangs off that.

Today a body stores the world position of its grid's **origin** — tile (0,0)'s corner — and turns
about its centre of mass by moving that origin to hold the pivot still (`Pose.turnedAbout`). This
plan inverts it: the stored position *is* the centre of mass, the grid superimposes itself around
that point according to its mass distribution, and rotation stops needing a pivot argument because a
free body's pivot is its own origin.

## Why

**The world centre of mass currently drifts when nothing pushes the ship.** Cargo sliding down a
rail moves the COM in grid coordinates; the origin is what is stored and held, so the COM moves in
*open space* with no external force acting. That is a conservation error. It is small and nothing
reads it today, but it is the wrong way round: momentum conservation says the COM is the thing that
stays put and the hull is the thing that recoils.

Inverting it makes world-COM invariance hold **by construction** — no term to book, no drift to
audit — and the hull shift that falls out is the physically correct one.

⚠️ **The existing justification is not the one to preserve.** `Vessel.kt:930` argues that a
COM-anchored pose would make "the whole grid lurch sideways whenever an ingot slides down a rail."
That lurch is real physics, not an artifact, and the comment defends the right conclusion for the
period during which nothing depended on it. It gets deleted, not amended.

Secondary, but not incidental: this is a prerequisite shape for per-vessel grids. A body whose stored
position is its COM is a body that does not need to know it is *the* vessel.

## The shape

`Pose` becomes COM-anchored and carries the offset it needs to place the grid:

```
Pose(x, y, ang, comLocalX, comLocalY)     // (x, y) is the world COM

toWorld(local) = C + R·(local − comLocal)
toLocal(world) = R⁻¹·(world − C) + comLocal
```

Direction transforms — `turnedX`, `unturnedX` — never involved the offset and do not change.

Rotation of a free body becomes `ang += by` with `C` untouched. That is the whole of "the rotation
problem dissolves."

⚠️ **`turnedAbout` survives, confined to the composite case.** A docked pair turns about the *joint*
centre (`Weld.jointOf`, `OutofspaceSim.kt:884`), which belongs to neither member, so each member's
stored COM still has to be displaced as the pair turns. It stops being the general case and becomes
the one case that genuinely has a pivot other than its own.

⚠️ **The per-tick freeze is already in the contract.** The transform now depends on the mass
distribution, which changes *during* a tick as matter moves — so a conversion early in the tick and
one late in it must not disagree. `Pose` already says "Construct one per body per tick" because it
caches `cos`/`sin`, and that existing lifetime is exactly the freeze point this needs. Nothing new to
enforce; it does need saying out loud in the class doc.

## Steps

Ordered so the case that can invalidate the shape comes first. Steps 1 and 2 are the design; 3–6 are
consequences.

### 1. `MassDistribution.comX/comY` in `Flight.PER_TILE` units

Prerequisite, and independently green. The COM becomes load-bearing for the world transform, where
today it only feeds torque, so millitiles (1e6× coarser than a coordinate) stop being obviously
enough.

Cheap, because `massDistribution` accumulates `momentX` in **tile** units and only the output scale
is millitiles — `scaledRatio(momentX, mass, MILLI_TILE)` becomes `…, Flight.PER_TILE)`. The
accumulator is untouched, so no new overflow: the 2e17 bound the comment states still holds.

⛔ **The gyration pass still wants millitiles.** `rx = tileCentre(…) − comX` is squared, and `rx²` at
PER_TILE is 1e18 before it is summed. That pass converts down locally. Same for `Composite` (whose
`d²` term relies on millitile² being micro-tile²) and `Contact`'s arms.

Consumers to convert: `Composite`, `Contact.kt:559`, `Weld.kt:101`, `Diffusion.kt:329`,
`FlightControls.kt:145`, `OutofspaceRenderer.kt:186`.

**Gate:** whole existing suite. This step is a pure refactor — no behaviour change intended, so
anything that moves is a transcription error.

### 2. `Pose` carries the COM, and the weld with it

The type change, `toWorld`/`toLocal` rewritten, `turnedAbout` split into `turned(by)` (free) and
`turnedAbout(by, pivot)` (composite only).

Every `Pose(...)` construction site must now supply the distribution — there are nine:
`Weld.kt:117`, `RigidBody.kt:240`, `RigidBody.kt:249`, `RockContact.kt:331`, `RockContact.kt:375`,
`RockContact.kt:413`, `OutofspaceSim.kt:1079`, `Vessel.kt:940`, `Save.kt:1207`.

**Do the weld in this step, not later.** `Weld.stationPose` and the docked branch of the integrator
are the only places a pivot is not the body's own centre, so they are what decides whether the split
above is a real distinction or a leak. If COM-anchoring is going to fail to be clean, it fails here.

**Gate:** `PoseTest`, `WeldTest`, `DockingTest`, `DockingPortTest`, `RotationTest`.

### 3. The vessel stores its COM

`VesselState.positionX/Y` becomes the world COM. `pose` builds from `distribution`. The integrator
at `OutofspaceSim.kt:884` becomes `pose.turned(spin).movedBy(v)` for free flight and keeps
`turnedAbout(jointCom)` while docked.

**Gate:** `MomentumLedger`, plus the new invariant below.

### 4. Rigid bodies store their COM

`RigidBody.positionX/Y` becomes the COM; `comX`/`comY` (`RigidBody.kt:291`) stop being derived and
become the stored value; `centreX` (bounding box) stays derived, since the silhouette question is a
different question.

This one pays for itself: `RockContact`'s `px`/`py` arrays (`:268`) start carrying COMs directly, and
`Contact.kt:559` (`arx[i] = (c.pointX − body.comX)`) stops deriving one per contact.

**Gate:** `BodyContactTest`, `ContactTest`, `RockContactTest`.

### 5. Save v23

`position` changes meaning. Migration reads the old origin-anchored value, computes the distribution
at load, and writes `C = origin + R·comLocal`. `Save.VERSION` is 22 today.

⚠️ Migration needs the *loaded* grid before it can place the body, so it runs after layers are read,
not while the header is.

### 6. Readouts and the renderer

Nav coordinate label (`OutofspaceHud.kt:960`), origin marker (`:930`), density-field UVs (`:917`),
camera (`OutofspaceRenderer.kt:181`, already COM-anchored and so already correct).

This is where the reported bug dies: the nav coordinates stop tracing a circle when the ship spins in
place, because the number they print is the pivot.

## The invariant this buys

A new test, and the point of the whole exercise:

> **An intra-grid mass move leaves the world COM bit-identical.** Slide a packet down a rail, fill a
> buffer, deconstruct a machine — the vessel's stored position does not move, and every tile's world
> position shifts by exactly the negative of the COM's grid-space shift.

Its twin, for rotation:

> **A vessel spinning in place holds its stored position bit-identical across a full turn.**

Both fail today. Neither should be able to fail again.

## Open

- **Whether millitile COM would have been enough.** `rotScale`'s note says contacts resolve in
  millitiles, so the grid offset may not need PER_TILE at all — step 1 might be unnecessary. It is
  cheap and it removes the question, but it is worth measuring rather than assuming, because the
  concern is not the magnitude but whether a *per-tick changing* quantisation shimmers where a static
  one would not.
- **`atmosphereDistribution` takes the vessel's COM as its pivot** and is deliberately a second body.
  Nothing here changes that, but it reads `about.comX` and so inherits step 1's unit change.
- **Debris and `PLAN_rigid_debris.md`** — a body that splits gets two COMs from one, which is a
  natural fit for this shape and worth checking against before step 4 sets the field's meaning.
