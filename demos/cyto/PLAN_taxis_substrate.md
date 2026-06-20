# Plan — matter-field substrate for taxis (PARKED)

**Status: PARKED.** Blocked on **world rescale + torus geometry** (do that first; this plan tunes values
that only make sense once the world is the right size and a real torus). Written 2026-06-21.

## Why this exists (the chain that led here)

Goal: a **directional, steerable** swimmer (locomotion controller → chemotaxis). The motor works — the
velocity-reconciliation fix (`91145a6f`) made drag see spring-driven motion, and a lateralised bend
(`cc` organizer → `bc` gradient → one-flank clock-gated `Contract`) produces real thrust. But **steering
needs a goal and an instrument**, and the substrate can't currently provide either:

- **Steer toward what:** the only spatially-varying resources — **matter** (depletable grid) and **light**
  (moving band). Matter is the better first target: an organism digs its own steep "eaten-behind /
  fresh-ahead" gradient.
- **Instrument:** there is **no external sensor** (gate operands are all local-internal: `Chem`/`Conc`/
  `Biomass`/`Touching`). A cell senses its environment only **indirectly** — a cell in richer matter
  imports more → different internal chemistry. Because a body spans space, cells on the food-side differ
  chemically from the far-side → a body-internal asymmetry that encodes gradient direction. **The
  multicellular body is the differential instrument.** (Same machinery as the `bc` morphogen gradient, but
  sourced by the *environment* instead of a seeded determinant.)

The blocker: the matter grid is **far too coarse and diffusion flattens it.**
- `GRID_RES=64`, world `SPAN=2048` cell-diam ⇒ **each grid cell is 32 cell-diam wide**. A ~5-cell body sits
  inside one grid cell → every body cell reads the *same* matter value → **zero gradient, unreadable.**
- `MATTER_DIFFUSE=1/8` every 8 ticks refills depleted cells → smears any self-dug gradient back to flat.

## Prerequisite — world RESCALE: ✅ DONE (`5ec4903b`, 2026-06-21)

`CELLS_PER_AXIS` 1024→128 + world-scale constants ÷8; matter grid cell now 4 cell-diam (was 32). Caught +
fixed a real seam bug (weld solver subtracted positions non-modularly → explosion across the seam) and added
`CytoTorusTest` (boundary≡centre + field periodicity). Body-relative dynamics preserved; goldens re-baselined.
Next: bump `MATTER_GRID_RES` for a ≈1-cell-diam grid, then the gather rework below.

## (Historical detail) world RESCALE (not a wrap fix)

**Correction (2026-06-21):** cyto is ALREADY a proper Int-torus — `Coord` wraps via two's-complement Int
overflow (positions wrap free), `SpatialGrid` tiles the full signed-Int torus with bitmask wrap (broadphase
wraps), and the fields wrap at `SPAN = 2·CELLS_PER_AXIS` = exactly the Coord boundary (logical ±1024 =
±Int.MAX). Nothing is broken. The issue is purely **scale**: the torus is 1024 cell-diam across, so a colony
of tens of cells fills <1% of it and never reaches the seam → behaves like an open plane. (Earlier "physics
doesn't wrap / desyncs from fields" note was wrong.)

Fix = **scale objects up** so the fixed ±INT_MAX torus holds the right number of cells (as drockets/scavengers do):
1. **Drop `CytoUnits.CELLS_PER_AXIS`** 1024 → ~128. Routes through `SCALE = 1/CELLS_PER_AXIS`, so it
   uniformly scales every object up in raw Coord units; body-relative dynamics should be preserved
   (stiffness/damping are dimensionless `Frac` rates; drag works on `toLogical(speed)` where the rescale
   cancels) — confirm via goldens/probes. Bonus: grid cell = `SPAN/RES = 2·CELLS_PER_AXIS/RES`, so at 128
   with `RES=64` a grid cell is already 4 cell-diam (was 32) — 8× finer for free, which is why this is first.
2. **Re-tune world-scale-dependent (non-body-relative) constants:** light band width / `LIGHT_FALLOFF`,
   matter seeding `MATTER_PEAK`/`MATTER_FALLOFF`, and any literal-`1024`/world-absolute distances (grab
   reach, spawn spread, probe placements — grep them).
3. Re-baseline goldens + determinism gates; probe that a colony drifts, **wraps at the seam, and interacts
   across it**; visual render check at the new scale.

## The shift (this plan) — fine static matter grid + Gaussian local gather, no diffusion

Once the world is right, replace the matter-field mechanics:

1. **Crank matter resolution, decoupled from light.** Split `GRID_RES` into `MATTER_GRID_RES` (fine, grid
   cell ≈ 1 cell-diam) and `LIGHT_GRID_RES` (stays coarse — light is a wide band, doesn't need detail).
2. **Remove diffusion entirely.** Diffusion did two jobs: (a) feed immobile cells after they deplete their
   spot, (b) smooth the field. We keep (a) via gather below and *drop (b)* — that smoothing is exactly what
   destroys gradients. Also removes the per-step fresh-RES²-grid allocation (the GC wall at high res).
3. **Gaussian (or disc) local gather.** A cell pulls matter from a weighted **neighborhood** around its
   position (radius σ) instead of only its own grid cell. This:
   - keeps sessile cells fed (σ is the new "reach", replacing diffusion-rate);
   - **is the sensor** — intake ∝ local density, so a cell over fresh field pulls more than one over a dug
     well → the differential signal taxis needs, for free;
   - lets the self-dug depletion crater **persist** (no diffusion) → sharp, stable gradient across strokes;
   - is a **perf win**: O(cells × kernel) where organisms are, vs O(RES²) + big alloc every 8 ticks.

### Implementation notes (determinism + conservation)
- **Precomputed integer stencil**, computed once (e.g. 7×7 weights summing to a power of two). NO runtime
  `exp()` — the engine is fixed-point deterministic and avoids transcendentals.
- **Conservative integer apportionment:** a cell pulling `want` takes from kernel grid-cells weighted by
  (stencil × amount present) and decrements each by exactly what it took (largest-remainder rounding) so
  atoms stay bit-conserved (the matter-conservation gate must stay green).
- **Order-dependent but deterministic:** overlapping gatherers process in fixed entity-id order, like the
  existing biology passes.
- **Scope:** gather replaces the grid side of `passiveEnvExchange` + `Import`. **Deposit** (waste,
  death-recycle) stays **local** (single cell) — reach out to eat, dump where you are. Heads-up: with no
  diffusion, death-recycle leaves a **local matter pile** → carcasses become food patches (emergent; watch
  for clumping).
- Goldens re-baseline (matter dynamics change fundamentally). A **disc** (uniform-in-radius) kernel is a
  cheaper, easier-to-conserve fallback if the Gaussian stencil proves fiddly.

### Knobs to fix once world scale is set
- gather radius **σ** (cell-diam — ~2–3 so a small body's cells see meaningfully different neighborhoods),
- **MATTER_GRID_RES** (grid cell ≈ 1 cell-diam at the new world size),
- kernel shape (Gaussian vs disc) for v1.

## Then: resume the locomotion controller (taxis)

On this substrate, swap the bend's lateralising signal from the fixed `cc` organizer to an
**environment-driven, matter-sensitive metabolite** (higher on the food-side). "More food on my left →
those cells behave differently → bend left → swim toward food." Validate with `ControllerProbe` (add a
matter-patch setup + measure heading bias toward the patch). The size cap is **not** required — a body that
grows to carrying capacity is fine; capping it didn't change directionality (senescence caps at 24 and
still wanders).

## Artifacts already in place
- `SwimProbe` (`-Dswimprobe=1`), `ControllerProbe` (`-Dctrl=1`), `CollisionChannelProbe` (`-Dcollprobe=1`).
- `cyto-swimmer-v0/v1.gene` — bend-swimmer drafts (v1 lateralises, organizer off-centre 0.55, swims but
  wanders ~0.34 ≈ a circle's 1/π; needs the env-driven asymmetry above to *steer*).
