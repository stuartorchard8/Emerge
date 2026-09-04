# Chemical rockets, and the electrolyzer that feeds them

Status: **planned, nothing built** (2026-09-04). Two machines, one new buffer role, one widened
stream vocabulary. All of the chemistry already exists and none of it needs writing.

A thruster stops being a hole that dumps warm water and becomes a rocket that *makes its own heat*:
two inputs at a ratio the player dials, a chamber that ignites them, and an exhaust whose velocity
comes out of the reaction rather than out of a thermostat. The electrolyzer is what makes it
feedable — the one machine in the game that splits water back into things worth burning.

> A cold water thruster is worth **1040 m/s**. Burn hydrogen fuel-rich and it is **4247**. That
> factor of four is the whole plan, and — this is the part that surprised us — **almost none of it
> is energy**. It is molar mass.

---

## 1. Why this and not a reactor

The obvious alternative was a hot-gas thruster: one propellant, a big element, no ratio, no
ignition. Structurally simpler, and it is the wrong one to build first for a reason that took a
measurement to see.

**Heating water cannot get you past ~3600 m/s at any temperature**, because `v_e = √(K·R·T/M)` and
water's M is stuck at 18. Burning hydrogen fuel-rich reaches 4247 m/s at a chamber *cooler* than
that, because unburnt H₂ drags the mean molar mass to 6 faster than the lost enthalpy costs:

| H₂:O₂ by mass | unburnt H₂ | chamber | M̄ g/mol | v_e |
|---|---|---|---|---|
| 1:8 (stoichiometric) | 0% | 3508 K | 18.0 | 3600 |
| 1:6 | 3.6% | 3146 K | 14.0 | 3805 |
| 1:4 | 10% | 2623 K | 10.0 | 4044 |
| **1:2** | **25%** | **1795 K** | **6.0** | **4247** |
| 1:1.5 | 32.5% | 1508 K | 5.0 | 4245 |

Computed against the game's own `Species` table (cp, molar mass, `adiabaticK`) and the corrected
hydrogen enthalpy from `6f1ff780`. The peak sits more fuel-rich than reality's O/F ≈ 6 because there
is no dissociation and cp does not vary with temperature; the peak *value* lands within 4% of a real
LH₂/LOX vacuum figure, which is closer than this model has any right to be.

⚠️ **The ratio dial is therefore not a nicety — it is the only way to reach the low-M̄ regime at
all.** A bipropellant engine with a fixed stoichiometric mix would be worth 3600, which a reactor
could match by heating water. The dial is the mechanic.

### ⛔ The energy is free, and pretending otherwise would be a lie

`Work.heatBuffer` (`OutofspaceSim.kt:1996`) does `generatedEnergy += energy`. A furnace element mints
its joules; the ledger closes because there is a term for it, but nothing in the world pays. There is
no power system — the only `watt` in the codebase is `milliWattsPerMetreKelvin`, a conductivity.

So the chain water → electrolyzer → rocket → water is **minted energy laundered through chemistry**,
and it is energetically the same free lunch a big-element hot-gas thruster would be. That is a known
gap, recorded here rather than designed around:

- It does **not** invalidate the plan, because what this buys is molar mass and not joules. No amount
  of free heat gets water below M = 18.
- The binding constraint today is machines, deck space and throughput, exactly as it already is for
  the furnace.
- A power grid is what eventually closes it, and it gates the ion drive entirely. Out of scope.

⚠️ **Build the chemical rocket before the reactor.** A hot-gas thruster arriving first would be the
free-lunch version of the same mechanic and would make combustion pointless before combustion exists.

## 2. What already works and must not be rebuilt

Verified by reading, 2026-09-04:

- **A store reacts with itself.** The chemistry pass runs over `listOf(w.rail.stuff,
  w.buffers.stuff)` (`OutofspaceSim.kt:613`), and a machine's stores live in the buffer layer. So
  **combustion in a chamber already happens** — deliver a blended packet to a store over 773 K and
  `2 H₂ + O₂ → 2 H₂O` fires, today, with no new code. `applyStoreEnthalpy` puts the released energy
  back into that store, so the next tick's `exhaustVelocity` reads the hotter number.
- **`exhaustVelocity` is mole-weighted and needs nothing.** K is the exact mole-weighted mean, so a
  chamber holding water *and* leftover hydrogen is priced correctly with no averaging anywhere.
- **`Thruster.bell(grid)` is `grid.neighbour(center, facing)`**, which for `reach == 1` lands on the
  front-centre tile of a 3×3. `exhaustPath` walks outward from there. ✅ **The whole exhaust path
  works unchanged on a 3×3** — this was the thing most likely to need new geometry and does not.
- **Two outputs are precedented.** The concentrator already has Product and Waste ports on different
  faces.
- **Demand already routes by species.** A motor's fluid appetite is stated as one `Acceptance` per
  fluid at its port tile (`OutofspaceSim.kt:3990`); two ports stating two different filters is the
  same machinery twice.

## 3. Streams: the vocabulary has to widen first

⛔ **`Stream { Product, Waste }` assumes one of two outputs is the good one.** The concentrator earns
that — concentrate out the front, tailings out of the floor. **Neither new machine does.** An
electrolyzer's oxygen is not waste, and a rocket's oxidiser is not a lesser input.

This is the first step because both machines' ports and both machines' stores are named through it,
and doing it after would be a save migration rather than a rename.

✅ **Half of it is already done** (`ea4015e0`): `labelOf`'s fallback used to hand CONCENTRATE and
TAILINGS to *every* machine with a Product store, so a pump's drawn gas and a furnace's fired charge
both read CONCENTRATE. The fallback is neutral now and the concentrator says its own words. **Keep it
neutral** — anything specific there is a claim made on behalf of every machine nobody has thought
about yet.

What is left is the enum itself. `Stream` is a tag on a `LocalPort` (defaulting to `Product`) and is
what pairs a port with the store that serves it. It wants entries that name a *role in a process*
rather than a rank: `Fuel`, `Oxidiser`, `Gas`, `Liquid`, `Tailings`… ⏸ **The list is Stu's to fill;
the requirement is only that adding one is cheap and that no entry implies precedence.**

## 4. A fifth buffer role

A bipropellant needs fuel + oxidiser + chamber = three stores. `BufferRole` has four entries and
`Inside` is spoken for by the chamber, so the two inputs need two roles and there is only one
`Input`.

✅ **This is cheap, and `BufferRole.kt:33` says why**: the buffer layer is keyed by *tile*, not by
(machine, role) — "the four roles of one machine resolve to four distinct tiles of its own
footprint." A 3×3 has nine tiles and this uses four. Add the entry, add its offset in
`localBufferOffset`, and the compiler finds every exhaustive `when`.

⚠️ **Do not reuse `Waste` for the oxidiser.** It fits the tile rule and it is a lie in the one place
a reader checks. This codebase deleted `Material` and `MachineKind` rather than live with a name that
lies.

## 5. The electrolyzer

**3×3, one water input, two gas outputs, no new geometry.**

### ⛔ It must be a machine mechanism, not a `REACTIONS` row

This is a genuine departure from a principle the codebase states out loud — the `Furnace` is proud of
having "no recipe and no rate", because machines control conditions and chemistry does the work.
Electrolysis breaks that in two independent ways, and either alone would be enough:

1. **It has no onset temperature.** It is power-driven at ambient. Any onset low enough to *be*
   electrolysis would split every drop of water in every room on the ship. The thermal alternative —
   real thermolysis at ~2500 K — is a different reaction telling a different story.
2. **Its products recombine instantly.** `2 H₂ + O₂ → 2 H₂O` lights at 773 K and a store reacts with
   itself. Split water in a hot store and it re-burns on the same pass, and since the rate model is
   one-directional rather than an equilibrium solver, the result would be an artefact of row order
   rather than chemistry.

Both dissolve if the machine does the split internally and puts H₂ and O₂ into **separate stores**,
which is exactly what two output ports buys. So: the first machine in the game that genuinely
*performs* a reaction rather than hosting one, and it is worth being explicit that this is a
precedent rather than an oversight.

### The numbers

`2 H₂O → 2 H₂ + O₂` costs **+484 kJ per 36 g of water** — the exact reverse of the combustion row
corrected in `6f1ff780`, which is a pleasing check on that fix. Per `heatBuffer`, that energy is
minted; see §1.

⏸ **Rate is open.** The furnace's `HEATER_POWER` is 360 kJ/tick, so one electrolyzer's worth of that
splits about 27 g of water a tick — roughly 1.7 kg/s, against a motor's 32 kg/s appetite. That ratio
wants deciding deliberately rather than inheriting: either the electrolyzer is much stronger than a
furnace, or a rocket is fed from tanks that fill slowly between burns, which is arguably the better
game. **Decide this before building, it sets the shape of every vessel that uses one.**

### Where oxygen comes from today, for comparison

Roasting hematite (`6 Fe₂O₃ → 4 Fe₃O₄ + O₂`, onset 1730 K) or photosynthesis, plus pumping a room.
All three work; none is a bulk propellant supply. That is the gap this machine closes.

## 6. The chemical rocket

**3×3 Square, two rear ports, a chamber, a ratio dial, an igniter.**

### Geometry

Ports mirror the docking port's two-on-one-face pattern (`Port.kt`), which is the precedent for two
doors on one side:

```
facing Right (exhaust to the right), r = 1

    (-1,-1) fuel  ──►┌───┬───┬───┐
                     │   │   │   │
                     ├───┼───┼───┤
                     │   │ C │ B │──►  exhaust
                     ├───┼───┼───┤
    (-1,+1) oxid  ──►│   │   │   │
                     └───┴───┴───┘
```

`C` is the chamber (`Inside`, at the anchor); `B` is the bell at `(+1, 0)`, which is what
`bell(grid)` already returns. `FootprintShape.Nose` is not used by this machine.

### The chamber, and ⛔ why it must not gate on its setpoint

The chamber is a small store refilled each tick from the two input stores at the dialled ratio, with
a furnace-style thermostat on it. Small deliberately: a 200 kg store held at 3000 K bleeds heat into
the vessel through `BUFFER_CONTACT_CONDUCTANCE` all day, which cooks the ship and wastes the heat.

⛔ **Do not hold the propellant until it reaches temperature and then release it.** That makes firing
a duty cycle — heat, vent, drop below setpoint, stop, reheat — and two motors either side of the
centre of mass cycling out of phase make the flight balance see a different subset of available
engines every tick. The ship wobbles, and the wobble is created by the gate rather than by the
chamber.

✅ **Vent continuously, heat continuously, and make the setpoint a ceiling.** Chamber temperature is
then an equilibrium:

```
T_chamber = T_in + P_heater / (ṁ · cp)      capped at the setpoint
```

v_e becomes a smooth function of temperature, and the mechanic that falls out is free and good:
**you reach your setpoint only if you throttle down far enough.** "My engines run at 2400 K cruising
and 900 K when I floor it" is a legible dial, and it is exactly what a resistojet does. The ceiling
is also what stops the low-throttle case running away — ungated, the equilibrium at 10% throttle is
20,000 K.

⚠️ For a *bipropellant* the thermostat's real job is **ignition**, not bulk heating. Reaching 773 K
in a small chamber is affordable; heating a full mass flow to a useful temperature is not, and the
reaction is what pays for the rest. The thermostat matters more for a monopropellant that cannot
burn, which is the same chamber with one input.

### Flight controls: ✅ no architectural change

The worry that a warming chamber breaks the flight solver does not survive reading it. `Motor.push`
enters `flightActivations` in exactly one place:

```kotlin
val torque = term.toLong() * m.cross * (m.push / Budget.GRAM)
```

It is **a weight in the torque-balance ratio and nothing else** — it never scales activation, and for
a single motor or a symmetric pair it cancels entirely. So:

- `chamberPush` reads the chamber store instead of the input store. **One line.**
- Heterogeneous, time-varying motors are already the design — that is what `8dad7374` changed
  weighting from mass flow to thrust *for*.
- The balance is re-struck every tick, so a warming motor reports a low push and genuinely produces
  low thrust. The error cancels rather than accumulating.

⚠️ **`flightPlan` runs at `OutofspaceSim.kt:437`, before the machine loop at 471**, so the plan reads
last tick's chamber. That is the correct side of the one-tick rule (`PLAN_one_tick_causality.md`), and
it means the first tick of a burn balances against cold chambers. Self-correcting; not a bug.

### The dial

A ratio, in permille of fuel, presented the way `Furnace.SETPOINTS` presents a ladder of round
numbers. It is a **control surface**, not a logistics setting: the point is to trade thrust against
v_e mid-burn, which you cannot do by re-plumbing a belt.

✅ **This finally builds `Thruster.filter`'s missing UI.** The filter has existed since the motor came
back on rails and is settable only from tests — there is no `Edit` and no control, because
`Edit.LockStorageSpecies` only matches `is Storage`. The dial work is where that lands.

## 7. Steps

Each ends at a green gate. Commit directly to main, one focused commit per step.

1. ✅ **DONE — the hydrogen and ammonia enthalpies** (`6f1ff780`). Two rows released half their
   energy; `everyWrittenEnthalpyIsWorthWhatTheTextbookSaysItIs` is the check that now sees it.
2. ✅ **DONE — output buffers stop borrowing the concentrator's words** (`ea4015e0`).
3. **Widen `Stream`** (§3). Naming only, no behaviour. Gate: every existing port keeps the stream it
   had, and the inspector reads the same on an unchanged save.
4. **A fifth `BufferRole`** (§4). Gate: `BufferRoleTest` still holds `localBufferOffset` and `portsOf`
   in agreement, and every existing machine's stores land where they did.
5. **The electrolyzer** (§5) — 3×3, water in, H₂ and O₂ out of separate ports into separate stores.
   ⚠️ **Decide the rate first.** Gate: a tonne of water becomes hydrogen and oxygen in the right mass
   ratio, both ledgers stay closed, and the two products never meet.
6. **The chemical rocket** (§6) — footprint, two inputs, chamber, dial, igniter. Gate: a motor fed
   H₂ and O₂ at 1:2 makes measurably more thrust than the same motor fed water, `chamberPush` reports
   the chamber, and `vesselImpulse + exhaust + bodies + vented − debug == 0` still holds every tick of
   a burn.
7. **Fly it.** ⛔ **A panel is not done until screenshotted.** The v_e and thrust readouts the panel
   still does not have (§8) are what make any of this legible.

## 8. What this leaves open

- ⛔ **The motor panel shows no exhaust velocity and no thrust.** `LISTENS TO`, `PUSHES`, `FIRING` and
  nothing else, so "hot chamber, light molecule" is invisible to the player. `chamberPush` already
  computes the number. This is the cheapest legibility win in the game and it should probably land
  before step 6 rather than after.
- ⏸ **Packets are still fixed mass** (`PLAN_fluid_thrusters.md` §7), so a hydrogen line delivers as
  many kg/s as an oxygen one. Until that changes, the only thing making a light propellant expensive
  is that there is less of it about.
- ⏸ **No power grid**, so §1's minted energy stands. It gates the reactor and the ion drive.
- ⏸ **Cold gas stays.** It is the cheap early engine and the table says it is honestly worse, which is
  correct rather than a problem to fix.

## 9. Explicitly not doing

- **Not** a reactor, and not nuclear physics. §1 — it is two projects, and its thruster half is a
  chamber with a bigger element that would trivialise this one if it arrived first.
- **Not** an ion drive. Its v_e comes from accelerating voltage, not temperature, so it is a different
  equation rather than a different heat source; it needs a power system that does not exist; and its
  mass flow is small enough that whether it floors to zero in the mass units wants checking before
  anything is designed.
- **Not** heat transport around the vessel. The pipe network was deleted on purpose (`9fdd39ca`) and
  moving heat as hot packets on rails is an interesting idea that belongs to the reactor plan.
- **Not** a merged thruster hierarchy. Cold gas, chemical and hot gas should be *one chamber with a
  pluggable heat source* internally and **distinct buildings** to the player — the internals share so
  a new engine is a new element and a new footprint, not a second copy of the firing path.
- **Not** dissociation, nozzle expansion ratio, or condensation enthalpy. Every nozzle is a perfect
  vacuum nozzle until something flies where there is a back-pressure to expand against.
