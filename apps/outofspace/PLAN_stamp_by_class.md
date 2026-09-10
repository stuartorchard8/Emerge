# A stamp pastes by class, not by kind

Status: **BUILT** (2026-09-10). Shipped with the re-aim gesture in §5, which was not part of the
original plan and turned out to belong with it: both are about a paste landing on a machine that is
already standing, and neither reads right without the other.

Was: *proposed* (2026-09-09). **The smallest plan in this directory** — one predicate, three
call sites — and it exists because the argument is worth keeping, not because the change is hard.

Extracted from `PLAN_footprints_and_rotation.md` §1 and §4, which is superseded. It was carried into
`PLAN_machine_relocation.md` for one draft and taken back out (Stu, 2026-09-09): *"it's something I
want, but I think it's totally independent of machine moving."* It is. Nothing here touches a
footprint, an orientation or a location.

## 1. What is wrong

`OutofspaceController.stampOnto:304` refuses unless `standing.kind == settings.kind`, so a
**warehouse's filter cannot be pasted onto a silo** — even though all three store sizes are one
`Storage` class, and `MachineSettings.withSettings` already handles them in a single branch
(`MachineSettings.kt:96`, `is Storage -> Setting.Present(filter)`).

⭐ **That branch's own comment is the tell:**

> *"A capture is only pasted onto a machine of the same kind (see the caller), so this never quietly
> turns a warehouse's settings into a buffer's — it is one branch because the code is identical, not
> because the kinds are interchangeable."*

The kinds **are** interchangeable. A warehouse, a silo and a buffer are one machine at three
capacities — see `project_oos_storage_sizes`, and `Storage.capacity` for where the size actually
lives. ⛔ **The guard is in the wrong units**: it compares kinds where what it means is "would this
setting mean the same thing over there", and `withSettings` already answers that question correctly
one layer down.

⚠️ **The same argument does not extend to the temperature dial, and that is the check on it.** The
furnace and the rocket deliberately share a setpoint — *"a chamber ceiling and a kiln setpoint
are the same setting"* — but they are not one class, and nothing here should let a furnace's dwell
reach a chamber that has no residence time to serve. Class, not "has a field with the same name".

## 2. The change

**One guard moved, and the other two left alone** — which is less than the first draft of this
section proposed, and the reason is worth keeping:

| site | before | after |
|---|---|---|
| `OutofspaceController.stampOnto` | `standing.kind != settings.kind` | ✅ `!settings.appliesTo(standing.kind)` |
| `OutofspaceSim` — `Edit.ReplaceDeckMachine` | `oldMachine.kind != edit.machine.kind` | unchanged, and **not** a family check |
| `OutofspaceSim` — placing a fresh machine under a stamp | `settings?.kind == kind` | unchanged — see §3 |

⚠️ **The reducer's guard is not the same guard.** It compares the machine *standing* against the
machine the edit *carries*, and `stampOnto` builds that machine out of the standing one — so the two
kinds are equal by construction and the check is a sanity assertion about the edit, not a rule about
pasting. Relaxing it to a family comparison would let an edit swap a warehouse for a buffer in place,
which is a resize and not a re-tune, and nothing raises such an edit.

`settingsFamily` lives in `MachineSettings.kt`, next to the `withSettings` branch it is the argument
for: one kind names each family, every kind is its own but the three stores, and a new size joins a
family only when it is genuinely the same machine at a different capacity.

⚠️ **`MachineSettings.kind` becomes provenance.** It stops being a guard and is read only for the
HUD's label. Worth keeping for that, and worth not trusting for anything else.

⚠️ **No place/paste ambiguity arises**, which is worth stating because it is the obvious objection.
`canStand` refuses **any** occupied tile, so a click on a standing machine could never have meant
"place one here" — before or after this change. The cursor already distinguishes the two: green for a
re-tune, per `reference_oos_build_cursor_plan`.

## 3. Still open

1. **Does a cross-family stamp apply to a *freshly placed* machine?** The third guard above is the
   one that decides it, and relaxing it means grabbing a warehouse, switching the brush to silo and
   placing gets you a silo already wearing the warehouse's filter. Probably wanted — but it is a
   different gesture from re-tuning something already standing, and the two do not have to answer the
   same way. **Carried unanswered from the superseded plan, and still unanswered.** ⚠️ It cannot even
   be reached today: `controller.brush`'s setter drops the stamp the moment the *kind* changes, so
   the palette cannot be switched from warehouse to silo with a copy still in hand. Answering this
   question means changing that setter to drop by family too, which is the actual work in it.

## 4. Explicitly not doing

- **⛔ Taking the facing out of `stampOnto`.** ✅ Done separately, in `PLAN_machine_relocation.md`
  increment 3, once `Tool.Move` existed to be the other gesture. This section said it was not
  independent and it was right: it went first, and §5 below is what it left behind.
- **⛔ Generalising `withSettings` beyond what it already does.** It is correct as written; only its
  caller's guard was wrong.

## 5. R over a machine: the paste that *does* turn something

Increment 3 made a paste stop turning what it lands on, which is right — facing is a placement
property — and it left one gesture with nowhere to go: copy-and-paste was also the cheap way to say
*turn this one round*, and the move tool is a press-drag-release for a machine that never actually
moves. So the turn came back **asked for, per machine**, rather than as the default.

**`R` is aimed now.** The host hands the pointer to `rotateBrush(over)`, and it does one of three
things, in order of how specific they are:

1. a machine **in hand** turns (the move tool, unchanged);
2. a machine a **stamped click would re-tune** turns — `controller.reaimed` remembers *which one*,
   and `brushFacing` carries the facing it was given;
3. otherwise the brush turns, and the intent is forgotten.

⛔ **Keyed by the target's anchor, and that is the whole safety argument.** `brushFacing` is one
field and it is also what fresh placements are aimed by, so "the brush points down" cannot on its own
mean "turn whatever I click on" — every machine of that family on the deck would swing the first time
it was pasted onto. Keyed by anchor, a stale intent can only re-apply itself to the machine the
player actually turned, where it is already true.

⭐ **Parity decides how far a turn goes**, because the machine has to land back on the deck it is
standing on:

| shape | example | offered |
|---|---|---|
| sides of equal parity | 3×3 warehouse, 1×1 valve, 1×3 silo | all four facings — R steps a quarter turn |
| sides of unequal parity | 1×2 buffer, 1×2 thruster, 3×2 electrolyzer | two — R flips it end for end |

⚠️ **A candidate with no room is skipped, not offered.** A silo across a corridor cannot swing
through the walls, so R gives it the half turn instead of previewing a paste the reducer would then
decline. The fit is asked with `canStandAfterMoving(anchor, anchor, facing)` — *the same call the
move tool's cursor asks of a turn in place*, so the two gestures cannot come to disagree.

⛔ **A thruster is an ordinary 1×2 and not a special case** (Stu, 2026-09-10). An earlier design had
engines pivoting about their hub; re-laying a footprint is what the move tool is for.

### And the cursor draws the machine that would stand

The re-tune preview used to be drawn from the **brush** — so hovering a buffer with a warehouse
copied showed a 3×3 pointed left over a 1×2 pointed up: a picture of a placement that was not going
to happen, on top of the machine that was. It is drawn from `edit.machine` now, which *is* what the
reducer will stand there: the target's kind, the target's facing, or the turned facing when the
player has asked for one.

### Where it is

`OutofspaceController.reaimed` · `pasteTarget` · `nextFacing` · `rotateBrush(over)`, the preview in
`planAt`, the harness's `turn [n]` command, and `PasteOntoMachineTest`.
