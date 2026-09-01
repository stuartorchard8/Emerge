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

⛔ **`turnedAbout` does not survive, and the composite is not an exception — this plan said twice
that it was, and was wrong both times.** A docked pair turns about the joint centre, which belongs to
neither member, and the conclusion drawn from that was that some caller must still be able to name a
foreign pivot. It does not follow: the pair *is* a body, the joint centre *is* that body's own
centre, and what gets advanced while docked is the pair. Every caller turns about its own mass. So
the pivot stops being a parameter at all — `turned(by, about)` takes the distribution and reads the
centre off it — and the composite case is deleted rather than accommodated. Done in `e96a15c8`.

⚠️ **The per-tick freeze is already in the contract.** The transform now depends on the mass
distribution, which changes *during* a tick as matter moves — so a conversion early in the tick and
one late in it must not disagree. `Pose` already says "Construct one per body per tick" because it
caches `cos`/`sin`, and that existing lifetime is exactly the freeze point this needs. Nothing new to
enforce; it does need saying out loud in the class doc.

## Steps

Ordered so the case that can invalidate the shape comes first. Steps 1 and 2 are the design; 3–6 are
consequences.

### 1. ✅ The centre of mass as a position — `5780a572`, `30799ff8`

Done, in two commits, and the shape changed on contact with the code.

**It is an addition, not a unit change.** `MassDistribution.comX` anchors the whole rotational
system — `torqueAbout`, every `tileCentre(…) − com` lever arm, three gyration passes, `Composite`'s
parallel-axis term — and that system genuinely wants millitiles: `Rotation`'s note says radii are
millitiles, `torqueAbout` divides by `MILLI_TILE`, and `gyrationSq`'s scale only coincides with
`GYRATION_SCALE` because a millitile² *is* a micro-tile². Those are radii. What the grid needs is a
**position**. So the distribution carries both: `comMilliX` the arm, `comX` the position.

**The margin was measured, not assumed.** On the starter vessel — 96.3 t, a 100 kg packet — one
packet moving one tile shifts the centre by **1.04 millitiles**. A full packet-tile move is one unit
at radius resolution and everything finer is zero, so a grid hung off it would snap in whole
0.001-tile steps. At the position scale the same move is 1 038 521 units. That settles the question
the Open section raised: millitiles would *not* have been enough.

**Split so the compiler did the finding.** Both quantities are `Long`, so a site taking the wrong one
is silently wrong rather than red. `5780a572` renames `comX` → `comMilliX` and nothing else — 82
sites, every one visited because the build refused otherwise. `30799ff8` adds `comX` back as the
position, which nothing reads until step 2, so nothing could break.

⚠️ **The two are carried, not derived from one another.** Collapsing them moves every torque arm by
up to a millitile. That is a change to argue on `MomentumLedger`'s evidence — do it in step 3, where
the integrator is already under the microscope, not before. `RotationTest` pins them to one point
meanwhile.

`Rotation.PER_MILLI_TILE` now states the position/radius conversion once; `RigidBody.COM_SCALE` and
`Composite.PER_MILLI_TILE` were already two copies of it.

### 2. ✅ The pivot stops being a parameter — `e96a15c8`

`Pose.turnedAbout(by, pivotX, pivotY)` → `Pose.turned(by, about)`, `turnedAbout` deleted, and with it
the idea that a body can be told where to spin. It reads `MassDistribution.comX`, so the integrator
stops rounding its pivot to a thousandth of a tile — the first use of what step 1 built.

**The weld needed no rebuild.** It was offered one and did not want it: once the pivot comes off the
distribution, `moveAbout` being the pair's rather than the vessel's is the entire docked case, and
`Pose` cannot tell the difference. The remaining asymmetry — "the vessel carries the pair's momentum"
— is about *momentum storage*, not rotation, and nothing in step 3 needs it flipped.

`VesselState.distribution` is memoised, because `turned` puts the mass walk on the advance path
rather than only on the way out.

⚠️ **This step did not move the anchor.** `Pose.x` is still the grid origin and `toWorld`/`toLocal`
are untouched, so the world COM still drifts on an intra-grid mass move. That property arrives in
step 3, which is where the plan's whole claim actually lands.

### 3. The anchor flips — vessel, bodies and saves together

⛔ **These cannot be separated, and the plan was wrong to number them apart.** The moment
`toWorld(local) = C + R·(local − comLocal)` replaces `origin + R·local`, `Pose.x` *must* be a centre
of mass at every construction site at once — there is no half-way state that compiles and means
anything. `RigidBody.pose` is built from `positionX`, so bodies flip with the vessel; `Save` reads
`position` into that field, so the migration flips with both. One commit, or a deliberately broken
tree across three.

What it covers:

- `VesselState.positionX/Y` and `RigidBody.positionX/Y` become world centres of mass.
- `Pose` gains `comLocalX/comLocalY` and both transforms subtract it. ⚠️ Not added earlier on
  purpose: until the transform reads it, it is a field several sites would have to supply a
  meaningless value for — `Save`'s legacy migration pose has no centre of mass to name.
- `RigidBody.comX/comY` stop being derived and become the stored value; `centreX` stays derived,
  because the silhouette is a different question.
- `RockContact`'s `px`/`py` start carrying centres, and `Contact.kt:559` stops deriving one per
  contact.
- **Save v23** (`Save.VERSION` is 22): read the old origin, walk the loaded layers for `comLocal`,
  store `C = origin + R·comLocal`. The migration needs the grid, so it runs after the layers are
  read, not while the header is.

**Gate:** `MomentumLedger`, `PoseTest`, `WeldTest`, `DockingTest`, `RotationTest`, the contact
suites, and the two new invariants below — which fail today and are the point of the exercise.

### 4. Readouts and the renderer

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

- ~~**Whether millitile COM would have been enough.**~~ **Closed, measured:** no. One packet moving
  one tile is 1.04 millitiles on the starter vessel, so the radius scale resolves a full packet-tile
  move to a single unit and everything smaller to nothing. See step 1.
- **When to collapse `comMilliX` into `comX`.** Deferred out of step 1 on purpose — it moves every
  torque arm by up to a millitile and wants the ledger's evidence. Step 3 is where that evidence is
  already being read.
- **`atmosphereDistribution` takes the vessel's COM as its pivot** and is deliberately a second body.
  Nothing here changes that, but it reads `about.comX` and so inherits step 1's unit change.
- **Debris and `PLAN_rigid_debris.md`** — a body that splits gets two COMs from one, which is a
  natural fit for this shape and worth checking against before step 4 sets the field's meaning.
