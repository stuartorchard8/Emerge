# Norns — a spiritual successor to Creatures (1996)

`:demos:norns` is an attempt to recreate the *mechanism* of the original Creatures (Albia /
C1, 1996) artificial-life simulation on the Emerge engine: biochemistry, a genome, a
neural-network brain, biology/physiology, drives, and reproduction — deterministic, on the
engine's fixed-tick ECS.

## Working agreement (how this gets built)

Stu wants me to get as far as possible **autonomously**, then do **fidelity/styling tuning
together** afterward (2026-06-07). The reasoning: I iterate far faster without a human in the
loop, so I should max out what I can self-verify and defer what only human judgment can settle.

This works *only* because every subsystem is built against a **self-verification harness** I can
run headlessly:
- **Internal-consistency tests** — determinism (same input → bit-identical), conservation,
  boundary behaviour.
- **Behavioural-proxy tests** — does the mechanism produce the *shape* of life? (a drive loop
  closes; learning changes behaviour; a population persists across generations.)

Where I **cannot** build such a harness — anything that needs the original's data or a human's
eyes — I **stop and log it** below rather than guess. That log is the agenda for Stu's tuning
passes.

**Visuals are deferred.** The renderer/host is the last phase; Stu will guide styling as
interactive passes. Until then the sim is verified purely through tests. The creatures will look
nothing like Norns to start.

## What "true recreation" means here (scope)

Target: **C1 mechanism-faithful**, not a byte-exact replica. I have no original binary, genome
files, or chemical/brain tables — only the simulation logic, reconstructed from public
community reverse-engineering + my knowledge of the design. So the realistic bar is *"the same
kinds of systems, wired the same way, producing life-like dynamics."* Numeric fidelity to the
original's hand-tuned constants is a **known gap** (see below) — that's where Stu's tuning comes
in, and possibly sourcing reference data.

## Subsystem roadmap

Each subsystem: build → gate on a harness → commit → update this doc. Sequence is
dependency-ordered (later ones read earlier ones).

1. **Biochemistry engine** — ✅ **done.** Chemical concentrations, half-life decay, reactions,
   emitters (locus→chemical), receptors (chemical→locus). The homeostatic core. Gated by
   `BiochemistryTest`: half-life decay, reaction stoichiometry + limiting reactant,
   emitter/receptor activation, clamping, determinism, and a closed hunger-regulation loop
   (starving→drive climbs, fed→loop holds it low).
2. **Genome** — ✅ **done.** A `Gene` sealed hierarchy (Emitter/Receptor/Reaction/HalfLife
   variants now; brain/biology variants join later) → `expressBiochemistry()`; deterministic
   per-locus `crossover` + bounded `mutate` (seeded `GeneRng`, same LCG as the engine). Gated by
   `GenomeTest`: expression reproduces the subsystem-1 hunger regulation, half-life table build,
   crossover/mutation determinism, both-parent inheritance, mutation bounds + immutability.
3. **Brain (neural net)** — ✅ **done.** `Lobe`s of neurons wired by learnable dendrite
   `Tract`s; reward-modulated Hebbian learning (`Δw = learnRate × reward × pre × post`).
   Gated by `BrainTest`: weighted propagation, reward strengthen / punishment weaken / inactive
   unchanged, determinism, and the capstone — the brain **learns a context→action association**
   from reward (RED→EAT, BLUE→REST).
4. **Biology / physiology** — ✅ **done.** An aging clock advancing `LifeStage`
   (EMBRYO→…→SENILE), organs with health, injury/repair from loci, and death by vital-organ
   failure or old age. Publishes age + life stage to the locus bus. Gated by `BiologyTest`:
   stage progression, vital vs non-vital organ failure, repair offsetting injury, death of old
   age, dead-stays-dead, determinism.
5. **Drives + sensorimotor** — drives as chemicals read by the brain; perception of world
   objects; action verbs (eat/rest/move). Harness: hungry→seek→eat→hunger-drops loop.
6. **World + embodiment** — a simple world of objects (food, etc.) and the embodied creature;
   the reducer that runs all the above per tick on the ECS.
7. **Reproduction** — mating → genome crossover → egg → hatch → new creature. Harness: a
   population survives N generations without dying out or exploding.
8. **Rendering + host** — DEFERRED. Visual layer, wired with Stu (JS/Android targets added here).

## Assumptions (things I chose without confirmation — revisit in tuning)

- **A1.** Module working title is "Norns"/`:demos:norns`. Renameable; "Norn" is Creatures
  terminology — a spiritual-successor may want an original name (Stu's call).
- **A2.** Chemical concentrations are floats in `[0, 1]` (normalised). C1 used 0–255 integer
  steps; a fidelity pass may rescale. Determinism is unaffected (IEEE float ops are
  deterministic, as the cyto chemistry already relies on).
- **A3.** Biochemistry tick order: emitters → reactions → decay → receptors. Chosen as a
  plausible-faithful order; the original's exact intra-tick order is unverified.
- **A4.** Reaction kinetics: each tick fires `rate × (limiting reactant / reactant amount)`
  reactions (limiting-reactant capped, rate-scaled). A reasonable mass-action-ish model; not
  confirmed against C1's exact formula.
- **A5.** Gene activation: all genes are always active. C1 genes carry life-stage and sex
  activation flags; that gating is deferred to the biology subsystem (4).
- **A6.** Genome variation is numeric-only: `crossover` assumes positionally-aligned, same-shape
  parents, and `mutate` perturbs numeric fields within bounds. Structural variation (differing
  genome lengths, gene duplication/deletion, mutating a gene's chemical/locus *indices*) is
  deferred — it's where a lot of Creatures' open-ended novelty comes from, so it's flagged.

## Verification gaps (need Stu / reference data — the tuning agenda)

- **G1. Numeric fidelity.** All constants (half-lives, reaction rates, emitter/receptor
  gains/thresholds, drive weights, brain wiring) are invented placeholders. Matching the
  original's *feel* needs human judgment or the original's data tables.
- **G2. Behavioural fidelity.** "Does it behave like a Norn?" is subjective and emergent — only
  judgeable by watching it run, which needs the (deferred) render host. My harnesses prove
  loops *close*, not that they feel right.
- **G3. Emitter/receptor modes.** C1 had analog/digital modes and per-emitter clocks; I start
  with analog/per-tick only.
- **G4. Brain detail.** The learning rule is a single fixed reward-gated Hebbian law and tracts
  are densely connected. C1 used per-tract **SVRule** bytecode (bespoke state/weight update
  rules) and sparse, genome-specified connectivity. Both are deferred — they're where the
  brain's richer dynamics (habituation, decay schedules, specialised lobes) live.
- **G5. Brain genome encoding.** The brain is currently hand-wired, not gene-encoded. C1's genome
  specifies lobes/tracts/SVRules; `LobeGene`/`TractGene` variants are a planned genome extension
  (so brain structure is heritable + mutable like the biochemistry).
- **G6. Biology detail.** Injury/repair apply uniformly across organs from single loci, and life
  stages are driven by an age clock. C1 damages organs independently (each runs its own
  chemistry) and advances stages via genome life-stage chemicals. Per-organ coupling + biology
  genes (organ definitions, life-stage gene activation) are deferred.

## Current status

- Subsystem 1 (biochemistry): ✅ done, 9 tests green.
- Subsystem 2 (genome): ✅ done, 7 tests green.
- Subsystem 3 (brain / neural net): ✅ done, 4 tests green.
- Subsystem 4 (biology / physiology): ✅ done, 7 tests green.
- Subsystem 5 (drives + sensorimotor): next — drives as chemicals read by the brain; perception
  of world objects; action verbs (eat/rest/move).
