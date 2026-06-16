# Cyto tasks

The **board**. `MORPHOGENESIS.md` is the design source-of-truth (the *what/why*); this file is the
*what-next* (status only). Perf findings live in `PERF.md`. Move items between sections as they
progress; drop from **Done** freely — `git log` is the real archive. Each task links the MORPHOGENESIS
§ that specifies it.

---

## Now

_Active focus — Stu pins this. The three-mechanism trio (A/B/`Conc`) and the locomotion controller are
all teed up in **Next**; focus order is your pick (per the 2026-06-17 top note)._

---

## Next

Ready to build, roughly ordered:

- [ ] **(A) Drop the leak-block — paid retention via `Import`** (§A. Symmetric diffusion). Delete the
  `!canHold` condition on the leak branch of `exchangeSpecies` (leak always; uptake + `diffuse` stay
  canHold-gated). Reopens the env-mediated food web. **Re-baselines** `metabolicLeakRetains…` spec +
  the goldens; watch the break-powered-division autotroph (its hoarded `ab` reserve gets taxed — may
  need an `Import("ab")` gene). Gated behind getting a feel for morphogen behaviour first.
- [ ] **(B) `Lyse` — predatory lysis of touching, un-welded cells** (§B. Direct harm). New snapshot-based
  `attack()` biology phase consuming the contacts touch-adjacency (order-independent + parallel-safe like
  `diffuse`). Efficiency gear `g` = predator-strategy axis (low `g` brutal shredder → spills to commons;
  high `g` surgical digester). Conserved. Adds a carnivore tier + defence pressure.
- [ ] **`Conc` operand — concentration gates** (§Morphogen maintenance…). Add *alongside* `Chem` (don't
  replace — mutation explores both); integer cross-multiply (`sp·denom ⋛ k·total`, no float). Gives a
  developmental clock for free (fixed bolus dilutes as the cell grows). Keep `Biomass`-vs-`Constant`
  absolute. Open pick: denominator = total cytoplasm (mole-fraction) vs biomass (per-body). Own commit.
- [ ] **Locomotion controller** — a genome-readable oscillator driving a travelling *contraction* wave
  (the `Contract` actuator + asymmetric drag are built; `Expand` banned 2026-06-16). The pending half of
  the locomotion thread. Natural pairing with chemotaxis (see transcript-mined: directed movement).
- [ ] **Gradient cost on synthesis (Convert / FormBond)** — *conditional*: only if synthesis turns out to
  need the same diminishing-returns treatment Import got; currently flat. Revisit when a profile/balance
  reason appears.

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

### Transcript-mined (SimulifeHub Part 1 — candidate evolvable primitives)
Cyto is **selection-driven**, not hand-authored — so these are *primitives that would let morphogenesis
emerge under selection*, not scripted genomes. Each noted vs what already exists. Source:
`SimulifeHub-Part1.md`.
- [ ] **One-way differentiation lock ("methylation")** — permanent gene silencing once a fate is taken.
  Cyto fates currently persist via *trace-morphogen isolation* (§Sensing ≠ permeability), not locking — a
  methylation primitive would make committed fates robust/irreversible. _New; no equivalent yet._
- [ ] **Programmed cell death (`Apoptosis` / self-`Lyse`)** — a gene-driven self-kill action (with a rate),
  distinct from `Lyse`-on-others (B) and from passive death-by-starvation. Enables sculpting + the
  reproduction "apoptosis wave" (soma clears, germline disperses). _New as a gene action._
- [ ] **Memory latch (positive-feedback trigger gene)** — a gene that gates on its own product → bistable
  cell memory (the transcript's genes 0–5). Check whether a self-sustaining latch is *already evolvable*
  with asymmetric mitosis + `Conc` gating; if not, it's a missing primitive for developmental memory.
- [ ] **Explicit timer/countdown gene** — fire a morphogen after N ticks (transcript: asymmetric, stays in
  mother). **Likely redundant** with the `Conc` developmental clock (bolus dilutes with growth) — note,
  don't build unless `Conc` proves insufficient for sequencing.
- [ ] **Multi-cell morphogen range / gradient** — transcript morphogens reach up to 9 cells. Cyto's gradient
  substrate is the **env reservoir diffusion** (mechanism A) + 1-hop cell↔cell `diffuse`; explicit
  multi-hop range is an alternative. Cross-ref A — probably emerges from A, don't pre-build.
- _Neighbor-count sensing: already covered by the `Touching` operand (un-welded contact count); a
  welded-neighbor count would be the only addition. Low priority._

### North-star behaviours to watch for (validation, not build tasks)
Emergent organism-level milestones the transcript demonstrates — targets to recognise if/when selection
(or a hand-authored probe genome) produces them:
- [ ] Growth-boundary morphogen that limits body size.
- [ ] Differentiation into skin (boundary) / flesh (interior) / germline (centre) from one genome.
- [ ] Regeneration — boundary cells re-divide where a local morphogen is absent, then re-seal.
- [ ] Reproduction cycle — apoptosis wave clears soma, germline disperses and restarts development.

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
