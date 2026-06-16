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

1. **Base-efficiency gene field — DONE (2026-06-16).** `Gene.efficiency` (gear g, 0..16): a throughput
   action (Convert/Import/Repair; FormBond/Mitosis exempt) does `g+1` actions per energy unit but may spend
   at most `EFFICIENCY_REF=2^16 >> g` energy/tick. g=0 = uncapped 1:1 baseline. Niche-dependent optimum
   (energy-poor → high g/efficient, energy-rich → low g/throughput); a low-g gene is *always* less efficient
   even when its ceiling goes unused (the cost that makes high throughput a niche adaptation, not a free
   bonus). Landed alongside the **light nerf** (LIGHT_QUANTA_SCALE 6_000_000→120_000, ~50×) that makes
   light-powered division non-viable *emergently* (peak quanta < biomass/4) — replacing the rejected
   hard-coded break-only-Mitosis rule. Wired through codec (`@g`)/save/mutation/editor/panel.
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

Biology is the dominant phase (~49% at normal pop, ~75% at thousands). Second round landed:

- **runGenes made allocation-light** (CytoBiologyCore + CellWork scratch): the per-cell cytoplasm.copy()
  snapshot, two genome.filter lists, and per-gene consume HashMap are now reused per-CellWork scratch
  (MoleculeStore.copyFrom, activeScratch, consumeIds/Per). Bit-identical. Biology 11.4→10.0 ms and tick
  garbage 17→11 MB at 5.5k cells; ~0.9 MB (was 1.3) at normal pop. A clean sequential win at all N.

- **Grid-cell-parallel gene phase** (CytoSoaReducer.buildGridGroups + disjoint over groups): each grid-cell
  is independent (touches only its own reservoir cell), so it's bit-identical (parallelMatchesSequential
  gates it). **Verified a net LOSS and DEFAULTED OFF** (threshold Int.MAX_VALUE). CytoBench A/B up to ~5.5k
  cells: every phase slows ~1.5× under the fan-out — including untouched single-threaded phases AND the
  existing parallel spring solver — because pinning 8 cores busy every tick holds the desktop CPU at its
  all-core clock (~1.5× below single-core turbo). Partial coverage (only `genes` is parallel) + per-tick
  invokeAll overhead can't offset that. Kept (tested) for flat-all-core-clock targets (servers); lower the
  threshold to enable. NOTE the spring solver (springParallelThreshold=2048) loses on this machine too — its
  "2.1–2.7× at scale" win was likely measured on different hardware or phase-isolated; worth re-checking.

Other levers if biology speed still matters at normal pop: parallelise MORE of biology (light/passive/finish
per-group + diffuse) so the parallel fraction offsets the clock penalty (only helps on flat-clock CPUs), cut
the remaining per-cell compute (repeated totalBiomassBonds, the two-pass light shading), or reduce work via
harder culling / smaller world / population cap. Carrying capacity under moving light is a few hundred (the
4546-cell figure was an older static-light save), so normal-pop tick is ~1.5 ms — already smooth.

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
