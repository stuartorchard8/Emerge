# A stamp pastes by class, not by kind

Status: **proposed** (2026-09-09). **The smallest plan in this directory** — one predicate, three
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
furnace and the electrolyzer deliberately share a setpoint — *"a chamber ceiling and a kiln setpoint
are the same setting"* — but they are not one class, and nothing here should let a furnace's dwell
reach a chamber that has no residence time to serve. Class, not "has a field with the same name".

## 2. The change

Three guards, all comparing kinds, all meaning class:

| site | today |
|---|---|
| `OutofspaceController.stampOnto:304` | `standing.kind != settings.kind` |
| `OutofspaceSim.kt:2352` — `Edit.ReplaceDeckMachine` | `oldMachine.kind != edit.machine.kind` |
| `OutofspaceSim.kt:2900` — placing a fresh machine under a stamp | `settings?.kind == kind` |

⚠️ **`MachineSettings.kind` becomes provenance.** It stops being a guard and is read only for the
HUD's label. Worth keeping for that, and worth not trusting for anything else.

⚠️ **No place/paste ambiguity arises**, which is worth stating because it is the obvious objection.
`canStand` refuses **any** occupied tile, so a click on a standing machine could never have meant
"place one here" — before or after this change. The cursor already distinguishes the two: green for a
re-tune, per `reference_oos_build_cursor_plan`.

## 3. Open question

1. **Does a cross-family stamp apply to a *freshly placed* machine?** The third guard above is the
   one that decides it, and relaxing it means grabbing a warehouse, switching the brush to silo and
   placing gets you a silo already wearing the warehouse's filter. Probably wanted — but it is a
   different gesture from re-tuning something already standing, and the two do not have to answer the
   same way. **Carried unanswered from the superseded plan.**

## 4. Explicitly not doing

- **⛔ Taking the facing out of `stampOnto`.** That is the *other* half of what the superseded plan
  called increment 3, and it is **not** independent: paste-over is the only way to change a standing
  machine's orientation today, so removing it before there is another gesture leaves a gap with
  nothing in it. It belongs to `PLAN_machine_relocation.md` and is sequenced after that tool exists.
- **⛔ Generalising `withSettings` beyond what it already does.** It is correct as written; only its
  caller's guard is wrong.
