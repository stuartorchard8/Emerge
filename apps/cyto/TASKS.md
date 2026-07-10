# Cyto tasks

The **board**. `MORPHOGENESIS.md` is the design source-of-truth (the *what/why*); this file is the
*what-next* (status only). Perf findings live in `PERF.md`. Move items between sections as they
progress; drop from **Done** freely — `git log` is the real archive. Each task links the MORPHOGENESIS
§ that specifies it.

---

## Now

**⚡ Locomotion unblocked at the root (2026-06-21, `91145a6f`).** Diagnosing why a hand-built welded swimmer
wouldn't move (not even a tiny COM shift over tens of thousands of ticks) found the real blocker: the weld
solver moves cells through the **position-correction channel** (`impPos`) but `integrate` discarded it when
setting velocity, and **drag reads only the velocity channel** — so drag was blind to all spring-driven
motion. Fixed with PBD velocity reconciliation (`v = Δx/dt` in `integrate`, no drag change). A breathing
organism that was bit-frozen now drifts ~115 cell-diam/20k ticks; the 130-cell save stays stable. **Caveat
on prior claims:** earlier "swims, COM drift ~37/10" numbers were on *growing* colonies and are
growth-confounded (asymmetric growth shifts the centroid without locomotion) — true propulsion on a
non-growing organism was zero pre-fix. So self-propulsion is now genuinely real, but **direction is
incidental** (inertia + asymmetric breath, not steered); steering still needs the phased-wave controller
(see Next). Also tuned welds for viable life: `CONNECTION_BREAK_DAMAGE 3→5`, `OVERSTRETCH_BREAK_MULTIPLE
2.0→2.5`, `DRAG_COEFFICIENT 0.2→0.8`. Design: `MORPHOGENESIS.md` §Physics. Diagnostics: `SwimProbe` /
`CollisionChannelProbe`.

**⚡ Systematic optimization pass — biology ~2× (`2026-07-04`).** Benchmarked at 4145 cells: biology
81%, contacts 0, lifecycle 415µs. Sub-phases: build=1661µs, quanta=402µs, genes=1994µs, exchange=3579µs,
diffuse=217µs, finish=3425µs, writeback=241µs. Key wins: round-robin gene eval with tick-based sync +
mitosis cooldown (`9fe02b14`); spring iterations 4→3 (`889a1962`); cytoplasm diffusion period 2→4 (`2574f788`),
frequency every 2nd tick (`4136a097`); exchange group overhead reduced via binary-search transfer in leaf stores
(`766da1d7`), scratch flat arrays replacing HashMap in diffuse (`bb7cf7ed`), 64-entry scratch hash table for
balanceBatched (`2c715421`); presence mask skipping for empty stores (`de969090`), for balanceBatched
(`8ec14762`); pre-computed gene properties for efficiency gear/action type (`87cc4fb2`); species count cache
(`47ff2b39`); bond/atom presence masks in MoleculeStore (`4e8df334`); pre-populated species cache + cached
damage/contract checks (`a37865eb`); parallel executor uses
ThreadPoolExecutor with daemon threads (`3d8f28ec`). See `PERF.md` for full breakdown.

**The front line: a differentiated, self-propelling organism (the jellyfish goal).** Substrate is complete:
`Conc` + AND-gate, retain-side, the metabolic source/sink morphogen loop, produce-without-diffuse
(intracellular determinants), and **oriented division** (✅ `ce9cede` — Mitosis `along`/`across` a morphogen
gradient → threads *or* 2D sheets). Demonstrated in the `CytoSandbox`: a single-cell-grown genome forms an
organizer (isolated `cc` determinant) → `bc` morphogen gradient → light-clocked `Contract` on the high-`bc`
side → it **swims** (COM drift 37 vs 1.8 unmoving); and `Mitosis cc across bc` widens the body from a thread
(41x0) to a **2D sheet** (5x6). **✅ THE BELL WORKS (`cyto-brush-jellyfish.gene`):** `Mitosis cc across bc`
makes a 2D sheet, the `cc` organizer settles **off-centre** (orgOff ~0.5–0.76) → lateralised `bc` gradient →
the high-`bc` flank contracts under daylight → **bend → directional drift ~10**. A single-cell-grown,
genetically-differentiated 2D bend-swimmer. **Remaining = polish, the next frontier:** (1) a **faster
oscillator** (a chemical clock vs the slow daylight band — ~1 stroke/orbit is the speed bottleneck); (2) a
steadier bend (the organizer wanders); (3) directional control (heading is emergent-arbitrary); (4)
cytoplasm-seed-on-spawn tooling so the brush actually differentiates. (B) `Lyse` + competition parked. NOTE: a
brush-painted cell lacks the maternal `cc` seed, so
it grows but doesn't differentiate — functional spawning needs a cytoplasm seed (future tooling).

---

## Next

**⚑ SEQUENCED PLAN for steerable locomotion (chemotaxis) — `PLAN_taxis_substrate.md` (2026-06-21).** The
bend motor works (velocity reconciliation `91145a6f` + lateralised `Contract`), but *steering* needs a goal
(matter/light gradient) and an instrument (the body as a differential sensor of local intake). The matter
substrate can't supply a readable gradient: grid cell = 32 cell-diam (a body sits in ONE cell), and
diffusion flattens any self-dug gradient. Fix, in order:
1. [x] **World RESCALE — DONE (`5ec4903b`, 2026-06-21).** `CELLS_PER_AXIS` 1024→128 + world-scale constants
   ÷8 (LIGHT_FALLOFF 200→25, LIGHT_ORBIT_PERIOD 3600→450, MATTER_FALLOFF 70→9). Body-relative dynamics
   preserved (probe contraction amplitude byte-identical), seeded world still grows, goldens re-baselined.
   Matter grid cell now 4 cell-diam (was 32). **Caught + fixed a real torus bug:** `springSolve` widened
   positions to Long and subtracted non-modularly → a weld across the seam exploded; now Int-modular. New
   `CytoTorusTest` locks down boundary≡centre (homogeneity + field periodicity).
2. [ ] **Matter field: fine static grid + Gaussian local gather, NO diffusion** (`PLAN_taxis_substrate.md`).
   `MATTER_GRID_RES` (fine, ≈1 cell-diam) decoupled from coarse light; cells pull from a precomputed integer
   Gaussian/disc stencil (conservative apportionment) — feeds sessile cells, IS the intake-density sensor,
   lets self-dug craters persist, and is a perf win (O(cells×kernel) not O(RES²)+alloc). Deposit stays local
   (carcass food patches).
3. [ ] **Resume the controller as taxis:** swap the bend's lateralising signal from the fixed `cc` organizer
   to an environment-driven matter-sensitive metabolite → bend toward food. Size cap NOT needed.

**Substrate update (2026-06-17): the gradient needs NO new substrate code.** Decay = metabolism: a morphogen
is an ordinary metabolite in a **centre-source → everywhere-sink loop** (centre synthesises it, every cell
consumes it back), and it diffuses for free because it's `canHold`-true (existing `CytoBiologyCore.diffuse`).
The planned signal-decay rule + `canDiffuse`/`canMetabolise` split are **dropped** (see `MORPHOGENESIS.md`
§Morphogens for shape). So the next step is straight to authoring + probing:

1. [ ] **Author the v0 metabolic-loop genome + probe the gradient** (`HOPEFUL_MONSTER.md`). Source gene
   `Break P IF Conc(X)>0 : FormBond → M` (X = §C determinant marking the centre); sink gene `Break M : FormBond
   → P` in every cell (the decay); a `Conc(M)`-band readout driving two tissues. Pick `P`/`M` so the loop
   **closes atomically** (no-repeat-bond + FormBond fragments — `AB`↔`CC` doesn't balance). Run **mutation-off,
   calm physics**; probe whether a readable point-source gradient forms (watch the integer-floor tail) and a
   stable core/skin body that self-heals on a cut. **The reachability proof.**
   - Spread dial available: the **efficiency gear on `FormBond` is a per-tick cap** (✅ `a264a79`; `REF>>g`, no
     multiplier) — set `g` on the sink gene to tune the consumer rate `k` (hence reach `λ≈√(D/k)`).

Parked until shape is cracked (then re-introduce one axis per campaign — see `HOPEFUL_MONSTER.md` staging):

- [ ] **(A) Drop the leak-block — paid retention via `Import`** (§A). Still wanted (the food web); delete the
  `!canHold` leak-branch condition (leak always; uptake + `diffuse` stay gated). Re-baselines
  `metabolicLeakRetains…` + goldens; the break-powered autotroph's `ab` reserve gets taxed (may need
  `Import("ab")`). Interacts with the `canDiffuse` split above — sequence after it.
- [ ] **(B) `Lyse` — predatory lysis** (§B) — **demoted** (competition, not morphogenesis). Snapshot `attack()`
  phase over the touch-adjacency; efficiency gear `g` = predator-strategy axis. After v0.
- [ ] **Locomotion controller** — genome-readable oscillator → travelling *contraction* wave for
  **directional** (steered) swimming. Physics substrate is now FULLY wired (`Contract` actuator + asymmetric
  drag + velocity reconciliation so drag actually sees the motion — `91145a6f`; `Expand` banned). What's
  missing is purely the *controller*: a phase signal the genome can read (clock/morphogen vs
  position-along-axis) so contraction fires in a travelling phase across the body → a chosen heading instead
  of the current incidental drift. Becomes v2 chemotaxis (dispersal). After v0.
- [ ] **Gradient cost on synthesis (Convert / FormBond)** — *conditional*, only if synthesis needs the
  diminishing-returns treatment Import got; currently flat.

---

## Later

### Tooling & interaction
Stu's stated direction: improve ability to **test & interact**. (Done so far: content-coloring,
focused-cell mutation freeze.)
- [ ] Click-to-inspect / "follow this cell" across saves (IDs renumber on save; throwaway probes keep
  being needed).
- [ ] On-screen global readouts (population, reservoir vs cell matter, species histogram over time).
- [ ] Matter-field heatmap toggle (a chosen species' reservoir concentration, like the light heatmap).
- [ ] Environment painting (drop/clear matter to set up a test patch).
- [ ] Single-step / step-N alongside pause.

### Transcript-mined (SimulifeHub Part 1) — slotted into the v0/v1/v2 ladder
The morphogen-gradient mechanism (transcript's "morphogens reach N cells, neighbours react to
concentration") is now the **front line** (Now/Next — `HOPEFUL_MONSTER.md`), *hand-authored first* (these
circuits don't emerge spontaneously), then refined by selection. The rest slot into the ladder. Source:
`SimulifeHub-Part1.md`.
- [ ] **One-way commitment ("methylation")** — **v1.** Make a fate persist independent of current position:
  at a gradient threshold, commit a **determinant** (isolated, non-decaying — the §C path). Lighter than
  true gene-silencing; revisit a hard lock only if soft commitment drifts.
- [ ] **Programmed cell death (`Apoptosis` / self-`Lyse`)** — **v2.** Gene-driven self-kill action; the
  reproduction "apoptosis wave" (soma clears, germline disperses). _New gene action._
- [ ] **Directed movement / chemotaxis** — **v2.** The locomotion controller (below) reading the gradient →
  dispersal. The transcript's "move away from morphogen 12."
- [ ] **Memory latch** — likely **subsumed by the determinant** (persistent, isolated, no decay) — a
  self-feeding latch shouldn't be needed. Note, don't build.
- [ ] **Explicit timer/countdown gene** — likely **subsumed by `Conc`** (a fixed bolus dilutes as the cell
  grows = a developmental clock). Don't build unless `Conc` proves insufficient for sequencing.
- [ ] **Welded-neighbour count operand** — optional v0 **sidecar** (gives *topology*: surface vs interior;
  data already in `connectionDamage`). The gradient gives *geometry* and is the real substrate — neighbour
  count is the cheap rind-only shortcut. Low priority now that gradients are the focus.

### North-star behaviours to watch for (validation milestones, not build tasks)
The ladder targets these (v0 → v2). Recognise them if a hand-authored genome (then selection) produces them:
- [ ] **(v0)** Two tissues from one gradient — dividing core + non-dividing repairing skin — and
  self-healing on a cut (the live gradient re-reads exposed cells as boundary).
- [ ] **(v1)** Intrinsic size regulation (gradient bounds growth, not just resources); a committed centre.
- [ ] **(v2)** Reproduction cycle — apoptosis clears soma, germline disperses and restarts development.

### Parked / redundant
- [ ] **Cytoplasm capacity = f(biomass), overflow leaks** — *doubly redundant* now (import-gradient
  soft-cap + emergent metabolic size limit). Revisit only if a hard-ish capacity still feels wanted.
- _Rejected: per-resource energy sharing (divide light only among light genes) — keep the genome-bloat
  tax; it drives solar-vs-chem divergence._

---

## Artifacts (watch — not bugs)

- [ ] **Lex-smallest fragment selection** biases `FormBond` toward small molecules — a cell can't reliably
  build a specific larger molecule (e.g. ABBA) while smaller fragments are present. Alt rule
  (longest-match / round-robin) would re-baseline; left as-is.
- [ ] Both cell-panel flow arrows are *predicted* from genome + state, not measured per-tick deltas (fine
  for the steady story; a gated-off gene still shows its intent).
- [ ] **Cells hoard hard** (one held 252k cytoplasm molecules — the gradient soft-cap barely bites under
  abundant-matter dials; also coarsens the broadphase, so capping helps perf too). Mechanism A may
  address via leak.
- [ ] **Genome-bloat outlier** (max 53 genes, median 10) — check the bloat tax is still effective.
  Perf angle (2026-06-19, see `PERF.md`): after the biology micro-opts, `bio:genes` is volume-bound on
  genome size and `Biomass`/`Conc` gates dominate — so capping genome growth is now the top *performance*
  lever too (not just a selection-health one), attacking gates + exchange at the source.

---

## Done (recent highlights — git log is the archive)

- [x] **SoA landing complete** — `CytoSoaReducer` now uses `SoaPipeline` (`06f7470a`); `REPAIR_WELD_MODE`
  enum replaced with `InternalOnly` (`491c1335`, `99313aaa`); broken connections after CSR rendering refactor
  fixed (`60efe400`); AoS fully retired, goldens are the sole gate.
- [x] **Weld fixes** — auto-weld on overlap disabled (`fd9ebafb`); repair welds can form the first connection
  when `weldedDegree == 0` (`fd9ebafb`); `AUTO_WELD_ON_OVERLAP = false` added to `CytoTuning` (`fd9ebafb`).
- [x] **Degrade deposit** — now goes to center-most touching cell, not arbitrary (`9b7ab254`).
- [x] **Round-robin gene eval** — fixed to gate condition evaluation, not gene execution (`716c2966`).
- [x] **Life viability / overpopulation** — reduced both via tuning changes (`2de08043`).
- [x] **Oriented division timeout** — `acrossOrientedDivisionGrowsA2DSheetNotAThread` test timeout fixed
  (`586edd46`).
- [x] **Subtler cell coloring** — improved visual distinction (`e480e8f0`).
- [x] **Tests reliability** — gradle tests exit reliably and discover all cyto tests (`59caaca3`); bio shed
  to grid (`745ca699`).
- [x] **Velocity reconciliation — locomotion unblocked** (`91145a6f`, 2026-06-21) — `integrate` now sets
  velocity to the realized per-tick displacement (`v = Δx/dt`), folding the weld solver's position-correction
  back into `velX/velY` so drag/contacts see spring-driven motion. No drag-equation change. A bit-frozen
  breathing organism now swims ~115 cell-diam/20k ticks; welds gain inertia (damping bleeds the ring), save
  stable. Only the GROWTH `physics` golden moved; determinism gates held. + viability tuning (less-fragile
  welds, stronger drag). Diagnostics `SwimProbe`/`CollisionChannelProbe` (`7197fd2f`). §Physics.
- [x] **Oriented division** (`ce9cede`) — Mitosis `along`/`across` a named axis-morphogen's gradient
  (computed at division from welded neighbours) → threads or 2D sheets. Opt-in (empty axis = unoriented),
  byte-identical goldens. Unblocks 2D bodies (the jellyfish bell). The `CytoSandbox` shows the swimmer go
  thread→sheet with `Mitosis cc across bc`.
- [x] **Differentiated swimmer (sandbox)** — single-cell-grown organism that propels via light-clocked
  one-side `Contract` + asymmetric drag (COM drift 37 vs 1.8). Organizer determinant + morphogen gradient +
  contraction all genetic. Saved as `cyto-brush*.gene` (needs a `cc` cytoplasm seed to differentiate).
- [x] **Authoring/observing tooling** — mutation rate is now **in-game tunable + saved** (`3a1f896`:
  `CytoSimParamsComponent` on the world, `-1`=inherit-cfg sentinel keeps goldens/tests untouched; codec v7;
  "Mut" button cycles off→1/1M→1/100k→1/10k→1/1k); `CytoTuning.MUTATION_ENABLED` master switch (`19e1442`);
  keyboard: Space pause/play, `[`/`]` slower/faster (`c61664a`). Desktop UI/keys need interactive validation.
- [x] **Produce-without-diffuse — intracellular memory** (`993ed17`) — `handleableOf` split into `canHold`
  (metabolic ∪ synthetic = retain) vs `canDiffuse` (metabolic only = cell↔cell share). A species the genome
  only synthesises (FormBond) and never consumes is now intracellular: held + sensed, never shared — so a
  core determinant can be produced on demand and stay isolated (the intra-vs-inter morphogen split). Additive
  (presets metabolise what they synthesise → all goldens byte-identical, incl. mutationOn).
- [x] **FormBond efficiency cap** (`a264a79`) — the gear's per-tick cap (not the `g+1` multiplier) now applies
  to FormBond = the morphogen-spread dial; editor shows EFF for FormBond. Additive (g=0 byte-identical).
- [x] **Asymmetric-mitosis retain-side** (`123c860`) — `Mitosis <m> mother` keeps the morphogen in the
  mother (centred/radial source) vs the default daughter (edge/axial). Body-plan selector; additive (goldens
  byte-identical). Not yet mutated (deferred).
- [x] **Gene editor — multi-clause AND + Conc + Mitosis morphogen/retain-side** (`40283c8`) — authors the
  full current gene model in-UI (add/remove AND-clauses, Conc fields, MORPHOGEN + KEEP picker). *Needs Stu's
  interactive validation (no headless GLFW).*
- [x] **`Conc` operand + AND-conjunction gate** — concentration readout (`count·1000/biomass`,
  size-normalised) + a gene gate that's now a list of clauses, all must hold (`lo < Conc(m) & Conc(m) < hi`).
  Mutation adds/drops/mutates clauses (capped at `GENOME_MAX_CLAUSES`); codec `&`-joins; editor edits
  clause 0. Additive (single-clause presets byte-identical) — only `mutationOn` golden re-baselined. ✅ 2026-06-17
- [x] **Codec — `Mitosis <morphogen>` round-trips** — asymmetric-mitosis (§C) genomes are now
  hand-authorable / saveable as text (was silently dropped); bare `Mitosis` stays symmetric. ✅ 2026-06-17
- [x] **(C) Asymmetric mitosis via morphogen concentration** + **sensing ≠ permeability** — persistent
  one-genome→many-cell-type differentiation affirmed end-to-end. ✅ 2026-06-17
- [x] **Sensed-vs-metabolic split** — `handleableOf` ignores condition operands (a sensor is not a
  channel); morphogen fates stay trace and persist. ✅ 2026-06-17
- [x] **Mutation drifts species operands atom-by-atom** (explores any-length operands). ✅ 2026-06-17
- [x] **Base-efficiency gene field** (`Gene.efficiency` gear `g`) + **light nerf** (light-powered division
  non-viable emergently). ✅ 2026-06-16
- [x] Metabolic leak (retain usable, leak waste); active-uptake gradient cost; emergent size limit;
  break-powered division (biomass/4); cell panel; sim/draw decoupled + in-game gene editor;
  content-coloring; focused-cell mutation freeze.
