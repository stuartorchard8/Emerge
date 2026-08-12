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

That is a comfortable thing to change, because **the ledgers are diagnostics, not simulation state**.
`extractedGrams`, `ventedGrams`, `storedJoules`, `generatedJoules` and their siblings exist so
conservation can be checked across the world; nothing reads them back into the physics. They can
therefore be held in a **coarser unit than the per-tile fields** — tiles in nanojoules, totals in
millijoules — which costs nothing real and buys the entire gap. It also retires §6.3, the monotonic
accumulators that grow with playtime rather than with world state.

⚠️ **`NumericLimitsTest`'s `ship joules` row is 25× pessimistic** — it multiplies a whole machine's
capacity by every tile in the grid, when a grid holds 230 smelters rather than 5760. Correcting it is
step 1, because as written it is the row most likely to block the target for a reason that is not
real.

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

### 1. Correct the tripwire's pessimistic row
`ship joules` should bound a grid's worth of *machines*, not a grid's worth of whole-machine
capacities. Also add a per-tile-energy row, since §2 shows that is the number that actually governs.
**Test**: the tripwire itself.

### 2. Audit every mass- and energy-dimensioned constant
Find them all, and make each one *derived from the budget* rather than a literal that happens to be
right. Known already: `Storage.CAP`, `MACHINE_BUFFER_CAP`, `MACHINE_OUTPUT_CAP`,
`Extractors.BUFFER_CAP`, `Packet.PACKET_GRAMS`, `Edit.INJECT_GRAMS`, `Edit.WATER_INJECT_GRAMS`,
`Plumbing.MILLIMOLES_PER_TICK`, `Material.AIR_FILM`, plus gram literals in tests.
`Negligible` is already a fraction of ambient and needs nothing — **that is the pattern the others
should follow**, and where a constant can be expressed as a fraction of something physical it
should be, rather than rescaled.
**Test**: a test asserting no surviving literal is silently mass-dimensioned is not really
expressible; instead each constant gets its derivation in a comment, and step 8 is the check.

### 3. Move the ledgers to a coarser unit
The unblocking step. Per-tile fields go to the fine unit; `extractedGrams`, `ventedGrams`,
`airVentedGrams`, `injectedAirGrams`, `storedJoules`, `generatedJoules`, `radiatedJoules`,
`solidToAirJoules`, `baselineJoules` and their air counterparts hold coarse units. The conversion
happens at exactly one place per ledger, on the way in.
⚠️ The balance identities must be re-derived, not merely re-scaled: a sum of coarse units compared
against a sum of fine ones is the exact failure mode these ledgers exist to catch. Every `balanced`
check in the HUD and every conservation test is a consumer.
**Test**: existing conservation tests, plus one that runs a world long enough for a ledger to exceed
what the fine unit could have held.

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

## 5. The option not taken, and why it stays on the table

**Carrying the diffusion remainder** instead of flooring it. The stranding floor is 5 units because
`count × FACE_SHARE / SLOTS` floors to zero below `SLOTS/FACE_SHARE`; the largest-remainder
technique `apportion` already uses would eliminate stranding **entirely, at any scale**.

That is a better answer to "make negligible negligible" than any amount of `k`, and it is cheaper.
It is not in this plan because it changes conservation-sensitive transport code and deserves its own
increment with its own measurements — and because the two are complementary rather than alternatives:
the rescale fixes what a *unit* means, remainder-carrying fixes what *flooring* does to it.

Worth doing straight after, and worth remembering if step 8 turns out harder than it looks.

---

## 6. Risks

- **The ledger unit split (step 3) is the one that can silently corrupt.** A coarse total compared
  against a fine sum reads as a leak, or worse, reads as balanced when it is not. It gets the most
  test attention.
- **Rate constants are the quiet ones.** `Plumbing.MILLIMOLES_PER_TICK` and friends are stated in
  absolute units, so a rescale changes what they *mean* without changing what they *say*. Step 2 is
  the whole defence.
- **The 8 pre-existing test failures** (rock contact, flight acceleration, processor purity) are
  unrelated to this work and were failing before it started. They must not be allowed to absorb a
  real regression: compare against the recorded baseline, by test name, at every step.
- **`Frac` bounds accelerations near ±1.41 regardless of mass units** (`NUMERIC_LIMITS.md` §4). If
  anything in the flight rework pushes an acceleration past that, no mass unit will save it.
