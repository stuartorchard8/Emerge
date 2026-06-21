# Cyto matter field — adaptive quad-tree (design spec)

**Status: DESIGNED 2026-06-21, implementing.** This is the authoritative spec for the environment matter
field — the replacement for the flat `CytoMatterGrid`. Records the full design agreed with Stu so it can be
picked up cold. Companion: `PLAN_taxis_substrate.md` (why we got here), `MORPHOGENESIS.md` §Physics.

## Why

The taxis substrate needs a matter field that is **sharp where organisms are** (so a body senses local
density via differential intake → chemotaxis) and **cheap everywhere else**. The flat sub-cell grid (RES=1024
= 1M cells, uniform-seeded so all non-empty) made every full-grid op O(1M) (decay, conservation, digest) —
too heavy. The quad-tree gives **storage ∝ observed area**: fine (0.25 cell-diam) only under observers,
collapsing toward coarse in the void. Crucially it also **re-introduces diffusion the right way**: matter
pools/mixes only in *unobserved* regions ("structural diffusion gated by observers"), and **cells themselves
act as diffusion junctions** that mix their footprint (incl. across tile borders) wherever they are.

## Data model

- The torus is a fixed **base grid of `BASE_RES × BASE_RES` tiles**, **torus mod-indexed** — the wrap lives
  entirely here, so no quad-tree ever straddles the seam. `BASE_RES = 2` (a 2×2 floor: the world never fully
  unifies, which is fine — there's ~always ≥1 observer).
- Each tile is the root of an **adaptive quad-tree**, max depth `MAX_DEPTH = 9`. With `SPAN = 2·CELLS_PER_AXIS
  = 256` cell-diam: tile = 128 cell-diam, finest leaf = 128/2⁹ = **0.25 cell-diam** (sub-cell; a cell's radius
  spans ~2 fine leaves → ~13-leaf circular footprint).
- A **node** is one of:
  - **leaf**: `{ MoleculeStore store, int lastAccessTick }`
  - **internal**: `{ 4 child node-refs, int[3] monomerRemainder }` — *no* `lastAccessTick`.
- **Storage**: pooled index arrays (struct-of-arrays) + free-lists for nodes and `MoleculeStore`s, so
  split/merge recycle and steady-state per-tick allocation is ~0. **Sparse**: only split nodes exist; the void
  is a few tile-leaves.
- **Tunable constants** (all sim-behaviour knobs): `BASE_RES=2`, `MAX_DEPTH=9`, `COLLAPSE_DELAY` (≈64,
  per-layer no-access delay before pooling), `DECAY_PERIOD` (species atomisation rate), `MAINTAIN_PERIOD`
  (≈8–16, how often `maintain` runs), and a disc-radius cap (giant-cell footprint bound).

## Invariants (must hold — gated by `matterIsConserved` + `parallelMatchesSequential`)

- **Conservation:** every operation is exact integer; atoms are never created/destroyed. Split distributes
  `⌊c/4⌋` per child + handles the remainder (below); merge sums children + releases the stash; decay splits a
  molecule into fragments whose atoms sum to the original; exchange moves matter cell↔leaf with `Σdeltas = 0`.
- **Determinism:** fixed tile order, fixed child order (NW,NE,SW,SE), integer apportionment everywhere. No
  floats in the conserved path. Required for the golden gate + cross-platform lockstep.
- **Torus:** handled only at the integer tile index; within a tile geometry is plain (no wrap).
- **Observation holds a region open:** any `exchange` access splits the footprint to fine and stamps
  `lastAccessTick`; unaccessed regions collapse progressively.

## Functions

### `splitLeaf(node)` — refine one level
Leaf → internal + 4 child leaves, at depth < `MAX_DEPTH`.
1. Alloc 4 child leaves from the free-list.
2. **Complex species (atomCount ≥ 2):** `⌊c/4⌋` to each child; **atomise the `c mod 4` remainder** (decompose
   each remainder molecule to its monomers) and fold those into the monomer tallies *before* splitting
   monomers. (Rapid decay of the un-even part — consistent with env decay already destroying bonds.)
3. **Monomers (a,b,c):** `⌊c/4⌋` to each child; **stash `c mod 4` (≤3 each) on the new internal node's
   `monomerRemainder[3]`** (≤9 units total, released on merge). No remainder ever goes to a specific child ⇒
   **no spatial bias.**
4. Children inherit the parent leaf's `lastAccessTick`; release the parent store to the pool; node becomes
   internal.

### `mergeNode(node)` — pool one layer (the structural-diffusion step)
Internal node whose **4 children are all leaves** → leaf. Called only by `maintain` on a stale such node.
1. Fresh merged `MoleculeStore` from the pool.
2. Sum the 4 children into it (exact integer add).
3. **Release the stash:** add `monomerRemainder[a,b,c]` back in (the ≤9 locked monomers re-enter).
4. **`merged.lastAccessTick = currentTick`** — *not* `max(children)`. This is what makes collapse
   *progressive*: the parent can't see all-stale children until `COLLAPSE_DELAY` after *this* merge, so
   collapse climbs one layer per delay (matter spreads over 2× area each `COLLAPSE_DELAY`).
5. Release the 4 children (stores → pool, nodes → free-list), clear `monomerRemainder`, node becomes leaf.

The "diffusion" is deferred: merge only **pools** (sums); the uniform smear happens when the leaf is later
**re-split** (even distribution). A region that pools and is never revisited just holds its lump cheaply. For
the uniform seed, pool→resplit is **lossless** (uniform splits back to uniform).

### `descendDisc(cx, cy, radius, currentTick, visit)` — access traversal (refine + stamp)
Walks to the finest leaves overlapping the disc, splitting on the way, stamping access, visiting each.
1. **Tile selection:** the disc's bbox → overlapping tiles, torus mod-indexed (1, up to 4 at a boundary/seam).
2. Per tile, recurse from root over its region:
   - **prune:** region ∩ disc empty → return.
   - **finest (`MAX_DEPTH`):** if region centre within `radius` of (cx,cy) → `lastAccessTick = currentTick`,
     `visit(leaf)`. Return.
   - **descend:** else if node is a leaf → `splitLeaf` it (access refines); recurse into all 4 children.
3. Radius capped (giant-cell bound). Boundary nodes the disc merely clips get split but not visited — they
   simply re-collapse later (accepted).
No matter moves here; `splitLeaf` is conservative, `visit` does the transfer.

### `exchange` — the diffusion junction (cell ↔ footprint; **replaces withdraw + deposit**)
A cell registers as a **diffusion target**; the grid balances its footprint toward that target, symmetrically.
Policy is the biology's; the spatial balance is the grid's.
- **Biology computes**, per species: an **effective level `C_eff`** = cytoplasm count, biased by genes
  (`Import` lowers `C_eff` ∝ efficiency → inward diffusion; Export, when built, raises it). Species set =
  `canDiffuse` species present in the cell or footprint. **Excluded:** determinants (`canHold && !canDiffuse`,
  intracellular) and foreign species (`!canHold`, never enter ⇒ selective permeability). **No** exposure term
  (buried-cell buffering is *emergent* — interior footprints are pre-drained by neighbours). **No** OUT-ONLY /
  passive leak — waste accumulates until **death or an export gene** (food web is fed by corpse deposits +
  decay).
- **Grid does:** one `descendDisc` that refines+stamps the footprint and **collects the N in-radius fine
  leaves** (handle/scratch). Then per species: `bucket = ⌊C_eff / N⌋` (remainder kept in cell, untransacted);
  for each collected leaf, **balance** `e` vs `bucket` toward equal, **larger source keeps the odd unit**
  (`total = e+bucket`; if `e ≥ bucket` → `e' = ⌈total/2⌉, b' = ⌊total/2⌋`, else swap); accumulate `Σb'`.
  Net `Δ = Σb' − N·bucket` returned; biology adds `Δ` to real cytoplasm.
- **Conservation:** per leaf `e'+b' = e+bucket` ⇒ `Σ(leaf Δ) = −Δ`; cell+grid = 0, exact.
- **Junction property:** because every footprint leaf is balanced toward the *same* `bucket`, a leaf-rich side
  and a leaf-poor side both move toward it ⇒ matter mixes *through the cell* across its footprint — local
  diffusion (incl. across tile borders) wherever a cell sits, without collapsing the grid.
- **API shape:** stateful handle to keep it one traversal + opaque — `h = grid.openFootprint(cx,cy,radius,
  tick)` (one `descendDisc`, caches N leaf refs + their species union); `grid.balance(h, sp, C_eff) → Δ` per
  species; `h.close()`. Grid never sees genes; biology gets the footprint species union back from the handle.

### `deposit(cx, cy, species, amount, currentTick)` — death / export
A trivial `descendDisc` (refine to fine) that adds `amount` to the footprint leaves (spread, integer). Used by
death recycling (corpse → fine deposit at the cell's position) and by a future export gene.

### `maintain(currentTick)` — the self-upkeep tick (collapse + decay), run every `MAINTAIN_PERIOD`
**One post-order walk** per tile root, `maintainNode(node) → (isLeafNow, lastAccessTick)`:
- **Leaf:** run `decayLeaf`; return `(leaf, lastAccessTick)`.
- **Internal:** recurse all 4 children *first* (post-order); then if **all 4 are leaves AND all stale**
  (`currentTick − child.lastAccessTick ≥ COLLAPSE_DELAY`) → `mergeNode` → return `(leaf, currentTick)`; else
  `(internal, —)`.
Post-order is what yields **progressive** collapse for free: a node merged this pass is fresh
(`lastAccessTick = currentTick`), so its parent sees a non-stale child and waits another delay.

`decayLeaf(leaf)`: for each multi-atom species, atomise `⌊count / DECAY_PERIOD⌋` by peeling the leftmost bond
(`abc → a + bc`), both fragments into the same leaf. Conservation-exact; does **not** touch `lastAccessTick`
(decay isn't observation). So unobserved matter both **pools** (collapse) and **erodes to monomers** (decay).

Walks only **allocated** nodes ⇒ the void (a few tile-leaves) is ~free — the perf win over the flat 1M grid.

## Gene-control mapping (summary)
- `canDiffuse` (metabolic reach) → **bidirectional** exchange (absorb when footprint richer, leak when cell
  richer; `Import` biases toward absorb).
- `canHold && !canDiffuse` (synthesised-only determinant) → **excluded** (intracellular memory).
- `!canHold` (foreign) → **never enters** (selective permeability).
- Waste (held, un-metabolisable) → **no passive leak**; accumulates until death/export gene.

## Behavioural changes vs the flat grid (re-baselines / tests to revisit)
- Passive waste-leak removed (`metabolicLeakRetainsUsableMatterDumpsWaste` retires/changes).
- Exposure damping removed (emergent via footprint depletion).
- Matter only diffuses in **unobserved** regions (progressive pooling) + via **cell junctions** (local) —
  not globally.
- Goldens re-baseline; `matterIsConserved`, `autotrophGrowsIntoAColony`, `parallel==sequential`,
  `grownStateRoundTrips`, torus-homogeneity must stay green. Save codec must serialise the tree (flatten to
  leaves, or per-tile structure) — conservation across save round-trip is gated by `saveRoundTripsTheMatterWorld`.

## Future / open
- Proportional (even-circular-crater) gather instead of larger-keeps-remainder, if crater *shape* matters.
- `concentrationOver` read for a matter heatmap (rendering only; biology senses via intake).
- Export gene (the active-out counterpart to `Import`).
- Tune `COLLAPSE_DELAY` / `DECAY_PERIOD` for the desired background-mixing feel.
