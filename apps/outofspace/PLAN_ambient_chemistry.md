# Ambient chemistry

Status: **scoped, nothing built** (2026-08-19).

Chemistry today is something one machine does to one buffer, and it does not actually do it —
`cook` in `chem/Chemistry.kt` is a stub that returns its input unchanged. This plan makes chemistry
a property of matter and temperature rather than a property of a machine.

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

---

# Increment 0 — the `Fluid` subset

**Do this first. It is behaviour-neutral, it is the largest and most certain win in this plan, and
it makes a class of bug uncompilable.** It is worth doing even if no chemistry ever lands.

The species that can be in the air or in a pipe are a small subset of the 165. Counting the
`THE VOLATILES` block in `Species.kt`: 15. Plus the four halogens as elements, plus the volatile
metals the roasting reactions need — mercury (cinnabar roasting *is* mercury vapour, historically),
zinc, cadmium — plus sulfur vapour: **~23**. Size it at 32 for headroom.

That is a **5× reduction** in the air and pipe arrays — 7.6 MB → ~1.5 MB per tick copy — and every
`densityAt`, `millimolesOf`, `heatCapacityAt` and diffusion sweep over them gets 5× shorter.

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

# Increment 1 — one reaction, end to end

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

# Increment 2 — reagent contention

Needs two competing reactions in one tile, so it arrives with iron oxidation.

Decision 2 says the oxygen comes from the tile's atmosphere, and "the oxygen attacks the carbon
first and burns it off" is a statement about **allocation order**. Several consumers, one tile's
oxygen. Resolving it by iteration order gives a rule nobody can predict and a Gauss-Seidel
leftward bias — the exact thing `stepSolidHeat` documents itself as avoiding, and the thing the
rigid-body solver is required to stay clear of.

**Jacobi, like everything else here.** Compute every reaction's oxygen *demand* against a snapshot,
then `apportion` the available oxygen across them if oversubscribed. `apportion` (`chem/Mixture.kt`)
already distributes a scarce total across weights with an exact sum and no float.

Then "carbon burns preferentially" falls out of carbon's rate being higher at that temperature,
rather than from a priority list somebody wrote. Order-independent, deterministic, and it means the
no-atmosphere room in decision 2 is a strategy the physics produces rather than a special case.

---

# Increment 3 — `cook` becomes a heater

Per decision 3. Push energy into the buffer's `setEnergy` toward `setTemperature`; delete the
chemistry from `refine(ThermalDecomposer)` at `OutofspaceSim.kt:700`. `ticksPerAction` becomes
redundant — the reaction is gated by temperature now, and the machine's job is to reach and hold it.

The buffer is already a real `Body` in `BodySlot.BufferStore` that `stepSolidHeat` conducts into,
so the thermal side of this is plumbed. The machine becomes the waste-heat sink its own kdoc says
it should be: the reaction stalls when the player's heat budget stalls.

`BufferRole.kt:116` gives `ThermalDecomposer` a `Waste` role of `NO_OFFSET` — one output port. With
gaseous products venting to the tile's air (increment 1) that is correct and needs no second port:
solids leave by the belt, gases leave by the room. Which is also the argument for putting the
decomposer somewhere you have thought about the ventilation.

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

An earlier version of this scope proposed a `REACTIVE_MASK` — three words of "is any species here a
reactant" ANDed against `StuffLayer`'s presence bitmask — and called it foundational. **It is not,
and it should not be built yet.**

The presence bitmask already gives the win: `forEachSpecies` on a rail row costs 2–3 iterations,
not 165. A reactive mask on top turns three lookups into three `and`s. Real, but a constant factor
on an already-cheap loop.

The dense 165-wide fluid arrays were carrying the entire perf case, and increment 0 removes it.
With ~23 species in the air, gating a 23-entry loop is not obviously worth the bookkeeping. Add the
mask if and when a profile asks for it, not before.

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
