# Cyto backlog

Queued work + known artifacts, so we don't lose track. (Design rationale for the energy-economy thread
lives in the conversation history; MORPHOGENESIS.md is the source of truth for the model.)

## Recently landed (context)

- **Metabolic leak** — passive exchange leaks only species the cell *can't* metabolise; usable matter is
  retained, so cells can hoard an imported reserve. Food web now via death + degradation.
- **Active-uptake gradient cost** — Import yield `⌊k·SCALE/(SCALE + max(0, cyto−env))⌋`
  (`IMPORT_GRADIENT_SCALE`): 1:1 at/below ambient, diminishing as the cell concentrates above it. Hoarding
  self-limits (soft cap); nutrient-poor patches become an energy-rich niche; competition rewards
  over-pushing before the cheap band depletes. (Passive exchange + synthesis unchanged.)
- **Emergent size limit** — replaced the hard Convert cap (`1 − biomass/MAX`) with throttling every op
  except Mitosis by `SCALE/(SCALE+biomass)` (`METABOLIC_BIOMASS_SCALE`): metabolism slows with size while
  decay rises, so growth crosses decay at an emergent (strength-dependent) size — no flat 32000.
- **Cell panel** — env|CYT|BIO metabolism table with two flow arrows; reservoir shown; FormBond displayed
  by its two operative atoms.
- **Sim/draw decoupled** (own sim thread + speed control); **in-game gene-editor** (tap a gene → edit /
  dup / delete, dropdowns + hold-to-repeat).
- **Content-coloring** — cells drawn by HSV: hue = biomass a/b/c→R/G/B atom mix, saturation = cyto:bio
  instance ratio, value 0.75 (focused cell 1.0).
- **Focused-cell mutation freeze** — the inspected cell (panel open) is exempt from natural mutation.

## Queued — energy economy (Stu's order)

The guiding principle: **too many things are hard-coded engine rules that should be genetic choices; the
blocker is that actions cost too much, so cheap-enough actions is the unlock.** The gradient-cost above is
the first step (efficient-slow is the free default; over-push only when contested).

1. **Base-efficiency gene field** — an explicit evolvable trait so a cell can *choose* its rate↔efficiency
   point per gene (not just ride the gradient). Must be a trade-off (not a monotone dial like raw potency),
   e.g. discrete gears where high gear = more output per energy but a lower ops/tick rate cap — optimum is
   niche-dependent (energy-rich → fast/wasteful, energy-poor → slow/efficient). Stu wants this.
2. **Sensed-vs-metabolic split** — a `ChemQty` *sense*-gate currently makes the sensed species handleable →
   retained → the cell hoards a signal it only senses (e.g. cell 1403 hoards `c` from a `ChemQty cc` gate).
   Split "metabolic reach" (retain) from "sensed-only" (still absorbable to sense, but leaks so it tracks
   the reservoir). Proper fix for the hoarding-a-signal artifact.
3. **Gradient cost on synthesis** (Convert / FormBond) — only if synthesis turns out to need the same
   diminishing-returns treatment; currently flat.
4. **Cytoplasm capacity = f(biomass), overflow leaks** — Stu's idea; now *doubly redundant* (import
   gradient soft-cap + the emergent metabolic size limit). Revisit only if a hard-ish capacity still feels
   wanted.

Rejected: per-resource energy sharing (divide light only among light genes) — keep the genome-bloat tax;
it drives the solar-vs-chem divergence.

## Known artifacts (not bugs, watch / maybe address)

- **Lex-smallest fragment selection** biases FormBond toward small molecules — a cell can't reliably build a
  specific larger molecule (e.g. ABBA) while smaller fragments are present. Alt rule (longest-match /
  round-robin) would re-baseline; left as-is for now.
- Both cell-panel flow arrows are *predicted* from genome + state, not measured per-tick deltas (fine for
  the steady story; a gated-off gene still shows its intent).

## Performance (2026-06-16 check, then optimised)

The perceived slowdown was never a per-cell code regression — it's ~3.6× more cells (metabolic-leak/hoarding
raised carrying capacity) and denser welded colonies (break-powered division). With the profile re-run on a
535-cell founder colony (CytoBench probe, `-Dcytobench=1`), then a round of **bit-identical** throughput
work landed (golden-gated; tick **3.73 → 1.35 ms, ~2.8×** at 535 cells):

- **Fast exact integer sqrt** (Frac2.longISqrt, Frac.isqrt, the cyto reducer's lenRaw copy): the old
  ~32-iteration division-per-step bisection → a double seed corrected to the exact integer floor (≈2
  divisions). Biggest win — it's per-edge×iter in the spring solve and per-pair in contacts. Forces
  942→154 µs, connections 129→20 µs, biology (biomassRadius) 800→656 µs.
- **Contact box-filter before the sort**: a single large hoarding cell coarsens the broadphase grid
  (cellSize ≥ 2·maxRadius), so each 3×3 window held ~73 far candidates and the O(cc²) per-cell insertion
  sort dominated. Move the AABB test into the neighbour gather → sort only the ~0.8/cell that overlap.
  Contacts 2092→377 µs.
- **SpatialGrid reuse** across ticks (clearForReuse) — steady-state broadphase is now allocation-free
  (tick garbage 1.59→~1.3 MB). GC win, not a CPU one.
- **Reusable biology cell-order array** (drop per-tick Integer boxing) — small steady alloc win.

Remaining: biology is now the dominant phase (~49%) and is compute-bound + sequential **by design** (shared
reservoir grid + EntityId-ordered mutation PRNG → not bit-identically parallelisable without grid-cell
partitioning). The next real lever is parallelising biology across grid-cells (medium effort, determinism-
sensitive) or reducing per-cell work; otherwise harder culling / smaller world / population cap.

Watch-items the bench surfaced (not perf-critical): cells hoard hard (one held 252k cytoplasm molecules —
the gradient soft-cap barely bites under the abundant-matter dials; this also coarsens the broadphase, so
capping it helps perf too), and genome bloat has an outlier (max 53 genes, median 10) — check the bloat tax
is still effective.

## Tooling / interaction (candidates)

Stu's stated direction: improve ability to **test & interact**. Done: content-coloring, focused-cell
mutation freeze. Remaining candidates (proposed, not yet picked):
- Click-to-inspect / "follow this cell" (IDs renumber on save; throwaway probes keep being needed).
- On-screen global readouts (population, reservoir vs cell matter, species histogram over time).
- Matter-field heatmap toggle (see a chosen species' reservoir concentration, like the light heatmap).
- Environment painting (drop/clear matter to set up a test patch).
- Single-step / step-N alongside pause.
