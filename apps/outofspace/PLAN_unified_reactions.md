# Unified reactions

Status: **increments 0, 1, 3 and 4 built** (2026-08-27). Increment 2 is parked by decision; 5 (the
few hundred rows) is what is left. There is **one reaction type and one pass**. Successor to `PLAN_ambient_chemistry.md`, which
built four reaction shapes as four classes swept by two passes. This plan makes them **one shape
swept by one pass**, before the table grows from twenty-two rows to a few hundred.

> A reaction is a fact about matter and conditions. It should not also be a claim about which array
> the matter is stored in — because that claim is made when the row is written and checked by
> nobody, and matter moves between arrays on its own.

The prompt for this is a row that cannot fire. `CH₄ → C + 2H₂` at 1300 K is in `DECOMPOSITIONS`,
which is swept only over the cargo layers; methane above 191 K is evicted from a cargo layer by
`offGas` on the same pass. The row is reachable **only inside a sealed tile**, where `holdsAirOut`
blocks the eviction. Methane pyrolysis works if and only if it happens inside a wall.

## The measured problem

Every row whose principal reactant is a fluid, against that fluid's critical temperature:

| Table | Row | Onset | Tc | Verdict |
|---|---|---|---|---|
| HEAT | `2 NH₃ → N₂ + 3 H₂` | 1100 K | 405 K | evicted before it can fire |
| HEAT | `CH₄ → C + 2 H₂` | 1300 K | 191 K | evicted before it can fire |
| REAGENT | `CO₂ + C → 2 CO` | 973 K | 304 K | evicted before it can fire |
| REAGENT | `6 H₂O + 6 CO₂ → …` | 273 K | 647 K | survives, but needs water and CO₂ **as cargo in a hopper** |

Three dead, one alive for the wrong reason — out of twenty-two. `Combustion.kt` credits the
Boudouard row with "quietly filling the rooms with CO"; it has never fired outside a bulkhead.

**Nothing in the code can tell you this.** The row balances atom-for-atom, its enthalpy is quoted
correctly, `DecompositionTest` and `MineralTest` both pass, and the reference panel prints it as a
route the player can plan around. The only signal is a reaction that never happens.

At twenty-two rows this is four mistakes. At three hundred it is a coin flip on every row, and the
rows that fail are exactly the interesting ones — anything involving a volatile, which is most of
what a comet-mining vessel does.

## What is already unified, and what is not

Three of the six things a reaction does have one implementation already:

- **Rate** — `reactionFraction(kelvin, onset, baseRate)`, one Arrhenius table in reduced
  temperature, shared by all four classes.
- **Enthalpy** — `perKilogram`, one sign convention, quoted per kg of one nominated reactant.
- **Mass closure** — `apportion` over product formula-unit weights, telescoping so no path can
  invent or lose a gram.

What differs is one thing wearing three hats: **which store a species is drawn from and returned
to.**

| | Reagents | Contention well | Products |
|---|---|---|---|
| `Decomposition` | 1 × layer | none | layer |
| `Oxidation` | 1 × layer + O₂ from air | the tile's oxygen | layer |
| `Reduction` | 2 × layer (+ catalyst) | per reductant species | layer |
| `Combustion` | 2 × air | the tile's oxygen | air |

Contention is the same rule in both columns that have one — "one well per species per tile" —
written twice because one well happened to be an array and the other a layer. Product placement is
the same rule four times. Even the two base rates are the same rule: `COMBUSTION_BASE_RATE` is 8×
`BASE_RATE` because a solid burns at a surface and a gas burns throughout, which is a fact about
*phase at this tile*, not about the row.

## There are exactly two stores

Worth stating because it is smaller than it looks, and it is what makes this tractable:

- **`StuffLayer`** — cargo. Rails and buffers. Any of the 168 species, row-allocated with a presence
  bitmask, carrying its own energy per tile.
- **`MassArray` over `Fluid`** — the fluid field. Air (`w.masses`) and pipes (`w.pipeMass`). The 23
  species that can ever be a fluid.

**Condensate is not a third store.** Frost and puddles live in the fluid field like everything else;
"how much of this is condensed" is derived per tile per tick from the dome (`vapourMass`,
`condensedFraction`). Nothing moves condensed matter into a cargo layer except an `Extractor`
explicitly calling `liftFrost`. So a species in the fluid field can be gas, liquid or solid without
moving, and the store axis has exactly two values.

The deck's own `StuffLayer` is fabric, not cargo, and no chemistry pass touches it today — a hull
plate does not corrode. That is a real gap and it is **out of scope here**; see *Not solved*.

## Decisions taken (Stu, 2026-08-27)

1. **Products go to the store the principal reactant came from.** The principal is the one the rate
   is a fraction of and the enthalpy is quoted against, which every table already nominates. This
   reproduces all four current behaviours exactly — mineral → layer, carbon → layer, oxide → layer,
   fuel-in-air → air — from one rule.
2. **`offGas` and condensation pick up the cases where that is not where the matter belongs.** A
   reaction never decides phase. This is the property that fixed the sealed-tile bug and it is kept:
   a calcining rock keeps its CO₂ until there is somewhere for it to go.
3. **Consolidate before growing the table.** The few hundred rows come after this, not before.
4. **The carbon problem is answered by widening the fluid field, and it is parked.** When a
   gas-phase reaction makes something that is not currently a `Fluid`, the answer is to let the
   atmosphere hold that species too — not to invent a third store and not to forbid the row.

   ⚠️ **Deliberately a blurry target.** There is no strict definition of "a gas-only reaction" to
   enforce, and trying to write one would be the fiction this codebase keeps refusing. The rule is
   the direction of travel, applied per species as rows need it: *if a gaseous reaction produces it,
   the atmosphere can hold it.* That is the smallest widening that does not compromise fidelity —
   the alternatives either put cargo in a room with nothing to move it, or add a store.

   ⛔ **Parked (Stu, 2026-08-27).** Methane pyrolysis is not load-bearing: carbon is already
   reachable by methane burning → CO₂ → photosynthesis → algae → pyrolysis, which is a longer chain
   and a more interesting one. So increment 2 waits, and the increments that are *known* correct go
   first. Widening `Fluid` is cheap to do later — it is an entry in the enum and a wider array, and
   `Fluid.kt` says in as many words that the door is held open on purpose.

## The model

One type. No kinds.

```
Reaction(
    principal: Species,          // what the rate and the enthalpy are quoted against
    reagents: List<(Species, Int)>,   // includes the principal
    products: List<(Species, Int)>,
    onsetKelvin: Int,
    baseRate: Long,
    enthalpyPerKg: Long,
)
```

The store is not a field. At each tile the pass asks where each reagent *is* — cargo layer, fluid
field, or split across both — and draws proportionally from what is actually there. Products go to
the principal's store, per decision 1.

`baseRate` stops encoding surface-vs-volume. Whether the principal is condensed at this tile is
already derivable, so a frozen lump of methane burns at the surface rate and methane gas at the
volume rate without a second row. **This is a behaviour change, not a refactor** — flagged as such
in increment 3 rather than smuggled in.

`ReactionKind` survives only as a *derived* label for the reference panel: what the player must
arrange is still a real distinction ("heat alone" vs "heat and a reagent you supply"), it just is
not a distinction the sim needs. Derive it from where the reagents live, and the methane article
stops lying without anybody editing it.

## Increments

Ordered so the case that decides the design comes first. **Each increment is one commit on `main`**,
green before it lands.

### Increment 0 — pin the problem ✅ BUILT

A test that walks every row of every table and asserts its principal can actually exist, in the
store that row is swept over, at that row's onset temperature. Red on three rows on the day it
lands, so they get `@Ignore` with a pointer here rather than quietly staying wrong.

This is the guard that has to survive to the end: at three hundred rows it is the only thing
standing between a plausible row and a dead one. Cheap — the audit that produced the table above
was twenty lines.

### Increment 1 — one reaction, end to end: ammonia cracking ✅ BUILT

`2 NH₃ → N₂ + 3 H₂`, dead today, becomes live in the air.

The *awkward* case on purpose, exactly as `CARBON_BURN` was for the last plan: the principal is in
the fluid field, so products go to the fluid field, and the row currently lives in the table that
sweeps the other store. It forces the whole shape — reagent lookup across stores, principal-store
placement, energy from the right ledger — while every product is a fluid, so it does **not** force
the soot question. That is increment 2's job, isolated on purpose.

Ammonia rather than methane for exactly that reason.

What must be true at the end: ammonia in a hot room cracks; ammonia in a hopper below 405 K does
not (it is cargo, and the row's principal is not there); the air ledger closes; `GasFireTest`'s
"total mass of a tile's gas is unchanged, exactly" still holds for the fires.

### Increment 2 — the soot question ⛔ PARKED

Parked on 2026-08-27, before increment 0 was built — see decision 4. The direction is settled (widen
the fluid field); the trigger for doing it is a row worth having that needs it, and methane pyrolysis
is not that row. Everything below is the scoping as it stood, kept because the constraint is real
and the next gaseous reaction with a solid product will meet it.


`CH₄ → C + 2 H₂`. Air principal, **non-fluid product**, and the one place decision 1 has a genuine
hole: `offGas` moves matter layer → air and condensation handles phase within the air, but nothing
moves a non-fluid *out of* the air, because a non-fluid cannot be in the air to begin with —
`MassIndex(tile, Species.Carbon)` does not compile, by `Fluid.kt`'s design.

**This does not exist today**: every current gas fire's products are fluids. So the cheapest
correct answer is to make that a rule rather than an accident —

> a reaction whose principal is in the fluid field may only have fluid products

— enforced at table construction, costing nothing now and rejecting methane pyrolysis as written.
Then the row is either dropped or the game accepts soot and needs a home for it. Three candidates,
in increasing order of how much they change:

- **Forbid it.** Pyrolysis becomes a machine reaction, not an ambient one.
- **Falls into the cargo layer at that tile.** Precedent exists in reverse (`liftFrost`), but carbon
  as cargo on a tile with no belt is a pile nothing can move, and it puts mass into the cargo ledger
  from the air side, which is a crossing `oxidise` deliberately closed.
- **A third store for settled solids.** Real, and the largest thing on this page.

**Decided (Stu, 2026-08-27): none of the three.** The answer is the fourth option — widen the fluid
field so the atmosphere can hold the product. See decision 4 for why, and for why it waits.

### Increment 3 — one well for the tile's oxygen ✅ BUILT

The pass that replaces `oxidise` and `combust`.

This is where the order-dependence bug dies. `Reaction.kt` says *"⛔ Never resolve contention by
iteration order. Whoever ran first would get the whole supply."* That rule is enforced between rows
and violated between passes: `OutofspaceSim` runs `oxidise(rails)` → `oxidise(hoppers)` → `offGas` →
`combust`, all against the same `w.masses`. At one tile, rail matter gets first refusal on the
oxygen, then hopper matter, and gas fires get what is left. Burning carbon on a belt structurally
starves a methane fire in the same room, by a rule nobody wrote and no player can see.

One demand phase across every consumer, one division, and each pass then takes its own share of its
own demand. The Jacobi argument is the file's own; only the well changes.

## ⚠️ Scope changed during the build (2026-08-27)

**As scoped this was "one pass, one well per species". As built it is "one well for the oxygen", and
the passes stayed separate.**

The reason is ordering, not difficulty. A single well *per species* means a demand function that can
apportion N reagents at once — and there is no way to write one while there are four row classes with
four different demand signatures. That generalisation belongs with the collapse, so it moved to
increment 4, where there is one row type to generalise over.

What is built is the whole of the actual defect: oxygen is the only species contended *across*
tables, and it is now divided once, before anybody takes any. The remaining contention (a reductant
between two reductions in one layer) was already per-species and already Jacobi.

⚠️ **The Boudouard row therefore does not move here.** It needs a reagent in each store, which needs
the N-reagent path. It stays pinned in `ReactionReachabilityTest` until increment 4.

### The well was necessary and not sufficient

Found by the test rather than reasoned out. With the oxygen shared, running the fire before the belt
still gave a different answer from running the belt before the fire — the two agreed on the carbon
and disagreed on the CO₂. `combust` derives the room's temperature from `airEnergy` when it is not
given one, and an oxidation has already moved heat into the air by the time it looks. **The fire's
rate was reading a room the belt had warmed.**

So a snapshot is two things, not one: the oxygen *and* the temperature, both taken before anything
reacts. `OutofspaceSim` now derives the air temperature once and hands it to every pass. ⛔ A caller
that omits it gets the old behaviour rather than an error, which is the same shape of trap the
`oxygenScale` argument has and is called out at both.

### Not here

The derived `baseRate` — surface-vs-volume from the principal's phase at this tile — was scoped into
this increment and moves to 4 with the rest of the generalisation. It is a rate change and it belongs
with the pass that computes rates, which is the one increment 4 builds.

### Increment 4 — the four classes become rows ✅ BUILT

`Decomposition`, `Oxidation`, `Reduction` and `Combustion` collapse into `Reaction`. The tables
become data and `SpeciesInfo.kt`'s flattening becomes the identity function.

⚠️ **Larger than "mechanical", because increment 3 handed it two things.** The N-reagent demand and
apportionment lands here, since it is only writable once there is one row type; so does the derived
`baseRate`. And the Boudouard row — a reagent in each store — is the case that proves both, so it is
the one to do first within this increment.

## What the build found (2026-08-27)

Four things, and three of them were bugs the unification *created* and then made visible. Worth
keeping because each is a shape that will recur as the table grows.

**1. Store-agnostic rows started firing where their products cannot go.** Methane pyrolysis and
photosynthesis both have a fluid principal and a solid product. Once the pass found the principal in
the air, `addTo` looked up `Species.fluid`, found nothing, and returned — dropping the mass on the
floor every pass with both ledgers none the wiser. Methane pyrolysis is **deleted** (it had never
fired anywhere but inside a wall; the fix is the parked widening). Photosynthesis got a **new
principal**: asking "where should the products go?" answers "what is the principal?" — the bloom is
the thing that grows, so the reaction happens in the tank and draws the room's water and CO₂ into it.
That is a better model than the one it replaced, and it fell out of the rule rather than being
argued for.

**2. A same-store draw was moving heat that had not moved.** Matter reacting where it already is has
not gone anywhere, so its share of the store's warmth must stay. Taking it and not handing it back
cooled a room every time it cracked its own ammonia — caught by `AmmoniaCrackingTest`'s energy
identity, a couple of billion joules short.

**3. A row reserved a reagent it could never use.** A fire's rate depends only on its fuel and the
temperature, so a fire with no oxygen anywhere still "wanted" a share of the carbon — and the well,
dividing honestly between everybody who asked, gave it one. The carbon was then not consumed; it was
simply withheld from the reduction that could have had it. The symptom is a charge that reduces at
the same rate in air as in vacuum, and `ReductionSweepTest` had a case named for exactly that
suspicion.

⛔ **The obvious fix is wrong.** Clamping each row's demand to what the supply could support looks
thorough and destroys the apportionment: when a reagent is the binding constraint, *every* row clamps
to the same ceiling, so carbon and iron ask for the same oxygen and iron outbids carbon. Scarcity is
the well's job. All the clamp may remove is demand for a reagent that is **absent**, not scarce.

**4. Jacobi is a promise about one snapshot, and the react phase was re-asking.** `feasible` reads
live supply, so recomputing it after the first consumer had drawn gave a different answer — and two
identical cargo layers at one tile stopped burning identical amounts. What each consumer asked for is
now *kept* between the phases.

The reference panel's `ALL_REACTIONS` stops being a place a table can be forgotten — which is the
bug fixed by hand on 2026-08-27 and worth deleting the possibility of.

### Increment 5 — the table grows

The few hundred rows. Everything is in place for them.

⚠️ **Half the table is still derived rather than written.** `REACTIONS` hand-writes the rows that
have been rewritten in this shape and mechanically converts `DECOMPOSITIONS` and `REDUCTIONS` as the
list is built. That was the safe migration order — twenty-two rows of hand-copied stoichiometry is
twenty-two chances to transpose a digit into a table where a wrong number is invisible — but it is
not the end state. Retyping them (and deleting `Decomposition`, `Reduction` and the catalyst field
with them) is the first thing to do here, and `UnifiedReactionTest` closes every row atom by atom
either way.

### Increment 4f — the derived base rate

Still outstanding. `COMBUSTION_BASE_RATE` is eight times `BASE_RATE` because a solid burns at its
surface and a gas burns throughout — which is a fact about the *phase of the principal at a tile*,
and the phase model can answer it. Frozen methane should burn at the surface rate and methane gas at
the volume rate, from the same row. It is the last piece of "which store am I in?" surviving in a
table.

## Perf

The current sweeps are cheap for reasons that are load-bearing and easy to lose:

- `forEachOccupiedTile` walks rows, so an empty vessel costs nothing.
- One compare against `LOWEST_ONSET` is where nearly every tile stops.
- The presence bitmask means a tile costs the handful of species it holds, not 168.
- `DECOMPOSITION_OF` is indexed by species ordinal; `REDUCTION_GROUPS` is pre-grouped.

A unified pass must keep all four. The shape that does: `rowsByPrincipal: Array<List<Reaction>?>`
indexed by species ordinal, reached from the presence bitmask, so a tile considers only rows whose
principal is actually present. Scratch arrays hoisted per sweep, as now.

⚠️ **Measure the phase share, interleaved** — the chemistry phase runs on a stagger and the machine
drifts ±25%. See `reference_oos_perf_levers`.

### Measured after increments 0–3 (2026-08-27)

`benchOutofspace 2000 1`, 41×26 = 1066 tiles, 168 species, against `4f90f88f`. Two runs each way,
because the machine drifts ±25% and a single pair proves nothing:

| | chemistry phase | share of tick |
|---|---|---|
| before | 0.013 / 0.015 ms | 0.6–0.7% |
| after | 0.021 / 0.033 ms | 1.1–1.3% |

**Roughly doubled, and it does not matter yet.** Twelve microseconds on a two-millisecond tick is
below the noise on the whole-tick figure, which came out between 1.90 and 2.56 ms on *both* sides.

The cost is two new dense per-tile walks: `oxygenScales` and `reactInFluid`. Both are one compare for
nearly every tile — `oxygenScales` bails where there is no oxygen and skips the fire loop below
505 K; `reactInFluid` bails below 1100 K — so what is being paid for is the walk itself, not the
work.

⚠️ **This is the number increment 4 has to beat, not a budget it may spend.** One pass replacing
three walks should take it back down; if the collapse lands and chemistry is still at 0.03 ms with
twenty-two rows, the sparse index is wrong and three hundred rows will find out.

At three hundred rows the demand phase is the thing to watch: it is per present-principal per tile,
and it is the half that cannot be short-circuited, because Jacobi means asking everybody before
giving anybody anything.

## Not solved, deliberately

- **Hull corrosion.** No pass touches the deck's `StuffLayer`. A unified pass would naturally
  include it, which is a behaviour change — a titanium hull in a hot oxygen atmosphere would start
  to scale. Wanted eventually (`PLAN_ambient_chemistry.md` decision 1 says chemistry happens across
  all layers including the deck); not smuggled in here.
- **Cargo in pipes.** `combust` runs on `w.pipeMass` but nothing sweeps a cargo layer there, because
  there is not one. Unchanged.
- **Reversibility.** Every row is one-directional. Equilibrium, and a reaction that runs backwards
  when the products pile up, is a different model and not this one.
- **The catalyst field.** `Reduction.catalyst` stays a rate gate in the sim. The reference already
  presents it correctly as a reactant on both sides (2026-08-27); making the *sim* express it that
  way means algae flowing through `apportion` and is a microgram-residue risk
  (`reference_oos_microgram_deadlock`). Separate work.
