# Cyto → evolvable macro-biology (design)

The goal: turn Cyto into a **hands-off "watch evolution" god-sim** — a substrate where a genome grows
and sustains a multicellular body, and natural selection can act on it given time. This doc is the
settled design we build toward; it is the contract, not a status log.

> **2026-06-12 — matter rework.** This supersedes the earlier *closed-energy* model (a depletable
> energy reservoir). The conserved/limiting resource is now **matter**, not energy. The standalone
> `CHEMISTRY.md` / `BIOMASS.md` proposals are folded in here. The depletable-energy coupling that was
> in flight is parked (`git stash`: "5b closed-energy coupling …") — its grid / draw-deposit /
> conservation-invariant / AoS↔SoA-parity / save patterns **retarget** to the matter store, so the
> mechanism survives; only the currency changes.

> **2026-06-17 — three mechanisms for stronger selection.** A mutating, biodiverse population exists, but
> selective pressure is too weak for more complex forms to be favoured — and two mechanics have drifted
> from this contract. Three **independently buildable** mechanisms (see **§Three mechanisms for stronger
> selection** near the end): **(A)** reopen symmetric down-gradient diffusion — *drop the leak-block*,
> concentrate via paid active `Import` (PLANNED); **(B)** a direct-harm `Lyse` action (predatory lysis of
> touching cells) for active competition (PLANNED); **(C)** gene-driven **asymmetric** mitosis via
> morphogen concentration, the differentiation keystone — ✅ **built**, together with **sensing ≠
> permeability** (a sensed morphogen stays trace), so persistent one-genome→many-cell-types
> differentiation is affirmed end-to-end. Remaining: A, B, and the additive **`Conc`** operand.

> **2026-06-17 (later) — keystone shift: morphogens for SHAPE, not just fate.** Designing toward a
> hand-authored *hopeful monster* — a genome that develops into a *shaped, differentiated* body (see
> `HOPEFUL_MONSTER.md`) — surfaced that the morphogen machinery was built backwards for that goal.
> Isolated, persistent morphogens (§C + sensing≠permeability) give *fate/memory*; a *shaped* body needs
> *positional gradients* — morphogens that **spread and decay** (source + diffusion + decay → a
> steady-state gradient a cell reads to know *where it is*). Neighbour-count gives only *topology*
> (am I on the surface); a gradient gives *geometry* (how far from the centre, which axis). Decision
> (with Stu): pivot the keystone to **positional gradients**, and reclassify signals into three
> **genome-derived roles** — **determinant** (sensed + mitosis-allocated, never produced → isolated,
> persistent = memory/fate; the existing §C path), **morphogen** (FormBond-produced but not metabolised
> → diffuses cell↔cell + decays = shape), **metabolite** (metabolised → food). Fate becomes *downstream*
> of position (read the gradient → at a threshold, commit a determinant). This **promotes** the gene-logic
> + signal work and **demotes B** (`Lyse`, competition) until shape is cracked. **Locked decisions:**
> morphogens **cost matter** (FormBond source; decay recycles atoms back to monomers); diffuse
> **cell↔cell** (not the coarse env grid — that sets a bad precedent); the gene gate becomes an
> **AND-conjunction of binary clauses** (NOT via `<`; OR via separate genes; still no weighted sums). See
> **§Morphogens for shape — positional gradients** below.

## The central principle: matter is closed, energy is open

Like a real ecosystem (sunlight pours in for free; nutrients are finite and recycled):

- **Energy is an open throughput.** Light is a *static, unlimited* environmental flux (the existing
  `CytoLightField`, non-depletable). Energy is pumped in by light, spent on gene actions, and
  dissipated. It is **never stored as a free-floating pool** and is **not conserved**.
- **Matter is a closed budget.** **Atoms** are the conserved quantity. They are seeded once into the
  environment, cycle through cells (import → cytoplasm → biomass → death), and are returned on death.
  Total atoms across {environment + every cell's cytoplasm + every cell's biomass} is **invariant**
  (the one exception: the player may hand-place a cell, which injects matter — a deliberate external
  event, accounted for explicitly; the autonomous sim conserves).

This is what gives the hard carrying capacity and kills the filament/runaway exploits: you cannot
build biomass from atoms that do not exist, and there is no way to mint them.

## Chemistry — chemicals, bonds, energy

- **A chemical is a molecule**: a string of **atoms** over a small alphabet (today's string-keyed
  species model carries straight over — `"ab"`, `"aba"`, …). The **bonds** are the adjacent pairs
  (`"aba"` has bonds `ab` and `ba`); a chain of length *L* has *L−1* bonds.
- **Quantities are discrete integer counts** — a cell (and each environment grid-cell) holds an
  integer number of molecules per species, not a continuous Frac concentration. Matter conservation is
  then exact integer arithmetic, trivially lockstep-identical across AoS/SoA. **`Frac` leaves the
  chemistry entirely** (it stays for physics: position, radius, springs).
- **Polymerisation is forbidden.** A molecule may contain **at most one of each ordered bond type**.
  So `"aba"` cannot accept another `b` on either end (it would make a second `ab` or `ba`). This is
  the structural brake on complexity — it bounds molecule length to ≈ |alphabet|²+1 and bounds the
  whole species set to a finite set, with **no decay-rate hack needed**. (Replaces the old enzyme
  trick of *breaking* a chemical that already had the bond — now we just refuse to form it.)
- **Energy lives in bonds (one fixed quantum per bond).** Forming a bond *consumes* exactly one
  quantum; breaking it *releases* exactly one quantum. So a bond is a battery and the cycle
  `a + b ⇌ ab` is **energy-neutral** — no free-energy exploit, no sink. A molecule's **stored energy
  and its biomass value are the same axis: its bond count** (see Biomass).
- **Energy is per-gene, private, and discrete.** A gene draws energy *only* from its own declared
  source for its own action, *this tick*, use-it-or-lose-it. Energy is counted in **quanta (1 per
  bond)**: a gene with *N* quanta available performs *N* bond-ops (integer); any sub-quantum remainder
  is lost (dissipated). Genes cannot pool, bank, or borrow energy; a gene that cannot source a whole
  quantum this tick simply does not act. There is **no energy state anywhere** — only matter is
  stateful. (Consequence: the dominant `energy` chemical and its dense column disappear.)

## The gene — one energy source, one binary gate, one action

A gene is exactly three parts (no multi-input weighted sums like the legacy model):

1. **Energy source** — sets *how many quanta* (hence how many bond-ops) the gene gets this tick:
   - **Light** — the static field at the cell's position, scaled by **surface exposure** (interior
     cells are shaded; the existing `CytoExposure` weight carries over), floored to an integer quanta
     count. Autotrophy. **Shading (interference competition):** cells sharing an environment grid-cell
     split that grid-cell's incident light by **capture weight = exposure × radius**, so a bigger cell
     captures a larger share and starves smaller neighbours (a cell alone in its grid-cell keeps the
     full amount — capture share 1). Growth is thus an active weapon, not just self-benefit.
   - **Break bond X** — break a specified bond in a cytoplasmic molecule: releases its 1 quantum to
     power the action *and* splits the molecule into two fragments returned to cytoplasm (matter
     conserved). Heterotrophy / catabolism.

   **Genome-bloat tax:** each gene that is *active* this tick (gate on + real work to do) is throttled
   to a **1/N share** of its energy source, where N = the number of active genes — regardless of source
   (Light → ⌊quanta/N⌋, Break bond → ⌊matching-molecules/N⌋). The unclaimed share is lost, so firing
   many genes at once is costly and lean genomes out-grow bloated ones.
2. **Condition — an AND-conjunction of binary clauses** (2026-06-17; was a single gate). Each clause is
   `operand ≷ operand`; the gene fires only when **all** clauses hold. NOT is `<` (below/absence); OR is
   separate genes with the same action; still **no weighted sums** (rejected — monotone activation
   saturates). This is the minimum logic for a *positional band* readout (`lo < Conc(m) AND Conc(m) < hi`),
   the French-flag primitive shape needs. Baseline operands:
   - quantity *or* **concentration** (`Conc`) of a given chemical ≷ a threshold (or another live quantity);
   - total biomass ≷ a threshold; un-welded **contact** count (`Touching`); **welded-neighbour** count.
3. **Action** — one of (extensible list; baseline):
   - **Form bond** — join two **whole** molecules end-to-end (one ending in atom *a* + one starting
     with atom *b* → `…ab…`); refused if the product would repeat a bond (the no-repeat rule);
   - **Contract / Expand** radius beyond baseline;
   - **Active import** a specific chemical (environment → cytoplasm) — *also seals that species against
     passive down-gradient leak* (§Three mechanisms A);
   - **Active export** a specific chemical (cytoplasm → environment);
   - **Sticky** (adhere on contact) / **Separate** (disconnect);
   - **Mitosis** (divide) — optionally **asymmetric** (a named morphogen allocated whole to one daughter;
     §Three mechanisms C);
   - **Lyse** — predatory harm to a *touching, un-welded* cell (§Three mechanisms B);
   - **Convert** a chemical into biomass (lock it as structure).

   *(**Cell↔environment exchange is passive down-gradient by default** — see §Three mechanisms A and
   §Resolved-#1 — and an `Import` gene both pumps a species up-gradient *and* seals it against passive
   leak. Cell↔cell diffusion is cytoplasm-only, passive, floor-split — §Resolved-#5.)*

Autotroph vs heterotroph, builder vs forager all fall out of which energy source + action a genome
wires up — none of it is hardcoded.

## Biomass & cell size

- **Size is biomass.** A cell's base/target radius is a function of **total biomass**, replacing the
  old `energy → radius` map. Total biomass = Σ over the cell's **biomass molecules** of their **bond
  count** (so per-atom, long molecules are more valuable — but capped by the no-repeat rule).
- **Two per-species count maps:** a cell holds `{cytoplasm: species → count}` (mobile, transferable,
  the substrate genes act on) and `{biomass: species → count}` (locked structure, non-transferable).
  Total biomass is *derived* (Σ count × bond-count). The **Convert** action moves one molecule
  cytoplasm → biomass; degradation/death move biomass back.
- **Biomass degrades** (homeostasis pressure): at a rate, a biomass molecule loses **one bond at a
  deterministic position** (e.g. an end bond — *not* PRNG-random, to keep the hot loop lockstep-clean;
  see Determinism), splitting into two fragments returned to **cytoplasm**. The bond's energy is
  **dissipated, not recovered** — so biomass is a net energy *sink* over its lifecycle unless a gene
  *actively* breaks it to reclaim the energy. A cell must continually Convert to hold size.
- **Death = biomass collapse.** When total biomass falls below a minimum the cell dies; death returns
  **all** its atoms to the local environment — biomass molecules returned **whole** (no extra
  breaking), cytoplasm as-is. Death is the matter-recycling event that closes the loop.

## The environment — matter store + static light

- **Static light field** (`CytoLightField`, already built): a fixed scalar field, the energy source.
  Non-depletable. Surface cells are exposed; interior cells shaded (via `CytoExposure`).
- **Matter store** — a **finite, depletable, spatial chemical reservoir**: per grid-cell, a
  multi-species **integer count** map. Seeded **once** with the world's whole atom budget. Cells
  **import** from / **export** to the grid-cell they sit in (active-only — no passive flux); **death**
  deposits the cell's molecules there. This is the closed matter loop and the carrying capacity. **It
  is the retarget of the parked `CytoEnergyGrid`**: same discrete per-cell access, draw/deposit
  primitives, conservation invariant, AoS↔SoA byte-identity, and save round-trip — but a multi-species
  integer-count store instead of a single energy scalar.

## Mitosis

- **The gate is the whole control** — no charge accumulator / threshold (the old `divideCharge` /
  `DIVIDE_THRESHOLD` go away). A Mitosis gene fires whenever its binary condition holds and its energy
  source can power it. **The half-split is its own brake:** division halves biomass + cytoplasm, which
  drops both daughters back below any biomass gate, so back-to-back division needs genuine surplus.
- **Symmetric split is the default** (biomass and cytoplasm ~50/50 to the two daughters). **Gene-driven
  asymmetric split is now designed** (§Three mechanisms C): the Mitosis gene's `a` operand names a
  **morphogen** allocated *whole to one daughter* while everything else still halves — the deterministic
  morphogen/axis source for differentiation. Empty `a` ⇒ today's symmetric split.

## Differentiation (one genome → different cells)

Unchanged in spirit; sources weakest→strongest:
- **Positional information** — neighbour-topology gradients + contact + distance-to-light, gene-
  readable, emergent/noisy.
- **Asymmetric division** — the keystone: an unequal split establishes a morphogen gradient + body
  axis deterministically from one founder.
- **Fate memory** — a self-sustaining chemical latch (produce F + insulate F + read F), with a
  first-class `Differentiate`-style action as the clean long-term mechanism.

## Conservation invariant (the gate)

`Σ atoms(environment) + Σ atoms(every cytoplasm) + Σ atoms(every biomass)` is **bit-constant** every
tick, absent player injection. A test must assert this over N ticks (the matter analogue of the parked
energy-conservation test). Energy is deliberately *not* conserved (open throughput) — there is no
energy invariant.

## Determinism

Strict fixed-point lockstep with an AoS↔SoA **byte-identity** gate. Therefore:
- **No PRNG in chemistry.** Biomass degradation breaks a **deterministic** bond (an end), not a random
  one. (Stu's note: a deterministic break is a "free-ish lunch" a genome could come to depend on —
  acceptable for now; revisit, possibly via seeded-PRNG in canonical cell-then-bond order, if it gets
  gamed.)
- Any cross-cell sequential step (e.g. import/export sharing one grid-cell) must run in **canonical
  cell order** (ascending EntityId) in *both* paths — as the parked draw step already did.

## Performance

Per-cell chemistry is the historical wall. The matter rework removes the dominant `energy` column (no
energy state) but adds: multi-species environment grid, the cytoplasm/biomass split, and bond
bookkeeping. Discipline stays: **one biology path** (`CytoBiologyCore`), correctness first via the
equivalence gate, optimise from profiles. The bounded species set (no polymerisation) helps cap the
per-cell map sizes.

## Tech debt (carried forward)

De-float to finish what `dc22be3` started — still-`Float` spots that aren't yet bulletproof
cross-platform deterministic: spring stress-damage; division split geometry; (the light-field bilinear
*sample* matters again only if the matter store ever interpolates — the discrete per-cell access keeps
it `Frac`-exact, as the parked grid already did).

## What carries over vs. what's parked

- **Carries over (live):** heritable per-cell genome + clonal inheritance (✅ `75690fb`); `CellType` =
  label + preset; the SoA/AoS one-biology-path + byte-identity gate; `CytoLightField` (now *the*
  energy source); `CytoExposure`; the SoA `CytoWorld` / save / equivalence scaffolding; the
  `CytoEnergyGridComponent` plumbing commit (`1dfbc9c`) as the **substrate to retarget** to the
  multi-species matter store.
- **Parked (`stash@{0}`):** the closed-energy draw/deposit coupling, the energy-conservation test, and
  the Collector-colony equivalence scenario — mined for their patterns when wiring the matter store.
- **Replaced:** the `energy` chemical + dense column; the weighted-sum gene model; `divideCharge` /
  `DIVIDE_THRESHOLD`; energy→radius; the Secrete-energy Collector preset; all type presets and the
  `GeneCodec` text format; the closed-energy reservoir.

## Resolved (2026-06-12 y/n review) & still-open

Resolved:
1. **Cell↔environment exchange is gradient-based** (revised 2026-06-13, Stu): **passive down-gradient
   transport is FREE** — per species, a cell moves ⌊(env−cyto)/2⌋ between itself and its grid-cell each
   tick (absorb when the env is richer, leak when the cell is). This is how cells feed for free on
   what's around them and how an autotroph's surplus leaks out (root-exudate-style) to feed
   heterotrophs — it's what makes the **food web** flow. **Active Import/Export genes cost energy** and
   exist only to push *against* the gradient (concentrate / dump). (Earlier this was "active-only"; the
   passive baseline is the correction. Future idea, deferred: a maintained gradient as an energy
   *source*, chemiosmosis-style.)
2. **Biomass = a second per-species integer-count map**; total biomass derived (Σ count × bonds).
3. **Form bond joins two whole molecules end-to-end**, refused on a repeated bond.
4. **Discrete integer matter; discrete energy quanta** (1 per bond); a gene does *N* = available-quanta
   bond-ops, remainder dissipates. `Frac` leaves chemistry.

5. **Cell↔cell diffusion stays**, as an integer port of the existing neighbour diffusion: **cytoplasm
   only** (biomass is locked), **passive/free** (only cell↔env exchange is active), floor-split — send
   ⌊count/(maxDegree+1)⌋ of each species to each connected neighbour, keep the remainder. (Caveat: a
   species with count < degree sends 0, so abundant building blocks reach interior cells but scarce
   signals don't — fine until the differentiation step.) `GeneOutputType.Inhibit` does not return in v1
   (no per-species diffusion suppression).

Still open — **tuning knobs only** (pick a value, tune later; v1 starting values in the spec below):
light→quanta scale, degradation period, atom alphabet size, initial matter total + distribution,
biomass→radius coefficient, death threshold.

## v1 implementation spec (the concrete contract to build)

The first end-to-end slice: a hand-written **light-only autotroph** that imports matter, builds a
molecule, converts it to biomass (grows), divides, and plateaus on the finite matter — testable
headless. Concrete decisions (knob magnitudes are starting values, tagged ⚙ tunable):

- **Atoms / alphabet:** start with **{a, b}** ⚙. Molecules are strings over the alphabet with **no
  repeated bond**; with 2 atoms the legal set is `a, b, ab, ba, aba, bab` (bond counts 0,0,1,1,2,2).
- **State per cell:** `cytoplasm: Map<species, Int>` (mobile) + `biomass: Map<species, Int>` (locked).
  No `energy`/Frac chemistry. Total biomass = Σ count × bondcount(species).
- **Environment:** per grid-cell `Map<species, Int>`; seeded once with free monomers **a, b** in a
  gaussian around the 4 light sources ⚙ (good real estate = light + matter), a finite global total ⚙.
- **Energy this tick** for a cell = `quanta = ⌊ light.sampleAt(pos) × exposure × LIGHT_QUANTA_SCALE ⌋`
  ⚙ (target: a surface cell on a source gets a few quanta/tick), then **shaded**: cells sharing a
  grid-cell split it by capture weight `exposure × radius` (a lone cell keeps it all). 1 quantum = 1 op.
  Each active gene then gets a **1/N** slice of the cell's quanta (Light) or of the matching cytoplasm
  molecules (Break bond), N = active genes — the genome-bloat tax; the unused slice is lost.
- **Gene** = `{ energySource: Light, condition: (ChemQty(species, ≷, n) | Biomass(≷, n)), action }`.
  v1 actions: **Import(species)** env→cytoplasm, N ops; **FormBond(a,b)** join a cytoplasm molecule
  ending in `a` with one starting in `b` (canonical = lexicographically-smallest candidates), refused
  on repeated bond, N ops; **Convert(species)** cytoplasm→biomass, N ops; **Mitosis** (1 op to trigger
  when gated). Deferred: Break-bond energy source, Export, Contract/Expand, Sticky/Separate.
- **Cell↔cell diffusion:** as resolved (#5) — cytoplasm only, passive, integer floor-split.
- **Degradation:** per-cell integer wear accumulator gains `totalBiomassBonds` each tick; `broken =
  accumulator / DEGRADE_PERIOD` ⚙, `accumulator %= DEGRADE_PERIOD`; each broken bond splits the
  **lexicographically-smallest biomass molecule**'s leftmost bond → two fragments to cytoplasm; the
  bond energy dissipates (not recovered). Degradation ∝ size.
- **Size:** `targetRadius = sqrt(totalBiomassBonds) × RADIUS_PER_SQRT_BOND` ⚙, coerced ≥ `MIN_RADIUS`.
- **Death:** `totalBiomassBonds < DEATH_BIOMASS` ⚙ → cell dies, deposit **all** its cytoplasm + biomass
  molecules (whole) into its environment grid-cell.
- **Mitosis split:** each species count C → daughter `⌊C/2⌋`, mother keeps `C−⌊C/2⌋`, for both
  cytoplasm and biomass (deterministic). The biomass halving is the division brake.
- **Determinism:** all of the above is integer + canonical-order; no PRNG. Cross-cell sequential steps
  (env import/export sharing one grid-cell) run in ascending-EntityId order in both paths.
- **The test genome (autotroph):** ① `Import a` gated `cytoplasm.a < Ta`; ② `Import b` gated
  `cytoplasm.b < Tb`; ③ `FormBond a,b` gated `cytoplasm.a > 0`; ④ `Convert ab` gated `cytoplasm.ab >
  Tc`; ⑤ `Mitosis` gated `biomass > Tm`. Expect: a founder grows, divides into a colony, and the
  colony **plateaus** as the local environment's a/b is drawn down (matter carrying capacity) — and
  matter is bit-conserved throughout.

## Three mechanisms for stronger selection (2026-06-17)

The diagnosis: a mutating population sustains itself but selection is too weak to favour complex forms.
Three mechanisms address it; each is **independently buildable** (Stu picks the focus order), and each
preserves the matter-conservation invariant, integer/PRNG-free determinism, and the live SoA
byte-identity + parallel==sequential gates — so each lands as its own commit with regenerated
`CytoGoldenTest` goldens.

> **Mechanics already in code, ahead of this doc, that the three designs build on:** the per-gene
> **efficiency gear** `g` (rate↔efficiency — each energy unit does `g+1` ops but spend is capped at
> `EFFICIENCY_REF >> g`); **selective uptake** via `Handleable.canHold(species)` (a cell absorbs/holds
> only species *all* of whose bonds its genome reaches — this is what bounds per-cell species count);
> the **Repair** action + connection stress damage; the **Contract** locomotion actuator;
> size-scaled **metabolic slowdown** and **degradation-as-env-leak**; gradient-cost **active Import**;
> the `Touching` operand (un-welded contact count). The SoA path is the **live** path (`CytoSoaReducer`),
> not a future re-introduction.

### A. Symmetric diffusion — drop the leak-block, concentrate via active Import (PLANNED)

**The regression.** `CytoBiologyCore.exchangeSpecies` gates the passive *leak* branch on `!canHold`: a
cell sheds only waste it can't metabolise and **hoards every species it can use**, killing the
env-mediated food web this doc intended (autotroph surplus feeding heterotrophs root-exudate-style).

**The reframing that simplifies everything.** `canHold` gates **three separate branches**, and only one
touches the block:
- **uptake** (env→cell): canHold-gated — a cell won't absorb what it can't metabolise.
- **cell↔cell diffuse**: canHold-gated — only sends to neighbours that can metabolise the species.
- **leak** (cell→env): `!canHold`-gated — *the block lives only here.*

So a species' **cell↔cell isolation is already free**: an unhandled (trace) species is never diffused to
siblings and never absorbed by a neighbour, because both paths are canHold-gated. The block adds nothing
there; it only governs whether a *handled* surplus leaks to env.

**Design.** **Drop the leak-block** — every species leaks down-gradient freely; the only way to hold a
reserve *above* ambient is the energy-costed **active Import** gene (paid retention, no free lunch —
matches the doc's energy philosophy). This fixes the food web (handled surplus now bleeds to the commons)
without touching morphogen isolation (which lives on diffuse/uptake, not leak).

**The honest caveat.** Today's **break-powered division** funds its `biomass/4` cost by breaking a
*hoarded* `ab` reserve (`AUTOTROPH_GENES`). Remove free retention and that reserve is continuously taxed
by leak — the authored autotroph would need an `Import("ab")` gene to hold it, or the division-energy
model needs a rethink. Not fatal (a fast builder stays ahead of a half-per-tick leak), but a real balance
shift the goldens will show loudly. **Status: planned, not built** — and the `metabolicLeakRetains…` spec
test currently *pins* the old block, so dropping it re-baselines that test + the goldens. Gated behind
getting a feel for the morphogen behaviour first (below).

**Touch point.** `exchangeSpecies`: delete the `!canHold` condition on the leak branch (leak always);
leave uptake + `diffuse` canHold-gated.

### B. Direct harm — predatory lysis of touching cells (active competition)

**Problem.** The only competition today is indirect light-shading; nothing lets lineages directly fight.

**Design — new action `Lyse`**, targeting **touching, un-welded cells** (the pairs the `Touching` gate
already senses; welded colony-mates are spared). The contacts phase records the touch *adjacency*
(pooled, like `bioNbrs`); a new snapshot-based `attack()` biology phase consumes it (order-independent +
parallel-safe like `diffuse` — the victim's biomass is snapshotted and shared among attackers in
canonical EntityId order). **Predation and lysis in one verb, with the efficiency gear `g` as the
predator-strategy axis** (per Stu, 2026-06-17):

- **Damage** — bonds of victim biomass torn off this tick = `min(energyUnits, EFFICIENCY_REF >> g)` ⚙.
  Here the gear *lowers* the damage ceiling (higher `g` ⇒ less raw damage); the `g+1` op-multiplier does
  **not** apply to damage.
- Of the torn-off matter, the attacker **assimilates only species it `canHold`**; **metabolically
  unsupported species are dumped to the environment** reservoir.
- Of the *assimilable* matter, the **captured fraction rises with `g`**:
  `captured = ⌊assimilable × (g+1)/(EFFICIENCY_MAX_GEAR+1)⌋` ⚙ → into attacker cytoplasm; the remainder
  is **lost to env** (digestive spill).

So **low `g` = a brutal shredder** (high damage, most of it spilled to the commons — feeds scavengers);
**high `g` = a surgical digester** (less damage, but nearly all of it consumed immediately rather than
lost). Every atom torn off the victim ends up in attacker-cytoplasm or env ⇒ conserved. (Coefficients ⚙
tunable; the gene's `condition` will typically read `Touching > 0`; energy source is Light or BreakBond
like any action.)

**Selection consequence.** A carnivore tier appears, and prey gain reasons to evolve defences — size,
separation, fast division, sealing — supplying the pressure that's currently missing.

### C. Asymmetric mitosis via morphogen concentration — the differentiation keystone ✅ BUILT

**Problem.** Division was a strict 50/50 floor-split (`CytoLifecycleSystem.floorSplit`), so a clonal
colony had no deterministic way to differentiate from its founder.

**Design.** The Mitosis gene's operand `a` names a **morphogen species**. On division that species is
allocated **whole to the daughter** (the newly-spawned cell along the outward split normal); the mother
keeps none, and **all other species still floor-split 50/50** (odd remainders to env, as now). Empty `a`
⇒ today's symmetric split — so every existing genome and the golden trajectories are **byte-identical**
(the asymmetric path is taken only when a Mitosis gene names a morphogen). The morphogen is **not** added
to the handle-set (Mitosis isn't processed by `handleableOf`), so naming it keeps it a *trace* species.

**Effect.** The two cells start with different cytoplasm composition, so genes gating on
`Chem(morphogen)` fire in the daughter but not the mother → **deterministic differentiation** from a
single genome. Matter conserved (the morphogen is allocated whole to one side; everything else unchanged).

**Implementation (landed).** `CellWork.divideMorphogen` set when a Mitosis gene fires → captured by the
reducer's finish loop → carried on `CellDivisionIntent(id, morphogen)` → `CytoLifecycleSystem.divide`
withholds the morphogen from `floorSplit` (new `skip` param) and hands its whole count to the daughter
(deposited to env, conserved, if the split is non-viable). Affirmed by
`CytoSoaSpecTest.asymmetricMitosisAllocatesMorphogenToOneDaughterAndItPersists` (the split + trace
persistence) and `morphogenGatedFatePersistsAsBehaviouralDifferentiation` (a `Chem(morphogen)`-gated fate
stays divergent across the colony — see Morphogen maintenance below).

### Morphogen maintenance, the active-import crux, and the `Conc` operand

**Maintaining a gradient (C's payoff) interacts with A.** Asymmetric division seeds a difference; what
keeps it?
- **cell↔cell equilibration is already prevented** for a *trace* (unhandled) morphogen — `diffuse` and
  uptake are canHold-gated, so a low daughter literally cannot acquire it. (Cytoplasm also never decays —
  only biomass degrades — so a held morphogen persists indefinitely.)
- **env-leak is the one remaining loss path.** A trace morphogen sheds toward its grid-cell's level each
  tick (`⌊(cyto−env)/2⌋`); it settles at env-equilibrium rather than vanishing, but if the grid-cell is a
  big sink it can drain low. **Whether enough is retained to keep a `Chem(morphogen)` gate firing is
  empirical** — what the C experiment probes. (Early result: in a shared grid-cell the leaked morphogen
  raises local env to the cell's level, the gradient flattens, and the low sibling can't take any up — so
  the *cellular* asymmetry persists; see the test.)

**Sensing ≠ permeability (LANDED).** Originally `handleableOf` added a species a gene merely *sensed* in a
`Chem` condition to the handle-set, so the moment a fate gene gated on a morphogen it became `canHold` and
uptake+diffusion equilibrated it across the division weld — behavioural differentiation washed out. Fixed:
`handleableOf` no longer adds condition operands (a sensor is not a channel). A gated morphogen now stays
trace, so the fate persists — affirmed by `morphogenGatedFatePersistsAsBehaviouralDifferentiation`, and
**golden-neutral** (all `CytoGoldenTest` scenes incl. `mutationOn` unchanged: every species the presets/
evolved genomes sense, they also metabolise, so the mask never differed). Empirically the env-leak loss
path is benign: a trace morphogen floors at its grid-cell's level and the low sibling can't take it up, so
the cellular asymmetry holds without any seal.

**The remaining crux:** **active Import still un-traces** a species (`handleableOf` adds an Import gene's
species — a pump implies a channel), re-enabling its cell↔cell diffusion. So *self-maintaining* a morphogen
by actively pumping it up isn't possible without it spreading to siblings. Not needed for the current
mechanism (the division bolus + no-decay + trace-isolation maintains it), but if a future fate wants an
actively-regenerated morphogen, that's the option-(3) "split pump from permeability" work — deferred until
a genome actually needs it.

**Concentration vs absolute counts (a separate, additive build).** Absolute-count gates (`Chem(sp) > k`)
are confounded by size — a big cell trips them for free — and for morphogens especially, *concentration*
is the meaningful signal (it gives a developmental clock for free: a fixed bolus dilutes as the cell
grows, so the fate "times out" with no decay machinery). **Decision:** add a **`Conc` operand**
*alongside* `Chem` (don't replace it — mutation explores both), compared deterministically by integer
cross-multiply (`sp·denom ⋛ k·total`, no float); **keep `Biomass`-vs-`Constant` absolute** (the 1000
death-floor is a genuine hard quantity). Open pick: the denominator (total cytoplasm = mole-fraction vs
biomass = per-body). Separate commit; not part of C.

## Morphogens for shape — positional gradients (the keystone shift, 2026-06-17) — PLANNED

The goal a shaped body needs is **positional information**: a cell must know *where it is* (centre vs
boundary, how far along an axis) to lay out tissue geometrically. That comes from a **morphogen gradient**
— a *dynamic steady state*, not a frozen field: a localised **source** produces the signal, it **diffuses**
outward, and it **decays** everywhere, so concentration falls with distance from the source. Decay is the
non-negotiable ingredient — without it a diffusing signal equilibrates to flat (no gradient). Cyto's
"cytoplasm never decays" rule forbids exactly this, so adding decay *for signal species* is the keystone.

**Three signal roles, all derived from genome role (no explicit flags — like `handleableOf`):**

| Role | Genome signature | Behaviour | Use |
|---|---|---|---|
| **Determinant** | sensed (`Chem`/`Conc` clause) + mitosis-allocated, **never** FormBond-produced | isolated (no diffuse, no uptake), **persistent** (no decay) | memory / committed fate (the §C path) |
| **Morphogen** | **FormBond-produced** but **not** metabolised (no Convert/BreakBond/food-Import) | **diffuses cell↔cell** + **decays** to monomers; not eaten | positional gradient = **shape** |
| **Metabolite** | metabolised (Convert / BreakBond / Import-as-food) | uptake + retained (existing `canHold`) | food / structure |

**The enabling refactor — split `canHold` into two predicates.** Today `canHold(species)` conflates
*metabolise*, *absorb*, and *diffuse*. A morphogen must diffuse + decay **without** being eaten, so:
- **`canDiffuse(species)`** — true for a **morphogen** (a species the genome FormBond-produces but does not
  metabolise). A **sender** with `canDiffuse(s)` pushes `s` down-gradient to its **welded** neighbours, who
  receive it passively into cytoplasm (the gradient flows from source outward through the colony). Diffusion
  is **cell↔cell only** (locked decision — the env grid is too coarse and would set a bad precedent).
- **`canMetabolise(species)`** — the existing `canHold`: governs uptake-as-food + retain-vs-leak.

This **preserves sensing≠permeability** (sensing alone still opens no channel) and resolves the deferred
"split pump from permeability" item: *production* opens the diffusion channel, not sensing.

**Decay (new).** Each tick a morphogen species sheds a tunable fraction back toward monomers, atoms returned
to cytoplasm — **matter-conserved**, consistent with the remainder-to-environment / degradation rules. The
decay rate vs the diffusion rate sets the gradient's length scale (λ ≈ √(D∕k)) — the tuning knob for "how
big is the patterned region."

**Source (costs matter — locked decision).** A morphogen is produced by an ordinary **FormBond** gene from
monomers, so signalling competes with growth for atoms (a real, conserved selective cost — very Cyto). To
make the source *localised*, gate production on a **determinant** the source cell holds: asymmetric mitosis
(§C) seeds one lineage-cell with a persistent determinant `F`; a gene `Conc(F) > 0 : FormBond … (→ m)` makes
only that cell a source, and `m` radiates the gradient. So the two signal classes **work together** — the
determinant marks *who* sources, the morphogen carries *how far*.

**Source placement selects the body plan (not a metaphysical label).** "Which daughter keeps the
determinant" carries no *identity* meaning — but in Cyto it is bound to a *physical direction*, so it is not
arbitrary. `CytoLifecycleSystem.divide` spawns the daughter along the **outward** normal (away from
neighbours, toward free space: `motherPos + offset`) and steps the mother **inward** (`motherPos − offset`).
So which side keeps the determinant sets *which way the source drifts as the colony grows*: **daughter-retention**
rides it to the expanding edge → an **axial / polar** gradient (a head–tail body axis); **mother-retention**
keeps it embedded near the origin → a **radial / concentric** gradient (the skin-flesh-core plan). The
asymmetry *defines a direction, and that direction is the body axis.* (Crisp only for cells with lopsided
neighbours; for a fully-surrounded interior cell the outward normal degenerates to the cell's ~arbitrary
angle — there the choice washes into jitter.) **Decision:** make the retain-side a **Mitosis parameter**
(cheap; a body-plan selector, not a bug-knob); default **mother-retention** for the concentric v0. A
rock-stable source regardless of side: commit the founder to a **non-dividing organizer** (gate its Mitosis
off once it sources) so it stops moving via division entirely — though the side chosen *during the growth
phase* still sets where that organizer ends up.

**Oriented division — the anisotropy lever (v1, separate from placement).** Daughter placement is currently
*toward free space*, which fills space isotropically → **blobs**. Real morphogenesis orients the *division
plane*: consistently-oriented divisions elongate a structure (**strings / filaments / limbs**), mixed
orientations fill out blobs. Make this a **Mitosis axis parameter** — divide *along* vs *across* a reference
axis. The reference axis is the crux, and **the morphogen gradient supplies it for free**: ∇m gives every
cell a globally-coherent polarity, so the same field that says *where am I* says *which way do I divide* (real
PCP / oriented division). Cost: that needs **directional** gradient sensing (a vector — compare `m` across
neighbours), heavier than v0's scalar `Conc` and overlapping the v2 chemotaxis sense ⇒ a **v1 lever, after the
scalar gradient works**. **Across-axis is *not* the only correct mode** — strings are exactly the non-blob
morphology the continuous-space substrate can express and a fixed grid cannot (the payoff of the richer
substrate). Caveat: forcing an axis places a daughter into *occupied* space (vs today's free-space,
rest-length, no-kick placement) — the spring solver turns that into the elongation push, but it reintroduces
the division-kick risk the no-kick work fixed; place at rest length along the axis and let physics relax
gradually.

**Readout (`Conc` + AND-gates).** A cell reads its position with a concentration **band**:
`lo < Conc(m) AND Conc(m) < hi` → "I'm in the middle ring" → express that tissue's action. This is why the
AND-conjunction gate (above) is a prerequisite, and why `Conc` (size-independent) beats raw `Chem` count
here. (Open: `Conc` denominator = total cytoplasm (mole-fraction) vs biomass (per-body) — lean
mole-fraction.)

**Honest risks.** (1) **Signalling circuits don't emerge spontaneously** — a source nobody reads and a
reader of an absent signal are each useless, so selection can't climb to them incrementally; the circuit
must be **hand-authored first** (the hopeful monster), then refined by selection. (2) **Physics adds noise**
— a jiggling, growing, dividing colony is a far harsher substrate for a reaction-diffusion gradient than
SimulifeHub's frozen hex grid; the source+decay steady state self-corrects, but **calm the physics while
cracking shape** (turn down locomotion/competition first). Both reinforce the staged plan in
`HOPEFUL_MONSTER.md`.

## Build order

1. ✅ Heritable per-cell genome, inherited on division (`75690fb`).
2–6. ✅ **Matter-model biology, end-to-end (AoS)** (`c3bd291`). The gene rewrite (source+gate+action;
   `Molecules` + no-repeat-bond + per-bond quantum), cytoplasm/biomass integer state (no `energy`),
   the `CytoMatterGrid` reservoir (retarget of the energy grid) + static light, deterministic
   degradation / size / death-recycles-matter, gate-only mitosis, and the matter-conservation test —
   all landed and validated: the autotroph (`AUTOTROPH_GENES`) grows **1 → 24** and plateaus
   (carrying capacity) with **total atoms bit-constant**. **Collapsed to the single AoS path** for the
   rewrite — the SoA structural-win path + its equivalence/perf gates are **shelved (recoverable from
   git, pre-`c3bd291`)** until the model is proven. (Export/Break-bond/Contract/Sticky/Separate
   actions are deferred.)
7. **Hand-built organism → mutation + selection.** Next: richer authored genomes (heterotrophy via
   Break-bond, differentiation via asymmetric division + a fate latch), then mutation + selection.
   (The SoA path is now the live shipping path with its byte-identity gate — done, not pending.)
8. **Stronger selection — the three-mechanism plan** (§Three mechanisms for stronger selection,
   2026-06-17). Independently buildable. **(C)** morphogen-asymmetric mitosis ✅ **built** + **sensing ≠
   permeability** ✅ **built** (both additive, all goldens byte-identical incl. `mutationOn`): persistent
   one-genome→two-states differentiation is affirmed end-to-end. **(A)** drop the leak-block /
   paid-retention-via-Import — still wanted (the food web). **(B)** `Lyse` predatory harm — **demoted**
   (competition, not morphogenesis; after shape).
9. **Morphogens for shape → the hopeful monster** (§Morphogens for shape; the 2026-06-17 keystone shift;
   full program in `HOPEFUL_MONSTER.md`). The current front line. Substrate: **AND-conjunction gate** +
   **`Conc`** operand + **signal decay** + the **`canDiffuse`/`canMetabolise` split** (cell↔cell morphogen
   diffusion) + **codec fix** so `Mitosis <morphogen>` round-trips. Then hand-author a gradient-sourced,
   threshold-read, two-tissue organism (mutation off, calm physics) as a *reachability proof*, then turn
   selection back on from that ancestor.
