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
     count. Autotrophy.
   - **Break bond X** — break a specified bond in a cytoplasmic molecule: releases its 1 quantum to
     power the action *and* splits the molecule into two fragments returned to cytoplasm (matter
     conserved). Heterotrophy / catabolism.
2. **Binary condition** — flatly gates the gene on/off (extensible list; baseline):
   - quantity of a given chemical ≷ a threshold;
   - total biomass ≷ a threshold.
3. **Action** — one of (extensible list; baseline):
   - **Form bond** — join two **whole** molecules end-to-end (one ending in atom *a* + one starting
     with atom *b* → `…ab…`); refused if the product would repeat a bond (the no-repeat rule);
   - **Contract / Expand** radius beyond baseline;
   - **Active import** a specific chemical (environment → cytoplasm);
   - **Active export** a specific chemical (cytoplasm → environment);
   - **Sticky** (adhere on contact) / **Separate** (disconnect);
   - **Mitosis** (divide);
   - **Convert** a chemical into biomass (lock it as structure).

   *(There is no passive cell↔environment diffusion — all exchange is the Import/Export genes, so a
   sealed cell is the default and no Insulate-vs-environment action is needed. Cell↔cell diffusion is a
   separate question, TBD.)*

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
- **Symmetric split now** (biomass and cytoplasm ~50/50 to the two daughters). **Asymmetric,
  gene-driven split is the later keystone** — the deterministic morphogen/axis source for
  differentiation (see below). Not now.

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
- **Energy this tick** for a gene = `quanta = ⌊ light.sampleAt(pos) × exposure × LIGHT_QUANTA_SCALE ⌋`
  ⚙ for a Light-sourced gene (target: a surface cell on a source gets a few quanta/tick). 1 quantum =
  1 op.
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
   **Re-introduce the SoA path** (and its byte-identity gate) once the model is settled and perf
   matters.
