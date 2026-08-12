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

### 3. Park the energy conservation checks, and let those ledgers rot
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

### 4. Fix the five multiply-before-divide sites
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

### 5. Fix `reducedPressure` at the packing wall
Independent of the rescale but in the same arithmetic. Clamp density short of `CLOSE_PACKED` by a
**margin** rather than by one unit, so `3 − ρr` cannot be 1 and the thermal term cannot reach 1e17.
Three of four species currently wrap; the symptom is a pressure that flips sign above close packing
and a solver that pushes gas *into* an over-full tile.
**Test**: pressure is monotonic in mass across and beyond the packing limit, for every species —
which is the assertion the survey's measurements should have been.

### 6. Small loose ends, while we are in the files
- ~~The heaviest buildable ship's velocity wraps.~~ **Struck at step 1 — it does not.** It was the
  25× footprint error, not a real edge.
- `Body.kelvin` divides by a capacity it does not guard; `RigidBody.kelvin` does. Match them.
- `gramsPerTileOf` needs a `require` stating the invariant that keeps it safe (per-mille
  compositions only) — it currently holds by convention across two call sites and nothing says so.
- Osmium's `relativeAbundance` is 0, so nothing can obtain it. Decide: anchor-only, or a very small
  abundance, which would be truer to a metal that is genuinely among the rarest in the crust.
- The design top speed (2 tiles/tick) is faster than the sim's own speed of sound (0.25 tiles/tick,
  CFL-pinned). Not wrong, but state it on purpose or fix it.
- Audit for more of the **"caller enumerates the species it believes a field holds"** family. Argon
  found one in the injector; `AirField.mixtureAt` documents an earlier one. There will be others,
  and they are invisible until a species is added.

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
