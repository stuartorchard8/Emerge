# Liquid vs vapour velocity — the handoff

*Written 2026-08-07 at the end of a long session, deliberately self-contained. Commits `e3e13192`
(Maxwell construction) and `c9104703` (water injector). **Read §1 and §2 and you have everything;
nothing here needs re-deriving.***

---

> ## ⚠️ Update 2026-08-07 — §2's headline measurement was attributing the wrong cause
>
> The runaway evaporation this document was written to explain was **mostly `applySpeciesDrift`, not
> the shared velocity field.** Measured by rerunning the same pool with the drift pass varied and
> nothing else changed:
>
> | pool tile, tick 20 | |
> |---|---|
> | drift on all mass (as written) | 165,252 g |
> | drift on the vapour only | **622,407 g** |
> | drift off entirely | 615,844 g |
>
> §2's note that "excluding water from drift entirely barely changed the decay" does not reproduce;
> excluding it is very nearly the whole effect. Two mechanisms, both measured:
>
> 1. `mixing()` differences *concentration* across the surface of the pool. A pool is the steepest
>    concentration gradient there is, so Fick's law dissolved it at ~22 kg per face per tick. Fick's
>    law describes a mixture, and a pool under an atmosphere is not a mixture — it is two phases with
>    an interface.
> 2. Drift moves mass without the energy on it. That is the sub-kelvin settling term the code comment
>    claims *for gases*, and it is not one for water: the first tick took a receiving tile from 230 K
>    to 55 K, which condensed everything in it, took its pressure to **zero**, and manufactured a
>    607-atmosphere gradient from nothing for `applyPressureForce` to act on at full whack.
>
> **Fixed** by `vapourGrams` (`chem/Saturation.kt`): drift plans against each cell's vapour mass and
> applies the moves to real grams — phase stays a reading, not a second place mass is stored. Whole
> suite green; a no-op wherever nothing is condensing, which is everything the vessel carries today.
> `BoilingTest.a cold pool stays put` pins it, and the latent-heat test now runs too.
>
> **§3 onward stands, and §1's thesis is still right — it is just the *second* half.** With drift
> fixed, a pool heated to 300 K sits at its own saturation pressure (5.7 atm against a room near 1)
> and **still will not boil off**: over 60 ticks the peak cell goes 624,729 → 633,999 g. That is the
> shared velocity field, cleanly isolated now that nothing else is competing with it, and it is the
> increment this document describes. The measurement in §2 to work from is that one, not the
> 46,764 g/tick figure below.

> ## ⛔ Update 2026-08-07b — two cheap formulations tried and BOTH REJECTED. Read this before coding.
>
> The attractive shortcut is to keep one mixture momentum field and derive per-phase velocities from
> it, so the momentum path in §4 never has to be touched. **It does not work, and the reason is the
> same both times.** Measured against the same cold pool, twenty ticks, freefall:
>
> | pool tile | |
> |---|---|
> | no phase split (today) | **622,166 g** |
> | split the accumulated momentum by volume | 314,079 g |
> | split this tick's pressure *impulse* by volume (drift-flux slip) | 548,653 g |
>
> Both are *worse than doing nothing*, and neither made the hot pool boil off.
>
> **Attempt 1 — split the momentum `p` by volume, `v_k = α_k·p/m_k`.** Momentum on a face is an
> *accumulation*, and around a pool nearly all of it was earned by the liquid's inertia. Handing a
> 259 g vapour pocket 12% of the momentum of 630 kg of water gives it hundreds of tiles per tick, so
> it saturates CFL and is expelled *entirely, every tick*. The cell can then never accumulate the
> nitrogen whose partial pressure was what balanced it, so the pool bleeds out. A CFL clamp does not
> save it — saturating the clamp *is* the failure.
>
> **Attempt 2 — split the impulse `J` instead, `Δv_k = α_k·J/m_k`, add only the slip.** Correct
> physics (`-∇p` is a force per unit volume, so this really is `F = ma` twice) and it fixed the
> runaway magnitude, but it still leaked. Same root cause.
>
> ### ⚠️ The root cause, measured, and it defeats any single-momentum scheme
>
> **A phase's volume fraction has no meaning at a face.** At the pool/room interface:
>
> ```
> pool tile   gas = 0 g       gasShare =            39   (of SCALE = 100,000,000)
> room tile   gas = 1000 g    gasShare = 100,000,000
> face mean   gas = 500 g     gasShare =    50,000,019   ← "half gas", 500 g of it
> ```
>
> The face between a solid pool and a room claims to be a half-open gas channel holding 500 g, when
> one side has **none**. Every per-phase face velocity divides by that fiction. Using *donor* values
> instead removes the fiction but then a face has two different velocities depending on which phase
> is asking, which is not a face velocity at all and breaks the staggered-grid discretisation.
>
> **Conclusion: the phases need genuinely separate momenta on the faces** — `mxGas/myGas` and
> `mxLiquid/myLiquid` — so that each phase's velocity comes from its own accumulated momentum divided
> by its own mass, and no volume fraction is ever evaluated at a face. That is the §4 blast radius,
> and it is not avoidable. Budget for `applyPressureForce`, `applyBuoyancy`, `Projection`,
> `advectMomentum` and `subStepsFor` all becoming per-phase, plus the save format if the fields
> persist (they may not have to — see whether one tick of relaxation is enough).
>
> **Landed from this attempt** (`Pressure.kt`): `tilePressure` used to *throw* when gas was crammed
> into a nearly-solid-liquid cell, despite its own comment promising "merely a very large pressure,
> which is both finite". Now clamped at close packing. Pinned by `VolumeTest`. Unreachable before,
> reachable as soon as anything pushes gas at a pool.

## 1. Where things stand in one paragraph

Phase transitions emerge from van der Waals, and the instability that parked this — the falling
stretch of the isotherm, where `dP/dρ < 0` and no timestep can help — is **fixed**, by the Maxwell
construction in `chem/Saturation.kt`. Pools no longer explode. What they do instead is **evaporate
away far too fast**, and that has a single measured cause: *every phase shares one velocity field*.
Fixing that is the next increment and is what this document is for.

You can now pour water into the running game (`Q` to the `WATER` tool) and watch it happen.

## 2. The measurement that defines the work

A cell holding a 50%-full pool at 230 K, in freefall:

| | |
|---|---|
| total water in the cell | 353,186 g |
| of which is **vapour**, by the lever rule | **259 g** |
| exported in the **first tick** | **46,764 g** |

**The solver moves 180× the entire vapour content of the cell, in one tick.** Over 12 ticks it
sheds 224 kg. Saturating every one of the room's 36 tiles takes about **19 kg** — so it is not a
tuning error, it is a structural omission.

### Why, precisely

`MomentumField` gives `velocity = momentum / total mass`. In a two-phase cell that single number is
wrong for both phases at once:

- the **liquid** gets a velocity it should not have (a pool at rest should have ~none), and
- the **vapour** gets one ~1000× too *low*, because it is sharing inertia with 353 kg of liquid it
  is not attached to.

`advectMass` then moves every species at that one speed, so the pool is advected as though it were
gas. `applySpeciesDrift` compounds it by mixing down the concentration gradient — a pool is a
maximal concentration gradient — though drift is the smaller term (measured: excluding water from
drift entirely barely changed the decay).

### What the fix has to produce

Give the phases separate momenta and the behaviour comes out on its own: the pressure impulse lands
on the vapour, which has almost no inertia, so it moves fast and carries its ~259 g out; the room
saturates within a few ticks; the gradient vanishes; **the pool stops losing mass because the
driving force is gone, not because a rule forbids it.**

⚠️ **Do not implement this as a flux cap.** Capping the advected mass at the vapour share asserts
"the liquid velocity is zero", which is right for a pool and wrong the moment water should flow
down a pipe — and then you need a second rule for when liquid *may* move, and a third for the
boundary. Stu flagged this risk explicitly and he is right. The two-fluid / drift-flux formulation
has one closure in it (interphase drag) rather than an open-ended list, and even the zero-drag limit
behaves correctly — drag only sets how fast the phases equilibrate.

**There is a precedent to build on rather than fight:** `applySpeciesDrift` already moves species
*relative to the mixture* for physical reasons (heavier settles, Fick's law). That is a drift-flux
relative-velocity term in all but name. This extends a concept the codebase already accepts.

## 3. What you already have to build it from

- `liquidFraction(densityR, temperatureR)` — volume fraction of liquid, lever rule, in `SCALE`.
- `saturatedLiquidDensity` / `saturatedVapourDensity` / `saturationPressure` — the dome, one table
  for every fluid.
- `liquidVolumeFraction(grams, species, volume, full, kelvin)` — the same, from raw grams.
- Per-phase masses follow from those and need no new constants.

## 4. ⚠️ Risk, and why this was left for a fresh session

It touches the **momentum path**, which everything else stands on: `applyPressureForce`,
`applyBuoyancy`, `Projection`, `advectMass`, `MomentumAdvection`, and the CFL sub-stepping in
`subStepsFor`. That is a materially bigger blast radius than the last two changes, both of which
were confined to the pressure function and provably no-ops outside the saturation dome.

The invariants that must survive, all already asserted by the suite: `airBalance == 0`,
`airJouleBalance`, parallel==sequential, and the vessel-momentum ledger (`undeliveredX/Y` measures
the discretisation error in thrust and should not grow).

## 5. Gravity is a SECOND, separate problem — do not conflate them

705 kg of liquid pressed against a hull needs an exact normal force to sit still; the explicit
projection turns the unbalanced momentum sideways instead. With gravity on, the same pool spreads
across 25 tiles in 20 ticks; in freefall it holds its shape and thins slowly. `BoilingTest` runs in
`FREEFALL` **on purpose**, so that hydrostatics does not mask the phase behaviour. Solve the
velocity question first, in freefall, and only then turn gravity back on.

## 6. Also still open, and genuinely independent

- **Peng-Robinson.** Van der Waals has no acentric factor, so model water boils near **−33 °C**
  (Psat 4.9 atm at 293 K against a real 0.023). This is why the injector delivers water at 230 K.
  Still cubic, drops into the same slot, ω is measured not tuned, and it would **not** disturb the
  Maxwell machinery — the equal-area solve is generic, only the table's numbers change. See
  `PLAN_phase_transitions.md` §5c.
- **The latent-heat ledger.** `stepFluid(latentHeat = false)` still. Turning it on needs
  `thermal + cohesion + vented − fromSolid`. And `cohesionJoules` is knowingly wrong *inside* the
  dome — it wants the lever-rule mixture, not the attraction of the mean density, which is what
  makes latent heat come out linear in the fraction boiled. Both are documented at the function.
- **The near-critical cusp.** `ρ_liq`/`ρ_vap` carry up to 8% error near `Tc`, because the dome closes
  as `√(1 − Tr)` and a table sampled evenly in `Tr` cannot follow a cusp. Sample evenly in
  `√(1 − Tr)` if it ever matters. Pressure is unaffected.

## 7. How to see it yourself in thirty seconds

```
./gradlew :apps:outofspace:desktop:outofspaceAgent --args="apps/outofspace/agent-scripts/water.txt out"
```

Or in the game: `Q` to `WATER`, hold over a tile. `INJECTED` climbs, `MASS BALANCE` stays
`BALANCED`, and the DENSITY overlay shows the pool plainly.
