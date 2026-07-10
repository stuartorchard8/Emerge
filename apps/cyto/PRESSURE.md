*Ideas for breaking the steady-state equalibrium in cyto*

Currently there are only 2 meaningful sources of equalibrium disruption in cyto:
1. Low-probability mutation
2. Player input (disconnecting/deleting cells)

In order to speed up evolution by natural selection, cells need more pressure from their environment.
Increased pressure (resulting in death of the unfit) would free up chemical resources for the fit to use to replicate.
In the current simulation, pressure is so low that cells are mutating excessively without any consequences, and thus their genes are effectively meaningless to their survival.
Additionally, there appears to be a problem with how cells access environmental resources, in that they don't all get an equal of what's made available in the environment. This would be undetectable if it were random which cells got access, but it appears to be the same cells soaking up the chemicals and dividing (even though their daughter cells arguably should have the exact same genome and therefore the exact same access). It would also be fine if a genetic advantage were allowing the cells to get preferential access, but the daughter cell problem also refutes that being the case.

Proposals:
1. 1000x all chemicals. Going below 1000 biomass results in death. Cell radius is scaled down such that 1000 biomass now is equivalent to 1 biomass before. 1000x decay rates for biomass.
2. When biomass decays, the smaller chemical is ejected into the environment - the larger one is retained in the cytoplasm.
3. When multiple genes are active at once, they compete with one another for the finite resources the cell has available (regardless of whether they actually use the same energy source). 2 genes active means each gene can only utilize 50% of the available energy it relies on. 5 genes -> 20% cap.
-- Note that if 2 genes code for the same activity, then it's nearly a wash: 
--- If light exposure is 501, and 2 active genes use light to bind "ab", then each gene will be able to use 250 light for binding even though the action is identical (remainder is lost).
--- If light exposure is 500 and "ba" present is 100, and 1 active gene uses light to bind "ab", and another active gene breaks "ba" to bind "ab", then the first gene can only use 250 light, and the second gene can only use 50 "ba" even though the source is different.
4. Light exposure per tick in any given grid cell is split amongst all bio cells in that grid cell. This emulates shading.

---

## Implemented 2026-06-14 (Claude)

Critique first established that the observed "same cells soak up everything / daughters starve"
symptom was **not** biology but an *iteration-order artifact*: passive cell↔env exchange ran per-cell
in ascending-EntityId order, so the oldest founder skimmed the shared grid-cell first every tick and
its own (higher-id, identical-genome) daughters were served last — selection was acting on birth
order, not genome. None of the four proposals targeted this directly, so it was fixed first. Landed,
in order:

1. **Fair, order-independent passive env exchange** (`bf8e530`) — the root-cause fix. All cells sharing
   a grid-cell now draw against one snapshot; over-subscribed absorbers split it proportionally
   (`⌊want·env/Σwant⌋` + deterministic remainder). This is **proposal 4's mechanism (split a shared
   environmental resource fairly) applied to the conserved resource (matter), where it actually
   matters**, rather than to light (open throughput).
2. **Light is a shared per-cell quanta budget** (`87127fd`) — the clean core of **proposal 3**. Every
   light gene drew its own full copy of the cell's flux (N genes = N× free work, rewarding genome
   bloat); now they share `work.quanta`, spent down in genome order, and a sated gene releases the
   remainder. Dropped proposal 3's cross-source 1/N split (incoherent for BreakBond; lossy remainder).
3. **Decay ejects the smaller fragment to the environment** (`612a07e`) — **proposal 2**, verbatim.
   Biomass upkeep is now a real matter leak the cell must import against (and a food-web feed), not a
   free cytoplasm treadmill. Synergises with #1 (the leak redistributes fairly instead of feeding back
   to the strongest cell).

**Deferred (revisit only if pressure is still too low after observing the above):**
- **Proposal 1** (1000× rescale for fitness-landscape resolution): the uniform rescale is a dimensional
  no-op except for resolution, and bumps the per-op throughput cap (`MAX_OPS_PER_GENE`) — if pursued,
  raise *only* the quantities/thresholds where integer quantization washes out gene signal, and scale
  `LIGHT_QUANTA_SCALE` with it.
- **Proposal 4 as light-shading**: light is a non-conserved open throughput; matter is already the
  carrying-capacity brake. Adding a second density brake on light risks over-damping. `CytoExposure`
  already shades neighbour-buried cells.

> NB: the "cells mutate excessively without consequence" framing was half-diagnosis — `mutationRateDenom`
> is already tuned low (1/100k; a meltdown at 1/10k drove it down). The real lever was selection
> *differential*, which the three changes above sharpen; mutation supply was not the problem.

---

## Implemented 2026-06-15 (Claude) — the two deferred selection mechanisms, picked up

Stu reframed proposal 4: its value isn't *exploitation* (everyone shares thinner) but **interference** —
an autotroph that grows takes up space/light and *actively harms* its neighbours, starving the unfit so
they decay, die, and release their matter to the commons (death already recycles a cell's whole store to
its reservoir grid-cell). With that framing both deferred mechanisms became worth building:

4. **Light shading as interference competition** (`4214f42`) — **proposal 4, properly**. Cells sharing a
   grid-cell now split its incident light by **capture weight (exposure × radius)**, not equally: a bigger
   cell captures a larger share, so growth is a weapon. A cell alone in its grid-cell keeps full light
   (capture share 1 ⇒ quanta bit-identical), so only crowding moves trajectories. The earlier worry
   ("light is open throughput, a second brake over-damps") is answered by making it *competitive* rather
   than a flat density tax — and it's the selection gradient that will drive locomotion (move toward
   unshaded light). Footgun hit & noted: `exposure * radius` as `Frac×Frac` overflows `Long` once a grown
   cell's radius leaves Frac's safe ±2 range → negative capture; compute capture in reduced raw-longs.
3. **Genome-bloat tax, cross-source** (`603f308`) — the part of **proposal 3** that was dropped as
   "incoherent for BreakBond". Each *active* gene is throttled to a 1/N share of its source (N = active
   genes), regardless of source: Light → ⌊quanta/N⌋, BreakBond → ⌊(matching molecules)/N⌋, remainder lost.
   Reproduces both of the proposal's worked examples exactly. "Active" excludes guaranteed no-ops (an
   always-on Repair with nothing damaged), so it taxes real bloat without crippling a functional genome.
   Subsumes the 2026-06-14 shared light-pool (per-gene slices can't overdraw the pool).

**Proposal 1 — deliberately deferred again** (Stu's call). Death *already* works: a starved cell decays
to `DEATH_BIOMASS` and recycles its matter, and shading now *creates* the starved cells — so the loop is
closed without it. Proposal 1's real teeth need the resolution rescale (the operating band is only ~4–9
integer bonds — `DIVIDE_BIOMASS 8`, halving on division — so no *safe* death floor fits between a
freshly-divided daughter and a meaningful threshold without finer units). High-churn (≈6 coupled
constants + autotroph-viability risk); revisit only if pressure is still too low after observing shading +
the bloat tax in the live sim.
