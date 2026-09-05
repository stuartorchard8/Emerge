# Specific heat, and the temperature a tile reads

Status: **scoped, not started** (2026-09-05). Found while auditing reaction enthalpies; parked
deliberately so the reaction table could finish. Nothing here blocks that work — see *Rework risk*.

> Energy is the integral of a heat capacity, and this game multiplies instead. Every consequence
> below is that one sentence.

## The defect

```kotlin
fun energyAtKelvin(thermalMass: Long, kelvin: Int): Long =
    scaledRatio(kelvin.toLong(), Budget.CAPACITY_DIVISOR, thermalMass)
```

`E = c · T`, which is only correct when `c` is constant all the way from absolute zero. It is not
constant in either of the two ways that matter, and the single column hides both:

**1. It is not constant with phase.** Liquid water is 4182 J/kg/K and steam is 2080. `Species` has
one number, so one of them is wrong wherever water goes.

**2. It is not constant with temperature.** Hydrogen gas is 14300 at 298 K and about 10400 at 20 K,
because its rotational modes freeze out. The table carries 14300, so **cryogenic hydrogen is ~40%
wrong in both phases** — a larger error than the phase question, in the species the thruster model
leans on hardest.

## What the table actually contains

Scored against what each molecule's own shape predicts — `adiabaticK` is stored as `2·Cp/R` exactly
so this arithmetic stays in integers:

| fluid | table | shape | off | what the table's number actually is |
|---|---:|---:|---:|---|
| Water | 4182 | 1848 | 126% | liquid water |
| Bromine | 474 | 182 | 161% | liquid bromine |
| Iodine | 214 | 115 | 87% | **solid** iodine |
| Mercury | 140 | 103 | 35% | liquid mercury |
| Cadmium | 232 | 186 | 25% | **solid** cadmium |
| Zinc | 388 | 320 | 21% | **solid** zinc |

The other seventeen fluids agree within 23%, which is the equipartition model's own slop (sulfur
dioxide is 23% off it and is *right*). The 147 solid-only species carry solid values and are fine.

⛔ **Every one of the six is an exact match to that substance's condensed value.** This is not
scattered error, it is one mistake made six times: looking up "specific heat of X" returns whatever
phase X is in at room temperature.

⚠️ **Three of the six are Zinc, Cadmium and Mercury** — the roasting metals. They are `Fluid`s for
the express purpose of leaving a roasting bed as vapour, and they carry solid-metal capacities. When
roasting lands, a room full of zinc vapour will be ~21% too hard to heat.

⚠️ `SpeciesPropertyTest` pins the four it can resolve as an asserted exception set. Zinc and cadmium
sit inside the model's slop and are caught only by this document.

## ⛔ Why not just fix the six numbers

Asked and answered 2026-09-05. There is **no single number that is right** for a species genuinely
living in two phases, and six of them do. Water is ice or vapour far more often than it is liquid
(both ~2080, so the gas value would be an improvement); zinc is solid ore on a belt far more often
than it is vapour. Picking a convention trades one silent wrongness for another and buys nothing but
the appearance of consistency.

## The fix

**One enthalpy curve per species, not three capacity columns.**

```
H(T) = ∫₀ᵀ c(T′) dT′
```

- **Monotone by construction.** H is strictly increasing for any positive `c`, however violently `c`
  varies with temperature or phase. ⚠️ This matters more than it looks: `settleCohesion` bisects
  `f(K) = capacity·K + cohesion(K)` and its entire correctness argument is that `f` is strictly
  increasing. Making `capacity` phase-dependent *and keeping the multiply* puts a `K·dc/dK` term in
  `f′`, which survives only while `L > K·Δc`. For water at 373 K that is 2260 against 784, so it
  would in fact hold — but it fails approaching the critical point, which is exactly where
  `reference_oos_settle_cohesion_nonmonotone`'s two open failures already live. Integrating removes
  the question instead of winning the argument.
- **Phase-dependence is a kink in the curve**, and the latent heat is the *jump* — which `cohesion`
  already models separately and correctly. They compose rather than compete.
- **Temperature-dependence comes free**, with no extra mechanism and no extra column.

### Shape

The `Saturation.kt` / `SaturationTables.kt` pattern, which is already 1329 lines of exactly this:
per-species knot tables solved offline, read by interpolation through an ordinal-indexed array. An
`H(T)` table is the same shape and smaller. The *inverse* — temperature from energy — is already a
bisection inside `settleCohesion`; it evaluates a different function and the machinery is unchanged.
`kelvinOf` becomes a lookup and an interpolation instead of a divide.

### Cost

21 call sites of `kelvinOf` / `energyAtKelvin`, across `Thermal`, `LatentHeat`, `Save`, `DeckMachine`,
`OutofspaceSim`, `StuffLayer`, `StationIndustry`, `StarterVessel` and `TrackLayers`.

⚠️ **This re-tunes every thermal behaviour in the game**, which is a decision and not a refactor —
the same warning `Species.milliWattsPerMetreKelvin` carries about wiring in real conductivities, and
for the same reason. Expect the thermal fixtures to move and expect to have to decide which of the
moves are corrections.

## Rework risk for the chemistry work in flight

**Low, and worth stating precisely** because it is why this is parked rather than done first.

A reaction row states **formula units**, an **onset in kelvin**, a **base rate**, and an enthalpy
derived from **kJ/mol at 298 K**. Every one of those is a physical quantity that does not depend on
how the game maps energy to temperature. A row written today stays correct.

What changes is *how much energy it takes to reach a stated temperature* — so a furnace's climb, a
dwell time, and the tuning of anything measured in ticks will move. That is the right thing to move.

⚠️ The exception is **balance observations**, not rows: measurements like the decarburisation table
in `REACTIONS` ("6% converted at 1400 K in ~400 passes") were taken against today's capacities and
will need retaking. They are recorded as measurements with their conditions, which is what makes that
survivable.

## Not solved here

- **Heat of fusion.** There is none anywhere in the game: solid and liquid are one condensed bucket
  carrying identical cohesion, so crossing a melting point moves no energy and there is no melting
  plateau. Adding one is a second term in `cohesionOf`, which `settleCohesion`'s bisection picks up
  for free — the equation stays monotone. Related and separate.
- **Real `c(T)` data for 170 species.** The curve machinery does not require it: piecewise-constant
  per phase is already a large improvement, and the same table takes better data later without
  another architectural change.
