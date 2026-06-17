# The hopeful monster — a reachability program for shape

**Why this doc.** Cyto has evolved an *ecology* (food webs, boom-bust, beneficial-variant selection) but
never a developmental *organism* — bodies are undifferentiated blobs. The Creatures emotional hook lives at
the **organism** level, so that's the missing payoff. The scoping diagnosis (2026-06-17): we've been running
four hard problems in parallel — rich physics + competition + open-ended evolution + morphogenesis — so the
substrate/competition fires steal the oxygen from the one thing that delivers the payoff (a developed body).

SimulifeHub got their organism by *separating concerns*: they **designed** a working developmental genome by
hand on a trivial substrate (frozen hex grid, no physics, no selection), and only *then* (next video) add
mutation. The lesson isn't "use a simpler substrate forever" — it's **design first to prove the target is
reachable, then evolve to explore around it.** Selection refines neighbourhoods of working solutions; it does
not invent body plans from nothing in desktop-scale populations.

So: **hand-author a hopeful monster** — a genome that develops into a shaped, differentiated, self-maintaining
body — as a *reachability proof and scaffold*. Then earn back toward Cyto's distinctive corner (physics +
competition + evolution) one axis at a time.

This doc is the **program** (how we'll do it + prove it). The **contract** (the rule changes) lives in
`MORPHOGENESIS.md` §Morphogens for shape and §The gene. The **board** is `TASKS.md`.

## The substrate this needs (summary — contract in MORPHOGENESIS.md)

Morphogens are built **for shape, not fate**: a localised **source** + cell↔cell **diffusion** + **decay**
→ a steady-state **positional gradient** a cell reads to know where it is. Three genome-derived signal roles:

- **Determinant** — sensed + mitosis-allocated, never produced → isolated, persistent = **memory/fate**.
- **Morphogen** — FormBond-produced, not metabolised → diffuses + decays = **shape**.
- **Metabolite** — metabolised → **food**.

Locked decisions: morphogens **cost matter** (FormBond; decay recycles atoms); diffuse **cell↔cell** (not the
coarse env grid); gene gate is an **AND-conjunction** (NOT via `<`, OR via separate genes, no weighted sums).
Position is **upstream** of fate: read the gradient → at a threshold, commit a determinant.

## The ladder — smallest recognisable body first

- **v0 — differentiated tissue from one gradient.** A clonal colony with a single founder-sourced morphogen
  gradient, read at two thresholds: high-concentration core cells do one thing, low-concentration boundary
  cells do another (e.g. core = divide/bulk, boundary = repair/skin). Size still resource-capped (not
  intrinsically regulated). *This is the minimum that looks like an organism, not a blob.* **Reachability
  proof.**
- **v1 — size regulation + committed fate.** The gradient bounds growth at an intrinsic size (not just
  resources), and a fate persists independent of current position (commit a determinant at a threshold → a
  true germline/core that stays put). Add a third tissue band.
- **v2 — life cycle.** Programmed apoptosis clears soma; dispersal (chemotaxis via the Contract actuator)
  scatters germline; development restarts. Needs new actions (Apoptosis, a locomotion controller).

## Gap analysis (vs the gene model as of 2026-06-17)

| Need | Cyto today | Verdict |
|---|---|---|
| Persistent determinant (memory) | trace morphogen (no cytoplasm decay) + asymmetric mitosis | **HAVE** (§C) |
| Binary differentiation | morphogen-gated fate | **HAVE** (§C) |
| Stop-dividing / block | gate `Mitosis` off | **HAVE** |
| **Positional gradient (shape)** | morphogens isolated; no decay; no `canDiffuse`/`canMetabolise` split | **BUILD** (the substrate) |
| **Concentration band readout** | only `>`/`<` count gates, single clause | **BUILD** (`Conc` + AND-gate) |
| Localised source | FormBond exists; localise by gating on a determinant | **HAVE** (compose) |
| Hand-author asymmetric mitosis in text | `GeneCodec` drops the `Mitosis` morphogen operand | **TINY FIX** |
| Centred source (vs edge) | §C allocates determinant to *daughter* (outward) | **KNOB** (mother-retention option) |
| Surface vs interior *topology* | `Touching` = un-welded only | optional sidecar: **welded-neighbour count** |
| One-way commitment (methylation) | fate is soft/position-reactive | v1 (commit a determinant at threshold) |
| Programmed apoptosis | passive starvation only | **MISSING** — v2 |
| Directed movement / chemotaxis | `Contract` actuator; no controller/direction | **MISSING** — v2 |

## Illustrative v0 genome (untuned — tune empirically)

Pseudo-`GeneCodec`, one clonal genome. `f` = founder determinant (sensed + mitosis-allocated, never produced
→ persistent, isolated). `m` = morphogen (FormBond-produced from `a`+`c`, not metabolised → diffuses + decays).
`HI`/`LO`/`GROW`/`DIVIDE` are thresholds to tune.

```
# --- base metabolism (every cell): build & lock ab for biomass under light ---
Light : ab < GROW : FormBond a b
Light : Biomass < GROW : Convert ab

# --- source: only the founder lineage (holds determinant f) emits morphogen m ---
Light : Conc f > 0 : FormBond a c            # → 'ac' (= m); not metabolised ⇒ diffuses + decays

# --- symmetry break: keep f in one daughter so the source persists as a point ---
Break ab : Biomass > DIVIDE : Mitosis f      # asymmetric: f allocated whole to one side (centred-source = mother-retention knob)

# --- positional readout: two tissues by concentration band ---
Break ab : Conc m > HI : Mitosis             # core (high m): divide → bulk growth
Light    : Conc m < LO : Repair              # boundary (low m): tough, non-dividing skin
```

What to watch when this runs (mutation **off**, physics calmed): a stable concentration gradient from a point
source; a dividing core wrapped in a non-dividing repairing rind; and — the real prize — the structure
*re-forming after a cut* (expose core cells → local `m` drops → they re-read as boundary). If `m` never forms
a gradient, the decay/diffusion rates or the source localisation are wrong — not the concept.

## Staging — earn back to Cyto's corner

1. **Calm the substrate.** Mutation off; turn down locomotion/competition (shading, future `Lyse`); gentle
   physics. Crack the gradient + the v0 body in quiet conditions.
2. **Reachability proven** → you have a SimulifeHub-part-1 equivalent *in your engine, with a real physical
   body and free regeneration*.
3. **Re-introduce one axis per campaign:** mutation **on** from the v0 ancestor (does it diversify/refine or
   collapse?) → competition back on (does the body survive predation/shading?) → make physics *pay off*
   (locomotion as a real advantage, not an exploit). Each is selection doing what it's good at — varying a
   working organism — not conjuring one.

## First moves (see TASKS.md → Now/Next)

1. **Codec fix** — `Mitosis <morphogen>` round-trips (you can't even *write* §C genomes today).
2. **`Conc` operand** + **AND-conjunction gate** — the positional-band readout primitive.
3. **Signal decay + the `canDiffuse`/`canMetabolise` split** — cell↔cell morphogen diffusion; the gradient
   substrate. Re-baselines goldens.
4. **Hand-author the v0 genome, run mutation-off via a probe**, and tune until a stable two-tissue body
   forms and self-heals. That's the reachability proof — the entire point of the exercise.

`Lyse`, apoptosis, chemotaxis, welded-neighbour count, methylation-commit all stay parked until v0 proves the
floor.
