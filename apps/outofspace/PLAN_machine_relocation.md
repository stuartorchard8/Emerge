# Moving a machine

Status: ✅ **BUILT** (2026-09-09), all four increments. Replaces `PLAN_footprints_and_rotation.md`,
which is superseded in full — what carried forward is named in its banner and absorbed below.

| | landed as | |
|---|---|---|
| Increment 0 — the footprint model | `4a71d6c8` | `FootprintTest`, exact-tiles table |
| Increment 1 — a move is a rebuild with two anchors | `34c78b03` | `MachineMoveTest` |
| Increment 2 — the tool | `ef90bbda` | `MoveToolTest`, `agent-scripts/move.txt` |
| Increment 3 — a stamp stops carrying a facing | `6192ff6f` | `GrabAndEscapeTest` |

**Where it landed differently from the scope**, corrected in place below and noted here:

- ⭐ **`reach` came apart into four numbers, not into stated literals.** §5 said the offset tables
  would state their offsets; what they do instead is name a *direction* — `ahead`, `behind`, `below`,
  `above` — which reproduces every current value exactly and lets an oblong kind state its doors
  without a branch. Better than the plan, and the reason is in [Footprint.ahead]'s own doc.
- ⚠️ **A fourth non-footprint caller turned up**, and it read `diameter` rather than `reach`, so the
  §5 list of two had missed it and a grep would not have found it: `OutofspaceRenderer.kt:790`, the
  readouts drawn inside a square body.
- ⚠️ **The round-trip acceptance had to be a differential**, not a before-and-after. Heat conducts
  between casing and room every tick, so the first version read ten megajoules of ordinary conduction
  and called it a leak. See §5.
- ⭐ **`V` rather than an overloaded key**, and `R` needed no precedence rule at all — see §4.
- ⚠️ **Ghost and weld refusals moved to the pick-up**, not the drop, so a carry never starts on
  something that cannot land. The reducer keeps its own copies as a backstop for the agent harness.

> A machine that is standing in the wrong place has to be emptied, condemned, deconstructed onto a
> belt, carried to a stockpile, and built again from a bill. That cycle exists to answer two
> questions — **which stockpile sources this**, and **where does the material land** — and a
> relocation asks neither of them, because the material never becomes loose. It is the same machine,
> made of the same metal, holding the same cargo, at a different address.

## 1. What is actually wrong

### ⭐ The rail loop is answering a question relocation does not ask

This is the whole argument, and it is worth stating before any code. Construction and deconstruction
are routed through the rail network on purpose, and the reason is good: a bill of materials has to
come from *somewhere* and a demolished machine's fabric has to go *somewhere*, and having the network
decide both means the player never picks a stockpile and never nominates a destination. It is decided
by what is plumbed to what.

⭐ **A relocation has neither end of that.** The metal is already assembled, the stores are already
full, the heat is already in the casing, and all of it stays that way. There is no sourcing decision
because nothing is sourced, and no destination decision because nothing is set down loose. Putting a
relocation through the build loop is not charging the player a fair price for it — it is making them
answer a question that was never asked.

⚠️ **The cost this removes is real and is being removed knowingly** (Stu, 2026-09-09): rearranging a
plant becomes far cheaper than it is in the games this is descended from. That is the intended
direction, and §2 decision 6 records the one consequence that goes with it.

### ⛔ There is no gesture for moving a machine at all

Not a slow one — none. `Edit.Rotate` exists but `OutofspaceController.rotate` (`:920`) is called from
`OutofspaceAgentHarness.kt:430` and from nothing else; `R` is bound to `rotateBrush`
(`OutofspaceMain.kt:309`). The only way a player can change a standing machine's orientation today is
to paste a brush over it, because `stampOnto` forces the cursor's facing through
`MachineSettings.aimed()`. So turning is a side effect of re-tuning, and moving is demolition.

### ⛔ A footprint cannot be even in one axis

Carried unchanged from the superseded plan, because it is a fact about the code and not about
rotation. `Footprint.kt` states a kind's size as `diameter`, derives `reach = diameter / 2`, and
builds tiles as `w = rx * 2 + 1`. **Every Square and every Span is odd by construction.** A 3×2 — what
`PLAN_power_network.md` increment 4 wants for the cell — is not expressible. The one even footprint
aboard, the thruster and buffer at 1×2, is `FootprintShape.Nose`: a hand-written branch that bypasses
the arithmetic and sorts its pair into ascending index order by hand.

`reach` is doing service as a footprint half-width **and** as the offset three tables hang their
geometry off — `localPorts` (`Port.kt:71`), `localBufferOffset` (`BufferRole.kt:140`),
`localTerminalOffset` (`Terminal.kt:105`), each opening `val r = machine.reach`. Its own doc admits
the conflation.

⚠️ **This is now an independent track.** It is what the 3×2 cell needs; the relocation tool needs
nothing from it and works with today's shapes. Increment 0 below can land before, after, or instead
of the rest.

## 2. Decisions taken (Stu, 2026-09-09)

1. **⭐ Moving a machine is one edit, not a demolition and a construction.** The casing, the stores,
   the heat, the wiring and the settings travel with it. Nothing is minted, nothing is scrapped,
   nothing goes on a belt.
2. **⛔ The gesture is the placement cursor, not a physical object.** A machine in hand is drawn and
   validated exactly as a machine about to be placed is — see `reference_oos_build_cursor_plan`. It
   is not a rigid body, it has no momentum of its own, and it does not collide with anything.
   ⚠️ **A rigid-body version was scoped and rejected as too complex for what it buys** (Stu,
   2026-09-09) — see §8.
3. **⛔ A machine's own tiles and its own ports do not count as in the way.** Nudging one tile and
   turning in place are the two commonest uses of the tool, and both overlap the machine's current
   footprint. See §3.
4. **⛔ Placing is unconstrained, so there is no legality rule and no pivot.** The whole
   representability apparatus of the superseded plan — `Pivot`, the parity rule, "advance to the next
   representable orientation" — exists to answer *is this turn legal*, and a machine being placed is
   under no such constraint. The question stops being asked rather than being answered.
5. **⛔ Unplumbing is the player's problem** (Stu, 2026-09-09). A moved machine takes its ports with
   it and leaves the belts where they were. The cursor shows what a placement cursor already shows
   and no more — no port-connectivity preview, no warning, no refusal.
6. **⚠️ A loaded machine is a teleporter, and that is accepted for now** (Stu, 2026-09-09). A full
   warehouse dragged across the ship moves twenty tonnes instantly with no belt and no throughput
   limit, which is strictly better than laying a rail line for a one-off bulk move. *"The alternative
   is so much more complex with more open questions that I'm not ready to scope, so we'll just live
   with the teleporter consequence."* Recorded as a known consequence rather than an oversight.
7. **⛔ A ghost does not move**, whether it is building itself up or being taken apart. Reasonable
   limitation, and it keeps `rebuildInPlace`'s existing *"a ghost must stay a ghost"* hazard out of
   this path entirely.
8. **⛔ A connected docking port does not move.** It is a member of a weld — see
   `project_oos_weld_forest` — and severing an assembly is not what this gesture is for.
9. **⛔ A bridge moves like anything else.** Its three slots are `BufferRole.Input`, `Inside` and
   `Product` at ±`r` along its line (`BufferRole.kt:209`), so the role loop that carries every other
   machine's stores carries a gantry's load without knowing it is one. Bridges already survive this
   code path — `Edit.Rotate` on a span goes through it today.
10. **⛔ Orientation changes come exclusively from this tool.** A paste-over never changes the
    target's facing. Facing is a **placement property**, not a setting. Carried from the superseded
    plan, and stronger now that there is a real gesture to carry it.

## 3. The model

### ⭐ The operation already exists and is one parameter away

`rebuildInPlace` (`OutofspaceSim.kt:2748`) is this edit. Read its header:

> Swaps the machine at `centre` for `turned`, moving its casing and its stores with it. A
> demolish-and-rebuild rather than `deck[tile] = turned`, because a span's tiles change … **Booked
> through neither ledger — no metal arrives and none is scrapped, it is the same bridge** — so the
> energy is carried across by hand.

It already reads the casing's material *before* the demolish (`deck.materialOf(before)`, with its own
warning about why the order matters), reads every store by role, demolishes, re-stands **with casing
and material**, re-claims the roles, puts each store back at that role's **new** tile, and spreads the
carried heat over the new tiles.

⛔ **It takes one `centre` and uses it for both sides.** `deck -= centre`, `deck.stand(turned)`,
`buffers.claimRoles(grid, turned, centre)`, `originOf[t] = centre`. Under today's shapes the anchor is
always a fixed point of a rotation, so the assumption has never been false and is therefore invisible.

> ⭐ **The whole of increment 1 is splitting that parameter into a from-anchor and a to-anchor.**

⚠️ **This is the same split the superseded plan had already found**, for a different reason — it
proved that no tile of a mixed-parity footprint survives its own 180° flip, so the anchor moves even
when the block covers the same squares. That argument is retired along with in-place rotation, but the
change it demanded is exactly the change this needs, with a bigger delta. Nothing about that finding is
wasted.

### The moved machine is the same object at a new address

`DeckMachine.movedTo(center)` already exists and already says what it is for: *"The same machine
anchored at `center` — how a world states itself on a different lattice. Re-anchoring is the machine's
own job because only it knows how its footprint hangs off its centre."* Orientation is `rotated()`
applied zero to three times, which preserves every other field.

⚠️ **Not `newDeckMachine(kind, tile, facing)`.** That mints a fresh machine and would silently drop
the settings, the wiring and the carry — which is the whole of what "it is the same machine" means.

### ⭐ The self-overlap exemption, in three places

`canStand` (`Standing.kt:33`) asks three questions, and a move has to ask all three with the machine's
current standing excluded from each:

| check | today | for a move |
|---|---|---|
| `covered.any(occupied)` | any occupied tile refuses | the mover's own tiles are free |
| proposed ports vs `portsOn` | a port on the same conduit refuses | the mover's own ports are not conflicts |
| `displaceAir(covered)` | air must have somewhere to go | unchanged, **but** see below |

The first is already solved once: `canStandWhereItWouldTurn` (`Sim:2736`) reads
`originOf[it] == TileIndex.NONE || originOf[it] == centre`, with the comment *"its own tiles do not
count as in the way: it is standing on them already."* The move needs that same test in `occupied`
**and** in `portsOn`, **and in the preview** — a cursor that reads red for a one-tile nudge is a cursor
that lies about the tool's commonest use.

⚠️ **Air is the one check that is genuinely about the destination and not about the mover.** A solid
machine vacates tiles at the source and displaces air at the destination, and those are different
rooms. Asked with the mover excluded, `displaceAir` is answering about the world as it will be — which
is right — but a machine sealing itself into a room it is also the wall of is the case to have a test
for.

### ⛔ Not one ledger term is needed, and that is worth checking rather than assuming

`massBalance`'s own doc is unambiguous about the stakes:

> ⚠️ **Every new way for matter to cross the vessel's boundary has to be added here**, and the failure
> mode when it is not is not an error — it is an instrument that reads a leak for ever and that
> everyone learns to ignore.

⭐ **Nothing crosses.** The casing stays in `deck.stuff`, the stores stay in `buffers`, and both stay
inside `builtMass` and `inTransitMass` respectively. No `extractedMass`, no `ventedMass`, no
`scrapped`. That is precisely why `rebuildInPlace` is *"booked through neither ledger"* today, and
moving the anchor does not change it.

Linear and angular momentum likewise: moving mass **within** a vessel changes neither total, and both
are stored (`vesselImpulseX`, `angImpulse`). `momentumBalanceX/Y` needs nothing.

### ⚠️ What does change is the moment of inertia, and a spinning ship will lurch

Angular **momentum** is what is stored, not angular velocity, so `ω = L / I` and moving a heavy machine
changes `I`. And per `PLAN_com_anchored_frames.md` the vessel stores its grid origin and turns about
its *computed* centre of mass, so the pivot moves too.

On a stationary ship this is invisible. On a rotating one, dragging a loaded warehouse changes the spin
rate and swings the centre of rotation, and the whole vessel visibly lurches. ⛔ **This is a
discontinuity, not a leak** — accepted knowingly (Stu, 2026-09-09) — but it is the form the accepted
"COM jump" actually takes, and it is more visible than a static shift.

⭐ **No torque is involved and none may be booked** (Stu, 2026-09-09). Angular momentum is conserved
trivially, because nothing crosses the vessel boundary and internal rearrangement is not an external
twist; the change in angular *velocity* is a consequence, not a force. ⚠️ **And it costs nothing to
implement**: `Rotation.kt` derives the vessel's mass, centre of mass and gyration radius by walking
`forEachVesselMass` over rail, conduits, deck and buffers — none of it is stored, while `angImpulse`
and `vesselImpulse` are. So `ω = L / I` recomputes on the next tick with no code aware a move
happened. ⛔ **A torque term here would be a second way to spin the ship with nothing on the other
side of it**, which is exactly the defect `angularBalance` exists to report.

⚠️ **There is a linear counterpart, and `PLAN_com_anchored_frames.md` will invert it.** An isolated
body's centre of mass may not jump either: shifting a machine to starboard should slide the rest of the
ship to port so the world-frame COM keeps travelling straight. Today the vessel stores its **grid
origin**, so the grid holds still and the COM jumps instead — `momentumBalance` does not notice,
because it sums impulses and not positions. Once the stored position *is* the COM, the behaviour flips:
the COM holds still and the **grid visibly slides**, which is the correct one and which is a translation
you would see on a ship that is not spinning at all. Recorded so that change is not read as a
regression in this tool.

## 4. The gesture

A new `Tool.Move`, alongside `Build` and `Delete` in `Tool.kt`. **Press, drag, release** (Stu,
2026-09-09).

- **Press on a machine** — any tile of it; the reducer already resolves a tile to an anchor through
  `originAt`, which is how a click on a warehouse's corner edits the warehouse.
- **It is carried while the button is down**, drawn under the cursor as a placement preview: cyan
  where it fits, red where it does not, per `reference_oos_build_cursor_plan`. ⚠️ **Green does not
  arise** — a move is never a re-tune.
- **R turns the carried ghost**, one quarter-turn, with no legality question (decision 4).
- **Release over a valid destination** commits one `Edit.Move(from, to, facing)`.
- **⭐ Release anywhere else cancels**, changing nothing. The refusal *is* the cancel, so there is no
  separate cancel key and no state a player can get stuck in: let go and the machine is either moved
  or exactly where it was.

⛔ **The world is not edited until the button comes up.** Everything above is controller state, exactly
as the build cursor already is, which is what keeps a half-finished gesture out of the save and out of
the reducer.

### ⭐ `R` needs no precedence rule after all

`R` is bound to `rotateBrush` (`OutofspaceMain.kt:309`) and `OutofspaceHud.kt:445` hard-codes the hint
`"R rotate brush"`. The superseded plan needed a **precedence rule** for it — *"R turns the brush if
you are holding one, and the machine under the pointer if you are not"* — and argued at length about
the case where you are laying a row and hovering a neighbour.

That whole problem dissolves. A carried machine **is** what is on the cursor, so `R` means one thing:
turn what you are holding. There is no pointer-versus-brush ambiguity because there is no pointer
target — you cannot be carrying a machine and holding a brush at once. ⚠️ The hint text still has to
say which of the two it currently means.

## 5. What must be true, and what will hurt

**A move that goes nowhere changes nothing.** ⛔ The acceptance that matters most: pick a stocked,
warm, wired machine up and put it back where it was, and every ledger reads what it read before —
`massBalance`, `airBalance`, `momentumBalanceX/Y`, the stores gram for gram, the casing's material, the
energy, the wiring, the settings, the progress and the carry.

**⚠️ `angularBalance` cannot be asserted at zero.** It is **already non-zero on today's code, on
purpose**: `netTorque` books `pressureTorque` onto the ship with nothing on the other side, and
`Vessel.kt` says so in as many words. The acceptance here is a **delta across the gesture**, not an
absolute. A test that expects zero would be red for a reason that has nothing to do with this plan, and
would teach someone to widen it.

**A move that overlaps itself is the common case, not the corner.** One tile left, and turned in place,
are what the tool is *for*. Both must be cyan.

**The stores land by role, not by tile.** A bridge's three slots are the proof: `Input` at `-r`,
`Inside` at the middle, `Product` at `+r` along the new facing. Nothing may pair a store with a tile
index across the move.

**Heat is spread, not corresponded.** `rebuildInPlace` already says why: *"the tiles are not the same
tiles, so there is no per-tile correspondence to preserve."* True across a move for the same reason and
more obviously.

**Three refusals have to be reachable and legible**: a ghost, a welded docking port, and a destination
that does not fit. The first two are new refusals with no precedent in the cursor, so they need a way
to read as refusals rather than as the tool being broken.

## 6. Increments

**Each increment is one commit on `main`**, green before it lands.

### Increment 0 — the footprint model, and not one tile moves

*Carried unchanged from the superseded plan, and now independent of everything below.*

`Footprint(width, height, anchor)` as a value; `footprint()` built from it; every kind restated.
`diameter`, `reach` and `FootprintShape` deleted. ⛔ Its acceptance is an exact-tiles table — every
kind, every facing, the exact tiles — captured from the current code **first**. A test that says "it
still fits" is not that test.

⚠️ **There is no `pivot` field.** The superseded plan's fourth component existed to answer a question
decision 4 deletes.

⛔ **Four non-footprint callers need a half-width of their own, and one of them is not a `reach`
caller** — so a grep for `reach` will not find it and increment 0 will not compile:

| site | what it wants |
|---|---|
| `Docking.kt:111,114` | berth standoff, `port.reach + 1` for a 3×3 collar |
| `OutofspaceSim.kt:3141` (`reachedBody`) | the extractor's square bite radius |
| `OutofspaceSim.kt:3158` (`bite`) | the same, second site |
| **`OutofspaceRenderer.kt:790`** | `m.kind.diameter`, for readouts drawn *inside* a square body |

⚠️ **Two test files name what is being deleted**: `FootprintTest.kt:212` asserts
`Extractor.diameter == 5`, and `BufferRoleTest.kt:79` reads `m.reach`. `FootprintTest` already holds
fifteen tests, so this increment extends that file rather than writing a table from nothing — and its
`rotating leaves the footprint where it was and moves only the ports` (`:114`) is the square assumption
this removes, written down as an assertion.

⚠️ **Ascending index order is load-bearing, and the requirement is narrower than it looks.** Two walks
of one machine agree for free, because `energy` and `setEnergy` both call `tiles(grid)` in one
round-trip. What actually has to hold is that the order is a function of **the grid, not the facing** —
which every current shape has (`Span` orders off `rx`/`ry` and ignores facing; `Nose` hand-sorts to get
it). The natural new builder, walking the machine's own `w × h` frame and mapping through the facing,
gives local row-major order and quietly loses it.

⭐ **A 3×2 is expressible at the end of this and nothing is one.** `PLAN_power_network.md` increment 4
is unblocked here, without any of the increments below.

### Increment 1 — a move is a rebuild with two anchors

`rebuildInPlace(from, to, before, moved)`. `Edit.Move(from, to, facing)`. The three self-overlap
exemptions. The three refusals. No tool and no cursor — driven from the reducer and from the agent
harness, which is where `Edit.Rotate` has always been driven from.

⚠️ **Its acceptance is the round trip of §5**, and it is worth writing before the tool exists, because
a bug here is matter appearing or vanishing rather than a machine looking wrong.

### Increment 2 — the tool

`Tool.Move`, the carried preview, Q/E, Escape, and the HUD hint. ⚠️ **First increment in which a player
can move or turn a machine at all**, so it is also the first in which the paste path is not the only
way to change a facing.

### Increment 3 — a stamp stops carrying a facing

*Carried from the superseded plan.* `MachineSettings.aimed()` leaves the re-tune path
(`OutofspaceController.stampOnto:312`) and stays on the placement path (`OutofspaceSim.kt:2900`), so
pasting a capture onto a machine already standing re-tunes it without turning it. Decision 10, made
reachable.

⚠️ **After increment 2, deliberately, and this is the only reason this increment is in this plan at
all.** Paste-over is the only way to change a standing machine's orientation today. Taking the facing
out of it before the move tool exists would leave a gap with no gesture in it — so this is genuinely
downstream of the tool, unlike the *other* half of what the superseded plan called increment 3. See
§8.

## 7. Open questions

1. ✅ **The carry is press-and-drag-release, and `R` turns the ghost** (Stu, 2026-09-09). Releasing
   over a destination that will not take it cancels the move — settled, see §4.

None outstanding.

## 8. Explicitly not doing

- **⛔ A machine as a rigid body.** Scoped in full and rejected (Stu, 2026-09-09): grab a machine,
  turn it into a `RigidBody`, carry it on the cursor with every impulse booked in reverse through
  `BodyStep.handedX/handedTorque` so momentum conserves by construction, and snap it back to the grid
  on release. **It is buildable** — `BodyKind.FRAGMENT` is already declared as "a machine casing torn
  loose by dismantling", `RigidBody` already carries `machineKind` as provenance, and `BodyStep`'s doc
  already says *"every gram·tile of momentum the vessel gave the bodies this tick, **by any means**"*.
  What killed it was the rest: `RigidBody` can carry a kind but not a *tuned machine* with its
  settings, wiring and four stores; picking up would be a new mass **and** energy crossing in both
  directions, with the ledger term that implies; the tool's grip has to be a constraint whose reaction
  is booked, and the choice between a spring and a velocity constraint decides whether a dragged
  extractor is charming or a manoeuvring thruster; a held body either collides with a deck that is
  full, or passes through the ship it is booking momentum against. Two to three times this plan, with
  the risk in decisions rather than in code. ⚠️ **Recorded because the pieces are real** — if a
  physical carry is ever wanted, this is what it costs and none of it has to be rediscovered.
- **⛔ In-place rotation as its own edit.** `Edit.Rotate` and `canStandWhereItWouldTurn` stay for the
  agent harness, but no player gesture reaches them and no pivot or parity model is built. Turning is
  a move that happens not to change address.
- **⛔ Any port-connectivity preview.** Decision 5. A moved machine leaves its belts behind and the
  player deals with it.
- **⛔ Making a stamp paste by class.** It was carried in here for one draft and taken back out (Stu,
  2026-09-09) as *"totally independent of machine moving"*, which it is — it touches no footprint, no
  orientation and no location. `PLAN_stamp_by_class.md`, along with the open question about whether a
  cross-family stamp applies to a freshly placed machine. ⚠️ **Not to be confused with increment 3
  above**, which is the other half of what the superseded plan bundled under one heading: taking the
  *facing* out of a paste **is** downstream of this tool, because paste-over is the only way to turn a
  machine until the tool exists.
- **⛔ Charging anything for a move.** Not asked for. If a cost is ever wanted, the spin lurch of §3 is
  already a soft one.
- **The 3×2 cell.** `PLAN_power_network.md` increment 4, and what increment 0 is *for* — but a
  footprint model verified against machines that already exist is verified against cases where a
  regression is loud.
