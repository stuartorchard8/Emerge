# Phase transitions, and the liquid that will not step

*2026-08-07. Commit `4aa5482b`. Equation of state **built and live**; liquids **blocked**, with the
blocker measured. Read §1 and §5 first if you are picking this up cold.*

---

## 1. Where this came from, and what it settled

The question was whether to keep building on the grid or port a particle fluid sim (Lague's, at
`~/seb-fluid`). Stu's instinct was that a particle system would have "the right level of
expressiveness" for phase transitions, and that hard-coding a boiling point felt wrong because the
relationship between temperature and pressure is genuinely complicated.

Two things came out of this, and they point in opposite directions. Both are load-bearing.

**The emergence does not need particles.** It comes from the *equation of state*, not from the
discretisation. Lagrangian versus Eulerian is orthogonal to whether a phase transition appears.
Lague's sim would not give boiling either — its `PressureFromDensity` (`FluidSim2D.compute:106`) is a
stiff single-rest-density liquid model with no gas branch. Getting boiling out of SPH would require
the same change made here. That is now proven rather than argued: see §2.

**The liquid does need particles, or something very like them.** The density ratio between liquid
water and its vapour is about 50,000:1, and the liquid branch is roughly 30,000× stiffer than the
gas. An explicit compressible Eulerian solver cannot carry that. This is measured, not estimated,
and it is §5.

So the synthesis is a hybrid: Eulerian gas, Lagrangian liquid, **one shared equation of state**. The
work in this commit is not sunk cost if the particle route is taken — `reducedPressure` is exactly
the piece SPH needs to have phase behaviour at all, and it drops into Lague's sim roughly
line-for-line.

---

## 2. What was built

`chem/StateEquation.kt` — van der Waals in **reduced** units:

```
Pr = 8·Tr·ρr / (3 − ρr)  −  3·ρr²
```

Ideal gas has no liquid phase and cannot have one: molecules with no size and no mutual attraction
have nothing to condense with. Van der Waals adds exactly those two things — excluded volume (the
`3 − ρr` denominator, which is why nothing can be packed past `CLOSE_PACKED`) and attraction (the
`3·ρr²` term). Everything else is consequence.

### Why reduced units, and why it matters more than it looks

- **The equation is species-independent.** Law of corresponding states: water and nitrogen obey the
  same curve and differ *only* in where their critical point sits. There is no per-fluid phase
  behaviour to author, and no way to tune one fluid into a transition another cannot have. This is
  the strongest possible answer to "without hard-coding it".
- **It fits in a `Long`.** The textbook form's `a·n²/V²` squares a mole count already in the
  billions once a tile holds liquid, and overflows before it means anything. Reduced quantities live
  in `[0, 3)`.

### What arrives without being written down

| | how |
|---|---|
| boiling curve | the shape of the isotherm; Clausius–Clapeyron is derived, not tabulated |
| latent heat | `cohesionJoules` — the *same* `3·ρr²` term × volume. No heat of vaporisation is stated anywhere |
| critical point | above `Tc` the falling stretch does not exist, so there is no liquid phase at any pressure |
| partial-pressure coupling | `tilePressure` sums per-species pressures, so water must push against everything else in the room |

### Phase is a reading, never stored

`FluidPhase` / `phaseAt`, computed from density and temperature on demand — the same way `kelvin` is
computed from joules and capacity rather than kept beside them. A species does not have a phase; a
cell does, and only while its conditions hold. `FluidPhase.Separating` is a real state (mid-boil),
not an error.

**The proof test** (`PhaseEmergenceTest`): nitrogen is declared `Phase.Gas`, lives in
`Species.GASES`, and is called a gas throughout the codebase — none of which reaches the
calculation. Cool it to 80 K and it lands on the liquid branch. Water mirrors it: liquid at 400 K,
supercritical at 700 K.

---

## 3. Constants, and the one that is derived

⚠️ **`Tc`, `Pc` and critical density are over-determined.** Van der Waals *forces*
`Pc·vc/(R·Tc) = 3/8`; real fluids measure ~0.29. You can honour any two. `Critical` takes
temperature and density — they are what place the transition in the state space the solver moves
through — and **derives** `Pc`. Supplying a measured critical pressure alongside them would not be
more accurate, it would be inconsistent, and the inconsistency would show up as a fluid whose
boiling curve disagrees with its own density. The cost is that critical pressures come out ~30%
high, which is the honest price of a two-constant equation of state.

⚠️ **`SCALE = 100_000_000`.** At a million, 13 g of CO₂ in a tile quantised to a reduced density of
`33` — two significant figures — and a rarer species would have rounded to zero and stopped exerting
pressure entirely. A hundred million is the largest round value that keeps `8·Tr·ρr` inside a `Long`
at the extremes.

⚠️ **`TILE_LITRES = 830`** is the only place SI units touch the vessel. It is *derived*, not
declared: `AMBIENT_AIR` puts 1 kg of air in a full tile and calls it one atmosphere at room
temperature, which pins the tile at ~830 L. It exists solely to land critical densities quoted in
kg/m³.

⚠️ **Van der Waals cannot hold liquid water at its real density.** Real liquid water is `ρr = 3.1`,
past `CLOSE_PACKED = 3.0`. The model tops out at 966 kg/m³ against a real 998, and the stable liquid
branch at room temperature begins around `ρr = 2.1`. Twice critical density is *not* liquid — it is
still inside the unstable band. Reach for 2.5, not 2.0.

---

## 4. What changed in the solver

- `tilePressure` is van der Waals, with an ideal-gas fallback for species with no critical point.
  **The old behaviour is its sparse limit** — agreement within 1‰ at ambient density — which is what
  made the swap safe rather than a rewrite of every pressure in the game.
- `Species.GASES` → `Species.FLUIDS` across advection, drift, tile mass, gas capacity and interlayer
  transfer, so water participates everywhere. A no-op today: nothing in the world has any water.
- `applyBuoyancy` **inverts the equation of state with Newton** (`ambientMassAtPressure`) instead of
  a single multiply. "Ordinary air at the same pressure" is an inverse EOS, and a multiply can only
  invert a straight line. Under the old law it was exact; under this one it left a standing impulse
  under every cell in a pipe (measured: no drift at full tile volume, 0.3% per 500 ticks at
  `FULL/8`). Two Newton steps, from the old linear answer as the starting guess — which is exact
  wherever the gas is thin, i.e. most of the vessel.

⚠️ **Anything that changes how pressure relates to density must change `applyBuoyancy` too**, or it
silently puts a permanent force under the whole ship.

---

## 5. ⛔ The blocker — measured, do not re-derive

At 293 K, against an ambient pressure of 34,495:

```
 ρr      grams        water pressure      × ambient
 2.50    668,150         -7,814,958         -226
 2.55    681,513        +12,566,910         +364
 2.60    694,876        +40,188,169        +1165
```

**A 2% density change swings pressure by roughly 590 atmospheres.** Liquid water balances against a
one-atmosphere room on a knife-edge near `ρr ≈ 2.5075`. Saturated vapour at the same temperature is
near `ρr ≈ 0.00005`.

So the two phases the model is meant to move *between* are ~50,000× apart in density, and the liquid
one is ~30,000× stiffer than the gas. A pool started at `ρr = 2.50` is under 226 atmospheres of
tension, and the solver tears it apart on the first tick: the water disperses into the unstable
band, cohesion energy swings by more than the tile's entire thermal budget, and `cohesionUnpaid`
comes back at 9.1e10.

**None of this is the equation of state being wrong.** The vapour branch, probed over the same
range, is smooth and steps fine. It is specifically that an explicit compressible Eulerian scheme
cannot carry a liquid.

`BoilingTest` is written, `@Ignore`d, and is the right target — the sequence it describes (pool →
heat → spreads → cool → gathers, with nitrogen present, mass conserved) is still what success looks
like.

---

## 6. Also parked

**Latent heat is opt-in and off** — `stepFluid(..., latentHeat = false)`. Off is bit-identical to
every tick simulated before it existed, the same courtesy `volumes` and `gasJoules` each extend.

Switching it on breaks 19 ledger tests **correctly**: cohesion is a third energy reservoir, and from
`airJouleBalance`'s point of view the joules simply vanish. The ledger has to become
`thermal + cohesion + vented − fromSolid` before this can default on. That was deliberately not
bundled in, because the feature it serves cannot be exercised end to end until §5 is solved, and a
conservation guarantee should not be reopened for something that cannot yet be checked.

---

## 7. Three ways forward

1. **Port Lague's 2D sim, carrying this EOS across.** Answers the original question (what does CPU
   Kotlin manage at out-of-space scale?) *and* gets boiling, because a particle carries its own
   density and 50,000:1 costs it nothing. `reducedPressure` replaces `PressureFromDensity`. Note the
   spatial hash is a GPU design — on CPU use a counting-sort bucket grid, which is simpler than the
   bitonic sort, not harder.
2. **Make the dense phase incompressible** — implicit pressure solve for the liquid, explicit for
   the gas, with a per-tile liquid volume fraction. Keeps everything on the grid. Well-trodden
   (roughly how reservoir simulation works) but a genuine piece of work.
3. **Teach the ledger the third term** and turn latent heat on. Independent of the above, but of
   limited value until one of them lands, since boiling is what it exists to pay for.

Option 1 is the recommendation, and it is now a measurement rather than a preference.

---

## 8. Test state at this commit

`:apps:outofspace:core:jvmTest` — **447 tests, 5 skipped, 0 failures.** JS target compiles.

The 5 skipped: 3 pre-existing, plus `BoilingTest`'s two, parked on §5.

⚠️ `:apps:drockets:core` has **2 pre-existing failures** unrelated to any of this
(`DrocketsSoaEquivalenceTest`, `DrocketsWorldRoundTripTest`, both about in-gestation spawn). Drockets
depends only on engine modules, never on out-of-space. `./gradlew build` is red because of them, and
was before this work started.
