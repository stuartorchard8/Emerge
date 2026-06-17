# Cyto tasks

The **board**. `MORPHOGENESIS.md` is the design source-of-truth (the *what/why*); this file is the
*what-next* (status only). Perf findings live in `PERF.md`. Move items between sections as they
progress; drop from **Done** freely — `git log` is the real archive. Each task links the MORPHOGENESIS
§ that specifies it.

---

## Now

**The front line: author the v0 metabolic-loop monster.** The substrate is now complete — `Conc` + AND-gate,
asymmetric mitosis with a **retain-side** (radial/centred *or* axial/edge source), the metabolic source/sink
loop (decay = metabolism, rides existing diffuse), and the editor updated to author all of it. Next step is
purely authoring + tuning (see `HOPEFUL_MONSTER.md`): pick `P`/`M` so the loop closes atomically, write the
source/sink/readout genome, run mutation-off + calm physics, and probe whether a readable gradient + a stable
self-healing two-tissue body forms. **The reachability proof.** (B) `Lyse` + competition/locomotion stay
parked until shape is cracked.

---

## Next

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
- [ ] **Oriented division (v1) — strings vs blobs.** A Mitosis **`slice`/`project` param** relative to the
  ∇m axis: `project` offsets the daughter along ∇m (`o-x`→`o-o-o`), `slice` offsets perpendicular
  (`o-x`→`o<8`, the escape from gradient self-reference). Placement is a symmetric straddle (`±offset`), so the
  `±` is just the retain-side param — no sign tiebreak. ∇m read **once at division** (cheap, not per-tick).
  No-gradient fallback (no epsilon threshold — any nonzero diff = gradient): axis = ∇m → else neighbour-normal
  (today's free-space) → else `transform.ang`. The **4 Mitosis params are independent** (asym-morphogen ✅,
  retain-side = radial/axial body plan ✅ built `123c860`, axis-morphogen, slice/project) — only the last two
  remain for this item; don't force morphogen reuse (it traps body plans). Bare `Mitosis` stays today's
  behaviour (saves survive); goldens re-baseline. Spring
  reassignment generalises (feed `divide`'s ahead/side the gradient normal). Caveat: forcing an axis places
  daughters into occupied space → re-checks the no-kick placement.
- [ ] **Locomotion controller** — genome-readable oscillator → travelling *contraction* wave (`Contract` +
  asymmetric drag built; `Expand` banned). Becomes v2 chemotaxis (dispersal). After v0.
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

---

## Done (recent highlights — git log is the archive)

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
