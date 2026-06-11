# Cyto → evolvable macro-biology (design)

The goal: turn Cyto into a **hands-off "watch evolution" god-sim** — a substrate where a genome grows
and sustains a multicellular body, and natural selection can act on it given time. This doc is the
settled design we build toward; it is the contract, not a status log.

## Core model

- **No hardcoded cell-type behaviour.** A `CellType` is a **UI label + an associated preset genome**,
  nothing more. Every behaviour a legacy type had is expressed as genes in its preset.
- **A cell carries a heritable `genome`** — an ordered list of `Gene`s — driving its chemistry and
  behaviour. Seeded from `genomeForType` at spawn, carried per-cell, inherited clonally on division
  (daughters get the mother's genome), and (later) mutable. *(Done — commit 75690fb.)*
- **Genes** read chemicals / touch / (later) environment signals and emit outputs. Outputs come in two
  flavours:
  - **Continuous effects** — applied and forgotten each tick: `Contract` (shrink radius), `Inhibit`
    (suppress a chemical's diffusion), `Enzyme` (catalyse a reaction), `Sticky` (adhere).
  - **Charge-accumulating events** — the gene's activation each tick is a *rate of progress* that
    accumulates in a per-cell charge; when it crosses a threshold the event fires once and the charge
    resets to 0: `Mitosis` (divide), and later `Meiosis` (bud a propagule for cross-organism repro).

## Division (Mitosis) — a charge-accumulating event

- A `Mitosis` gene reads a substrate chemical; its activation accumulates `divideCharge`. At
  `DIVIDE_THRESHOLD` the cell divides and `divideCharge` resets to 0. **No cooldown** — the warm-up
  *is* the rate limit (`divideCooldown` was repurposed into `divideCharge`).
- **Self-bounding, two ways:** (1) the gene's input chemical is consumed each tick during warm-up, so
  a finite substrate pool yields finite divisions; (2) division halves the cell's energy, so both
  daughters drop below the bar and must re-accumulate.
- **Cascades are an emergent feature, not a bug:** because charge resets to 0, a daughter normally has
  to warm up again — *unless* it sits on so much surplus that one tick refills the whole charge. So
  back-to-back division only happens under extreme surplus. If a genome stumbles onto that and finds
  it useful, fine.
- **Stem preset** gates Mitosis on **energy** (`activation = energy − threshold`, charging only when
  fed). A *developmental* genome instead gates Mitosis on a **morphogen**, which is how a hopeful
  monster controls *where* growth happens — same machinery, different input chemical.

## Energy economy & environment

- **No free lunch.** Energy must be *collected* from the environment, not minted. (Today's `Support`
  "+5 from nothing" is a known placeholder.)
- **Collector cells** absorb energy from a **light field** (`CytoLightField`): a static scalar grid
  over the torus, the sum of radial decay kernels from fixed **sources** at the torus quarter-points
  (a 2×2 equidistant grid). Non-depletable for now → it's a fixed steady state, precomputed once and
  sampled O(1) per cell (a `GeneInputType.Light` input feeding a `GeneOutputType.Secrete` energy
  output: the `Collector` preset). This:
  - kills the exposure-only exploit (a 1-cell-thick filament can't farm free surface area, because
    intake is anchored to *place*, not surface/volume ratio);
  - rewards proximity, so **locomotion / positioning has a payoff**;
  - makes the central niche small + contested → a resource gradient that *drives competition* (the
    intended dynamic — deliberately not a shared finite budget, which would flatten the gradient).
- **Exposure-gated harvest = density dependence (the population brake).** Without it, a self-sufficient
  cell (collect + divide) grows *exponentially* — each daughter also collects, so income scales with
  population. The fix: a cell harvests `field × exposure`, where **exposure** is the largest angular gap
  between its connected neighbours (`CytoExposure`, an `atan2`-free monotonic "diamond angle"). Only
  surface cells harvest; the interior is fed by inward diffusion. So a colony's income scales with its
  *surface* (∝ √N in 2D) while upkeep scales with its *volume* (∝ N) → per-capita income falls as it
  grows → a real carrying capacity. **Verified** (`probeCytoPopulation`): the exploding genome now
  plateaus (e.g. 9 cells, flat for 6000+ ticks at STRENGTH 0.05) instead of running away; the plateau
  level scales with light, so you tune colony size with STRENGTH rather than starving the system to
  cap it. Open hole: a 1-cell-thick *filament* is all-surface → not capped by exposure; **depletable**
  resource closes that (a filament exhausts the finite energy along its length) — the next lever.
- Extensions when wanted: multiple / moving sources (spatial niches), depth-graded light, true
  occlusion shading (cells shadow those behind them), or a depletable diffusing nutrient (foraging).

## Differentiation (same genome → different cells)

Symmetry must break for one genome to make different cells. Sources, weakest→strongest:
- **Positional information** — neighbour-topology diffusion gradients + contact pressure + distance to
  the light source — gene-readable, no asymmetric division required, but emergent and noisy.
- **Asymmetric division** — the keystone: an unequal split along the existing division polarity
  establishes a morphogen gradient (and a body axis) from a single founder, *deterministically*.
- **Fate memory** — genes read only chemicals (+touch) today, so persistent fate = a self-sustaining
  chemical latch (produce F + `Inhibit` F's diffusion + read F). A first-class `Differentiate` output
  (set the cell's fate/type from chemical context) is the clean long-term mechanism, and keeps the
  type→economy mapping while letting the *genome decide fate*.

## Performance

Per-cell sim is expensive — historically the reason this stalled. The discipline:
- **One biology path.** The old dense "fast path" (skip genes for gene-less cells) does not survive an
  all-genes model; collapse to the single shared-core path now — correctness first, optimize from
  profiles later. *(Accepts a near-term regression on big colonies; eyes open.)*
- The non-cheaty lever to reclaim it later: avoid the per-cell chemical `HashMap` for cells that only
  touch the dense `energy` column (operate on the column in-place) — a specialization *inside* the one
  path, not a forked path.
- The structural win: a resource-limited energy economy is a **carrying capacity** — the world refuses
  to feed unbounded biomass, so cell count (and compute) is bounded by the ecology, not by tricks.

## Tech debt

- **Float still in the sim (de-float to finish what `dc22be3` started).** The chemistry is fixed-point
  `Frac`, but three things outside it are still `Float`/`Double` — i.e. not yet bulletproof
  cross-platform deterministic:
  1. **Spring stress-damage** — `ConnectionStateComponent.damage`, `connectionBreakDamage`,
     `connectionStressScale`, the CSR `edgeAux`, and the connection-maintenance accumulation. Physics /
     connection state, not chemistry.
  2. **Division split geometry** — the "ahead vs side" neighbour classification (`dot(...).toFloat()`
     compared to `0.75f`) in `CytoLifecycle.divide` / `CytoLifecycleSystem.divide`.
  3. **Light-field bilinear *sample*** — `CytoLightField.sampleAt` interpolates in `Double`. **Becomes
     conservation-critical the moment the field goes depletable** → Frac-ify it as part of that work.
- The dense SoA fast path was dropped (one biology path); reclaim via energy-only dense execution if a
  profile demands it (see Performance).

## Build order

1. ✅ Heritable per-cell genome, inherited on division (not type-keyed). *(75690fb)*
2. **▶ Mitosis as a charge-event gene output + drop the fast path** *(this change)* — Stem's division
   becomes a gene; `divideCooldown` → `divideCharge`; one biology path. Support energy stays a
   placeholder type-economy until Collectors land.
3. ✅ **Collector cells + the light-field environment** — `CytoLightField` (4 sources, non-depletable),
   `Light`→`Secrete` genes, `Collector` cell type, `renderCyto` headless heatmap. Support's "+5"
   placeholder still exists (used by perf/equivalence fixtures); retire it in a focused follow-up.
   Future: **depletable** + energy released to the field on cell death = a closed energy system.
4. Genome **serialization** — `GeneCodec` (text) ✅; still TODO: the **save file** (`CytoSaveCodec`)
   carries the actual genome once it can diverge from a type preset.
5. **Authoring** — ▶ v1 done: write a genome as a `.gene` file (GeneCodec), click the on-screen **Load
   Genome** button to (re)load it as the *brush*, paint with the existing **Spawn**/**Set** controls;
   the **Light** button toggles the heatmap. (All on-screen buttons — no keyboard-only controls.)
   Roadmap: a full **on-screen genome composer** (no leaving the game).
6. Hand-built **hopeful monster** (morphogen-gated mitosis + asymmetric division + differentiation).
7. **Mutation + selection** — the substrate evolves.
