# Plan — performance-friendly matter diffusion (optional, not scheduled)

**Status: NOT STARTED. Written 2026-07-15, right after `4d6597f8` made the matter field dense.** This is a
design sketch to execute from in a later session, not a commitment. Read §1 before §3 — the purpose decides
the design, and one of the purposes needs no sim work at all.

---

## 1. Read this first: diffusion has been removed TWICE, for good reasons

The field has no diffusion today. That is not an oversight, it is a conclusion reached twice:

- **`100addd2`** removed the flat grid's diffusion because **the disc gather replaced its "feed sessile
  cells" role**. A cell reaches across a footprint of texels and balances them all toward a common level, so
  matter no longer has to travel *to* a stationary cell — the cell reaches *out*. Diffusion's original
  function is already covered by `passiveEnvExchange`.
- **`PLAN_taxis_substrate.md` (2026-06-21)** then made it an explicit design decision: *"Dense per-species
  storage `IntArray(RES²)` × k species… **No diffusion** (removed) — self-dug craters persist."*
- The quad-tree's self-collapse (that same doc's "structural diffusion gated by observers") was the
  experiment to bring background mixing back. **`66e6c37c` removed it**: it could only ever fire where no
  cell was, and at the shipped `COLLAPSE_DELAY=2048` it moved ~2% of leaves over 5000 ticks.

So the current state — dense per-species columns, disc gather, no diffusion, craters persist — *is* the
substrate that doc decided on, arrived at the long way round. **Do not reintroduce diffusion without a
purpose that the disc gather demonstrably does not serve.** §2 is that question.

## 2. Decide the PURPOSE first — it changes the design by an order of magnitude

Three candidate motivations. They are not variants of one feature; they want different code, and the cheapest
one never touches the sim.

### (a) Taxis substrate — environmental gradients to sense and climb  ⭐ the likely real motivation
`PLAN_taxis_substrate.md` ends with *"NEXT: resume the locomotion controller as taxis (env-driven,
matter-sensitive lateralising signal)."* A cell releases morphogen into the field via `deposit`
(`CytoSoaReducer.kt` ~:410), which spreads it evenly over a **disc** — a hard-edged plateau with **no
gradient inside it**. A cell cannot climb that. Chemotaxis toward a source needs the signal to *spread*.

- **Scope: tiny.** Only the signal species need to diffuse — 1–2 columns, not 16.
- **Region: near cells only**, which is exactly where the active-tile bitmap (§4) is cheap.
- This is the one purpose the disc gather genuinely cannot serve. **If diffusion happens, this is why.**

### (b) Ecological recovery — craters heal, dead ground gets recolonised
Today a depleted patch is depleted forever unless a cell carries matter back. Long-run this means the world
monotonically accumulates dead zones (visible as the drift trails in `renderCytoMatter` at 5000 ticks).

- **Scope: all 3 monomers, whole field, very slow.** The expensive one.
- Ask whether it is even wanted: permanent scars are a legible record of where life has been, and Stu liked
  that framing. This is a *world-design* choice, not a perf problem.

### (c) Aesthetic — soften the scar edges
- **Do this in the RENDERER, not the sim.** The overlay already runs a domain-warp shader
  (`MATTER_WARP_AMP`); a blur there costs the sim nothing, re-baselines no goldens, and risks no
  conservation bug. If the want is "it looks too blocky", this is the whole answer. **Zero sim work.**

> **Recommendation:** if the trigger is taxis, build (a) — narrow, cheap, well-motivated. Treat (b) as a
> separate world-design conversation. Never build (b) or (a) to get (c).

## 3. The algorithm: integer edge-flux Jacobi

Conservation and determinism are both gated here (`checkCytoConservation`, `CytoGoldenTest`,
`parallelMatchesSequential`), so the algorithm is chosen to make them true **by construction** rather than by
testing.

**Per species column, per pass:** compute the flux on each *edge* exactly once and apply it symmetrically.

```kotlin
// delta: reusable IntArray(texels) scratch, zeroed per species (NOT per pass)
// f > 0 flows i -> j.  Each edge touched ONCE, so conservation is exact by construction.
val f = (col[i] - col[j]) / DEN          // NOT >>, see "truncation" below
delta[i] -= f
delta[j] += f
// ... then: for (i in ...) col[i] += delta[i]
```

**Why this shape:**

- **Conservation-exact by construction.** Every edge contributes `-f` to one texel and `+f` to the other. No
  clamp, no rounding reconciliation, no leak. This is the property the old flat implementation's comment was
  worried about: *"Keep `4·NUM/DEN ≤ 1` so a cell can't be over-drawn negative — violating it makes the
  bump-to-zero clamp destroy matter (breaks conservation)."* Edge-flux removes the clamp entirely.
- **Non-negativity** still needs `DEN ≥ 4`: worst case a texel loses on all 4 edges, total outflow
  `≤ 4·c[i]/DEN ≤ c[i]`. The deleted constants were `MATTER_DIFFUSE_NUM=1 / DEN=8` — already safe, and a
  reasonable starting point. **Add a unit test with an adversarial spike (one full texel, four empty
  neighbours) asserting no negative counts.**
- **Order-independent ⇒ parallel==sequential for free.** Integer `+=` into `delta` is commutative,
  associative and exact, so *any* edge visitation order gives the identical result. This is a much easier
  parallelisation than `passiveEnvExchange` got — **no drop-contested dance is needed**, because unlike the
  exchange there is no cell-owned cytoplasm to race on. Do not copy that machinery here.
- **Truncation: use `/`, not `>>`.** `>>` rounds toward −∞, so `(-7) shr 3 == -1` while `(-7)/8 == 0`;
  that makes the flux magnitude depend on which neighbour you subtract first, i.e. an index-order bias. `/`
  truncates toward zero and is symmetric: `(a-b)/8 == -((b-a)/8)`. The JIT lowers division by a constant
  power of two to shift+fixup anyway, so `/` costs nothing. (Conservation holds either way — this is about
  isotropy, not leaks.)
- **H/V operator split.** Do a horizontal sweep then a vertical one. Each is trivially parallel (rows are
  independent; then columns are). Not identical to a simultaneous 4-neighbour update, but standard, cheaper,
  and cache-friendlier on the horizontal pass.

**Per-species rate.** Mirror the junction: `passiveEnvExchange` damps by molecule size
(`denom = 2 shl (atomCount * DIFFUSION_SCALE_FACTOR)`). Scale `DEN` by `atomCount` the same way so bigger
molecules diffuse slower, and the two mechanisms stay conceptually consistent.

## 4. Making it performance-friendly

Naive cost: 16 species × 262k texels × 2 passes ≈ **8.4M edge-ops per invocation**. On the existing
`MATTER_MAINTAIN_PERIOD = 128` cadence that amortises to ~0.1 ms/tick — but it lands as a **single-tick
spike**, and tick avg is only ~19.5 ms, so a 10–20 ms spike is a visible hitch. **Watch p95/p99/max in
`benchCyto`, not avg** — the average will hide this completely.

Levers, in the order worth applying:

1. **Species subsetting — biggest and free.** Purpose (a) needs 1–2 signal columns, not 16. **8–16× off the
   top before writing a line of optimisation.** Do this first; it may end the conversation.
2. **Active-tile bitmap — restores the quad-tree's "void is free" without pointer chasing.** Split the grid
   into coarse tiles (say 8×8 texels ⇒ 64×64 bitmap). Diffuse only active tiles.
   - **Skipping a uniform region is EXACT, not an approximation:** zero gradient ⇒ zero flux ⇒ the pass is
     provably a no-op there. The far field is uniform at seed level (125/texel/monomer) and untouched.
     Measured: only ~61k of 262k texels are ever touched by tick 5000 (~23%) ⇒ **~4× immediately**, and
     bigger early on.
   - **Marking:** dirty a tile where matter is written — `balanceBatchedOn`, `deposit`, `decayAll`. Mark at
     **footprint granularity** (~1 mark per cell per batch, from the cell's disc) rather than per-texel in
     the innermost loop; per-texel marking would tax the hot path to save the cold one.
   - **Halo:** an active tile must also activate its 8 neighbours, or flux stops dead at the tile boundary
     and you get visible seams. Cheap; get it right or the artefact is obvious.
   - **Self-limiting:** clear a tile's bit when its pass produced zero total flux *including its border
     edges* — it is at local equilibrium, so skipping is exact until something writes to it again. See §5:
     the quantisation floor is what makes this actually terminate.
   - **No save impact:** the bitmap is derived state. On load, mark everything active; it settles within a
     few passes.
3. **Parallelise.** Order-independent (§3), so reuse `ColumnPartition.disjoint` / `ParallelExecutor` exactly
   as the biology does. ~8 cores ⇒ another ~4–6×. Note `parallelMatchesSequential` should pass *without any
   special handling* — if it doesn't, the implementation has an ordering bug.
4. **Stripe by species across invocations** to flatten the spike: diffuse species `k` when
   `(tick / MATTER_MAINTAIN_PERIOD) % nSpecies == k`. Each species then diffuses every `128 × n` ticks —
   slower per species, but the spike drops by `n`. Purely a rate re-tune.
5. **Do NOT diffuse at coarse resolution.** Tempting (16× cheaper) but breaks integer exactness, reintroduces
   blockiness, and is the quad-tree's mistake in a new hat.

## 5. The quantisation floor is a feature — and it is what makes §4.2 work

With `DEN = 8`, any gradient `Δ < 8` moves **nothing** (`⌊Δ/8⌋ = 0`). At the seed level of 125/texel that is
a ~6% floor. Consequences, both wanted:

- **Sharp gradients persist forever below the floor.** The field never fully homogenises, so craters keep
  their definition instead of smearing to mush. This is the same property Stu chose `COLLAPSE_DELAY=2048`
  for. It is a knob with character, not a rounding bug — **do not "fix" it.**
- **It makes diffusion terminate.** A settled region produces exactly zero flux, so its tiles clear their
  active bit and stay clear. Without a floor, everything diffuses slightly forever and the active-tile
  optimisation never converges. **The cheap thing and the good-looking thing are the same thing here.**

`DEN` is therefore the primary feel knob: it sets both the rate *and* the sharpness floor *and* the cost.

## 6. Gotchas specific to this codebase

- **Diffusion contradicts the junction's rule, deliberately.** `balanceBatchedOn` *never* introduces a
  species into a texel that lacks it (the old `binarySearchId(...) < 0 → continue`, preserved as
  `col[i] == 0 → continue`; it is load-bearing, see the `CytoMatterField` KDoc). Diffusion **will** spread a
  species into empty texels — that is the entire point. Do not "unify" these two rules; note the difference
  and move on.
- **`maintain` runs on the sim thread** inside the reducer's `interact` phase. The spike interacts with the
  throttle's carried deadline (`ed771fd7`). Check `benchCytoThrottle` if the spike is large.
- **`rebuildChannels` must run after diffusion**, not before — it already does (`maintain` = decay → [diffuse]
  → rebuild). Suggest diffusing **after** decay so freshly atomised monomers spread in the same pass.
- **No save-format change.** Diffusion is a rule, not state; v10 columns are unaffected.
- **Column allocation:** diffusing a species into a texel that never had it is fine — the column already
  exists field-wide. But a species absent from the *whole* field has a null column; skip it, don't allocate.

## 7. Validation plan (in order)

1. **Unit tests** (`CytoMatterFieldTest`) before anything else:
   - conservation through `diffuse` (spike, gradient, adversarial);
   - **non-negativity** under a full texel with four empty neighbours (pins `DEN ≥ 4`);
   - **a uniform field is a bit-exact no-op** — this simultaneously proves the active-tile skip is exact;
   - determinism (same op sequence ⇒ same digest);
   - symmetry: a point source spreads equally in ±x/±y (catches the `>>`-vs-`/` bias and halo bugs).
2. **`checkCytoConservation <save> 6000`** — the real gate. Must be exact on all three elements.
3. **`parallelMatchesSequential`** — should pass with no special handling.
4. **Population-curve A/B** (`git stash`, run both, diff — see the golden-gate memory). **Unlike the dense
   port, expect REAL divergence here**: this is a genuine rule change, not a reordering. The point is not
   "unchanged", it is "changed in a way Stu wants". Get eyes on it.
5. **Goldens re-baseline** — all five, with a comment in the established style saying *why*.
6. **Visual A/B**: `renderCytoMatter <png> 5000` with and without; the drift trails should soften at the
   tuned rate. Also drive the real GL overlay via `cytoAgent` (`overlay matter` + `shot`).
7. **Perf**: `benchCyto` — read **p95/p99/max**, not avg. `benchCytoRender` should be unaffected (diffusion
   is sim-side).

## 8. Suggested execution order

1. **Confirm the purpose (§2).** If it is (c), do the renderer blur and stop. If (b), have the world-design
   conversation first — permanent scars may be the better world.
2. Implement §3 whole-grid, single-species, **no optimisation**, behind a `CytoTuning` knob defaulting OFF.
   Unit tests + conservation.
3. Turn it on for the signal species only. Tune `DEN` by eye on `renderCytoMatter` / `cytoAgent`.
4. **Measure before optimising** (§4.1 may already be enough — it is 8–16× and free).
5. Active-tile bitmap (§4.2) only if the spike is real. Parallelise (§4.3) if still needed.
6. Re-baseline, document, commit.

**Rough size:** §3 + tests ≈ half a session. §4.2 + §4.3 ≈ another. Do not do them in one commit —
step 2 (correct, slow, off by default) is independently landable and makes the optimisation verifiable
against a known-good baseline, exactly as the collapse-removal did for the dense port.
