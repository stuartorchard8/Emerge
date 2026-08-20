# Ambient chemistry

Status: **increments 0 to 3 built** (2026-08-20). Two reactions compete for a tile's oxygen, and
the thermal decomposer is a thermostat with no chemistry in it. Increment 4 — the reaction table —
is next, and it is the one that needs two new species.

Chemistry used to be something one machine did to one buffer, and it did not actually do it —
`cook` in `chem/Chemistry.kt` was a stub that returned its input unchanged, and it is now deleted.
This plan makes chemistry a property of matter and temperature rather than a property of a machine.

> Heat an iron casing in the presence of oxygen and it oxidises. Heat carbon on a rail past its
> ignition point in the presence of oxygen and you get a fire. The thermal decomposer is not where
> chemistry happens — it is a well-insulated box where the player can *choose* the conditions, which
> is why it is the place chemistry happens most readily. Everywhere else it happens by accident.

Mass and energy stay conserved throughout, and the ledger gets **stricter** rather than looser: a
reaction that moves a solid into the atmosphere has to pay `solidBecameGas`, everywhere, every tick.

## The inversion at the centre

`OutofspaceSim.kt:700` holds the current model: `refine(ThermalDecomposer)` moves a lump
Input → Inside, charges `heatOfWorking`, counts down `ticksPerAction`, and hands the result to
`cook(inProgress, m.setTemperature)`. Chemistry is a **function a machine calls**, gated by a tick
counter, against a *setpoint* rather than a temperature.

The inversion: chemistry becomes a **pass over the world's stuff layers**, gated by the actual
temperature of the matter and the actual availability of a reagent in the tile. `cook` stops being
chemistry at all — it becomes a heater that pushes energy into a buffer until it reaches the
player's set temperature, and the reaction that follows is the same reaction that would have
happened anywhere else at that temperature.

Consequences:

- `ThermalDecomposer` keeps its footprint, its `Firebrick` casing (`Material.kt:194`) and its
  buffers, and stops being special. It earns its place by controlling conditions.
- `chem/Chemistry.kt` loses `cook` and gains the reaction table.
- Reactions become **cross-layer and cross-phase**, which is where all the risk is.

### This was anticipated

`StuffLayer`'s class doc already describes this feature, in these words:

> *"A layer holds every species at every tile it occupies, because it has to: a reaction cannot be
> told in advance which species will turn up (carbon on a rail meeting oxygen in the air produces
> carbon dioxide that neither side started with)."*

and documents `forEachSpecies` as *"the loop that a chemical pass runs over every occupied tile of
every layer"*. The row allocation and the three-word presence bitmask exist for this. **The solid
side needs no new storage.**

## Decisions taken (Stu, 2026-08-19)

1. **Chemistry happens everywhere**, across all layers of stuff — deck, buffers, rails, pipes. Not
   only inside a machine. This feeds the damage system later: a corroded casing is a weakened one.
2. **Gaseous reagents come from the vessel's atmosphere**, not from the input mixture. This makes
   *placement* a real decision: a carbothermic reduction wants a no-atmosphere room, because in air
   the oxygen attacks the carbon first, burns it off, and may oxidise the ore further.
3. **`cook` is just a heater.** It dumps thermal energy into the buffer toward a player-set target
   temperature. It performs no reaction.
4. **The `Vaporizer` is disposable.** It was built to fill a room with "gas" and does not need to
   exist. It is the only thing that will actively fight increment 0, so it goes rather than being
   ported.
5. **The `Fluid` subset is accepted, and liquid iron is deferred rather than forbidden.** Adding a
   species to `Fluid` later is an enum entry and a wider array; the door stays open. Not letting
   perfect be the enemy of good.

## The state of things, measured

| | storage | cost to ask "what is here?" |
|---|---|---|
| Solid layers (`StuffLayer`) | rows allocated on demand, 3-word presence bitmask | proportional to what is present |
| Atmosphere and pipes (`Stuff`/`MassArray`) | **dense** `tiles × 165` | `densityAt` loops all 165 |

`Species.COUNT` is 165. `MassIndex(tile, species) = tile.index * Species.COUNT + species.ordinal`
(`world/SafeArrayIndexes.kt:33`), so the air is `96 × 60 × 165 × 8` = 7.6 MB, copied every tick
because the reducer is pure.

**Nothing prevents serpentine from being in the air.** There is no concept of a gaseous species
anywhere in the codebase — no `isGas`, no volatility flag, no boiling point outside the five
species in `CRITICAL`. `Stuff.pressureAt` states the missing invariant as a comment instead:

> *"Assumes all species are gaseous. Invalid if any are solid or liquid."*

and `Work.vaporize` (`OutofspaceSim.kt:730`) will copy **any** species into the atmosphere, because
it just loops `Species.ALL`.

## Why not just make the atmosphere a `StuffLayer` too? (Stu, 2026-08-19)

The obvious simplification: drop the whole fluid/solid distinction, store the air as another
`StuffLayer`, and let the tried-and-tested presence bitmask do the work. It is a good question and
the answer is *take half of it*.

**Row allocation does not shrink the dimension.** A `StuffLayer` row is `Species.COUNT` wide —
`masses[row * Species.COUNT + ordinal]`. Rows make a layer cost what it *occupies*, not what it
could hold *per tile*. The air occupies nearly every non-vacuum tile, so air-as-`StuffLayer` is
still 165 longs per tile, ~7.6 MB, plus the bitmask, plus `rowOf`/`tileOf`, copied every tick. The
5× reduction comes only from narrowing the species axis, and that needs no unification. **The two
ideas are orthogonal, and the memory number is the bigger and more certain one.**

**The row indirection is actively wrong for a stencil.** `diffuseFluid` is the hottest loop in the
game, runs `subSteps` times a tick, and its core is:

```kotlin
deltaMass[MassIndex(tile, s)] -= out
deltaMass[MassIndex(neighbour, s)] += out
```

In a dense array a neighbour's block is pointer arithmetic. Through `rowOf` it is a dependent load
into an array indexed by an unrelated row number — rows are allocated in first-touch order and
`release()` reshuffles them, which is why nothing may hold a row number across a release. Worse,
`deltaMass` is a **fresh zeroed array every call**: as a layer it would start empty, so every
neighbour write hits `rowFor` → allocate → `clearRow`, zeroing 165 longs per newly-touched tile,
to build a structure that ends up dense anyway. `StuffLayer`'s own doc warns about this shape —
*"if asking allocated, one pass over the grid would make every layer dense"* — and diffusion
*writing* to neighbours does it legitimately rather than by accident.

That is the real distinction, and it is worth stating as a rule:

> **Rails are a scatter; the air is a field. Sparse rows are right for a few hundred tiles of
> track. A stencil computation over a mostly-filled grid wants dense addressing. The bitmask is
> right for both.**

**And unification would not merge the semantics anyway.** The air is a different *set of
operations*, not just a different store: `pressureAt`/millimoles, `ApertureField`, `EdgeGrid`,
venting to the rim, `airBalance`. You would still have an air-shaped API over a rail-shaped store.

### What is taken from it

The bitmask, which is separable from the row allocation — nothing stops a **dense** array having a
presence bitmask. Today `diffuseFluid` does `for (s in Species.ALL) { if (count <= 0L) continue }`:
165 contiguous loads, ~21 cache lines, to find the six species actually in the air. Three mask words
plus a bit scan touches one line plus the six, and the win survives the `Fluid` subset (23 → 6).

So: **dense arrays, plus a presence bitmask, plus one shared `forEachSpecies` interface** over both
backing stores. Chemistry gets exactly the uniformity that motivated the question — one iteration
API, no special-casing the air, no `Fluid?` bridging inside the reaction loop — without putting the
diffusion stencil behind a dependent load.

---

# Increment 0 — the `Fluid` subset ✅ BUILT

Landed in two commits: `Fluid` and the presence bitmask (0a), then the remap and the vaporizer's
deletion (0b). What actually happened against what is written below:

- The stride is `Fluid.COUNT` (23), so the air and pipe fields are **7× narrower**, not 5× — the
  volatile metals and halogens came in under the estimate. `PRESENCE_WORDS` is now **one** word.
- `MassIndex(tile, Species.Serpentine)` does not compile. That was the point.
- `MassArray` exposes both walks: `forEachFluid` for anything that stays inside the air, and
  `forEachSpecies` widening to `Species` on top of it, so a chemical pass can be one loop over
  either store.
- `Save` refuses an `air` or `pipeair` record naming a non-fluid rather than dropping it, and
  refuses a `VAPORIZER` machine by name — a machine holds cargo, and dropping it would take that
  mass out of the ledger silently.
- ⚠️ **The thruster turned out to be a second vaporizer.** Firing into a bulkhead put the whole
  chunk into the air field whatever it was made of. Its non-fluid part is now booked overboard as
  the solid it is. **Open:** a motor fed gravel should arguably refuse to fire rather than throw it
  away, which is an acceptance rule and Stu's call.

**Do this first. It is behaviour-neutral, it is the largest and most certain win in this plan, and
it makes a class of bug uncompilable.** It is worth doing even if no chemistry ever lands.

The species that can be in the air or in a pipe are a small subset of the 165. Counting the
`THE VOLATILES` block in `Species.kt`: 15. Plus the four halogens as elements, plus the volatile
metals the roasting reactions need — mercury (cinnabar roasting *is* mercury vapour, historically),
zinc, cadmium — plus sulfur vapour: **~23**. Size it at 32 for headroom.

That is a **5× reduction** in the air and pipe arrays — 7.6 MB → ~1.5 MB per tick copy — and every
`densityAt`, `millimolesOf`, `heatCapacityAt` and diffusion sweep over them gets 5× shorter.

**Then give `MassArray` a presence bitmask**, the same three-words-per-tile scheme `StuffLayer`
uses, and expose `forEachSpecies` on it with the same signature. Dense storage, sparse iteration —
see "Why not just make the atmosphere a `StuffLayer` too?" above for why those are separable and
why only the second half is wanted here. This is what lets increment 1 write one reaction loop
against one interface instead of two.

## Shape

A separate enum with a back-reference, and one nullable forward reference:

```kotlin
enum class Fluid(val species: Species) { Water(Species.Water), … ; companion object { val COUNT … } }
val Species.fluid: Fluid?   // null for the ~142 that can never be fluid
```

`MassIndex` then takes a `Fluid` and multiplies by `Fluid.COUNT`. The point is that
`masses[MassIndex(tile, Species.Serpentine)]` **stops compiling**. The invariant becomes a type
rather than a comment, which is the property the current code is missing.

## Files this touches

- `world/SafeArrayIndexes.kt:32-56` — `MassIndex`, `MassArray`, the `init` lambda.
- `world/Stuff.kt` — `massOf`, `pressureAt`, `heatCapacityAt`, `kelvinAt`, `densityAt`, `mixtureAt`,
  and the constructors at `:137`, `:145`, `:214`.
- `world/Diffusion.kt:167` — `deltaMass`.
- `world/Thermal.kt` — `heatCapacityAt(masses, tile)` loops `Species.ALL`.
- `world/PipeField.kt` — same array, same remap.
- `world/Save.kt:600,605` — `airMass`, `pipeMass`. **The save file is safe**: `Save` writes species
  by name, so nothing on disk depends on an ordinal. The *reader* must reject a non-fluid name in an
  air or pipe record rather than silently dropping it.
- `world/Vessel.kt:1116` — the regrid's `newMass`.
- `OutofspaceSim.kt:730, 785, 1449, 1483` — the `MassArray(1)` parcel sites.

## Where real decisions surface

The parcel sites and the vaporizer are the only places that have to *answer* something rather than
be mechanically rewritten. Per decision 4 the vaporizer is deleted, which removes the hardest one.

## Test

An invariant test that walks every construction site and asserts no non-fluid species can reach an
air or pipe array — and a save round-trip that rejects a hand-written air record naming a solid.
That second one is what would have caught this in the first place.

## Open

Molten metals are **not** in `Fluid` for now (decision 5). When liquid iron becomes interesting,
it is an enum entry plus whatever the phase model needs — the array widens, nothing is rewritten.

---

# Increment 1 — one reaction, end to end ✅ BUILT

`chem/Reaction.kt` (the arithmetic) and `world/AmbientChemistry.kt` (the sweep), run from a
`CHEM_PERIOD` section of the tick that sits **after the heat and before the pressure**: temperature
is what gates a reaction, and gas made this tick should push and spread in the tick it was made.

What the build decided that the scope did not:

- **The rate table is Arrhenius in reduced temperature**, `T/onset`, so one table serves every
  reaction the way one saturation curve serves every fluid. Generated from the law and re-derived by
  `ReactionTest`, per the `Saturation.kt` discipline.
- ⚠️ **`ACTIVATION` is 6, and real carbon is 27.** At the real stiffness the curve is not a curve, it
  is a step — matter sits inert and then vanishes inside one tick, and every question the player
  could ask has the same two answers. The *shape* is the physics; the steepness is chosen. This is
  the number to revisit when fire is tuned against a real save.
- ⚠️ **129 knots, not 33.** An exponential is most convex just above onset, which is exactly where
  the interesting play is; 33 knots put the chord 2% above the law there. The error falls fourfold
  per doubling and the table is static data.
- ⚠️ **The reaction is athermal.** It carries the heat the carbon *already had* — matter that
  changes medium takes its joules with it — but releases none of its own. `enthalpyPerKg` belongs to
  the table (increment 4), and inventing where the heat goes for one reaction and then deciding it
  again for the table is two answers to one question.
- ⚠️ **The rail layer only, and that is a ledger statement.** What rides a belt is cargo, which is
  what `solidBecameGas` books. The deck's matter and the conduits' own metal are *fabric*, counted
  by `builtMass`, and that identity has no term for becoming gas. Buffers are the same ledger as the
  rail and are one call away; a burning hull plate needs the fabric side of the ledger first.

## Cargo has a temperature (2026-08-20, found while doing the above)

Increment 1 gates on the temperature of a lump on a belt, and **nothing could change it.** The heat
solver builds bodies from the deck, the buffers and the conduits; `rail.stuff` — the lump itself —
was not among them, and its energy was not in `storedEnergy` either. So a packet kept whatever it
was minted with for its whole journey: it did not warm the track, the track did not warm it, and the
room might as well not have been there. The same lump conducted while it sat in a machine's buffer
and stopped the moment it was set down on a belt.

Inter-layer conduction itself was never missing — `stepSolidHeat` joins **every pair of bodies
sharing a tile**, unconditionally, before any face or permeability logic. Rail cargo simply was not
a body, so it had nothing to be joined to.

Fixed as `BodySlot.RailCargo`, shaped exactly like `BufferStore`: permeable, energy and capacity off
the layer, written back through `Body.tile`, with the same zero-capacity guard. The shared-tile rule
then gives it conduction with the track under it and the air around it for free.

- ⚠️ `CARGO_CONTACT_CONDUCTANCE` is a **third** of the buffer's. A charge in a hopper is packed
  against the walls that hold it; a lump on a belt rests on the track and is otherwise standing in
  the room. It is a dial, and it is the one that decides how far a hot lump travels before it goes
  cold.
- ⚠️ Writing the heat back is **guarded** where the other slots are not: the rail step runs between
  the bodies being built and the energy being applied, so the lump may have moved on or been lifted
  off. `setEnergy` allocates a row for a non-zero energy, so an unguarded write would leave heat on
  bare track and take it from the lump that actually has it.
- ⚠️ `storedEnergy` and `baselineEnergy` gained `rail.totalEnergy`. Before this, a machine setting a
  hot packet down **destroyed** that energy as far as the ledger was concerned and picking one up
  minted it. Nothing failed, because the solid energy identity is parked (`EnergyLedgers.PARKED`,
  for the overflow in `PLAN_unit_rescale.md` §2) — which is exactly how a term goes missing and
  stays missing. A save written before this carries a baseline without its cargo term; not migrated,
  because the identity it feeds is not being checked.
- What it buys the game: a lump leaving a furnace **cools over a distance**, so where a machine sits
  relative to what feeds it starts to matter, and a fire has to be *sustained* rather than running
  to completion once lit.

Still true, and still the reason it was the right first reaction:

`Carbon + Oxygen → CarbonDioxide`, ambient, on one layer. **This is the hard-shape case**: it is
cross-layer (rail ↔ air), cross-phase (solid ↔ gas), cross-ledger, and rate-limited. Every other
reaction is a table row after it. It needs **no new species** — carbon, oxygen and CO₂ all exist.

## The sweep

Per occupied tile, per layer:

1. `rowOf[tile] != NO_ROW` — the layer occupies this tile. Already O(1), already there.
2. Any reactant present? `forEachSpecies` already costs what the tile holds (2–3 species on a rail),
   not 165. **This is why a reactive bitmask is not needed yet** — see "Perf, honestly" below.
3. Reagent available in the tile's air. With increment 0 done, that is a lookup in a ~23-wide array.
4. Temperature at or above onset. `StuffLayer.kelvinAt` walks the row to get capacity, so compute
   it **once per tile per tick** and share it with the heat solver rather than having each reaction
   ask again.

## Rates, not thresholds

A boolean "above 900 K → decomposes" converts the entire mass in one tick. Ambient, that flash-rusts
the hull. The rate must be mass-per-tick as a function of how far above onset the matter is.

Arrhenius is `exp`, therefore float, therefore out. **The precedent to copy is `chem/Saturation.kt`**:
a knot table in reduced units with `sample()` doing fixed-point linear interpolation between knots.
Same shape, same units discipline, same file to read before writing a line of it.

## The ledger is the risk

Carbon on a rail plus oxygen from the air produces CO₂ that belongs in the **atmosphere**. So a
reaction declares, per participant, which medium it lives in — a solid layer or the fluid field —
and a product crossing into the air must go through `solidBecameGas`. That call is currently a
`vaporize` detail; it becomes a per-tick, everywhere concern, and `VesselState.airBalance` breaks
**silently** if any path misses it.

**Write the ledger-closure test before the second reaction exists.** Run a tick of ambient
chemistry, assert both the solid and air ledgers close per species, not just in total — a total can
balance while iron quietly turns into copper, which is what `conservationOf` is for.

---

# Increment 2 — reagent contention ✅ BUILT

Landed with iron oxidation as the second consumer: `4 Fe + 3 O₂ → 2 Fe₂O₃` in `chem/Reaction.kt`,
and the demand-then-apportion sweep in `world/AmbientChemistry.kt`.

Decision 2 says the oxygen comes from the tile's atmosphere, and "the oxygen attacks the carbon
first and burns it off" is a statement about **allocation order**. Several consumers, one tile's
oxygen. Resolving it by iteration order gives a rule nobody can predict and a Gauss-Seidel
leftward bias — the exact thing `stepSolidHeat` documents itself as avoiding, and the thing the
rigid-body solver is required to stay clear of.

**Jacobi, like everything else here.** `Oxidation.demand` is asked of every reaction against one
snapshot of the tile; only then is the supply handed out, in full if it covers the demands and by
`apportionInto` if it does not, and `Oxidation.react` takes an *allowance* rather than finding one.
The split into two calls is the whole mechanism.

Then "carbon burns preferentially" falls out of carbon's rate being higher at that temperature —
`BASE_RATE` is ten times `IRON_BASE_RATE` — rather than from a priority list somebody wrote.
Order-independent, deterministic, and it means the no-atmosphere room in decision 2 is a strategy
the physics produces rather than a special case.

## What the build decided that the scope did not

- ⚠️ **The second reaction runs the crossing backwards, and the ledger had no term for it.** Carbon
  leaving a belt is `solidBecameGas`; the oxygen iron *keeps* is the mirror image — the atmosphere
  shrinks on purpose and the cargo gets heavier. `gasBecameSolid` is that mirror, written as its own
  call rather than as the old one with negative arguments, because a caller passing a negative mass
  to a function whose name says it went the other way is a line nobody reads correctly twice.
  `ChemistryStep` grew to four numbers for the same reason: **never net the two directions**, or the
  first reaction whose halves happened to cancel would look like no chemistry at all.
- ⚠️ **Phase is not a field on a reaction.** Whether a product joins the air is `Species.fluid` —
  the same fact that made the air array narrow in increment 0 — so CO₂ leaves and Fe₂O₃ does not,
  and neither reaction had to say so. A flag would have been a third place for the phase of a
  species to be recorded and a third place for it to disagree.
- ⚠️ **`Oxidation` states formula units, never mass fractions.** `4 Fe + 3 O₂ → 2 Fe₂O₃` is three
  integers; every mass ratio is derived from them and the molar masses `Species` already holds.
  `OxidationContentionTest` closes every reaction atom by atom, which is `MineralTest`'s argument.
- ⚠️ **`IRON_OXIDATION_KELVIN` is 500 K, which is dry scaling and not rust in a puddle.** Iron in
  damp air corrodes at room temperature by an electrochemical mechanism this model has nothing to
  say about. Making rust appear at ambient by lowering the onset would be a rate dial pretending to
  be a mechanism, and it would quietly turn every belt in the game into ore.
- ⚠️ **A sum of floors is not the floor of a sum.** Each pass floors its own oxygen from its own
  reactant, so over several passes the world sits up to one unit per pass *under* the stoichiometric
  line. The direction is the safe one — flooring can only ever take less oxygen than the ratio calls
  for — but a test asserting exact equality across a multi-pass run is asserting a coincidence.
- **Contention has to be *provoked* to be tested.** In a room full of air both reactions get
  everything they ask for and the apportionment is never consulted, so a test against ambient air
  passes just as well with the demand pass deleted. The starved-tile fixture is the one that means
  anything.
- `apportionInto` is `apportion` writing into a caller's array; `apportion` is now one line over it.
  The sweep divides a tile's oxygen at every occupied tile of every layer every pass, and allocating
  a two-element array to do it is the kind of cost that only ever shows up as "the chemistry is
  slow".

## Still open

- Iron scaling is on the **rail layer only**, for the same ledger reason increment 1 was: a corroding
  hull plate is *fabric*, and the fabric side of the ledger has no term for changing phase in either
  direction. That is the next thing rust wants, and it is what feeds the damage system.
- Surface area is still not solved — see "Not solved, deliberately". `IRON_BASE_RATE` stands in for
  it and is expected to move once there is a real save to tune against.

---

# Increment 3 — `cook` becomes a heater ✅ BUILT

Per decision 3. `cook` is deleted — it returned its input unchanged for its entire life — and with it
the chemistry, the recipe, the tick counter and the throttle carry. `ThermalDecomposer` is now four
fields: where it is, which way it faces, its setpoint and its wiring.

What it does: pull a charge in, run an element until the charge is at [setTemperature], hand on
whatever the charge has *become*. What happened to it on the way is the ambient chemistry pass, which
now sweeps the **buffer layer** as well as the rail — the same pass, the same arithmetic, the same
ledger. The machine contributes conditions and nothing else.

`BufferRole.kt` gives `ThermalDecomposer` a `Waste` role of `NO_OFFSET` — one output port — and with
gaseous products venting to the tile's air that is correct and needs no second port: solids leave by
the belt, gases leave by the room. Which is also the argument for putting the decomposer somewhere
you have thought about the ventilation.

## What the build decided that the scope did not

- ⚠️ **The element is *in* the chamber: it heats the charge, and the charge heats everything else.**
  `Work.heatBuffer` puts the energy straight into the buffer's stuff, so most of it stays in the
  material and the casing gets what bleeds out through `BUFFER_CONTACT_CONDUCTANCE` — slowly — and
  the room gets what bleeds out of the casing. Stu's call, and the right one: modelled properly the
  element would be a third thermal body warming both the charge and the casing, and this gives the
  behaviour that was actually wanted for none of the machinery.

  It was built the other way round first — `heat()` into the tile, letting the solid-heat solver
  share it out — which is more nearly correct and feels worse. A charge warmed *through* nine tiles
  of firebrick lags its box by hundreds of kelvin and takes thousands of ticks to catch up, and the
  box has to overshoot to ~1400 K to drive a 1200 K charge at all.
- ⚠️ **The shortfall is the cap, and it only became correct when the element moved inside.** Never
  more than the gap the charge still has, so a light charge cannot be blown past its setpoint by one
  tick of an element sized for a full hopper. Pointed at the *tile* that same cap is a bug — it
  binds against a firebrick box that outweighs a chamberful of rock about fifty to one, and the
  machine crawls at a sixth of the intended rate. That cost a full debugging pass, and it is worth
  knowing that the two decisions are coupled: the cap and the target have to agree.
- **`HEATER_POWER` is derived, and honestly so**: a full chamber climbing a couple of kelvin a tick.
  The derivation is only true because the energy reaches the charge. ⚠️ Four tonnes of rock to 900 K
  is 3.2 GJ, which a real element delivers over hours rather than the seconds this is sized for —
  the game does not agree with the world about time, and `HEATER_KELVIN_PER_TICK` is the dial.
- ⚠️ **The dwell scales with the charge's mass**, because the element's power is fixed and a heavier
  charge has more to warm. A full hopper is a few hundred ticks; a light one is at temperature almost
  at once. Physically right, and free.
- ✅ **`heat()` used to throw away seven ticks in eight — fixed.** It accumulates into a per-tick
  array that the *heat* pass banked, and `heatAdded` is rebuilt with each tick's `Work`, so a machine
  that did its work on any of the seven ticks between heat steps had its whole output discarded —
  while still counting it in `generatedEnergy`, which is how a term goes missing and stays missing.
  Banking now happens in the **machine** block, with the machines that made the heat: it is a write
  into a layer and needs no solver, and a machine that did not run made nothing to bank.

  Measured on a processor fed off the beat: **284 K against 313 K**, the recovered energy matching
  the booked `generatedEnergy` to a fraction of a percent. ⚠️ **Every machine in the game now sheds
  about eight times the waste heat it used to** — `heatPerGram` is the per-machine dial if that
  proves too intense. `HeatTest` pins the invariant as a comparison so no tuning pass re-pins it.
- **A save's `carry` and `progress` are simply not read.** They belonged to a tick counter that no
  longer decides anything; the setpoint is the whole of what a decomposer stores now. The same
  disposal the extractor's second store got, and Stu's precedent.

## Still open

- **The release condition.** "At the setpoint" is the dwell. A reaction slower than the heating ramp
  does not finish before the charge is handed on. Increment 4 may want "and has stopped reacting"
  once there is a reaction table to ask — it needs the sweep to report per-tile activity, which it
  does not today.
- **A reaction can push a hopper over `MACHINE_BUFFER_CAP`**, since the cap is enforced where matter
  is *inserted* and iron scaling makes a store heavier in place. It self-corrects — the machine code
  then refuses to add to it and it drains — but it is a cap that is not quite a cap.

---

# Increment 4 — the thermal-only table

Now the table earns its place, and **two species are missing** to make it work:

- **Lime, CaO** — `Calcite → CaO + CO₂`. Named in `Minerals.kt` and in `ThermalDecomposer`'s own
  kdoc, and impossible today.
- **Periclase, MgO** — `Magnesite → MgO + CO₂`; dolomite needs both.

Everything else in tier 1 is **free**, needing no new species:

- `Serpentine → Forsterite + Enstatite + Water` — the marquee one, and the reason a dry inner-system
  rock is different from a wet one.
- `Pyrite → Troilite + Sulfur`
- `Hematite → Magnetite + Oxygen`
- `Ammonia → Nitrogen + Hydrogen`, `Methane → Carbon + Hydrogen`
- Carbothermic iron: `Hematite/Wustite + Carbon → Iron + CarbonMonoxide` — the reaction decision 2
  is really about.

## Table shape

Keyed by reactant, ordinal-indexed — `arrayOfNulls<Decomposition>(Species.COUNT)` built once from a
declarative list. Not a `Map`: `CRITICAL` and `MINERALS` get away with hashing because they are
queried per-species-present at setup, and this is read in a tick loop.

```kotlin
class Decomposition(
    val reactant: Species,
    val onsetKelvin: Int,
    val reagent: Fluid? = null,        // null = heat alone; else drawn from the tile's air
    val products: List<Pair<Species, Int>>,  // formula units, NOT mass fractions
    val enthalpyPerKg: Long,           // + endothermic
)
```

Plus a derived `onsetKelvin: IntArray` by ordinal and a global `LOWEST_ONSET`, so cold matter is
rejected with one compare.

**Derive the mass split, do not restate it.** State products as *formula units* and derive mass
through the existing `massPartsPerThousand` / `derivedMolarMass` / `apportion`. Then write the test
that matters: **every reaction closes atom by atom against `MINERALS`**. That is the `MineralTest`
precedent — molar masses are *derived* from the formulae rather than asserted alongside them, so a
typo is a test failure rather than a mineral that quietly weighs the wrong amount. Hand-written mass
fractions give you two sources of truth and no oracle, and "calcite yields lime" would silently
yield the wrong amount of lime.

Conservation stays structural, as everywhere else in `chem`: compute all products but one, and let
the last be the remainder.

---

# Increment 5 — roasting (later, if the tier earns it)

`sulfide + O₂ → oxide + SO₂`. Needs one oxide per metal: Zincite ZnO, Litharge PbO, Tenorite CuO,
Bunsenite NiO, MoO₃, Manganosite MnO, and Anhydrite CaSO₄ if gypsum should dehydrate in two stages.

**Weigh this against the species tax.** `Mixture` is a `LongArray(Species.COUNT)` per instance and
`plus`/`minus`/`take`/`conservationOf`/`dominant`/`color` are each O(COUNT); every buffer store is a
`Mixture`. Seven new oxides is a ~4% tax on all of it. Note this is the *solid* axis — increment 0
already fixed the fluid one — so it is a real but bounded cost.

`reagent: Fluid?` means roasting needs no new machine, which is most of why the field is shaped
that way.

---

# Perf, honestly

Two separate ideas got conflated in scoping, and separating them is most of the design:

- **Narrowing the species axis** (`Fluid`) shrinks what is *stored and copied*. 7.6 MB → 1.5 MB per
  tick, and it is the larger, more certain number.
- **A presence bitmask** shrinks what is *iterated*. It does not need sparse rows, and it is what
  gives the air and the solid layers one interface.

Increment 0 does both. Neither requires unifying the two storage types — see "Why not just make the
atmosphere a `StuffLayer` too?".

A third idea, `REACTIVE_MASK` — ANDing the presence words against a precomputed mask of "is any
species here a reactant at all" — was proposed and **withdrawn**. Once the presence bitmask exists,
iteration already costs what a tile holds: 2–3 species on a rail, ~6 in air. A reactive mask on top
turns three lookups into three `and`s, which is a constant factor on an already-cheap loop. Do not
build it without a profile that asks for it.

# Not solved, deliberately

- **Surface area.** Reactions are surface phenomena; the layers store bulk mass. Rate proportional
  to full mass oxidises a hull plate at the rate of its entire volume. Cheapest honest stand-in is a
  fixed exposed fraction per layer; more principled is mass^⅔, which is integer-cube-rootable. This
  is the difference between "rust is a slow threat" and "rust eats the ship" and it wants tuning
  against a real save, not a decision up front.
- **Electrolysis.** `Corundum → Aluminum` and `Water → H₂ + O₂` are electrolytic, not thermal.
  Leaving them out of the table is better than putting them in at unreachable thermolysis
  temperatures. They want a future machine that spends signal-network power.
- **Rare-earth separation.** `LANTHANIDE_SUITE` is untouched by any of this. It is deliberately not
  a smelt — it wants a cascade of near-identical stages — and it remains the marquee endgame build
  the species table was designed to make possible.
- **Damage.** Corrosion changing a casing's *structural* properties is the follow-on this feeds, and
  is not scoped here. `StuffLayer.heatCapacityAt` already answers correctly for a casing whose
  composition a reaction has changed, which is the half that is done.
