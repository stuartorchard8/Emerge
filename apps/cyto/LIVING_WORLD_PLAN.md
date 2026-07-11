# Cyto — Living World (in-world visuals) design plan

*The next major push before the campaign continues. Goal: make the world **read as alive** and
communicate what cells are doing — feeding, repairing, growing, dividing, starving — through
**in-world visuals**, so a new player learns by watching, not by reading the ENV/CYT/BIO table.*

> Companion to `CAMPAIGN_PLAN.md`. Motivated by an observability gap: Ch2 tells the player to "watch
> it feed and repair itself", but those processes had no visual signal — the only window onto them was
> the dense chemistry table, which we've now collapsed behind a `+ CHEMISTRY` tap (commit `2e0c137b`).
> Decluttering the panel moved the burden onto the world; this plan is the world paying it off.

## 1. The problem, precisely

The renderer (`CytoRenderer.draw`) draws each cell once per frame as a flat disc: position +
radius from `Transform`/`Collider`, hue from the atom-mix (`cellColor`, Bio/Cyt modes), value scaled
by focus/dim. The light field renders as a background grid. **Nothing about a cell moves or reacts to
what it's doing** — feeding, repair, decay, division all happen in the sim with zero visual trace. So:

- A healthy autotroph and a starving one look identical until one shrinks or dies.
- "It feeds where it's light, starves in the dark" (Ch2's whole point) is invisible.
- The repair loop ("tops itself up as decay wears it") — the thing Ch2/Ch3 narrate — is invisible.
- Division is a cell silently appearing. A still world reads as frozen, not calm-but-alive.

## 2. The core gap in the pipeline

The renderer reads component tables (`CytoCellComponent`, `Transform`, `Collider`) from the published
`frame`. Those carry **state** (current biomass, cytoplasm counts) but not **events** — there's no
per-cell "fired a light gene / absorbed N light / gained-or-lost M biomass this tick" signal. Every
visual below needs one. So the foundational work is a **per-cell visual-signal channel**, not the
animations themselves.

Design constraint (engine boundary, see `[[feedback_modularize_over_generalize]]`): the sim stays
game-agnostic and deterministic. Visual signals are *derived read-model*, published on the frame; they
must **never feed back into sim state** and must not affect the golden trajectory. The golden/spec
gate (`CytoGoldenTest`, `CytoSoaSpecTest`) must stay green untouched.

## 3. The visual-signal channel (foundation — build first)

A compact per-cell struct the reducer fills during biology and publishes on the frame alongside the
component tables. Candidate fields (all cheap, already computed mid-tick):

- `lightAbsorbed` — light energy consumed by light-powered genes this tick (drives feeding glow).
- `biomassDelta` — net biomass change this tick, signed (repair/growth vs decay/starvation).
- `geneFiredMask` / `divideThisTick` — which actions fired (division pinch, activity flicker).
- optional `contactCount` / weld events — for later (shedding, bonding cues).

Smoothing lives in the **renderer**, not the sim: keep a small per-entity ring of recent signal so a
one-tick event animates over ~0.3–0.5s of wall time (the sim tick rate ≠ frame rate). Entities come
and go, so key by `EntityId` and evict on absence (same pattern as `focusNeighbours`).

## 4. Slices, in priority order

**Slice A — Feeding glow (thin slice; rescues Ch2, proves the channel).**
When a cell absorbs light, pulse a warm rim/halo scaled by `lightAbsorbed`. In daylight it glows and
tops up; in the dark band it goes quiet. This alone makes Ch2's "feeds on light, dark = can't feed"
directly visible. Smallest end-to-end path through the new channel — build and validate this first.

**Slice B — Repair / growth / starvation.**
Ease the drawn radius toward the true radius so growth is gradual, not a pop. Signed `biomassDelta`
tints or shimmers: a gentle mend-glow while rebuilding, a desaturated/withering look while losing mass.
This is the "holds steady, tops itself up" loop and the future "starves without matter" (Ch4) cue.

**Slice C — Division.**
A brief pinch/split animation on `divideThisTick` — the disc necks and separates — so reproduction
reads as an event, not a spawn. Pairs with Ch4's "big enough, it splits in two."

**Slice D — Ambient life.**
Subtle idle motion (membrane wobble, slow matter-uptake motes toward feeding cells) so a calm world
still looks alive rather than paused. Lowest priority, highest polish.

## 5. Validation

Per `[[feedback_iterate_with_harness]]`: render + Read screenshots via `CytoAgentHarness` before
claiming anything reads right. Static PNGs won't show motion, so the harness may need a `shot` sequence
across ticks (feeding cell in light vs dark; a cell mid-repair; a division) to eyeball the animation
frames. Confirm the golden/spec gate stays green after the signal channel lands.

## 6. Open questions for Stu

1. **Scope of "alive":** minimum viable (Slices A–B, enough to fix Ch2's observability) vs the full
   ambient pass (A–D)?
2. **Art direction:** glow/halo/particle language, or something more diegetic (membrane deformation,
   colour temperature)? Any reference look?
3. **Signal set:** is the §3 field list the right initial cut, or are there processes you specifically
   want surfaced first (e.g. weld stress in Ch5/Ch6)?
4. Should the collapsed chemistry table eventually become **campaign-mask-gated** (hidden entirely in
   early chapters, not just collapsed) once the visuals carry its information?
