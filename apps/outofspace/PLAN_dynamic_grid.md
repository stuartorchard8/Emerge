# The grid fits the vessel

*Plan, 2026-08-05. Nothing built. Companion to `docs/out-of-space-plan.md` — this is one item, at
length, because it touches more of the world than anything since the fluid layer.*

---

## 1. What this is for

The grid is `Grid(96, 60)` and always has been. It was chosen as "a generous bound with the hull
drawn inside it", and `Grid`'s own doc says why: *fixed rather than growable because the atmosphere
solver is far simpler over fixed bounds, and because a generous bound gets the expansion fantasy
without the machinery.*

That was right while the vessel was a fixture. It stops being right the moment vessels are
**authored**, because a bound chosen once cannot be the right bound for a ship that has not been
designed yet. A long vessel wastes six thousand tiles of height; a tall one wastes them in width; and
either way every tick sweeps, floods, diffuses and projects across the waste.

**Goal.** The grid is always exactly the vessel's bounding box plus a constant padding of 4 tiles on
every side, and it changes shape when the vessel does.

**What that buys, in order of how much it matters:**

1. **A designed vessel gets a grid its own shape.** This is the point. Everything else is a
   consequence.
2. **The default world gets much smaller.** A starter vessel is 33×23; padded that is 41×31, which is
   1271 tiles against 5760 — a 4.5× cut in everything that is per-tile per-tick. The fluid solve is
   the bulk of the tick, so this is real, and it arrives immediately: rocks live outside the grid
   quite happily (§8), so the box does not have to cover the field.
3. **The hull can never touch the grid edge**, which today is a silent trap: `StructureMap` derives
   "inside" by flooding inward *from the grid boundary*, so a hull built flush against it cannot be
   flooded around and the whole ship reads as interior. A guaranteed pad of 4 makes that
   unrepresentable.

**What it does not buy, and must not be sold as:** it is not a fix for anything currently wrong. Every
ledger balances today. This is an editor affordance with a performance dividend.

---

## 2. The decision that shapes everything else

There are two versions of this feature and they differ by an order of magnitude in cost.

**A. Continuous fit.** Every tick, compute the bounding box; if it differs, remap the world. The grid
breathes as you build and as you delete.

**B. Grow-on-demand, fit-on-event.** The grid **grows** whenever an edit would come within the pad of
an edge, and is **fitted exactly** only at defined moments: world creation, save load, and an explicit
"fit" command. It never shrinks during play.

**Recommendation: B.** Three reasons, in descending order of force.

- **Shrinking is the half that costs the ledger.** Growing adds vacuum tiles at zero grams, zero
  joules and zero momentum, so no baseline moves and no identity is touched. Shrinking discards cells
  that may hold gas, heat and face momentum, every gram of which has to be booked somewhere or
  `airBalance` breaks — see §5. Booking it as vented is defensible ("it went overboard") but it means
  a plume crossing the boundary as the grid contracts reads as a vent, which is a lie told by a
  mechanism whose whole job is to not tell lies.
- **A shifting origin invalidates every absolute coordinate in the project.** See §6. This is the
  largest single cost of the whole item and it is proportional to how often the origin moves.
  Fit-on-event moves it at moments where nothing is mid-measurement; continuous fit moves it whenever
  the player deletes the leftmost wall.
- **Continuous fit buys the least.** The player's actual complaint is "the grid is the wrong size and
  shape for my vessel". Fitting at load and on demand answers that completely. Reclaiming four tiles
  the moment a wall comes down answers nothing anybody asked.

The rest of this plan assumes B, and notes where A would differ.

---

## 3. What is indexed by the grid

Everything below has to be remapped by one function. This list was assembled by reading every field
of `VesselState` and every consumer of `Grid`; the third column is what makes it interesting.

| What | Shape | The catch |
|---|---|---|
| `machines` | `List<Machine?>`, one per tile | Anchors only — a machine is stored once at its centre. Remapping the anchor moves the whole footprint, which is correct and free. |
| `conduits` | one `List<Segment?>` per layer | Four layers. `Conduits.empty(n)` is sized from the tile count. |
| `bridges` | `List<Bridge?>` | Same shape. |
| `debris` | `Map<tile, List<Resource>>` | Sparse — rekey, do not resize. |
| `diverters` | `Map<tile, Int>` cursor | Sparse likewise. Easy to forget; a lost cursor is a silently different world. |
| `air`, `pipeAir` | `LongArray(tiles × Species.COUNT)` + `LongArray(tiles)` joules | Two arrays each, and the joules array is per-tile while the grams array is per-tile-per-species. Getting the stride wrong here is a bug that will read as a temperature anomaly, not as a remap failure. |
| `momentum`, `pipeMomentum` | `MomentumField` over `EdgeGrid` | **The fiddly one.** Two index spaces: x-faces are `(w+1) × h`, y-faces are `w × (h+1)`. Neither is the tile space and neither is the other. See §4. |
| `rocks` | positions in `Flight.PER_TILE` billionths, vessel frame | Not indices — offsets. Shift by `dx * PER_TILE`. Cheap, and the only place where forgetting shows up as things teleporting rather than as an exception. |
| `signals` | keyed by `Channel` | Nothing to do. |
| `structure`, `occupancy`, `flow`, `bodies`, `fabricKelvin` | derived / lazy | Free. They rebuild from the new grid. This is a large part of why the change is tractable. |
| `motion` | presentation, per-tile | Can be **dropped** on resize rather than remapped. A resize is a frame where nothing animates; the save already drops it. |

And outside the state, which is the part that gets forgotten:

| What | Where | Why it matters |
|---|---|---|
| `selected` | `OutofspaceController` | The wiring panel would point at a different machine. |
| `dragFrom` | `OutofspaceController` | A conduit drag in flight would join two unrelated tiles. |
| `hovered`, `lastPainted` | each host's input loop | A stale hover paints the wrong tile on the next frame. |
| `injectTile` | `OutofspaceController` | Gas appears somewhere else. |
| `camX`, `camY` | `OutofspaceRenderer` | The view jumps. This is the one the player *sees*, and the one most likely to ship broken because no test looks at a camera. |
| `cfg.grid` | `OutofspaceConfig` | Becomes a lie the moment the grid moves — see §7. |

---

## 4. The remap

One function, in `world/`:

```kotlin
/**
 * The same world on a different lattice, translated by (dx, dy) tiles.
 * Cells that exist in both grids keep everything; cells only in the new one are vacuum.
 */
fun VesselState.remapped(newGrid: Grid, dx: Int, dy: Int): VesselState
```

`dx`/`dy` are where the **old origin lands in the new grid**, so growing left by 4 is `dx = +4`.

Shape of the implementation, in the order that keeps it honest:

1. **Tile-indexed lists** — allocate the new size, walk the *old* grid, copy each non-null to
   `newGrid.index(x + dx, y + dy)`. Walking the old grid rather than the new one means shrinking is
   the same loop with a bounds check, and the bounds check is where the discard hook goes (§5).
2. **Sparse maps** — same walk, rekey.
3. **Dense field arrays** — same walk, times `Species.COUNT` for the grams stride.
4. **Edge fields** — a walk of its own, per axis. An x-face at `(x, y)` in the old grid is the x-face
   at `(x + dx, y + dy)` in the new one; `xEdgeCount` and the `xStride = width + 1` both change, so
   this cannot share code with the tile walk. Write it twice rather than cleverly once.
5. **Rocks** — `copy(positionX = positionX + dx * Flight.PER_TILE, …)`.
6. **Everything derived** — do not remap. Let `copy` recompute it. But ⚠️ **the baselines are
   constructor defaults**, so they will *not* recompute on a `copy` — they must be passed through
   explicitly, unchanged, exactly as `workingVessel` in `RailFixtures` learned to do. A remap that
   let `baselineJoules` recompute would silently rebase the world's energy and every subsequent
   reading would be measured against the wrong zero.

The function belongs in `world/` and not on the reducer, because it is a statement about a state and
the fixtures and the save loader both want it.

---

## 5. The ledger, if it ever shrinks

Growing touches nothing. Shrinking must account for every cell it discards, or:

- `airBalance` breaks by the grams in the discarded cells,
- `airJouleBalance` breaks by their joules,
- the momentum identity breaks by their face momentum,
- `baselineJoules` breaks if any body was standing there — though it cannot be, since the box is
  drawn around the bodies.

**Rule: a discarded cell is vented.** Its grams go to `airVentedGrams`, its joules to
`airVentedJoules`, its face momentum to `exhaustMomentumX/Y`. That is physically the right story —
the tile left the world, and the only way out of this world is overboard — and it keeps all three
identities exactly as strict as they are now.

Debris and rocks are **not** subject to this: the box encloses them by construction (§8), so a
discard is a bug and should `require` rather than book. That asymmetry is deliberate. Gas is
diffuse and legitimately present in a padding tile; a rock is a thing, and a resize that ate one is
a resize that got its bounds wrong.

⚠️ This whole section is the argument for option B. Under grow-only it is **dead code that never
runs**, and per §5e's lesson a quantity only ever run at one value has not been run — so if we build
it, `remapped` should be tested at a shrink directly, even though play never triggers one.

---

## 6. The coordinate problem, which is the real cost

Grid coordinates are absolute and are written down in a lot of places:

- **The test suite.** Dozens of `grid.index(x, y)` sites across `MotionTest`, `DebrisTest`,
  `GaugeTest`, `RockContactTest`, `HeatTest`, `VesselSimTest` and the fluid tests.
- **All 11 agent scripts**, which name tiles as `x y` pairs and resolve them against the live
  `state.grid`.
- **`StarterVessel`**, which is written in absolute coordinates throughout.

None of these fail to compile when the origin moves. They keep working and quietly mean somewhere
else. That is the worst failure mode available and it is why this item is measured in days rather
than hours.

**Three mitigations, all of which we should take:**

1. **Fit once, at construction.** `starterVessel` builds at its own coordinates and *then* fits, so
   the vessel is authored in a stable frame and the fit is the last thing that happens. Everything
   downstream sees one grid for the rest of the session.
2. ~~**Never fit implicitly during play.** Growth is the only implicit change, and growth on the right
   and bottom does not move the origin at all. Growth on the left or top does — which means either
   accepting the shift, or growing only on the far sides and letting the pad be uneven until the next
   explicit fit. **Prefer the latter**: an uneven pad is invisible, and a moving origin is not.~~
   **Superseded 2026-08-06 — growth is side-agnostic; see `HANDOFF_P3.md`.** The far-only rule
   protected `(x, y)` but not stored *indices*, which move whenever `width` does, so the
   remap-the-holders work was unavoidable regardless. Accept the shift, report it, and have every
   holder of a coordinate consume it. What remains of this mitigation is the discipline in 3 below
   and the landmark addressing P2 built.
3. **A harness assertion.** `expect gridWidth`/`gridHeight`, and an `origin` readout, so a script can
   state the frame it believes it is in and fail loudly rather than measuring the wrong tile.

---

## 7. `OutofspaceConfig.grid`

Rename to `initialGrid`, because that is what it becomes.

Good news from the audit: **the reducer never reads it.** It reads `state.grid` throughout. The only
readers are the controller's `dragTo` and the tests. `dragTo` reading `cfg.grid` is already a
latent bug — it should read `state.grid` — and fixing it is a prerequisite rather than part of this
work.

---

## 8. What the box encloses

Every tile covered by a machine (**footprint**, not anchor — a smelter reaches two past its centre;
`RockField.boundsOf` already has this right and is the thing to copy), every tile carrying a conduit
segment or a bridge, and every tile holding debris.

**Not rocks.** An earlier draft of this plan required the box to enclose the rock field, on the
grounds that a rock outside the grid would drift through the hull. That is wrong, and the code is
explicit about it:

- `overlapsHull` bounds-checks every tile it tests (`if (tx < 0 || tx >= grid.width) continue`) and
  floors negatives correctly, with the comment *"a rock goes negative"*. Its doc already settles the
  question: *"Anything off the grid is open space, not wall."*
- More to the point, **the hull is inside the grid**. A rock can only reach it by overlapping hull
  tiles, which are in-bounds by construction — with a pad of 4, in-bounds with room to spare. There
  is no path from "off-grid and untested" to "inside the hull" that does not cross tested tiles, and
  `MAX_SUBSTEP` is what stops it being stepped over. That is independent of where the boundary is.
- The approach works for the same reason. The grid is the vessel's frame and travels with it, so a
  distant rock has a negative grid position that walks toward the ship as the ship flies at it and
  crosses into bounds on its own.

Rocks are therefore free to live outside the world, which is what H4 already assumes and what the
renderer already does (it draws every rock, unculled).

⚠️ **Consequence for the discard rule in §5:** debris still must not be discarded, but a rock leaving
the box is *ordinary* and books nothing. It is neither in `massGrams` nor in `inTransitGrams` while
loose, and its own ledger is positional-free — see `VesselState.rocks`.

⚠️ **One real behaviour change, small:** `platingFeltBy` treats "centre is on the grid" as "over the
deck", so a fitted grid shrinks the plating's reach from the whole 96×60 to vessel+4. That is *more*
correct — a field the ship makes should stop where the ship does — but it will move the numbers in
any fixture that sets `PLATING_ONE_G` and puts a rock far out, which is most of `RockContactTest`.
Expect it, and do not "fix" it by re-growing the box.

## 9. Phases

Each phase ends with a green suite. Nothing here needs a flag day.

**P0 — Prerequisites.** ~~Fix `dragTo` to read `state.grid`.~~ ~~Rename `cfg.grid` → `initialGrid`.~~
~~Add `gridWidth`/`gridHeight`/`originX`/`originY` readouts to the harness.~~ *No behaviour change.*
~~Half a day.~~ **DONE 2026-08-05** (25 files, 95 insertions, 91 deletions, suite green).

**P1 — `remapped`, tested in isolation.** ~~The function, plus a test file that builds a world, remaps~~
~~it by a known offset, and asserts: every machine/segment/bridge/pile/diverter landed where it should;~~
~~the fields are identical modulo the shift; the edge fields are identical on both axes; rocks moved~~
~~exactly `dx` tiles; every ledger identity holds; and a round trip through a `+4/-4` pair is the~~
~~identity. Nothing calls it yet.~~ **DONE 2026-08-06** (21 tests: identity, each field type, all six
ledger balances, round-trip, vacuum init, negative offset). The y-face boundary was the trap, as
predicted: `yEdgeCount = width * (height + 1)`, so those loops run `0..height`, not `0 until height`.

⚠️ **Two known gaps, both deferred deliberately.** `remapped` *silently discards* on a shrink rather
than venting per §5 — acceptable only while nothing shrinks, so P2 must not introduce a shrink
without §5 arriving with it. And `motion` is carried through by `copy()` despite the comment saying
it is dropped; it is `Motion.NONE` at construction so it is harmless today, but it is a per-tile
array sized to the *old* grid and will hand the renderer the wrong length the first time a resize
happens mid-play. Fix it in P3, before growth is live. ~One day.~

**P2 — Fit at construction and load.** ~~`fitGrid(state, pad = 4)` returning a remapped state; called
by `starterVessel`~~ and by `Save.read`. ~~The starter vessel's grid stops being `Grid(96, 60)` and
becomes whatever it needs.~~ **DONE 2026-08-06**, less `Save.read` — see below. The starter vessel fits to **41×26** — 1066
tiles against 5760, a **5.4× cut**, ahead of the 4.5× §1 predicts. The width lands exactly where this
plan guessed and the height comes in five under, so the estimate was pessimistic rather than the fit
wrong. The frame moved by **(+3, −3)** and is pinned by `GridFitTest.the starter vessel lands in a
known frame`; read that test before writing an absolute coordinate.

⚠️ **`Save.read` does not fit, deliberately.** `SaveTest.a hand-written world is a legitimate save`
parses a deliberate `grid 6 4`, and fitting on load would refit hand-authored worlds and mangle
exactly that. The plan's reason for load-fitting was migrating worlds authored at 96×60, and there
are none. **Settled 2026-08-06: it stays that way.** Manual refit is workable for the work in hand,
and an automatic one can arrive later if P4 shows it is wanted. A save records the frame it was
written in, and loading honours it.

**Where the fallout actually was.** §6 expected dozens of broken coordinate sites across the test
suite and this budgeted a day for it; it was **three tests and about twenty minutes**, because the
suite mostly builds its own fixtures rather than naming starter-vessel tiles.

**The scripts were the opposite story, and they are now fixed.** All ten were green at `4cc59ca1`
and six went red — `breach`, `collision`, `extractor`, `pump`, `rocks`, `smoke` — the fit's doing,
not inherited. Two failed loudly on coordinates that no longer existed (`rock 18 30`, `probe 48 30`
against 41×26); the rest still named in-bounds tiles that now *meant somewhere else*, which is
exactly the silent drift §6 called the worst failure mode available. **All ten green 2026-08-06.**

Two things came out of that repair and both outlive it:

- **A script can name a landmark instead of a tile** — `extractor`, `origin`, `smelter+8`. Landmark
  names derive from `MachineKind.DECK` rather than a hand-kept list, so a new kind becomes
  addressable by existing; `landmarks` prints them all with their current coordinates. **Prefer
  these to absolute tiles in any new script**, or P3 and P4 will break the suite again.
- **`Edit.DropRock` now takes a continuous `(x, y)`, not a tile index.** Tying a rock to
  `machines.indices` was wrong on its own terms: §8 has rocks living *outside* the box, so the
  bounds guard was rejecting the legitimate case. F6 drops where the pointer is, sub-tile. The
  consequence to know is that `rock` is the one harness command where an out-of-range coordinate
  is no longer an error.

⚠️ **And a defect the scripts revealed rather than suffered, now fixed:** `smoke.txt` printed
`! error on 'probe 48 30'` and **still exited 0** — a script could name a tile that did not exist
and pass. An errored command now records a failure. ~One day.~

**P3 — Grow on demand.** ~~In the reducer's edit pass: if an edit would place anything within the pad
of an edge, grow that edge to restore it. **Any of the four edges**. Growth reports the `(dx, dy)` it
applied; camera and controller indices consume it, pulled forward from P4.~~ **DONE 2026-08-06.**
Side-agnostic as `HANDOFF_P3.md` argued, and the far/near distinction never became a branch: the
shortfall on each of the four edges is computed the same way and only the near two are non-zero in
the offset handed to `remapped`.

**Growth runs at the end of `reduce`, not in the edit pass.** `Work` is built from the grid the tick
started on and every pass addresses tiles through it, so a resize partway leaves half a world on each
lattice. The edit lands on the grid the player clicked, and the world moves underneath afterwards.
`GridGrowTest` pins that ordering by digesting a world that grew against the same world built at the
final size, on all four edges.

⚠️ **The pad is opt-in, and that is the one design decision P3 added.** `VesselState.gridPad` records
the clearance a world keeps; `fitGrid` sets it, and a world that was never fitted keeps 0 and never
grows. Without this, growth is universal and the first tick silently grows every hand-authored
fixture — `AtmosphereTest`'s `Grid(9, 5)` with its hull on the border becomes 17×13 — moving every
coordinate written against it. That is §6's worst-failure-mode drift, and it broke about thirty test
files before the field existed. The principle is P2's, arrived at independently: a world records the
frame it was written in, and running it honours that.

The holders of a coordinate are handled by `FrameShift`, one per holder, which consumes the
difference in `frameShiftX`/`frameShiftY` and reindexes through the old grid rather than doing
arithmetic on a raw int — necessary because `index = y * width + x` goes wrong whenever the *width*
changes, including on a far-side growth where the offset is zero and nothing appears to have
happened. Consumers: the controller's `selected`/`injectTile`/`dragFrom`, the renderer's camera, and
the desktop host's `lastPainted` (`hovered` recomputes from the pointer and heals itself; Android
passes -1 and holds nothing).

`agent-scripts/grow.txt` is the end-to-end proof: build into the far pad, then the near pad, watch
the grid widen 41 → 43 → 46 with every ledger at zero and the landmarks shifting by exactly the
reported +3. ~Half a day.~ One day, with the pull-forward.

**P4 — The explicit fit.** A key, a HUD button, a harness `fit` command, on top of P3's growth
plumbing — which is now all in place, so this is the trigger and nothing underneath it. Note that an
explicit fit is the first thing that can *shrink*, and §5 has to arrive with it: P3 only grows.
Half a day.

**P5 — Shrink, or not.** Only if wanted. This is where §5 gets built and where continuous fit becomes
possible if we ever want it.

---

## 10. How we would know it worked

- Every existing test green, with the starter vessel on a fitted grid.
- A new `GridFitTest`: fit is idempotent; a vessel built one tile from an edge grows rather than
  clipping; the pad is exactly 4 on all four sides after an explicit fit; a rock **outside** the box
  leaves the box unchanged, and the box is drawn to machine **footprints**, not anchors.

  ⚠️ This bullet used to read *"a rock is inside the box"*, contradicting §8, which spends a section
  establishing the opposite. That contradiction is not hypothetical: a first attempt at P2 read this
  line, enclosed the rock field, and fitted the starter vessel to 92×50 instead of 41×31 — losing the
  entire performance case in §1 while passing its own tests. **Where two sections of a plan disagree,
  the plan is the bug.**
- `momentumBalance`, `massBalance`, `airBalance`, `airHeatBalance`, `heatBalance`, `rockBalance` all
  zero across a resize — the whole point of having six of them.
- A determinism check: a world built, grown three times and fitted digests identically to the same
  world built directly at the final size. **This is the strongest single assertion available** and it
  should be written first, because it catches every field anybody forgot to remap in one go.
- Visually: build a wall off the left edge of the starter vessel and watch the grid grow without the
  camera moving.

---

## 11. Estimate

| Phase | | |
|---|---|---|
| ~~P0 prerequisites~~ | ~~0.5d~~ | ~~no behaviour change~~ | **DONE** ✓ |
| P1 `remapped` + tests | 1.0d | the load-bearing phase |
| P2 fit at construction/load | 1.0d | most of the script fallout lands here |
| P3 grow on demand | 0.5d | |
| P4 explicit fit + camera | 0.5d | |
| **Total (option B)** | **3.5d** | |
| P5 shrink | +1.0d | optional; brings §5 with it |
| Option A instead | +1.5d | continuous fit: §5 becomes mandatory, §6 gets much worse |

---

## 12. Risks, ranked

1. **A field nobody remembered.** Mitigated by the digest determinism check in §10, which is why that
   test is written first rather than last.
2. **The edge fields.** Two index spaces that look like the tile space and are not. Mitigated by
   testing momentum remap on both axes independently, with an asymmetric field so a transposed bug
   cannot pass.
3. **Silent coordinate drift in tests and scripts.** §6. Mitigated by fitting only at construction,
   growing only on the far sides, and the harness frame assertion.
4. **The baselines recomputing on `copy`.** §4 step 6. Cheap to get wrong, and it reads as a ledger
   break a hundred ticks later rather than as a failed remap. The codebase has been bitten by exactly
   this twice.
5. **The performance win not arriving.** §8. Not a defect — a misunderstanding waiting to happen if
   this plan's §1 is read without its §8.
