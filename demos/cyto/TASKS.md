# Cyto tasks

The **board**. `MORPHOGENESIS.md` is the design source-of-truth (the *what/why*); this file is the
*what-next* (status only). Perf findings live in `PERF.md`. Move items between sections as they
progress; drop from **Done** freely — `git log` is the real archive. Each task links the MORPHOGENESIS
§ that specifies it.

---

## Now

**The front line: morphogens for shape → the hopeful monster** (the 2026-06-17 keystone shift — see
`HOPEFUL_MONSTER.md` for the program, `MORPHOGENESIS.md` §Morphogens for shape for the contract). Build the
substrate in order (1→4 in Next), then hand-author a gradient-based two-tissue organism mutation-off. This
**demotes (B) `Lyse`** and the broader competition/locomotion work until shape is cracked.

---

## Next

The morphogen-for-shape substrate, in build order:

1. [ ] **`Conc` operand + AND-conjunction gate** — the positional-band readout primitive (`lo < Conc(m) AND
   Conc(m) < hi`). `Conc` alongside `Chem` (mutation explores both), integer cross-multiply
   (`sp·denom ⋛ k·total`, no float); open: denominator = total cytoplasm (mole-fraction, leaning) vs biomass.
   Gate becomes a **list of clauses, all must hold** (NOT via `<`, OR via separate genes, no weighted sums);
   re-checked per op like the current single gate. Mutation adds/drops/mutates a clause. Codec: `&`-joined
   condition. Re-baselines `mutationOn` golden (PRNG route + new operand).
2. [ ] **Signal decay + `canDiffuse`/`canMetabolise` split** — the gradient substrate. Split `canHold` into
   `canDiffuse` (FormBond-produced-but-not-metabolised = morphogen; sender pushes down-gradient to **welded**
   neighbours, cell↔cell only) and `canMetabolise` (existing). Add per-tick decay of morphogen species back
   to monomers (matter-conserved). Source = a FormBond gene gated on a determinant (§C seeds it). **Re-baselines
   goldens** (new biology phase). Calm physics while testing the gradient.
3. [ ] **Hand-author the v0 genome, run mutation-off via a probe** (`HOPEFUL_MONSTER.md` illustrative genome).
   Tune decay/diffusion/thresholds until a stable point-source gradient + dividing-core / repairing-skin body
   forms and self-heals on a cut. **The reachability proof.**

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
  (today's free-space) → else `transform.ang`. The **4 Mitosis params are independent** (asym-morphogen,
  retain-side = radial/axial body plan, axis-morphogen, slice/project) — don't force morphogen reuse (it traps
  body plans). Bare `Mitosis` stays today's behaviour (saves survive); goldens re-baseline. Spring
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
