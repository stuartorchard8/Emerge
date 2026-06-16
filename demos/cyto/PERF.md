# Cyto performance log

Empirical findings + levers (reference, not a task list — open work lives in `TASKS.md`, design in
`MORPHOGENESIS.md`). Discipline: **one biology path** (`CytoBiologyCore`), correctness first via the
golden + parallel==sequential gates, optimise from profiles. CytoBench probe via `-Dcytobench=1`.

## 2026-06-16 check, then optimised

The perceived slowdown was never a per-cell code regression — it's ~3.6× more cells (metabolic-leak/
hoarding raised carrying capacity) and denser welded colonies (break-powered division). Profiled on a
535-cell founder colony, then a round of **bit-identical** throughput work landed (golden-gated; tick
**3.73 → 1.35 ms, ~2.8×** at 535 cells):

- **Fast exact integer sqrt** (`Frac2.longISqrt`, `Frac.isqrt`, the cyto reducer's `lenRaw` copy): the
  old ~32-iteration division-per-step bisection → a double seed corrected to the exact integer floor (≈2
  divisions). Biggest win — per-edge×iter in the spring solve and per-pair in contacts. Forces
  942→154 µs, connections 129→20 µs, biology (biomassRadius) 800→656 µs.
- **Contact box-filter before the sort**: a single large hoarding cell coarsens the broadphase grid
  (cellSize ≥ 2·maxRadius), so each 3×3 window held ~73 far candidates and the O(cc²) per-cell insertion
  sort dominated. Move the AABB test into the neighbour gather → sort only the ~0.8/cell that overlap.
  Contacts 2092→377 µs.
- **SpatialGrid reuse** across ticks (`clearForReuse`) — steady-state broadphase now allocation-free
  (tick garbage 1.59→~1.3 MB). GC win, not CPU.
- **Reusable biology cell-order array** (drop per-tick Integer boxing) — small steady alloc win.

Biology is the dominant phase (~49% at normal pop, ~75% at thousands). Second round landed:

- **runGenes made allocation-light** (`CytoBiologyCore` + `CellWork` scratch): the per-cell
  `cytoplasm.copy()` snapshot, two `genome.filter` lists, and per-gene consume HashMap are now reused per
  CellWork scratch (`MoleculeStore.copyFrom`, `activeScratch`, `consumeIds/Per`). Bit-identical. Biology
  11.4→10.0 ms and tick garbage 17→11 MB at 5.5k cells; ~0.9 MB (was 1.3) at normal pop. Clean
  sequential win at all N.
- **Grid-cell-parallel gene phase** (`CytoSoaReducer.buildGridGroups` + disjoint over groups): each
  grid-cell is independent (touches only its own reservoir cell), so it's bit-identical
  (`parallelMatchesSequential` gates it). **Verified a net LOSS and DEFAULTED OFF**
  (threshold `Int.MAX_VALUE`). CytoBench A/B up to ~5.5k cells: every phase slows ~1.5× under the fan-out
  — including untouched single-threaded phases AND the existing parallel spring solver — because pinning
  8 cores busy every tick holds the desktop CPU at its all-core clock (~1.5× below single-core turbo).
  Partial coverage (only `genes` is parallel) + per-tick `invokeAll` overhead can't offset that. Kept
  (tested) for flat-all-core-clock targets (servers); lower the threshold to enable. NOTE the spring
  solver (`springParallelThreshold=2048`) loses on this machine too — its "2.1–2.7× at scale" win was
  likely measured on different hardware or phase-isolated; worth re-checking.

## Levers if biology speed still matters at normal pop

- Parallelise MORE of biology (light/passive/finish per-group + diffuse) so the parallel fraction offsets
  the clock penalty (only helps on flat-clock CPUs).
- Cut remaining per-cell compute (repeated `totalBiomassBonds`, the two-pass light shading).
- Reduce work via harder culling / smaller world / population cap.

Carrying capacity under moving light is a few hundred (the 4546-cell figure was an older static-light
save), so normal-pop tick is ~1.5 ms — already smooth.

## Watch-items the bench surfaced (also tracked in `TASKS.md` Artifacts)

- Cells hoard hard (one held 252k cytoplasm molecules — gradient soft-cap barely bites; coarsens the
  broadphase, so capping helps perf too). Mechanism A (leak) may address.
- Genome bloat outlier (max 53 genes, median 10) — check the bloat tax is still effective.
