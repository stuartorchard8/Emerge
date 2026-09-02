# Build shortcuts, part two — a key per tool

Scope only. Nothing here is built. Written 2026-09-02 against `d18ddcad`, on top of the C/ESC work
in `b78e6fcc` + `f38e938f`.

## The idea

Right now the tools are reached by *cycling*: `Q` steps through all seven of them and `E` steps
through whichever sub-target the current one happens to have. That is one key to learn and a
lottery to use — reaching CUT from BUILD is four presses of `Q`, and the number of presses changes
whenever a tool is added.

The proposal replaces it with **a key per tool, and the same key aims it**:

| key | opens | and cycles |
|-----|-------|------------|
| `B` | BUILD | the palette (`Brush.ALL`) |
| `C` | BUILD **holding a copy** of what the inspector reads | — (already built) |
| `X` | DELETE | `DeleteLayer` — TOP / BRIDGE / RAIL / PIPE / WIRE / DECK / ALL |
| `Z` | CANCEL | nothing; it has no sub-target |
| `Q` | CUT | `Tool.CUTTABLE` — RAIL / PIPE / WIRE |
| `E` | — | the **material**, independently of which tool is out |
| `ESC` | one rung out — unchanged | |

`E` is the odd one and deliberately so: a material is not a tool, it is a standing choice that
survives changing tool, so it gets a key rather than a slot in some tool's sub-cycle. That is
already how `buildMaterial` is documented.

## Open build → pick a material

> Opening build (not via C) should auto-select the most abundantly available *loose* valid material
> **if material is null**. A non-null selection is always respected, even if it is not available.

The first half is nearly free: `Stockpile.buildableSpecies` is already "loose only, heaviest first",
which is exactly "most abundantly available". So the rule is `buildMaterial ?: stock.buildableSpecies.firstOrNull()`.

Three things it has to get right:

- **`C` must not go through it.** `grab()` sets the material off the thing it copied; an auto-pick
  running afterwards would either overwrite that or be a no-op that reads like luck. The auto-pick
  belongs in a new `openBuild()`, and `grab()` keeps its own path.
- **Non-null is respected even when unavailable**, which is already true of the field and must stay
  true: `buildMaterial` is a `Species?` with no validity check, and a ghost laid in a metal nobody
  has is a legitimate thing to do — it waits. The auto-pick fires *only* on null.
- **Creative.** `buildableSpecies` counts what is actually aboard; `Stockpile.CREATIVE_MATERIALS` is
  offered separately and is not in that list. On an empty creative ship the auto-pick would find
  nothing and leave the palette unusable while the picker visibly offers four materials. Needs a
  fallback to the creative list when `state.creative`.

⚠️ **Cost.** `VesselState.stockpile` is a `get()` that sweeps every buffer, every belt, the whole
deck and every conduit layer — the HUD comments say so and take care to ask once a frame. Once per
*keypress* is fine; a `defaultMaterial` computed in `planAt` or in a getter would not be.

⛔ **"Valid" is currently not per-brush.** There is no rule anywhere that says a given
`DeckMachineKind` cannot be made of a given `Species` — the picker offers the same list whatever the
brush is, and `BUILD_PURITY_PERCENT = 100` is enforced against the *bill* at the tile, not against
the kind. So "valid" today means "loose aboard" and nothing more. If it should come to mean more
than that, that is a separate change and a bigger one.

## What this costs elsewhere

- **`Q` stops cycling tools.** That is the point, but it is also the only keyboard path to INSPECT
  and the *only* path of any kind to INJECT and WATER except the tool buttons in the bottom-left
  panel. INSPECT is fine — `ESC` reaches it, and that is now the documented way back. The two debug
  tools would become mouse-only. Options: leave them mouse-only (they are debug), give them `F`-keys
  alongside the other debug bindings (`F5`, `F6`), or keep a wrap-around cycle on some key.
- **`E` stops cycling sub-targets**, so the two HUD hint lines that say `E cycles layer` and
  `E cycles conduit` become wrong. Both need to name `X` and `Q` instead. The bottom-left panel's
  key-hint block needs a rewrite anyway — it currently names `Q tool`, which will be a lie.
- **`TAB` also cycles the palette** and would duplicate `B`. Keep as an alias or drop; no strong
  view, slight preference for dropping it so there is one answer.
- **Number keys `1`–`9`/`0` set the brush without switching to BUILD.** Pressing `3` while holding
  DELETE silently changes what you would build if you were building. Pre-existing oddity, but this
  change makes it conspicuous — they should route through the same `openBuild()`.
- **Flight mode is safe.** `Q`/`E`/`Z`/`X` are pilot keys, but the flight branch returns before any
  of this is reached. No conflict.

## The one real design question

**Does the opening press also advance the sub-target?**

Recommendation: **no.** First press opens the tool and leaves its aim where it was; a *second* press
advances. Otherwise `X` can never leave you on TOP, `Q` can never leave you on RAIL, and — worst —
`B` could never leave you in the empty palette, which is a real state the ESC ladder puts you in and
that a click reads a tile from. Open-then-cycle keeps every state reachable from the keyboard.

The cost is that reaching DELETE·DECK from INSPECT is `X` then five presses of `X`, which is the
same count the old `E` scheme had once the tool was out.

## Shape of the work

Small, and mostly in two files.

1. `OutofspaceController` — `openBuild()` (auto-pick + tool), `cycleDeleteLayer()`,
   `cycleCutConduit()`, `cycleMaterial(delta)`. The three cycles exist inline in `OutofspaceMain`
   today and should move down so the harness and the web host can reach them; that is the same
   argument `nudgeSpeed` was moved for.
2. `OutofspaceMain` — the key table above.
3. `OutofspaceHud` — the hint lines, and the `E cycles …` text in the DELETE and CUT panels.
4. Harness — the cycles as commands, so a script can photograph each sub-target.
5. Tests — `GrabAndEscapeTest`'s neighbour: open-then-cycle for each tool, the auto-pick firing only
   on null, and the creative fallback.

Nothing here touches the reducer, the save, or any edit.

## Not in scope

- Per-kind material validity (see above).
- Any change to the ESC ladder.
- Touch/phone equivalents — these are keyboard shortcuts and the tool buttons remain the pointer's
  way in.
