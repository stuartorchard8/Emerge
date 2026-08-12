# Unit rescale: one stated budget, and a microgram floor

Status: **PLANNED, nothing built** — 2026-08-12.

Companion to `NUMERIC_LIMITS.md`, which is the survey this plan acts on. Read §5 (the constraint
ladder), §6 (what is already broken) and §11 (the physical anchors) first; this file assumes them and
does not restate their numbers.

**Guarded by `NumericLimitsTest`.** Its `targetMassScale` knob is the progress meter for the whole
job: raise it and the failures are the remaining work, by name.

---

## 1. The goal

Make the simulation's numbers mean something defensible, and leave the foundation stated rather than
assumed.

1. Every quantity anchored to a measured physical fact, with its unit chosen deliberately. The
   argon/CO₂ correction is the template.
2. **Push the representable bottom as far down as a `Long` allows.** The top is pinned by physics
   now (osmium — nothing can be denser), so all the available range is downward. This is what turns
   "negligible" from half a percent of a tile into something genuinely negligible.
3. Remove the multiply-before-divide sites that spend range for no benefit.
4. Collapse nine independently-chosen fixed-point scales into **one stated budget**.
5. Clean up the inconsistencies surfaced along the way.

Not in scope: gameplay, balance, shipping. This is the physics vehicle.

### The target

Reduced to five reorderings by the scope decision in §2 — with the ledgers out, nothing here needs
restructuring.

| | |
|---|---|
| Mass | **1 unit = 1 µg** (`Kₘ = 10⁶`) |
| Energy | **1 unit = 1 nJ** (`Kₑ = 10⁹`) |
| Relation | `Kₑ = 1000 · Kₘ`, forever |

That relation is not a preference. Specific heat is quoted per **kilogram**, so
`capacity = mass × specificHeat × Kₑ / (1000·Kₘ)`, and holding `Kₑ = 1000·Kₘ` makes that factor
exactly **1** — which is why `gasCapacityAt` can read `grams * specificHeat` today with no conversion
constant. Keeping the relation keeps that line true at any scale. Breaking it means carrying a lossy
divisor through every capacity in the game.

`Kₘ = 10⁶` is chosen because it is where a trace species becomes representable the way atmospheric
chemistry actually states one — parts per billion of a tile-load (`NUMERIC_LIMITS.md` §11.4).

---

## 2. The finding that shapes the plan

Energy inherits mass's dynamic range, then multiplies it by specific heat and by temperature. Two
ends must fit in one `Long`:

- **smallest meaningful**: one mass unit of the lowest-specific-heat material (uranium, 116 J/kg/K)
  warmed one kelvin — `0.116 / Kₘ` joules;
- **largest**: the whole-grid energy ledger — **7.56e12 J** for a 5760-tile grid holding its maximum
  230 smelters at 3000 K.

Their ratio is `6.5e13 × Kₘ`, against a `Long`'s 9.2e18:

| | ceiling on `Kₘ` |
|---|---|
| per-tile energy alone | **8e8** |
| whole-grid ledger total, in one `Long` | **1.4e5** |

**So `Kₘ = 10⁶` is reachable if and only if the ledger accumulators stop being single `Long`s.**
Per-tile energy has three orders of headroom to spare; the grand totals are the only obstruction.

### The decision: the ledgers are out of scope, and may rot

Deliberate, and taken 2026-08-12 to hold the scope down. The energy ledgers **will overflow** at the
target unit and that is accepted for the duration of the rescale. What this buys is large: the
accumulators were the only thing here needing *restructuring* rather than *reordering*, so removing
them from scope reduces the whole job to the five multiply-before-divide fixes.

Two refinements worth having on the record before anyone panics at a red conservation test:

- **The mass ledgers survive the target; only the energy ones do not.** Cargo across the grid is
  1.15e11 units, safe to `Kₘ = 8e7`. It is `storedJoules`, `generatedJoules`, `radiatedJoules` and
  `baselineJoules` that break — by ~820× at `Kₑ = 10⁹`, since the clean `Kₑ = 1000·Kₘ` relation
  spends two orders more precision at the bottom than the physics needs.
- **So mass conservation stays as the safety net**, which is the right one to keep: it is the check
  most likely to catch a mistake made while rescaling masses. See step 3 for how the energy checks
  get parked rather than deleted.

### The eventual answer: store divergence, not totals

Stu's design, 2026-08-12, and it is better than the coarse-unit split this plan originally proposed.

A ledger exists to answer "does it balance", never "how much has ever moved". So store the
**divergence** — `extracted − (aboard + vented)`, and its energy equivalent — which sits at zero for
a correct simulation and is bounded by the *error* rather than by playtime or world size. That
retires §6.3 outright instead of deferring it, and makes the ledger's range independent of `Kₘ`
altogether.

The cost is that a divergence does not say *when* it set in. That is a smaller loss than it sounds:
a total only reveals when it diverged if you kept a previous reading to diff against, whereas a
divergence leaving zero is detectable on the tick it happens.

Not in this plan, by decision. It is the natural first follow-on.

✅ **Fixed at step 1.** `NumericLimitsTest`'s `ship joules` row was 25× pessimistic — it multiplied a
whole machine's capacity by every tile in the grid, when a grid holds 230 smelters rather than 5760.
The same error was in two more places and its correction retracted a bug; see step 1.

---

## 3. The one budget

The replacement for nine scales chosen independently at nine different times. One place states what
a unit means; everything else derives.

```
MASS   1 unit = 1 µg          Kₘ = 10⁶
ENERGY 1 unit = 1 nJ          Kₑ = 1000·Kₘ    (forced by specific heat being per-kg)
LENGTH 1 tile = TILE_LITRES   unchanged — the one place SI already touches the vessel
```

Everything mass- or energy-dimensioned derives from those. The audit in step 2 is what makes that
true rather than aspirational.

Scales that stay as they are, and why: `VolumeField.FULL` and `ApertureField.OPEN` (1024) are
dimensionless fractions with no mass in them; `SignalField.FULL` (1000) is a percentage; `Frac`
belongs to the engine and bounds accelerations, not masses.

Scales that move into the budget: `Flight.PER_TILE`, `Composition.VOLUME_UNIT`, `chem.SCALE`,
`CAPACITY_SCALE`, `MILLI`.

---

## 4. Steps

Each step is separately committable and leaves the suite green. The tripwire's knob only moves at
step 8.

### 1. Correct the tripwire's pessimistic row — **DONE 2026-08-12**
`ship joules` now bounds a grid's worth of *machines* rather than a grid's worth of whole-machine
capacities, via a stated per-tile density (`densestTileCapacity`), and a per-tile-energy row was
added since §2 shows that is the number that actually governs.

The error turned out to be a **family, not a row**: `solid mass` and the flight test's
`heaviestBuildable` were built the same way. Correcting all three **retracts a bug this plan had in
scope**:

- The heaviest buildable vessel is **3.23e9 g against a 4.61e9 g flight budget — 0.7×, inside it.**
  The 17.5×-over figure was the 25× footprint error. There is no ship whose velocity wraps at one
  gram per unit, so **"heaviest-ship velocity wrap" is struck from step 6**; nothing to fix.
- `ship joules` corrected agrees with the 7.56e12 J in §2, which the old form contradicted by the
  same 25×. The two halves of this plan now use one number.
- Flight remains the tightest row in the budget regardless (`velocityX`, safe k ≈ 17). The
  constraint stands; only the claim that it was *already violated* falls.

`NUMERIC_LIMITS.md` §1 and §5 carry the correction. **Test**: the tripwire itself, green at k=1.

### 2. Audit every mass- and energy-dimensioned constant — **DONE 2026-08-12**

`world/Budget.kt` is now the single place that states what one integer means. Every mass- and
energy-dimensioned constant in `commonMain` derives from it:

| constant | was | now |
|---|---|---|
| `Capacity.PACKET_GRAMS` | `1_000_000L` | `100 × KILOGRAM` — the logistics quantum and the belt's throughput |
| `MACHINE_BUFFER_CAP` | `4_000_000L` | `4 × TONNE` — 40 ticks of throughput |
| `MACHINE_OUTPUT_CAP` | `4_000_000L` | `4 × TONNE`, deliberately equal to the input |
| `Storage.CAP` | `20_000_000L` | `20 × TONNE` |
| `Extractor.BUFFER_CAP` | `5_000_000L` | `5 × TONNE` — 50 ticks |
| machine `gramsPerTick` ×4 | `250_000L` / `125_000L` | `PACKET_GRAMS` — one belt-load a tick, all four |
| `Edit.INJECT_GRAMS` | `1000L` | `1 × KILOGRAM` |
| `Material.AIR_FILM` | `20_000L` | `20 × JOULE` — **energy**-dimensioned, so it moves with `MILLIJOULE` |

⚠️ **Buffers are sized in ticks of throughput, not in belt-loads** — and that distinction was learnt
the hard way. Written first as `4 × PACKET_GRAMS`, they shrank tenfold the moment the belt-load went
from a tonne to 100 kg, leaving every machine with two ticks of buffer. A buffer's job is to
decouple a machine from its supply *for a while*, and the unit of "a while" is ticks.

⚠️ **A producer may never out-produce the belt it feeds.** A belt tile holds one packet and a machine
hands over at most one per tick, so belt throughput *is* `PACKET_GRAMS` per tick — the hard ceiling
for every machine. The old hard-coded rates (250/125 kg per tick) were suddenly 2.5× what the belts
could carry, which broke every refinery line silently. All four producers now derive their rate from
the packet, so the invariant is stated rather than coincidental, and `BudgetParityTest` asserts it
for each of them.

Already ratios and needing nothing, which is the pattern: `Negligible` (per-mille of ambient),
`Material.RADIANCE` (a fraction of a hull plate's capacity), `Edit.WATER_INJECT_GRAMS` (a 64th of a
saturated tile).

⚠️ **`Plumbing.MILLIMOLES_PER_TICK` is molar and must NOT move.** A millimole is a particle count. It
is the one constant in the game that *reads* mass-dimensioned and is not, so scaling it with the
masses would make every pump a million times stronger. Called out on the constant itself and in
`Budget`'s "what is deliberately not here", alongside the other dimensionless scales.

**Test**: `BudgetParityTest`, and it is written to **survive step 8** — each assertion divides by the
unit, so it states a fact in grams and joules rather than pinning an integer. Asserting
`PACKET_GRAMS == 1_000_000` would need re-baselining by hand the moment the knob moves, which is the
worst habit a rescale could teach. Asserting *a packet is 100 kg* is true at every scale.

**Dry-run**: `MICROGRAMS_PER_UNIT` was temporarily set to `1` and `BudgetParityTest` stayed green, so
the derivation chain does hold at the target unit. Step 8 is now a one-line change.

**Test literals**: the belt-load change forced part of this early. `PacketTest`'s expectations are
now fractions of `Capacity.PACKET_GRAMS`, and `RailFixtures.ticksToMove` replaces hard-coded tick
budgets in the tests that wait for material to arrive — `run(s, 240)` told a reader nothing about
whether 240 was a deadline, a measurement or a guess. The rest of the ~134 literals stay for step 8.

**Found in passing**: `Save.kt` restated all four machine rates as literals in its load fallback, so
a save without a `rate` field loaded a machine running at whatever the rate was when that function
was written. Third instance of the "caller restates a constant it does not own" family, after the
argon injector and `AirField.mixtureAt`. Now derived from each machine's own default.

**Fixed in passing**: the step-1 tripwire used `String.format`, a JVM-only API, in **commonTest** —
so `compileTestKotlinJs` had been broken since it landed while every JVM run stayed green. Exactly
the trap `reference_common_source_set_jvm_apis` documents. Hand-rolled formatting now; JS compiles.

### 3. Park the energy conservation checks, and let those ledgers rot — **DONE 2026-08-12**
Not a fix — an explicit, temporary surrender, so that a known-overflowing ledger cannot be mistaken
for a real regression while the units move underneath it (§2).

- **Mass conservation stays live throughout.** It is the check most likely to catch a rescaling
  mistake, and it survives the target unit with room to spare.
- **Energy conservation gets parked**, not deleted: `@Ignore` on the tests that assert the joule
  identities, each naming this plan and this step, so the reason is on the test rather than in
  somebody's memory. The HUD's air-heat and heat `balanced` rows go with them.
- Nothing else changes. `storedJoules` and friends keep their current shape and are allowed to
  overflow.

⚠️ This is the step that most needs undoing afterwards. The follow-on that stores divergence (§2)
un-parks all of it; until then the energy ledgers say nothing, and the plan should not pretend
otherwise.
**Test**: the parked list is written down here and checked off by the follow-on.

#### What was done

**One switch, not a dozen annotations**: `EnergyLedgers.PARKED` in commonTest. Flipping it to
`false` un-parks everything below in a single edit, and the suite then names what is still broken.
That shape was chosen because almost none of the affected tests is *about* energy — `GridGrowTest`
grows a grid, `ValveTest` opens a valve — and `@Ignore`-ing them to silence one assertion each would
have taken a large amount of unrelated coverage down with it, against this step's own instruction
that nothing else changes.

**One incidental fix, and it belongs to the same bug family as the rest of this plan.** The
seven-term solid-heat identity was written out **longhand in six places** — the HUD and five test
files. It is now `VesselState.heatBalance`, the named twin of the `airBalance` / `airJouleBalance`
that already existed. This is the third "caller restates something it does not own" found by this
plan, after the argon injector and `Save.kt`'s rate fallbacks; `airJouleBalance` itself exists
because two halves of an identity were summed at separate call sites and only one learned about the
pipes. Parking a thing written down six times is not possible without first writing it down once.

**Parked — the checklist for the follow-on.** Flip `EnergyLedgers.PARKED` and work this list:

| Where | What is parked |
|---|---|
| `HeatTest.assertEnergyBalanced` | both identities; delegates to the switch, called from 3 live tests |
| `HeatTest :: energy is conserved on every tick of a working vessel` | `@Ignore` — the identity *was* the test |
| `GridVentTest :: a shrink vents the joules it discards` | `@Ignore` — every assertion in it is an energy identity |
| `RemappedTest :: airJouleBalance is preserved across remap` | `@Ignore` |
| `RemappedTest :: heatBalance is preserved across remap` | `@Ignore` |
| `GridFitTest` / `GridGrowTest` / `GridFitTriggerTest` `assertBalanced` | the `airJouleBalance` line and the longhand heat identity |
| `GridVentTest` (4 further sites) | `airJouleBalance` |
| `EditorToolsTest :: injected gas is admitted…` | the `airJouleBalance` line only; its mass half still runs |
| `ValveTest` / `PumpTest` `assertBalanced` | the air-energy half only; the air-*mass* half still runs |
| `OutofspaceHud` | the `balanced`/`LEAK` lamps for ENERGY and air heat, replaced by a parked note |

**Still live, deliberately**: every mass identity — `airBalance`, `massBalance`,
`atmosphereGrams`-vs-`baselineAirGrams` — and the momentum identities, which this step never touched.
The HUD keeps its MASS BALANCE lamp and all the energy *readouts*; what was withdrawn is the verdict
on them, since a LEAK lamp lit by the plan rather than by a bug is a lamp nobody looks at again.

**Verification**: 421 tests, the same 8 pre-existing failures as before this step and no others; 4
new skips, exactly the four `@Ignore`s above. `compileTestKotlinJs` green.

### 4. Fix the five multiply-before-divide sites — **DONE 2026-08-12, with two corrections**
- `velocityX`: `vesselImpulse * PER_TILE / mass` — divide first, or lower `PER_TILE`. Fixes the
  heaviest-ship wrap (§5 correction) at the same time.
- `frameAcceleration`: `netImpulse * FRAC_ONE / mass` — same shape.
- `apportion`: `weight * target / sum` — split whole part and remainder, as `gramsPerTileOf`'s loop
  already does. The tightest quadratic term in the codebase.
- `gramsPerTileOf`: the return statement does the naive multiply its own loop avoids.
- Temperature: `joules / capacity` becomes `joules × 1000 / (mass × c)`, because at `Kₘ = 10⁶` a
  single mass unit of uranium has a capacity below 1 and a pre-rounded capacity makes the coldest,
  lightest traces undefined.
**Test**: the tripwire, with `targetMassScale` raised locally per fix.

#### What was done, and where this section was wrong

Three of the five are fixed and are now **scale-invariant** — not merely widened. The other two were
mis-prescribed here, and one of them turned out to be a live bug rather than a margin.

**The prescribed fix does not work for a ratio of two masses.** "Split whole part and remainder, as
`gramsPerTileOf` does" was written for all of them, and it is worth stating why it fails, because the
reasoning is reusable. Splitting moves the worst intermediate from `numerator × scale` to
`denominator × scale`. That is a real gain when the denominator is a *constant* — a density, as in
`gramsPerTileOf`'s loop. It is worth almost nothing when the denominator also carries the mass unit,
because both ends move together: for `velocityX` the entire gain is a factor of the top speed, **two**,
against the 2.4e5 needed.

What works instead is to **reduce the fraction before scaling it** — shift both halves down together
until the scaling cannot overflow. A velocity is a ratio and a ratio is unitless, so this costs
nothing at one gram per unit and holds at any unit thereafter. That is `Flight.scaledRatio`, and it
takes the mass scale out of the expression entirely rather than buying another few orders:

| Site | Before | After |
|---|---|---|
| `velocityX` / `velocityY` (vessel and body) | safe k = **16.7** | scale-invariant |
| `frameAcceleration` | safe k = **780** | scale-invariant |
| `gramsPerTileOf` return | guarded an unstated invariant | scale-invariant, invariant gone |

The precision given up is not real: the denominator keeps ≥33 bits, so the ratio is good to about one
part in 10¹⁰, two orders finer than `PER_TILE` can express. `NumericLimitsTest` measures that rather
than asserting it.

#### ⚠️ Correction 1: `frameAcceleration` was overflowing **today**, at one gram per unit

Not a margin for a future unit — a live bug, and it had a failing test attributed to something else.
`RockContactTest :: a body that lands on the deck settles and stays put` was in the standing list of
"pre-existing failures" this whole plan has been measuring against. It passes now, and bisecting the
three fixes one at a time attributes it to `frameAcceleration` alone.

**Why the tripwire read green over it.** The row bounded the per-tick impulse using
`minTicksToTopSpeed` — a *design* assumption about thrust, giving ~2.2e6. But the same expression is
fed by **collisions**, and a rock landing on the deck delivers at least 4.3e9, which is known exactly
because that is `Long.MAX / FRAC_ONE`, the threshold at which the old form wrapped. The worst case was
understated by three orders of magnitude, so a real overflow sat inside a green row.

The transferable lesson, recorded on `minTicksToTopSpeed`: **a budget row is only as good as the worst
case handed to it**, and any row here whose worst case comes from a design intention rather than a
measurement deserves the same suspicion.

#### ⚠️ Correction 2: the temperature item is already satisfied, and the other one needs machinery

- **Temperature — no change needed.** This section asks for `joules / capacity` to become
  `joules × 1000 / (mass × c)`, on the grounds that at `Kₘ = 10⁶` a single mass unit of uranium has a
  capacity below 1. That was true when this plan was written and **step 2 removed it**: holding
  `Kₑ = 1000·Kₘ` (`Budget.ENERGY_PER_MASS`, guarded by `BudgetParityTest`) makes `capacity =
  mass_units × specificHeat` exact at every scale, with no truncation and no sub-unit capacity.
  Verified that every capacity in the game is extensive — `gasCapacityAt`, `RigidBody.capacity`,
  `Body.capacity` all multiply and never pre-divide; `specificHeatOf`, the one intensive form, has no
  callers. Rewriting these would add a divide and buy nothing.

- **`apportion` is NOT fixed, and cannot be by the method named here.** Both its rows are still red
  (safe k = 152 for a full storage). Splitting is provably a no-op: with `w ≤ sum`, the split on
  `target` leaves `w × (target % sum)`, and in the constrained case `target ≤ sum` that *is*
  `w × target`. Reducing the fraction — the trick that fixed flight — is also wrong here, because
  `apportion` must distribute mass **exactly**: at the target unit a 25-bit reduction leaves each
  share out by tens of grams, and the largest-remainder correction would dump the slop into one
  arbitrary species. Mass conservation would still close while the composition quietly went wrong.

  **So it needs an exact 128-bit `mulDiv`**, which this plan did not anticipate and which is a
  hot-path performance decision (`apportion` runs per species per transfer). Left undone deliberately
  rather than solved by inventing machinery mid-step. **This is the open item for step 4.**

#### Rows still red at `Kₘ = 10⁶` after this step

Run the tripwire with the knob at `1_000_000` to reproduce.

| Row | safe k | Whose problem |
|---|---|---|
| `apportion` × 2 | 152 / 759 | ~~step 4, open~~ → **closed at step 4b, without `mulDiv`** |
| `reducedDensity: packed liquid * SCALE` | 69,100 | step 5 territory (the packing wall) |
| `ambientPressureOf`, `potentialOf` | 95,700 / 327,000 | ~~unscoped~~ → **closed at step 4b** |
| `machine joules: heaviest machine at max kelvin` | 281,000 | **unscoped**, and it is a *stored* quantity, not a ledger — §2's exemption does not cover it |
| `ship joules`, `atmosphere joules` | 1,220 / 1.31e6 | ledgers — out of scope by §2, parked at step 3 ✅ |

**Verification**: 422 tests, **7** pre-existing failures — one fewer than the 8 this plan has been
carrying, being the `RockContactTest` case above. `compileTestKotlinJs` green.

### 4b. Clear the remaining quadratics without 128-bit arithmetic — **DONE 2026-08-12**

Added after step 4 closed with "`apportion` needs an exact 128-bit `mulDiv`". Stu's direction was to
avoid 128-bit **even at the cost of rewriting `apportion` for a similar-but-not-exact outcome**. In
the event no precision was traded away that matters, and no `mulDiv` was written.

**The finding that made it easy.** Step 4 looked at `apportion` alone and concluded the quadratic was
irreducible. Listing all four remaining k² rows together showed they are one expression in four
costumes — a ratio of two same-unit quantities, multiplied by a third. `scaledRatio` already existed
for exactly that. Written up as `NUMERIC_LIMITS.md` §6.5.

| Site | before | after |
|---|---|---|
| `apportion` (Storage / buffer) | k², safe k **152** / 759 | k¹, **4.61e11** / 2.31e12 |
| `ambientPressureOf` | k², safe k 95,700 | k¹, **6.91e12** |
| `potentialOf` | k², safe k 327,000 | k¹, **3.69e15** |

**What was done**

1. `scaledRatio` moved out of `world/Flight.kt` into a new leaf package, `num/Fixed.kt`. Three layers
   need it now and `chem/` must not depend on `world/`. Its KDoc gained the two properties callers
   now rely on — monotonicity, and exactness at the endpoints — stated as a contract, because
   `apportion`'s conservation argument rests on both.
2. **`apportion` rewritten from largest-remainder to a cumulative rule.** `out[i] = f(Σ≤i) - f(Σ<i)`.
   The total telescopes to `f(sum) = target` by construction, so conservation no longer depends on
   the precision of the division — which is what makes it safe to let the mass unit cancel. The
   O(n²) leftover loop is gone. See §6.5 for why largest-remainder specifically could **not** be
   saved by reducing the fraction: it ranks by remainders, and a reduced remainder is noise.
3. `ambientPressureOf`'s species share is now a startup table of billionths, reduced once against a
   constant. §6.5 records why reducing it at the call site instead would have silently destroyed
   trace species.
4. `potentialOf` takes the pressure ratio first. Telescoping is unaffected — it is still one function
   applied per tile, differenced afterwards.

**The behaviour change, stated plainly.** `apportion` splits a tie differently: three equal weights
sharing four units gave `[2,1,1]` and now give `[1,1,2]`. The spare unit goes wherever the running
total crosses an integer rather than to the largest fractional part. Both are legitimate
apportionments. `ChemistryTest :: apportion breaks ties by index…` was the only test that pinned it
and has been reformulated to assert what callers actually rely on — an exact total, no over-draw,
and determinism — with two new property tests: a sweep over spreads and targets, and one asserting
the shares are unchanged when the mass unit moves by 10⁶.

**Verification**: `ChemistryTest` and `NumericLimitsTest` green; `compileTestKotlinJs` green. Full
suite shows **7 pre-existing failures**, unchanged.

⚠️ **Not a step-4b regression, but noted here because it cost an hour**: the working tree carried an
uncommitted one-line change to the extractor in `OutofspaceSim.kt` (`input.mass <= m.gramsPerTick`
in place of `<= 0L`), which discards a partial bite and loses that mass. It fails 15 conservation
tests across `VesselSimTest`, `GaugeTest`, `WiringTest` and `FootprintTest`. Left in place, uncommitted.

**Still red at Kₘ = 10⁶ after this step** — and now *only* the rows no step owns:

| Row | safe k | Whose problem |
|---|---|---|
| `reducedDensity` | 69,100 | step 5 |
| `machine joules` | 281,000 | **unscoped**, and *stored*, so §2 does not exempt it |
| `ship joules`, `atmosphere joules` | 1,220 / 1.31e6 | ledgers — §2, parked at step 3 |

### 5. Fix `reducedPressure` at the packing wall — **DONE 2026-08-12, with one extra**

The margin went in as planned (`MAX_REDUCED_DENSITY = CLOSE_PACKED − SCALE/32`, so `3 − ρr` is never
below 0.03125), but the margin alone would have been a fix sized to an assumption. Written up fully
in `NUMERIC_LIMITS.md` §6.1; the three parts are a density margin, a pressure ceiling **derived from
the `CRITICAL` table rather than from a design temperature**, and making `partialPressure` total.

**The coupling was the disease; the overflow was the symptom.** Before this, the only thing between
"pour water into a tile" and an exception mid-tick was `leastRoomFor` computing exactly the right
volume at every call site. `partialPressure` now clamps reduced density itself, so no caller can
raise one. `reducedPressure` keeps its strict domain — `StateEquationTest :: packing the cell solid
is refused rather than answered` still holds, and deliberately: that contract belongs between the
equation and its internals, not between the equation and a caller holding grams.

**Two things worth remembering.** The ceiling clamps the *result*, not the thermal term: clamp the
numerator and the attraction term goes on growing underneath it, turning an overflow into a
*falling* pressure — the one thing the Maxwell construction exists to prevent. And it is sized from
the largest `c.pressure` any species has rather than from a maximum kelvin, because sizing a bound
from a design intention is exactly what §6.4 caught reading green over a live overflow.

**The extra**: `reducedDensity` was the last non-ledger row still red (safe k 69,100), and it is the
same ratio shape as step 4b — `grams / criticalDensity` is two masses. Taking the ratio first makes
it **scale-invariant**, so the packing wall and the last rescale blocker closed in the same commit.

**Test**: as prescribed — `a tile's pressure never falls as mass is crammed into it, right through
the packing wall`, swept in *grams* to twelve times close packing, every species, at 80/293/700/3000
K. Plus a compatibility test: every value below the ceiling is bit-for-bit what the old expression
produced, so nothing anybody could already represent moved. 426 tests, the standing 7 failures.

**The board at Kₘ = 10⁶ is now three rows, and no arithmetic among them:**

| Row | safe k | |
|---|---|---|
| `machine joules` | 281,000 | step 6b — store per tile |
| `ship joules`, `atmosphere joules` | 1,220 / 1.31e6 | parked ledgers (§2) |

### 6. Small loose ends, while we are in the files — **DONE 2026-08-12**
- ~~The heaviest buildable ship's velocity wraps.~~ **Struck at step 1 — it does not.** It was the
  25× footprint error, not a real edge.
- ~~`Body.kelvin` divides by a capacity it does not guard.~~ **Done.** Now matches `RigidBody.kelvin`:
  falls back to `Temperature.SPACE_KELVIN`, which is what an empty tile already reads.
- ~~`gramsPerTileOf` needs a `require` stating the invariant that keeps it safe.~~ **Neither a
  `require` nor a rescale concern — the normalisation is now built into the function.**
  - The bug was real: `capacityPerTileOf` wrapped, worst case pure water, returning a *negative*
    heat capacity. Pinned by `what a tile costs to warm does not depend on how much of it you were
    handed`, verified to fail against the old expression at −506,386,794.
  - ⚠️ **The first two rationales for it were both wrong**, and the corrections matter more than the
    fix. (1) "The bound is in units, so it divides by k" — **false.** Every caller passes
    *proportions* (`Material.composition`, `RigidBody.oreComposition`, `DEFAULT_ORE_BODY`, all
    summing to ~1000); proportions are not masses and the mass-unit rescale does not touch them.
    This never belonged to this plan. (2) An outer `scaledRatio` was not sufficient either —
    `Σ grams × specificHeat` overflows on its own, before any division can cancel the scale.
  - The fix is to average the specific heat **per species as it accumulates**, in thousandths, so
    nothing in the function grows with the total. Both factors are then bounded by construction
    (1.9e7 × 4.2e6 = 7.8e13) for any mixture, with no invariant left for a caller to uphold.
  - The lesson, which is the general one: *scale-invariant arithmetic is not the same as a stated
    contract.* Making `gramsPerTileOf` scale-invariant at step 4 was read as evidence the "proportions
    only" rule had evaporated. It had not — it was never arithmetic, it was meaning.
- ~~Osmium's `relativeAbundance` is 0.~~ **Now 1** — the smallest the table can say, so the anchor is
  reachable via mined rocks. Honestly documented as *the rarest the scale can express*, not osmium's
  real rarity (a part per billion, which no integer weighting against iron can hold).
  - ⏭️ **The real answer is minerals, not elements.** Ores should be compounds that smelting
    decomposes — iron(II) oxide into iron and oxygen by molar mass — at which point abundance
    becomes a fact about the mineral and stops needing nine orders of magnitude. Not now.
- ~~The design top speed is faster than the sim's speed of sound.~~ **Struck.** There is no design
  top speed. The 2 tiles/tick was `OutofspaceHud.NAV_FULL_SCALE_SPEED`, a HUD constant deciding
  where the nav needle pegs; nothing caps ship speed. The item was mis-framed.
- ~~Audit for more of the "caller enumerates the species it believes a field holds" family.~~ **One
  found**, and it is a copy rather than an enumeration: `OutofspaceSim.DEFAULT_ORE_BODY` restates
  four `relativeAbundance` values by hand. Left as a copy on purpose — it is an *authored* orebody
  that teaches the slag lesson, not a readout of what the world contains — but now says so, and the
  osmium change has already made the two diverge.

### 6b. Store a machine's energy and mass per tile — **SCOPED 2026-08-12, not built**

The last unscoped red row. `machine joules` is `capacityPerTile × thermalTiles × kelvin` held as one
lump on `Machine.joules`; stored per tile instead, the `thermalTiles` factor leaves the expression
and the row becomes one the tripwire already has:

| | worst | headroom |
|---|---|---|
| lump sum, as built | 3.2854e13 | 281,000 |
| per tile | 1.3267e12 | **6.95e6** |

**24.76×**, which is `thermalTiles` — a smelter and an extractor are both 25 tiles. That clears the
4e6 the target needs, so no new row is required: `machine joules` simply *becomes* `tile joules`,
which is already green at 10⁶.

⚠️ **The margin is 1.74×, and the row stays k¹.** This divides a constant by 25; it does not remove
an exponent the way steps 4 and 4b did. It goes red again at k ≈ 1.7e6. Adequate for a microgram,
and the first thing to come back if anything finer is ever wanted.

**Precedent, and the reason this is the right shape.** `AirField` already stores `grams` and
`joules` as per-tile `LongArray`s. The machine is the outlier, not the proposal — and that is also
why `atmosphere joules` and `ship joules` need no fix: those are *sums somebody computes*, never
numbers anything stores, which is exactly what §2 exempts. **After this and step 5, nothing is red
at 10⁶ that is not a parked ledger.**

**This is a data-model change, not an arithmetic one**, and it is being taken deliberately:
machines should be a real part of the world that interacts with the simulation as a set of adjacent
tiles, rather than a lump with a single temperature. So it carries work the earlier steps did not:

- `Machine.joules: Long` → per-tile storage over the machine's footprint; heat exchange, save format
  (another migration) and the HUD all follow.
- **A machine stops being isothermal** and needs conduction *between its own tiles*, which does not
  exist today. That is the substantive part, and the interesting one for a game whose stated purpose
  is being a vehicle for heat simulation: a smelter acquires a hot face and a cool one.

Sequenced after step 5 and sized separately. It is the only remaining item that changes what the
simulation *does* rather than how it computes it.

### 7. Save migration
Species are already stored by name, so composition changes are free. Masses and energies are not:
bump `Save.VERSION` and multiply on read from any older version. The migration must know which
fields are fine-unit and which are coarse (step 3).
**Test**: a fixture save from the current version loads and produces an identical world to one built
natively.

### 8. Turn the knob
Set `targetMassScale` to 10⁶ in `NumericLimitsTest` and make it green. Then flip the real unit,
re-measure the peaks from `NUMERIC_LIMITS.md` §10, and update both documents in the same commit.

### 9. Re-derive what the new floor buys
With the unit changed, the diffusion stranding floor is 5 units = 5 pg, i.e. 5e-12 of a tile-load.
Restate `Negligible` against it — it is already a fraction of ambient, so it should need no numeric
change at all, which is the test of whether it was defined correctly.

---

## 5. Options not taken, and why they stay on the table

**Carrying the diffusion remainder** instead of flooring it. The stranding floor is 5 units because
`count × FACE_SHARE / SLOTS` floors to zero below `SLOTS/FACE_SHARE`; the largest-remainder
technique `apportion` already uses would eliminate stranding **entirely, at any scale**.

That is a better answer to "make negligible negligible" than any amount of `k`, and it is cheaper.
It is not in this plan because it changes conservation-sensitive transport code and deserves its own
increment with its own measurements — and because the two are complementary rather than alternatives:
the rescale fixes what a *unit* means, remainder-carrying fixes what *flooring* does to it.

Worth doing straight after, and worth remembering if step 8 turns out harder than it looks.

**Divergence ledgers** (§2) are the other one, and they are the higher priority of the two, since
step 3 deliberately blinds half the conservation checking until they land.

---

## 6. Risks

- **The parked energy checks (step 3) are a real loss of cover, for the duration.** Half the
  conservation net is off while the units move. Mass conservation carries the load; anything the
  energy identities would have caught has to be caught by the per-step tests instead, and the
  divergence follow-on should be done sooner rather than later for exactly this reason.
- **Rate constants are the quiet ones.** `Plumbing.MILLIMOLES_PER_TICK` and friends are stated in
  absolute units, so a rescale changes what they *mean* without changing what they *say*. Step 2 is
  the whole defence.
- **The 8 pre-existing test failures** (rock contact, flight acceleration, processor purity) are
  unrelated to this work and were failing before it started. They must not be allowed to absorb a
  real regression: compare against the recorded baseline, by test name, at every step.
- **`Frac` bounds accelerations near ±1.41 regardless of mass units** (`NUMERIC_LIMITS.md` §4). If
  anything in the flight rework pushes an acceleration past that, no mass unit will save it.
