# Footprints, rotation, and the difference between placing a thing and turning it

Status: **proposed** (2026-09-09). Prerequisite for `PLAN_power_network.md` increment 4, which wants
a 3×2 cell and cannot have one. Replaces three stated designs rather than adding to them:
`FootprintShape`, `DeckMachine.rotated`, and the paste-over half of `PLAN_build_shortcuts`.

> A machine's footprint is stated as a **half-width**, so every footprint is odd and square. Its
> rotation is stated as **one quarter-turn of a facing**, so every machine turns the same way about
> the same point. Neither is true of the machines already aboard, and the exceptions are hand-written
> branches rather than a model. This is the model.

## 1. What is actually wrong

### ⛔ A footprint cannot be even in one axis

`Footprint.kt` states a kind's size as `diameter`, derives `reach = diameter / 2`, and builds the
tiles as `w = rx * 2 + 1`. **Every Square and every Span is odd by construction.** A 3×2 is not
expressible, and neither is anything else with an even side.

The one even footprint in the game — the thruster and the buffer at 1×2 — is `FootprintShape.Nose`,
which is a *hand-written branch* that bypasses the arithmetic entirely: it builds the anchor and one
neighbour and then sorts the pair into ascending index order by hand. It is not a size the model can
state; it is a special case with a comment.

### ⛔ `reach` means two different things and says so

Its own doc admits it: *"This is not 'how big the machine is' and it never was — it is where a square
kind's ports and stores sit … For a Nose or a Span it answers about the machine's width, and a
footprint rebuilt from it would be wrong."*

So one number is doing service as a footprint half-width **and** as the offset that three separate
tables — `localPorts`, `localBufferOffset`, `localTerminalOffset` — hang their geometry off. For the
square kinds those coincide. For anything else they do not, which is why `FootprintShape.Nose`'s doc
has to explain that a buffer's ports are *stated* rather than derived, *"at one tile wide `reach` is
zero, and both its doors would land on the same tile."*

### ⭐ Rotation conflates the turn with what it turns about

Every one of the eleven `rotated()` implementations is the identical
`copy(facing = facing.clockwise)`. One quarter-turn, no exceptions, and the reducer's `Edit.Rotate`
still opens with *"Rotation: footprints square, so covered tiles unchanged — only ports move"* and
then bolts the bridge on as an "except".

But a thruster and a buffer are the same shape and **turn about different points**, and that is a
fact about the machines rather than about their geometry (Stu, 2026-09-09):

> When I rotate a thruster, it makes sense to have the input point as the anchor — it's like pivoting
> the bell of the thruster around the anchor, the bell genuinely isn't fixed to the hull except
> through its connection to the thruster hub. When I rotate a storage buffer, the anchor ought to be
> half way between the two tiles it occupies, since both tiles are equally attached to the vessel
> grid itself.

⭐ **The buffer was given the thruster's shape because it was also 1×2, and inherited the thruster's
physics by accident.** That is the conflation, and it is the whole of §2.

### ⚠️ Paste-over carries an orientation it has no business carrying

`MachineSettings.aimed()` exists to force the build cursor's facing onto a machine already standing —
its doc says so plainly: *"what makes rotating the brush and clicking a machine already on the deck a
way of turning it."* That was the only way to turn a machine, because **the rotate edit is not
reachable from the UI at all**: `OutofspaceController.rotate` is called by the agent harness and by
nothing else, and `R` is bound to `rotateBrush`.

So turning is a side effect of pasting, and pasting an orientation the target cannot take fails the
whole paste rather than the orientation.

### ⚠️ Paste-over matches on kind where it means class

`stampOnto` refuses unless `standing.kind == settings.kind`, so a warehouse's filter cannot be
pasted onto a silo — **even though all three store sizes are one `Storage` class** and
`withSettings` already handles them in a single branch. That branch's comment is the tell:

> *"A capture is only pasted onto a machine of the same kind (see the caller), so this never quietly
> turns a warehouse's settings into a buffer's — it is one branch because the code is identical, not
> because the kinds are interchangeable."*

The kinds **are** interchangeable, because they are one machine at three sizes — see
`project_oos_storage_sizes`. The guard is in the wrong units.

## 2. Decisions taken (Stu, 2026-09-09)

1. **⭐ A footprint states a width and a height, not a half-width.** Odd, even, square and oblong are
   the same kind of statement. `diameter` and `reach` come out.
2. **⭐ A footprint states its pivot, and the pivot is a physical claim.** `Anchor` for a machine one
   of whose tiles is bolted to the hull and the rest hangs off — the thruster's bell. `Centre` for a
   machine every tile of which is equally attached — the buffer. This is the split `FootprintShape`
   was hiding.
3. **⛔ A 90° turn is legal only where it is representable, and 180° always is.** See §3 for the
   arithmetic; it is not a judgement about each machine.
4. **⛔ Placing is not rotating.** A player choosing an orientation for a machine that does not exist
   yet is under no constraint at all. Legality in decision 3 governs `Edit.Rotate` and nothing else.
5. **⛔ Orientation changes come *exclusively* from an explicit instruction to rotate.** A paste-over
   never changes the target's facing. Facing is a **placement property**, not a setting.
6. **A machine is rotated by hovering it in build mode and pressing R**, by one increment — 90° or
   180° according to its footprint. ⚠️ The precedence against today's `rotateBrush` is §7's open
   question.
7. **⛔ A stamp pastes where the machine *class* matches, not the kind.** A warehouse's settings go
   onto a silo.
8. **Rotation advances to the next representable orientation**, rather than refusing when the next
   quarter-turn is not representable. A 3×2 flips; it does not sit there declining.

## 3. The model

### A footprint is four numbers and a pivot

```
Footprint(width, height, anchor, pivot)
```

- `width`, `height` — in the machine's own frame, the one every offset table already states its
  offsets in (facing Right, turned by `DeckMachine.turns`).
- `anchor` — which tile of the block the deck stores the machine at, and which `Occupancy` points
  every covered tile back at. It is **not** necessarily the middle; `DeckMachine.center` has said so
  since `Nose` landed and forbids anything reconstructing a footprint from a centre and a half-width.
- `pivot` — `Anchor` or `Centre`. What a rotation turns the block about.

⛔ **It is a value, not a property of the enum.** `DeckMachineKind.footprint(...)` becomes
`Footprint.tilesAt(anchor, grid, facing)`, so a shape can be tested without standing a machine kind
up to hold it — which is what lets increment 0 prove a 3×2 works before any machine is one.

### ⭐ The legality rule falls out of parity, and needs no table

A `w × h` block's centre sits at a tile centre when both are odd, at an **edge midpoint** when one is
even, and at a **vertex** when both are. Turning it 90° about that centre lands the tiles back on the
grid exactly when the centre's two coordinates have the same half-integer character — that is:

> ⭐ **A 90° turn about the footprint centre is representable iff `width` and `height` have the same
> parity. A 180° turn always is, because it maps the block to itself.**

Checked against what is aboard: bridge and silo are 1×3, both odd, so they turn — which they do
today. The buffer is 1×2, mixed, so it cannot. A 2×2 would turn fine, about a corner. And the cell
`PLAN_power_network.md` wants at 3×2 is mixed, so it has **two** orientations, both always legal.

⚠️ **`Pivot.Anchor` is unconstrained.** The anchor is a tile and stays one, so the block may swing to
any of the four facings — which is the thruster's behaviour today, preserved exactly.

### What each existing kind becomes

| kind | today | width × height | anchor | pivot | 90°? |
|---|---|---|---|---|---|
| hull, airlock, vent, sensor, button, pump, gauge, valve, terminal | Square d=1 | 1×1 | the tile | Centre | trivially |
| warehouse, concentrator, furnace, electrolyzer, rocket, dock, panel | Square d=3 | 3×3 | centre | Centre | yes, tiles unchanged |
| extractor | Square d=5 | 5×5 | centre | Centre | yes, tiles unchanged |
| bridge, silo | Span d=3 | 3×1 | centre | Centre | yes, tiles move |
| **thruster** | Nose | 2×1 | **tail tile** | **Anchor** | yes, bell swings |
| **buffer** | Nose | 2×1 | **tail tile** | **Centre** | **no — 180° only** |

⚠️ **The buffer is the one behaviour change in this plan**, and it is decision 2 applied: a store's
two tiles are equally bolted down, so turning it ninety degrees would move it half a tile, which is
not a move it can make. It can still be *placed* in any of the four orientations (decision 4); what
it loses is turning a horizontal one vertical in place. Demolish and re-place is the answer, which is
already the answer `Edit.Rotate` gives a bridge with something in its swing.

### Rotation, restated

`rotated()` stops being eleven copies of `facing.clockwise` and becomes one rule: **advance to the
next representable orientation.** One quarter-turn for `Pivot.Anchor` and for same-parity blocks; two
for a mixed-parity block. `Edit.Rotate` keeps `canStandWhereItWouldTurn`, because representable and
*unobstructed* are different questions and the second one still has to be asked.

## 4. Paste, and what a stamp is for

### ⭐ Facing is a placement property

`aimed()` has exactly two callers and they are the two halves of decision 5:

- `OutofspaceSim.kt:2895` — a **newly placed** machine takes the cursor's facing. Legitimate; stays.
- `OutofspaceController.stampOnto:312` — a **re-tune** forces the cursor's facing onto a machine
  already standing. Goes.

So this is a split rather than a deletion: `grab` still copies a machine's facing onto the brush, so
copying a machine still places the next one the same way round. What stops happening is a paste
turning something that was already there.

⚠️ **`Setting.Absent` still has to mean what it means.** `aimed()`'s existing note — that turning
*"this kind has no such setting"* into *"this kind faces right"* would hand a facing to every machine
that has none — is unaffected and stays true of the placement path.

### The guard becomes a class

Three places enforce same-kind and all three become same-class:

| | |
|---|---|
| `OutofspaceController.stampOnto` | `standing.kind != settings.kind` |
| `OutofspaceSim` `Edit.ReplaceDeckMachine` | `oldMachine.kind != edit.machine.kind` |
| `OutofspaceSim.kt:2895` | `settings?.kind == kind`, for placing a fresh machine under a stamp |

⚠️ **No place/paste ambiguity arises**, which is worth stating because it is the obvious objection.
`canStand` refuses **any** occupied tile, so a click on a standing machine could never have meant
"place one here" — before or after this change. The cursor already distinguishes the two: green for a
re-tune, per `reference_oos_build_cursor_plan`.

⚠️ **`MachineSettings.kind` becomes provenance.** It stops being a guard and is read only for the
HUD's label. Worth keeping for that and worth not trusting for anything else.

## 5. What must be true, and what will hurt

**The refactor must not move one tile.** Increment 0 restates every footprint in the game, and the
one thing that would make it dangerous is a machine quietly covering a different set of tiles.
⛔ **The acceptance is a table**: every kind, every facing, the exact tiles — asserted against the
values the current code produces, captured before the change. A test that says "it still fits" is not
that test.

**Ascending index order is load-bearing.** `footprint()` returns tiles in row-major order and its doc
says why: several places pair a footprint with an array of per-tile values (`DeckMachine.energy`
against `setEnergy`), so two walks of one machine must agree. The `Nose` branch sorts its pair by
hand to keep this. The general builder must produce the same order for every shape, and the table
above is what proves it.

**The three offset tables stop deriving from `reach`.** `localPorts`, `localBufferOffset` and
`localTerminalOffset` all open with `val r = machine.reach`. For square kinds `±r` is still exactly
right and the literals can be written as they are today; for the oblong ones the offsets are
**stated**, which is what the `Nose` machines already do and for the reason the file already gives.

**Two other callers of `reach` are not about footprints at all.** `Docking.kt`'s berth standoff is
`port.reach + 1` for a 3×3 collar, and the extractor's bite radius is a square reach around its
anchor. Both want "half-width of a square machine" and should say so rather than ride a footprint
concept that no longer means that.

**Nothing else assumes square.** Checked: the renderer already draws non-square footprints over a
bounding box rather than off a diameter — the silo forced that — and `MAX_CACHED_FOOTPRINT` is 32, so
a six-tile machine caches like any other. `canStand` reads `kind.footprint(...)` and needs no change.

**⚠️ R is already bound**, to `rotateBrush`. Increment 2 is not "bind a key", it is "decide a
precedence" — see §7.

## 6. Increments

**Each increment is one commit on `main`**, green before it lands.

### Increment 0 — the model, and not one tile moves

`Footprint(width, height, anchor, pivot)` as a value; `footprint()` built from it; every kind
restated. `diameter`, `reach` and `FootprintShape` deleted, with the two non-footprint `reach`
callers given a half-width of their own. ⛔ Its acceptance is the exact-tiles table above, captured
from the current code first. **No behaviour changes here at all** — including the buffer's, which
waits for increment 1 so that a refactor and a decision are not in one commit.

⭐ **A 3×2 is expressible at the end of this and nothing is one**, which is the point: the shape is a
value, so a test can state one without a machine kind existing to hold it.

### Increment 1 — rotation turns about the pivot

`Pivot` starts being read. `rotated()` becomes "the next representable orientation". The buffer moves
to `Pivot.Centre` and loses its 90°, which is the one behaviour change and gets its own note in the
commit message. `Edit.Rotate`'s "footprints square" comment goes.

### Increment 2 — R turns the machine under the pointer

The binding, the precedence rule §7 settles, and a refusal the player can see when
`canStandWhereItWouldTurn` says no. ⚠️ First increment in which a player can turn a placed machine
without going through the paste path.

### Increment 3 — a stamp pastes by class

The three guards; `aimed()` leaves the re-tune path and stays on the placement one.
⚠️ **After increment 2, deliberately** — paste-over is the only way to turn a machine today, so
taking the facing out of it before R exists would leave a gap with no gesture in it.

### ⏸ Then: `PLAN_power_network.md` increment 4, with the cell at 3×2

Ports and baths on the lower edge, terminals on the upper. ⭐ **That layout deletes a mechanism the T
needed**: with a port and its store on the same tile, `BufferRole`'s *"a store sits on the port it
serves"* holds unmodified and the cell no longer needs `Storage`'s exception generalised. ⚠️ It also
retires the plan's argument that a terminal sharing a tile with an output port forces an insulating
segment — see that plan's §"the ports become a T", which this supersedes.

## 7. Open questions

1. ⛔ **R's precedence, and it needs an answer before increment 2.** R turns the brush today.
   - (a) Hovering a machine turns *it*, else the brush. ⚠️ While laying a row you are often hovering
     a neighbour, and R would turn that instead of your brush.
   - (b) The machine only when the brush is empty; with a brush in hand, always the brush.
     Unambiguous, but turning a machine means dropping what you are holding first.
   - (c) Two keys.
2. **Does a cross-family stamp apply to a *freshly placed* machine?** `OutofspaceSim.kt:2895` is the
   third guard, and relaxing it means grabbing a warehouse, switching the brush to silo and placing
   gets you a silo wearing the warehouse's filter. Probably wanted; stated because it is a different
   gesture from re-tuning something already standing.
3. **Is the buffer losing its in-place 90° acceptable?** It follows from decision 2 and it is a
   capability a shipped machine has today.

## 8. Explicitly not doing

- **The 3×2 cell.** It is `PLAN_power_network.md` increment 4 and it is what this is *for*, but a
  footprint model verified against machines that already exist is verified against cases where a
  regression is loud. A brand-new machine with a wrong footprint just looks like a design choice.
- **`METALLIC_CONDUCTION_MILLIWATTS`.** A live defect, unrelated, and Stu's call because it re-tunes
  friction.
- **The three red tests** in `ConcentratorBankTest` and `HeatTest`. In-progress concentrator work
  (Stu, 2026-09-09) — not to be fixed from here.
- **A general machine-shape editor, or footprints that are not rectangles.** An L-shaped machine is
  not asked for and every mechanism here assumes a rectangle. If one is ever wanted, this is the
  model to extend rather than the one to work around.
