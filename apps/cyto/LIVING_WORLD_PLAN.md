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
`frame`. Those carry **state** (current biomass, cytoplasm counts) but not **flows** — there's no
per-cell record of how much of each species moved ENV↔CYT, CYT→BIO, or BIO→ENV *this tick*. Every
visual below is driven by those per-tick transfers, so the foundational work is a **per-cell,
per-species transfer read-model** (§4), not the animations themselves.

Design constraint (engine boundary, see `[[feedback_modularize_over_generalize]]`): the sim stays
game-agnostic and deterministic. Visual signals are *derived read-model*, published on the frame; they
must **never feed back into sim state** and must not affect the golden trajectory. The golden/spec
gate (`CytoGoldenTest`, `CytoSoaSpecTest`) must stay green untouched.

## 3. The approach: cytoplasmic activity as surface particles

Rather than a handful of authored "event" animations, cells **render their metabolism directly**:
small particles and expanding fields on/around the cell surface, driven by the **actual per-tick,
per-species transfer amounts** the biology already computes. The visuals ARE the read-model of the
chemistry table — emergent from whatever the cell happens to be doing, not scripted per chapter. Four
metabolic flows, each with its own visual treatment (§5). A cell doing many things at once layers them.

Colour follows the same atom-mix logic as `cellColor`, but per **species** rather than per whole cell:
`r`→red, `g`→green, `b`→blue, `rg`→yellow, etc. (sum the atom channels of the species token, normalise).
A shared `speciesColor(token)` helper serves both the per-species particles and the averaged-colour
fields. This wants factoring out of `CytoRenderer.cellColor` so the two stay consistent.

## 4. The visual-signal channel (foundation — build first)

The four flows below are all computed inside biology each tick but not currently exposed. Foundation
work is a per-cell, per-species **transfer read-model** published on the frame:

- **ENV↔CYT** — signed per-species amount moved across the membrane this tick, from `passiveEnvExchange`
  (import/export bias + diffusion). Positive = into cytoplasm (flow 1), negative = out (flow 2).
- **CYT→BIO** — per-species amount locked into biomass this tick, from `Convert` (`work.biomass.inc`).
- **BIO→ENV** — per-species amount released by biomass decay this tick.

Constraints (§2): derived read-model only, published alongside the component tables, never fed back
into sim state, golden/spec gate untouched. Per-species maps are small (seed alphabet), but this is the
hot path — accumulate into reused per-cell buffers, don't allocate per tick.

**Renderer-side state (all animation lives here, not the sim):** particles for ENV↔CYT are spawned
discretely — each tick, emit a count of new particles per species proportional to that tick's transfer,
then advance/age them every frame independent of tick rate. The CYT→BIO / BIO→ENV fields are continuous,
so each cell keeps a per-flow **intensity** value that eases toward the current transfer rate — this is
the warm-up (fade-in as activity starts) / cool-down (fade-out as it stops). Key all per-cell state by
`EntityId`, evict on absence (same pattern as `focusNeighbours`).

## 5. The four flows (visual spec)

**Flow 1 — ENV→CYT (absorption).** Species-coloured particles fade into view just outside the cell's
border and drift toward the centre; at the halfway point they pivot to fading out, reaching fully
invisible at the centre. One particle stream per species, coloured by that species (`speciesColor`).
New particles spawn each tick in proportion to that tick's inward transfer for the species.

**Flow 2 — CYT→ENV (secretion).** The reverse of flow 1: particles fade in near the centre, drift
outward, and fade out as they cross the border. Same per-species colouring and per-tick spawn rule,
driven by outward transfer.

**Flow 3 — CYT→BIO (building).** A filled circle in the species' **average** colour (multiple species
may convert at once), starting at radius 0 at the cell centre with full opacity and expanding outward to
the cell's full radius, its opacity falling linearly to zero over the expansion. Continuous while
building, so it gets a **warm-up** (opacity envelope fades in when building starts) and **cool-down**
(fades out when it stops), driven by the eased CYT→BIO intensity.

**Flow 4 — BIO→ENV (decay).** A filled circle in the average species colour rendered **behind** the
cell, starting at the cell's radius with full opacity and expanding to 0.125 beyond the cell radius
(1.125×), fading linearly to transparent over the expansion. Same warm-up/cool-down envelope as flow 3,
driven by the eased BIO→ENV intensity.

Notes: flows 1–2 are **discrete per-species particles** (no warm-up/cool-down — the particle lifecycle
is the fade); flows 3–4 are **continuous averaged-colour fields** with the warm-up/cool-down envelope.
Render order matters — flow 4 draws behind the cell disc, flows 1–3 on/in front of it.

## 6. Build order

1. **Signal channel (flow 3 first — CYT→BIO).** The simplest single flow end-to-end: expose per-species
   Convert amounts, add the `speciesColor` helper, render the expanding build-circle with warm-up/
   cool-down. Proves the read-model + renderer-state plumbing on the calm grow-only autotroph (it's
   constantly building to top itself up, so it's easy to eyeball). Rescues Ch2/Ch3's "feed and repair."
2. **BIO→ENV field (flow 4)** — reuses the same intensity/envelope machinery, behind the cell.
3. **ENV↔CYT particles (flows 1–2)** — the discrete-particle system; more moving parts (spawn, age,
   pivot-fade), so it comes after the continuous fields prove the channel.

## 7. Validation

Per `[[feedback_iterate_with_harness]]`: render + Read screenshots via `CytoAgentHarness` before
claiming anything reads right. Static PNGs won't show motion or particle drift, so use a `shot` sequence
across successive ticks/frames (a building cell watched over its warm-up; an absorbing cell in daylight)
to eyeball the animation. Confirm `CytoGoldenTest` / `CytoSoaSpecTest` stay green after the channel lands.

## 8. Open questions for Stu

1. **Scope:** all four flows, or start with the two continuous fields (flows 3–4) and hold the particle
   system (1–2) for a follow-up?
2. **Tuning knobs** (defer to iteration, but flag now): particles-per-unit-transfer and particle size
   for flows 1–2; warm-up/cool-down durations for flows 3–4; whether opacity should scale with transfer
   *magnitude* or just presence/absence of the flow.
3. **Legibility when layered:** a cell simultaneously absorbing (flow 1), building (flow 3) and decaying
   (flow 4) stacks three treatments — acceptable as emergent richness, or do we need a priority/dominant
   flow so it doesn't muddy?
4. Should the collapsed chemistry table eventually become **campaign-mask-gated** (hidden entirely in
   early chapters, not just collapsed) once these visuals carry its information?
