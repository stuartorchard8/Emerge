# Out of Space

A 2D side-on management/automation game about building and running space vessels.

Design and phasing: **`docs/out-of-space-plan.md`**. Read that first — it records the decisions
(square lattice, vessel-local gravity kept as an explicit parameter, no inhabitants yet, first
playable is one vessel interior with no flight) and what is deliberately deferred.

## State of the app

**Phase 1 (chemistry) is built.** `core/…/chem/` is real and is the layer everything else will be
built to serve:

| File | What it is |
| --- | --- |
| `chem/Mixture.kt` | `Mineral`, and `Mixture` — integer grams per mineral. Plus `apportion`, the exact proportional split every other operation rests on. |
| `chem/Form.kt` | `Form` (what matter has been made into), the smelt table, and the binary crafting tree as data. |
| `chem/Chemistry.kt` | `smelt`, `process`, `craft`, `merge`, `takeFrom`, `conservationOf`. |

**Everything else is still the template's placeholder** — the bouncing-disc sim, its renderer and
its HUD (`OutofspaceSim.kt`, `OutofspaceRenderer.kt`, `OutofspaceHud.kt`). It is left in place only
so the app runs and the hosts stay exercised; the Phase 2 tile grid replaces it wholesale.

```bash
./gradlew :apps:outofspace:core:jvmTest    # 27 chemistry tests, ~80ms
./gradlew :apps:outofspace:desktop:run     # placeholder world
```

## The two rules of the chemistry layer

**Mass is an integer.** Floats would make the same world diverge between two machines and make
conservation approximate. "Where did the mass go" is the only bug this simulation is really capable
of having, so it is made exactly checkable.

**Conservation is structural, not tested-for.** Every operation that divides matter computes *one*
output and derives the other as `input - output`. There is no arithmetic path that can lose or
invent a gram. `conservationOf` exists so tests can assert it mineral by mineral — a total can
balance while iron quietly turns into copper.

## Where the ideas came from

The blended resource model is carried over from the Godot game at `~/out-of-space` (which stands on
its own and is not being replaced): ore is never "iron ore", it is a composition of minerals, and
refining efficiency is capped by the input's own purity. See `docs/out-of-space-plan.md` §2 for what
was taken, what was left behind, and the one deliberate divergence from its recipe table (the named
`*_ore` forms are gone; smelting blended ore is the only route into tier one).
