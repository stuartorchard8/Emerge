# Norns — a spiritual successor to Creatures (1996)

`:apps:norns` is an attempt to recreate the *mechanism* of the original Creatures (Albia /
C1, 1996) artificial-life simulation on the Emerge engine: biochemistry, a genome, a
neural-network brain, biology/physiology, drives, and reproduction — deterministic, on the
engine's fixed-tick ECS.

## North star (revised 2026-06-09, after Stu played Creatures 2)

Creatures 2 lands on two things: a **beautiful art style** and an **insane below-surface
depth** (its biochemistry / genetics / neural learning). People forgave everything else —
terrible performance, baked-frame animation, detached floating-window UI — to feel those two
things. Our bet: **match the depth, beat everything else.** Same below-surface complexity, but
*far more performant*, with animation that is **procedural, not baked frames** (era-bound,
heavy, un-tweakable), and an integrated UI.

The depth we already have the mechanism for (subsystems 1–7, mechanism-faithful and tested).
There's a community video on the Creatures series' learning/complexity systems Stu wants to mine
to push the depth further — that's a later track. **What comes first is the visuals**, because:

- Nailing the **art style** is both the hardest part and the most important — it's what makes a
  human brain fill the gaps and attribute *personality and story* to a Norn. Mechanism depth is
  invisible without a body you believe in.
- It's the one thing only Stu's eyes can judge, so it gates everything downstream.

**Therefore animation is procedural, but the art stays real.** The synthesis: composite the
creature from the **ripped Creatures-2 sprite parts** (genuine art) and animate them with a
*procedural, fully-editable rig* (no baked frames — the limitation we're rejecting). Every anchor,
pivot, rotation and per-action motion is authored in a tool, so both look and animation are
customisable. The immediate tool for this is the **rig compositor/editor** (`runNornsAnim`) — see
the 2026-06-09 status update below. (A pure procedural-*ellipse* body, `CreatureAnimation` /
`NornBodyRenderer`, also exists but reads as primitives, not C2 — kept only as the live renderer's
fallback.)

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
5. **Drives + sensorimotor** — ✅ **done.** Drives (discomforts in [0,1]) that rise over time and
   fall on drive-satisfying actions; reward = total-discomfort reduction — the reinforcement
   signal. Gated by `DrivesTest`: drive rise/clamp, action effects, drive-reduction reward, and
   the **capstone integration** — a creature wiring drives→brain→action→reward **learns to
   satisfy its own drives** (EAT when hungry, REST when tired) and holds lower average discomfort
   than an untrained creature. This is the core Creatures loop, working end to end.
6. **World + embodiment** — ✅ **done.** A `Creature` integrating brain + drives + biology over
   one tick, coupled to a minimal `World` (regenerating food). Sense food + drives → decide →
   act → drive-reduction reward trains the brain → sustained hunger injures organs → biology
   ages + kills. Gated by `CreatureTest`: a competent creature survives by eating, a never-eats
   creature starves and dies through the biology stack, a *learned* policy outlives an untrained
   one, and the tick is deterministic.
7. **Reproduction / evolution** — ✅ **done.** A `Population` of genomes that breed by truncation
   selection (genome crossover + mutate) and refill to size. Gated by `EvolutionTest`: a
   heritable trait **adapts toward selection** (evolution), the population persists across 100
   generations, and breeding is deterministic.
8. **Rendering + host** — 🟡 **interactive side-scroll ASCII host done; GPU host deferred.** A
   **side-scrolling, multi-floor** `NornsWorld` (creatures walk floors connected by lifts, forage,
   eat, age, starve / die of old age, and seek mates to breed) with a scrolling **follow-camera**,
   a **creature detail panel**, slower pacing + speed/pause controls, and **player interaction**
   (drop food, hand-feed, pick-up-and-place). Run live: `./gradlew :platform:desktop-app:runNorns
   -q --console=plain`. Gated by `NornsWorldTest`: viable + bounded multi-floor colony,
   in-bounds, determinism, the interaction commands, held-creature behaviour, and a well-formed
   camera frame. Under food scarcity the colony now **visibly evolves** (mean metabolism drifts
   down). The **GPU/styling sprite host** (the real visual layer) remains the deferred,
   human-judged collaborative pass (G2).

### Integration backlog (combines finished subsystems; surfaced during the build)

- **G8 — drives as chemicals.** Fold drives/metabolism into the biochemistry (drives = chemicals
  read via receptors; metabolism feeds biology) so the creature is one coupled chemical system,
  not brain+drives+biology bolted together.
- **G9 — implicit selection.** Replace the explicit-fitness GA with embodied selection: creatures
  that survive + mate in the world pass genes. Needs the brain gene-encoded (G5).
- **G5 — ✅ RESOLVED: brain instinct is gene-encoded.** A `BrainGene` encodes one instinct
  dendrite (action × sense × weight); a creature builds its starting brain from its genome's
  `BrainGene`s (`CreatureMind.build(genome, …)`), so instinct is heritable + mutable like the
  biochemistry. Gated by `BrainGenomeTest`: a brain obeys its genes, and a population's
  **behaviour evolves under selection** (random instincts → good decisions; mean behavioural
  fitness 1.0 → ~1.9). *Remaining*: only dendrite **weights** are gene-encoded — lobe/tract
  **topology** is fixed (no LobeGene/structural connectivity yet); reinforcement-learned weights
  aren't inherited (offspring inherit instinct, then learn). Structural brain evolution is the
  deeper, deferred piece.
- **G10 — ✅ RESOLVED: brain now drives behaviour.** Each creature's neural-net brain
  (`CreatureMind`) perceives its drives + senses (hunger, mating urge, food/mate proximity) and
  **decides a goal verb** (seek food / seek mate / rest); reward-modulated learning (drive
  reduction, two-phase tick so mating is credited) refines the choice. Newborns carry an
  **instinct prior** (hunger→seek-food, urge→seek-mate) so they act sensibly, then learn.
  *Remaining*: navigation to the chosen target is still mechanical (the brain picks the verb, not
  the steps), and instinct is a fixed prior rather than gene-encoded (G5). Gated by
  `CreatureMindTest` + the brain-driven colony staying viable.
- **G11 — the GPU visual host (in progress).** Procedural body-part animation is built + verified
  (`CreatureAnimation`, posed per action — `CreatureAnimationTest`). Each activity drives a distinct
  `CreatureAction` (REST / WALK / EAT / PICK_UP / COURT): walking strides the legs, eating chews,
  picking-up bends to the ground, courting hops. In the live Java2D sprite rig (`NornRig`, the
  primary host) the cues are leg-swing, a screen-space courting **hop**, and a forward **lean**
  about the planted feet for eat/pick-up (a rotation — no squashing), plus head/arm gestures
  (which read subtly on the ripped art). A GPU host
  (`runNornsGl` → `NornsGlView` + `NornsGlRenderer`, reusing the engine `CircleShader`) draws the
  animated blobs in a side-scroll window. **The GL drawing is UNVERIFIED by me** (no display in
  the authoring env) — it compiles and the world/animation it drives are unit-tested, but pixels
  need Stu's run. Iterating on the GL look + body-part tuning is the live collaborative loop.
  Still ASCII-host-only for the verified watch; real-time mouse interaction in the GPU host TBD.

## Assumptions (things I chose without confirmation — revisit in tuning)

- **A1.** Module working title is "Norns"/`:apps:norns`. Renameable; "Norn" is Creatures
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
- **G3. ✅ RESOLVED — Emitter modes.** C1 had analog/digital modes and per-emitter clocks; I start
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
- **G7. Drive/action model (PARTLY addressed — REST now meaningful via a fatigue drive; drive weighting + larger action set remain feel-tuning).** A fixed action→drive-effect table and a flat (unweighted)
  discomfort sum. C1 derives action effects from world objects + biochemistry and weights drives.
  Both are tuning surfaces; the action set is also a placeholder (EAT/REST), to be expanded with
  the world. *Observed during subsystem 6:* with a flat discomfort sum, multi-drive survival can
  misalign with reward (the creature relieves a non-lethal drive as readily as a lethal one) —
  drive weighting / lethality is real tuning needed for richer scenarios.
- **G8. ✅ RESOLVED — Biochemistry ↔ creature integration.** The embodied `Creature` wires brain + drives +
  biology, but NOT biochemistry yet — drives are a separate float system rather than chemicals
  read via receptors, and metabolism doesn't feed biology. Unifying drives-as-chemicals (the
  faithful C1 design) is the remaining integration step. Also: the world is abstract
  ("food present?") — spatial layout/movement belongs to the world+render phase.

## Current status — mechanism baseline COMPLETE (stopped at the verification wall)

All seven mechanism subsystems are built and self-verified; **38 tests green**. The autonomous
build stops here because everything remaining (subsystem 8 + fidelity) needs human judgment.

- Subsystem 1 (biochemistry): ✅ 9 tests — homeostatic core; hunger-regulation loop closes.
- Subsystem 2 (genome): ✅ 7 tests — genes express the biochemistry; deterministic crossover + mutation.
- Subsystem 3 (brain / neural net): ✅ 4 tests — reward-modulated learning of a context→action association.
- Subsystem 4 (biology / physiology): ✅ 7 tests — life stages, aging, organs, death.
- Subsystem 5 (drives + sensorimotor): ✅ 4 tests — drive-reduction reward; a creature **learns to satisfy its drives**.
- Subsystem 6 (world + embodiment): ✅ 4 tests — embodied **survival** loop (eat to live; starve to die).
- Subsystem 7 (reproduction / evolution): ✅ 3 tests — a population **adapts under selection**.
- Subsystem 8 (render host): 🟡 interactive side-scroll ASCII host done — multi-floor,
  follow-camera, detail panel (now showing the brain's current decision), player interaction
  (food/feed/pick/place), pacing controls; colony visibly evolves. Run
  `./gradlew :platform:desktop-app:runNorns -q --console=plain`. GPU sprite host deferred (G11).
- **Brain wired into behaviour (G10 resolved):** creatures now act via their neural-net brain
  (`CreatureMind`), instinct-primed + reward-refined. Gated by `CreatureMindTest`.
- **Brain instinct gene-encoded (G5 resolved):** instinct is heritable + mutable, so *behaviour
  evolves* (not just biochemistry). Gated by `BrainGenomeTest`. 48 tests green.
- **G11 in progress:** procedural body-part animation (`CreatureAnimation`) + GPU host (`runNornsGl`)
  drawing animated blobs (engine `CircleShader`). World is now **continuous-position** (smooth
  glide, no grid hops) with **durative animated actions** (a state machine: move → pick up → carry
  → eat, and pursue → mutual-court → breed, each taking ticks) and **food-holding** (eat only while
  carrying). Colony retuned + viable; behaviour is legible (action dots). Added a **text HUD**
  (reusing cyto's procedural-font `CytoTextRenderer`: world stats + followed creature's
  stage/age/drives/action) and **mouse interaction** (left-click follow, right-click drop food) —
  screen↔world picking is the tested `NornsView`. GL still unverified by me (no display) — needs
  Stu's run. 58 tests green.
- **G12 — called lifts (DONE, C2-style).** Lifts are real cars (`Lift`) that **idle at a floor
  until called** — exactly like Creatures 2. Changing floor means walking to the shaft, *pressing
  the call button* to summon the car, waiting for it, riding it, *pressing the destination button*,
  and disembarking — durative travel, not an instant hop. The car **commits to its current
  destination**: a button pressed mid-trip is queued (it does *not* yank the car back), and the car
  finishes the trip, **pauses** (`liftDwell`, doors open), then serves the next pending call. The
  render host draws the car as the C2 sprite: a planked wooden crate slung on ropes from a peaked
  hoist frame (cable up the shaft), an **X-braced front gate** drawn over the rider, and a per-floor
  **lamp-post call button** (glows amber when pressed), plus **▲/▼ movement buttons on the
  carriage**. The player can **click** any of them — the call lamp summons the car to that floor,
  the carriage buttons drive it up/down a floor (same as the C2 Hand pressing them). Button geometry
  lives in the shared `LiftLayout` so what's drawn is exactly what's clickable; there's also a
  `lift <n> up|down|<floor>` console command. Gated by `NornsWorldTest` (an uncalled car stays put;
  a called car travels to and parks at each floor; it finishes a trip before answering a newer,
  nearer call; the movement buttons step it one floor; cross-floor travel takes time + boards the
  car). Colony stays viable.
- **G13 — plant/fruit food ecology (NEXT, Stu-requested).** Replace the god-spawned food with
  *plants* that grow, fruit, and die of age; fruit is what creatures eat; un-eaten/spoiled fruit on
  the ground seeds new plants. A real producer layer → food becomes a dynamic, depletable resource
  (overgrazing, food deserts, plant booms = emergence). Expected to add instability (wanted); will
  ship with tunable stabilisers (carrying capacity, seed/spoil rates) and gate "doesn't permanently
  collapse or explode", but allow boom/bust.
- Deeper-fidelity gaps remaining: G3, G6–G9, brain topology evolution, GPU look/interaction tuning,
  click-drag to pick up & move a creature.

**The "alive" chain is demonstrated end to end:** chemistry regulates → the brain learns from
reward → the creature acts to satisfy its drives → it survives or starves in a world → a
population evolves. What it does NOT yet have: a body/world you can see (subsystem 8), tuned
constants for *feel* (G1), and the deeper-fidelity items (G3–G9). Those are the tuning agenda.

### What needs Stu next (the verification wall)
1. **Watch it run** — only human eyes can judge "does this feel alive / like a Norn?" (G2). Needs
   the render host (subsystem 8).
2. **Tune the constants** (G1) — half-lives, rates, gains, drive weights — for the right dynamics.
3. **Decide fidelity targets** — which gaps (G3–G9) to close, and whether to source C1 reference
   data to raise fidelity beyond "mechanism-faithful".

---

## Status update (2026-06-07): the body, world, and visuals are built

The "verification wall" above is largely crossed — there is now a **visible, watchable world** and a
real art pipeline. Current state:

### Rendering & art (the canonical host)
- **One renderer, Java2D**: `NornsImageRenderer` (in `:platform:desktop-app`). Two entry points
  share it: **`runNornsSwing`** (live window — ←/→ or A/D pan, F follow, left-click a Norn to
  follow, right-click drop food, P pause, `[`/`]` speed) and **`renderNorns`** (headless PNG frames
  + 2× zoom / surface crops, used to iterate the look). `runNorns` is still the lightweight ASCII
  host. (The old GPU host was removed — it predated the sprite rig.)
- **Real Creatures-2 sprites.** A pipeline (`tools/norns-sprites/`) rips a breed, decodes its S16
  sprites, and bakes per-age parts + a rig manifest. `NornRig` assembles them as an articulated
  skeleton (real ATT attachment points) with **continuous joint swing** for smooth walk/crawl — not
  a primitive blob. `CreatureAnimation` (the procedural poser) remains only as a fallback.
- **9 heritable breeds/species** (denali, bavaria, bilba, calypso, cloud, foxi, dog, duck, daffodil),
  picked per creature and inherited; **life-stage scaling** (tiny crawling babies → upright adults);
  **eggs** (the EMBRYO stage is inert + drawn as an egg, hatches into a baby); a painted Albia-style
  world (sky surface, caverns, flora). Placeholder art until Stu draws his own.

### Tuning done
- Colony is small + slow (sits ~12–20), food quartered, sim time-dilated to ~¼ for watchability;
  baby/child stages stretched so young are commonly visible.

### Still parked / open
- **G13 plant/fruit ecology** — still god-spawned food; the producer layer is not built.
- **Deeper biochem fidelity** (G3/G6–G9), **brain-topology evolution**, **G4 SVRule VM** — untouched.
- **Baby vs adult proportions** — babies use their own age-0 sprite but read similar to adults
  beyond scale (Stu parked this); **foxi** has no age-0 art so its babies use an older sprite.
- **Feel tuning** (G1) — joint swing amounts, pace, etc. are eyeballed, open to refinement.

---

## Status update (2026-06-09): visuals-first pivot → a sprite-part RIG COMPOSITOR

Following the North-star revision above, the priority is **nailing the look**, and the authoring
tool is built. The approach was refined live with Stu over the day and **landed on the synthesis of
both worlds**: keep the genuine Creatures-2 *art* (the ripped sprite **parts**) but drive it with
*procedural* animation — no baked frames. A creature is **composited** from its parts on a rig whose
every anchor/pivot/rotation is editable, so both the look and the per-action animation are fully
customisable.

(The path there: first a procedural *ellipse* Norn with tunable `AnimParams`; Stu judged primitives
can't read as C2 — confirmed earlier in this doc — so the tool renders the real sprite parts
instead. Not NornRig's fixed baked skeleton either: he wants to *compose* the creature from parts
with custom anchor points and author the animations. Hence the rig compositor.)

### The rig editor (`runNornsAnim`)
`./gradlew :platform:desktop-app:runNornsAnim` opens a Swing editor:
- Pick a **breed + age** and a **part**; tune that part's **anchor** (where it joins its parent),
  **pivot**, **rest angle**, **z-order**, and its **per-action animation** (`bias` + sine
  `amp`/`freq`/`phase`/`sign`) — plus the **global per-action body bob/lean/hop** — all live, with
  the selected part highlighted on the canvas.
- Pick an action (REST/WALK/COURT/EAT/PICK_UP), play or scrub the phase, onion-skin the cycle,
  overlay a C2 **reference image** to match by eye.
- **Save/Load/Export** the whole rig as a text file (`norn-rig*.txt`) — a look + its animations,
  reloadable and iterable. `--render <png>` writes a headless contact sheet (display-less checks).

### Code (all in `:platform:desktop-app`)
- **`NornParts`** — loads a breed/age's individual sprite-part PNGs + their `.att` anchor points as
  plain data (decoupled from the live `NornRig`).
- **`NornRigDef`** — the editable rig model (parts → sprite/parent/anchor/pivot/rest/z + per-action
  `JointAnim`; global per-action `GlobalAnim`). `default()` seeds it from the `.att` points + a
  sensible motion seed; `toText()`/`parse()` serialise it.
- **`NornCompositor`** — runs the rig FK and blits the parts (fit-to-height, feet-planted, facing
  flip, lean/hop), with a selection overlay.

### The other (now secondary) path
- **`AnimParams` + `NornBodyRenderer`** (the procedural *ellipse* Norn) remain as the **live-world
  renderer's fallback** (`NornsImageRenderer.drawCreature` uses it when `!NornRig.ready`) and stay
  unit-tested. Not the art direction, but kept working.

### Live world renders from the authored rig (DONE), scoped per (breed, age)
`NornsImageRenderer.drawCreature` now draws via `NornCompositor` + `NornRigDef`, not the old
hardcoded `NornRig` (now unused). Rigs are keyed per **(breed, age)** —
`assets/norns/rig-<breed>-a<age>.txt` — because baby (crawl) and adult (upright) are fundamentally
different layouts. **One rig per age, shared by every species** (Stu, 2026-06-09 — no per-species
rigs): `NornRigStore.rigFor(breed, age)` applies the single authored **denali** rig for the age
(`assets/norns/rig-denali-a<age>.txt`) to *that breed's own sprites* — normalized coords scale it to
each breed's sprite dimensions. Shipped a0..a3 (crawl baby/child, upright adolescent/adult), authored
by Stu in `runNornsAnim`. The editor saves/loads straight into `assets/norns/` (single source of
truth shared with the game) and keeps a session cache so switching age/breed never resets edits.
`NornBodyRenderer` (ellipse) is the missing-art fallback. Ages map BABY→0, CHILD→1, ADOLESCENT→2,
ADULT/OLD→3. (A breed that looks off under the shared rig is an art fix in its sprites, not a reason
for a separate rig.)

Anchor/pivot coords are **resolution-independent**: stored as a fraction (U/V, 0..1) of the relevant
sprite's w/h and denormalised with each sprite's actual pixel dims at pose time, so a rig transfers
cleanly across sprite sizes/ages (e.g. denali a0→a1, a3→a2 copies just work). `parse()` auto-converts
legacy pixel rigs on read. This keeps the rig clean, portable data — the right substrate for the
evolvable end state below.

### End state (Stu, 2026-06-09): the authored rig is a *baseline*, not the destination
The hand-authored per-(breed,age) rigs are a **source-of-truth of "something that looks good."** The
intended end state is a **brain-driven animation loop**: per-creature motion that is **tweakable via
learning** and **evolvable via natural selection** (continuous with the existing brain/genome/
selection stack — see subsystems 3/5/7 + G5). So `NornRigDef` should stay clean, decoupled data
(per part / action / age) that a creature could eventually *own* and vary — the editor just lets Stu
hand-build a good baseline to seed and sanity-check that. (Likely future step: move the rig pose
math to engine-side pure data so the sim can drive/evolve it; AWT stays in the compositor.)

### Open / next
- **Author the remaining actions** (rest/eat/court/pick) across the four denali ages in
  `runNornsAnim` — finishing the baseline. Walks + rest done; non-walk actions seeded from rest.
  (One rig per age, denali only — no per-species authoring.)
- **Floor / calibration + prop guides in the tool (DONE).** `runNornsAnim` has a scrolling **tiled
  floor**: while WALK plays it scrolls at the rate the per-age `walkStride` implies, so the planted
  foot's slip is visible and `walkStride` can be tuned (world-units of ground per cycle). A
  `groundOffset` slider seats the rig vertically. PICK_UP shows a **ground-food guide** ahead of the
  norn at `pickupReachX` (per-age, facing-relative) to line up the reach; EAT draws **food in the
  right hand** (`NornCompositor.draw(holdFoodInHand)` → berry at the `farmR` tip). All three values
  (`walkStride`/`groundOffset`/`pickupReachX`) are saved per age in the rig (`meta` line).
  `groundOffset` + held-food are applied by the live game (`holdFoodInHand = carryingFood`). **WIRE
  NEXT:** walk-speed scaling — live walk phase rate `2π·moveSpeed / walkStride` per age instead of the
  fixed `ticksLived·0.085`; and use `pickupReachX` to position a creature when picking up. Once Stu
  has calibrated and we can verify motion live.
- **Rest vs sleep is deferred (Stu, 2026-06-09).** The sim has a single `FATIGUE` drive → `REST`
  action; there is **no sleep** (no sleepiness drive, no SLEEP action — `grep -i sleep` is empty).
  C2 splits tiredness vs sleepiness into separate drives/behaviours; modelling that (a `SLEEPINESS`
  chemical + `SLEEP` action + brain wiring, after which SLEEP auto-appears in the editor and wants
  its own lying-down animation) is left for the **depth track** — not touching core mechanics until
  the current actions are all animated.
- **Brain-driven / evolvable animation** (the end state above) — the larger track once the baseline
  rigs exist: let a creature's genome/brain own + vary its motion.
- **Drag handles on the canvas** — v1 selects a part from a dropdown + sliders; click-and-drag the
  anchor/pivot directly is the obvious next iteration.
- **Per-part swaps / mixing** — the rig already lets a slot point at any sprite; expose part-swapping
  in the UI to mix breeds / customise a creature's look.
- **Retire `NornRig`** — the old hardcoded sprite skeleton is now dead code; remove once the
  compositor path is settled across all breeds/ages.
- **Depth track** (later): mine the Creatures-series learning/complexity video to push G4 (SVRule
  brain), brain-topology evolution, and the deeper biochem fidelity gaps.
