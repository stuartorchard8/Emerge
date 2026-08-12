# Numeric limits and dynamic ranges

Status: **survey, nothing changed** — 2026-08-12.

Every number below was measured against the code as it stands, either by evaluating the real
functions or by running a real scenario. Nothing here is an estimate unless it says so. The probes
were temporary and have been deleted; §9 is the permanent tripwire that replaced them and §10 says
how to rebuild the rest.

The question this answers: **if the mass unit stops being "one gram" and becomes an anonymous unit
`1 g / k`, how large can `k` be before something overflows, and what does that buy?**

---

## 1. The short version

| | |
|---|---|
| Tightest constraint | `velocityX = vesselImpulse * PER_TILE / mass` — **safe k ≈ 17** (reference ship) to 42 (measured bare hull); already impossible for the heaviest buildable vessel, see §5 |
| Next | `apportion`: `weight * target` — **safe k ≈ 152** (k², at a full Storage) |
| Next | ship-wide joules at 3000 K — **safe k ≈ 49** at the absolute worst packing |
| Already broken at k=1 | `reducedPressure` at the packing wall (§6.1) |
| Already broken at k=1 | diffusion strands anything under 5 units (§6.2) |
| Recommended | **k = 100** as-is; **k = 1000** after five fixes (§8, listed by the tripwire in §9) |

The headline for the stated goal: **negligibility is set by the diffusion stranding floor, which is
5 *units* regardless of what a unit means.** As a fraction of a tile of ambient air that is
`5 / (k · 1000)` — today 0.5%, at k=1000 five parts per million.

---

## 2. What the storage can hold vs. what the arithmetic can hold

Worth separating, because they are eleven orders of magnitude apart and only one of them binds.

A `Long` holds 9.22e18. The largest quantity any field actually *stores* is a tile of solid uranium
at 1.59e7 g. So the fields are using about **one part in 580 billion** of their range.

Every constraint in this document is therefore an **intermediate product**, never a stored value.
All of them have the same shape: a mass multiplied by a fixed-point scale before being divided back
down. That is a fixable code pattern, not a property of the simulation.

---

## 3. Dynamic ranges

### 3.1 Mass in one tile

A tile is `TILE_LITRES = 830` L — the one place SI touches the vessel. Since the solid-density
rebase, gas and solids are stated at the same real scale.

| What | grams/tile |
|---|---|
| Uranium, solid | 15,853,000 |
| Copper, solid | 7,436,800 |
| Iron, solid | 6,532,100 |
| RareEarth, solid | 5,818,300 |
| Titanium, solid | 3,743,300 |
| Aluminum, solid | 2,241,000 |
| Silica, solid | 2,199,500 |
| Carbon, solid | 1,875,800 |
| CO₂, close-packed liquid | 1,165,320 |
| Oxygen, close-packed liquid | 1,085,640 |
| Water, close-packed liquid | 801,780 |
| Nitrogen, close-packed liquid | 779,370 |
| **Air at 1 atm** | **1,000** |
| Diffusion stranding floor | 5 |

Useful span: **1.59e7 : 1**, or **3.2e6 : 1** if you exclude solids and ask only what the gas field
carries. The liquid figures are `3 × critical density` — the close-packing limit, beyond which van
der Waals has no answer at all (see §6.1).

### 3.2 Material tiles, as built

| Material | grams/tile | capacity (mJ/K) |
|---|---|---|
| STEEL | 6,373,892 | 2,884,823,519 |
| IRON | 6,532,100 | 2,939,445,000 |
| COPPER | 7,436,800 | 2,863,168,000 |
| TITANIUM | 3,743,300 | 1,946,516,000 |
| FIREBRICK | 2,217,988 | 1,752,210,520 |

### 3.3 Machines, whole

Structure mass is `gramsPerTile × thermalTiles`, deflated by `fillPermille`.

| Machine | grams | capacity (mJ/K) | tiles |
|---|---|---|---|
| EXTRACTOR | 14,037,375 | 7,299,435,000 | 25 |
| SMELTER | 13,862,425 | 10,951,315,750 | 25 |
| PROCESSOR / VAPORIZER / STORAGE | 5,053,455 | 2,627,796,600 | 9 |
| PUMP | 561,495 | 291,977,400 | 1 |
| BRIDGE | 391,926 | 176,366,700 | 3 |
| HULL / AIRLOCK | 382,433 | 173,089,411 | 1 |
| GAUGE | 261,284 | 117,577,800 | 1 |
| SENSOR / BUTTON / VENT | 149,732 | 77,860,640 | 1 |
| RAIL | 130,642 | 58,788,900 | 1 |
| PIPE / VALVE | 111,552 | 42,947,520 | 1 |
| WIRE | 14,873 | 5,726,336 | 1 |

Measured whole-ship mass for a bare 96×60 hull: **110,523,137 g**. A 5760-tile grid packed solid
with extractors would be 8.09e10 g — the absolute upper bound on vessel mass, and the figure the
worst case in §5 uses.

### 3.4 Enforced cargo caps

These are hard, checked ceilings, which makes them better bounds than any measurement:

| Cap | grams |
|---|---|
| `Storage.CAP` | 20,000,000 |
| `Extractors.BUFFER_CAP` | 5,000,000 |
| `MACHINE_BUFFER_CAP` / `MACHINE_OUTPUT_CAP` | 4,000,000 |
| `Packet.PACKET_GRAMS` | 1,000,000 |

A full Storage (2e7 g) is the largest single mass any one holder can present to a function.

---

## 4. The fixed-point scales, all of them

This is the actual problem. There are **five independently-chosen scales**, three of them at
1e8–1e9, and a mass has to pass through all of them.

| Constant | Value | Where | What it scales |
|---|---|---|---|
| `Flight.PER_TILE` | 1e9 | `Flight.kt` | position and velocity, per tile |
| `Composition.VOLUME_UNIT` | 1e9 | `Composition.kt` | volume in the density harmonic mean |
| `chem.SCALE` | 1e8 | `StateEquation.kt` | every reduced quantity in van der Waals |
| `Frac` scale | `Int.MAX_VALUE` ≈ 2.15e9 | engine | accelerations, gravity, apertures |
| `VolumeField.FULL` | 1024 | `Volume.kt` | cell fullness |
| `ApertureField.OPEN` | 1024 | `Apertures.kt` | how open a face is |
| `SignalField.FULL` | 1000 | `SignalField.kt` | wire signal |
| `CAPACITY_SCALE` | 1000 | `Thermal.kt` | millijoules per kelvin |
| `MILLI` | 1000 | `Pressure.kt`, `StateEquation.kt` | millimoles per gram |

Nine scales, chosen at nine different times for nine good local reasons. **No single place states
the global budget**, which is why a rescale is risky today: the mass unit has to fit through
`PER_TILE`, `VOLUME_UNIT` and `SCALE` simultaneously, and none of them knows about the others.

`Frac` deserves its own note: `Frac.times` computes `raw * o.raw / Int.MAX_VALUE`, so it overflows
once a value exceeds about **±1.41**, and stored values are bounded near ±4.3 regardless. Any
acceleration above roughly 1.4 tiles/tick² is unrepresentable no matter what the mass unit is.

---

## 5. The constraint ladder

`safe k` is the largest scale factor before that expression overflows a `Long`. Exponent is how the
expression grows with k: a k² term punishes you quadratically, so those are the dangerous ones even
when they look roomy today.

| # | Expression | k^ | worst case | safe k |
|---|---|---|---|---|
| 1 | `velocityX`: `vesselImpulse * PER_TILE` | 1 | ship at 2 tiles/tick | **16.7** (reference ship) — see below |
| 2 | ship joules: heaviest machine at 3000 K × 5760 | 1 | 1.89e17 | **49** |
| 3 | `apportion`: `weight * target` | **2** | full Storage, 4.0e14 | **152** |
| 4 | `apportion`: `weight * target` | **2** | machine buffer, 1.6e13 | 759 |
| 5 | `potentialOf`: `pressure * SOUND_IMPULSE` | **2** | close-packed liquid | 648 |
| 6 | `reducedPressure` at the packing wall | 1 | — | **already broken, see §6.1** |
| 7 | `reducedDensity`: `grams * SCALE` | 1 | packed CO₂, 1.17e14 | 7.9e4 |
| 8 | `ambientPressureOf`: `grams * share` | **2** | at close packing | 1.2e5 |
| 9 | `frameAcceleration`: `netImpulse * FRAC_ONE` | 1 | measured peak 927/tick | 4.6e6 |
| 10 | `potentialOf` at 10 atm (ordinary play) | **2** | 8.6e7 | 3.3e5 |
| 11 | `millimolesOf`: `grams * 1e6/molarMass` | 1 | packed water | 2.1e8 |
| 12 | body joules: heaviest machine at 3000 K | 1 | 3.29e13 | 2.8e5 |
| 13 | solid mass: full ship of extractors | 1 | 8.09e10 | 1.1e8 |
| 14 | cargo: `Storage.CAP` × 5760 tiles | 1 | 1.15e11 | 8.0e7 |
| 15 | `gramsPerTileOf`: `total * VOLUME_UNIT` | 1 | composition totals ~1000 | 9.2e6 |

### Notes on the ones that matter

**#1 is the binding constraint and it is a velocity ceiling, not a mass ceiling.** `vesselImpulse`
is momentum, so `mass × velocity`; multiplying by `PER_TILE = 1e9` before dividing caps the top
speed at `9.22e9 / mass` tiles/tick. That is 83 tiles/tick for the measured 110-tonne hull today, and
`83/k` after a rescale. `NAV_FULL_SCALE_SPEED` is already 2 tiles/tick, so at k=100 the ship tops
out at 0.83 tiles/tick — below the speed the nav instrument is drawn for.

> ⚠️ **Correction, found by the tripwire in §9.** The first version of this document reported a safe
> k of 5.7 for a maximally-heavy ship. That was wrong by a factor of a hundred — a stray divisor in
> the probe. Stated as a mass instead, at 2 tiles/tick the flight model can fly:
>
> | | grams | |
> |---|---|---|
> | ceiling (`Long.MAX / (PER_TILE × 2)`) | 4,611,686,018 | — |
> | reference ship (⅛ of the grid in hull) | 275,351,760 | 6.0% of the ceiling |
> | measured bare 96×60 hull | 110,523,137 | 2.4% |
> | grid packed solid with extractors | 80,855,280,000 | **17.5× over — already impossible today** |
>
> So there is a buildable vessel that the flight model cannot fly *at one gram per unit*, with no
> rescale involved: its velocity wraps and it flies backwards. Nobody builds a solid block of
> extractors, which is why this has never been seen, but it is a real edge and it is the first thing
> a rescale spends.

**#2 is a whole-ship sum, so it scales with grid area as well as k.** The 5760-tile figure is
today's default grid; the dynamic grid can grow, and this bound shrinks proportionally. A world
twice the area halves the safe k.

**#3 was the surprise.** `apportion` multiplies each weight by the target before dividing by the sum
— and both are masses, making it the tightest k² term in the codebase. It is reached through
`Mixture.scaledTo`, which is exactly what runs when a full Storage is rescaled.

**#15 is safe only by convention.** `gramsPerTileOf` computes `total * VOLUME_UNIT / volume`, which
would overflow for a real pile — the loop directly above it splits whole-part-and-remainder
*specifically* to dodge that, then the return statement does the naive multiply anyway. It survives
because both call sites happen to pass per-mille compositions totalling ~1000
(`Material.composition`, and `RockSpawner.mixtureForChunk` which normalises to `density = 1000`).
**This is an unstated invariant**: pass `gramsPerTileOf` a real pile mass and it breaks at 9.2e9 g.
Worth an explicit `require`, whatever else happens.

---

## 6. What is already wrong at k = 1

### 6.1 `reducedPressure` overflows at the packing wall

`vanDerWaalsPressure` computes `8·Tr·ρr / (3 − ρr)`. `leastRoomFor` deliberately drives density to
`CLOSE_PACKED − 1`, making that denominator **1**, so the term reaches ~1e17 and
`partialPressure`'s `c.pressure × reducedPressure` overflows:

| Species | `c.pressure` | worst reduced pressure | product |
|---|---|---|---|
| Oxygen | 2,243,416 | 4.65e18 | 2.2e18 (4.2× headroom) |
| Nitrogen | 1,496,218 | 5.71e18 | **negative** |
| CarbonDioxide | 3,434,814 | 2.37e18 | **negative** |
| Water | 12,294,900 | 1.11e18 | **negative** |

Three of four species are wrapped. The visible symptom is a tile pressure that flips sign and
oscillates as mass increases past close packing:

```
801,780 g water →  +45,280,586,281
850,000 g       →  -68,401,856,743
900,000 g       →  +91,503,964,939
1,100,000 g     →  -56,481,867,421
```

Negative pressure means the solver pushes gas **into** an over-full tile. Reachable in about 72
ticks of the debug water tool on one tile, or 9 ticks on a pipe cell. Independent of any rescale —
this wants fixing either way, by clamping short of the wall with a margin rather than by one unit.

### 6.2 Diffusion strands anything under 5 units

`SLOTS = 5`, `FACE_SHARE = 1`, so a tile sheds `count / 5` per face and **nothing below 5 units can
ever move**. That is a fixed number of units, not a fixed mass — it is the one quantity in the whole
system that k improves directly and unconditionally.

It is also, precisely, the trace gas the `Negligible` floor added on 2026-08-12 hides from the
overlays. That floor was set at 0.5% of ambient because that is where percentages round to zero;
the stranding floor is 5/1000 of ambient for unrelated reasons. **The overlay change is cosmetic
cover for this quantisation artifact.** Rescaling is the real fix, and `Negligible` should stay
defined as a fraction of ambient so it follows k down automatically.

### 6.3 Monotonic ledgers never reset

`extractedGrams`, `ventedGrams`, `airVentedGrams`, `generatedJoules`, `radiatedJoules` accumulate
for the life of a world. They are the only quantities bounded by *playtime* rather than world state.
At k=1 they are a non-issue; at k=1e6 a long session could plausibly reach 9.2e12 grams-equivalent
through the extractors. Worth stating as a bound rather than discovering later.

---

## 7. What each k buys

| k | unit | stranding floor, as fraction of a tile of air | cost |
|---|---|---|---|
| 1 | gram | 5.0e-3 | today |
| 10² | centigram | 5.0e-5 | fits everything as-is except #1 (top speed 0.83 tiles/tick) |
| 10³ | milligram | 5.0e-6 | needs #1, #2, #3 fixed |
| 10⁵ | 10 µg | 5.0e-8 | needs the three fixed-point scales restructured as one budget |
| 10⁸ | 10 ng | 5.0e-11 | at `reducedDensity`'s ceiling; nothing left over |

The absolute ceiling, if every listed expression were rewritten to divide before multiplying, is set
by #7 `reducedDensity` at **k ≈ 7.9e4** while liquids are representable — because `grams * SCALE`
with `SCALE = 1e8` is unavoidable in reduced units unless `SCALE` itself comes down. Trading
`SCALE` down to 1e6 costs phase-transition precision on trace species, which is the thing it was
raised to 1e8 to protect. **So ~1e5 is the practical ceiling for the whole system, and k=1e3 is the
comfortable one.**

---

## 8. If it goes ahead

Recommended: **k = 1000, one unit = one milligram.** Three orders of improvement in exactly the
direction wanted, every SI conversion stays a decimal shift, and it sits inside every constraint
once these are addressed:

1. **`velocityX`** — divide before multiplying, or drop `PER_TILE` to 1e6 (still six figures of
   sub-tile position). Buys 10³.
2. **`apportion`** — split whole-part and remainder the way `gramsPerTileOf`'s loop already does.
   Buys ~10³ on a k² term.
3. **Ship-wide joule sums** — the one that also scales with grid area; wants a bound stated in terms
   of both, and a decision about whether solid joules stay in millijoules.

Additionally, and regardless of the rescale: fix `reducedPressure`'s wall clamp (§6.1), and add the
missing `require` to `gramsPerTileOf` (§5, #15).

Not recommended without a separate think: going past 10³ in one step. The three 1e8–1e9 scales need
to become one stated budget first, and that is a different piece of work from changing the mass
unit.

---

## 9. The tripwire

`NumericLimitsTest` (in `core/src/commonTest`) is this document made executable, and it is the
warning system for the rescale itself. It rebuilds every worst case in §5 out of the game's own
constants — densities, caps, grid size, machine masses — so moving any of them moves the rows that
depend on it, by name.

**The knob is `targetMassScale`.** Set it to the unit you are aiming at (`1` = one gram, `1000` = one
milligram) and every row that cannot support that unit fails with its name, its actual headroom and
the headroom it would need. That failure list *is* the to-do list, generated rather than remembered.
At `targetMassScale = 1000` today it reads:

```
- velocityX: vesselImpulse * PER_TILE          headroom 16.7,     needs 4.00e+03
- frameAcceleration: netImpulse * FRAC_ONE     headroom 780,      needs 4.00e+03
- apportion: weight * target (full Storage)    headroom 2.31e+04, needs 4.00e+06
- apportion: weight * target (machine buffer)  headroom 5.76e+05, needs 4.00e+06
- ship joules: that across the whole grid      headroom 48.7,     needs 4.00e+03
```

Five expressions between here and milligrams — one more than §8 predicted, since `apportion`'s
buffer case and `frameAcceleration` both fall inside the safety factor at that target.

A second test states the flight ceiling as a mass rather than a margin, because that one already
binds (see the correction in §5). It prints the budget breakdown on every run.

Two deliberate omissions, both explained in the file: the §6.1 packing-wall overflow and the §6.2
stranding floor. Pinning either would mean asserting that a known bug is still present, which goes
red when somebody fixes it.

Runtime is 50 ms for both tests, so it costs nothing to keep in the suite.

## 10. How to re-measure

The analytic bounds are now pinned by §9. The *measured* peaks are not, and two probes produced
them, both deleted after reading:

- **Analytic bounds** — a `commonTest` class that evaluates `solidGramsPerTile`, `Material`,
  `MachineKind.gramsPerTile × thermalTiles`, the caps, and each intermediate's worst case, printing
  `Long.MAX_VALUE / worst` and its k-th root.
- **Measured peaks** — a bare hull from `FlightTest.bareHull`, breached midships, stepped 400 ticks,
  recording peak `netImpulse`, `vesselImpulse`, tile pressure, tile grams and tile joules.

The measured peaks came from a bare hull with **no logistics running**, so the cargo path figures in
§5 are the enforced caps rather than observations. A loaded vessel would confirm them.

§9 is that analytic probe, made permanent. What remains unpinned is the scenario side: a loaded
vessel with logistics running would confirm the cargo-path figures, which are currently the enforced
caps rather than observations.
