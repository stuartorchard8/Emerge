# The power network, and the first thing in the game that is not free

Status: **scoped, nothing built** (2026-09-06). Sibling to `PLAN_electrochemistry.md`, which needs a
wire but does not need one yet — see §7 for where the two interleave, and why the cell rather than a
heater is the load this network must be designed against.

> Every machine aboard runs on nothing. A furnace's element mints its own joules, an electrolyzer
> mints the energy to break water, and the ledger stays closed because the pool they draw from is not
> a pool anybody tracks. This is that pool.

## 1. Why now, and why not the signal network

Two things arrived at the same conclusion from opposite ends:

- **`PLAN_electrochemistry.md` §4.3 defers batteries on a missing prerequisite.** A galvanic cell
  sources electrical energy and there is nowhere to put it. Everything else in that plan works
  without a wire; batteries alone need one.
- **`HEATER_POWER` is already a real number.** It is derived in joules from a chamberful of rock at a
  stated climb rate, so the furnace is the one machine whose draw is not a fudge — it is waiting for
  something to bill it.

⛔ **This is not the signal network and must not reuse it.** `Wiring.kt` carries **verdicts** — *"a
term has a sign, not a strength"* — and that was a deliberate deletion of a proportional controller,
not an oversight. A wire that carries energy is a different conduit with different physics on it.
The two coexist the way rails and signals already do.

## 2. Decisions taken (Stu, 2026-09-06)

1. **Charge is the conserved quantity; energy is what gets spent.** Charge is an integer and is
   conserved exactly, alongside mass. A load draws charge at one potential and returns it at a lower
   one, and the difference is the energy it consumed. This puts electricity on the same footing as
   every other quantity in the game rather than inventing a second kind of bookkeeping.
2. **⭐ The hull is ground.** One terminal per machine, return through the structure, potentials
   measured against a vessel-wide zero. Physically honest for a metal ship, and it halves what the
   player has to build — no return wiring, no completing a loop by hand.
3. **⛔ Wires are given a generous capacitance, and it is a stated fiction (Stu).** See §4. It is
   derived from a settling target rather than chosen, recorded here as revisitable, and the thing
   that would revisit it is a **capacitor machine** — which would be a machine whose capacitance is
   large *on purpose*, leaving the wire's own value at whatever stability needs. Named now so that
   later work has something to push against.
4. **⛔ No hysteresis on threshold loads until one is measured chattering (Stu).** The hazard is real
   (§5) but the mitigation costs work every tick on every load, and the system may simply not need
   it. Increment 2 ships a **test** that detects a limit cycle, not a mechanism that prevents one.
   ⚠️ If it fires, the fix is hysteresis and this decision is what gets revisited — not the test.
5. **Billing the existing machines is its own increment, and it is last.** See §8.

## 3. The model

A wire is a `Conduit`, laid in runs and made of a species, exactly as a rail is. Each wire tile
carries a **potential** against hull-ground and a small stored **charge**; the potential is the
charge over the tile's capacitance. Each tick charge moves between neighbouring wire tiles in
proportion to their potential difference and the segment's conductance — Jacobi relaxation, which is
the solver this codebase already committed to for the rigid bodies and for the same reason.

Ohm's law falls out. So does the series/parallel behaviour of a run, so does a voltage divider, and
so does the fact that a long thin run of the wrong metal cannot deliver what a short fat one can.
None of that is written down anywhere.

### ⭐ Conductivity is derived, not typed

`Species` already carries `milliWattsPerMetreKelvin`. **Wiedemann–Franz** relates a metal's thermal
and electrical conductivity through one constant — κ/σ = L·T — so the electrical figure is derivable
from the table as it stands. Scored against the species already in it:

| Species | κ in table | σ derived | σ actual |
|---|---|---|---|
| Silver | 429 | 5.9e7 | 6.3e7 |
| Copper | 401 | 5.5e7 | 5.96e7 |
| Iron | 80 | 1.1e7 | 1.0e7 |

Within about ten per cent across two orders of magnitude, from data nobody has to add. This is
`MINERALS`' argument again: a derived number cannot quietly be the wrong number, and a hand-typed
conductivity column would be six more chances to be wrong invisibly.

⚠️ **It holds for metals only.** In an insulator heat moves by phonons and the ratio means nothing —
firebrick would score as a poor conductor for the wrong reason. Gate it on the metal/non-metal split
`Species` already sections by, and a non-metal is an insulator **by declaration**, which is the
honest statement and also the one the game wants.

⭐ This is the first time material selection has a *performance* consequence rather than a
mass-and-strength one. A copper run genuinely beats an iron run, by a factor nobody chose.

### ⛔ Capacitance, the one fiction on this page

A resistive network relaxed explicitly is **stiff**: when conductance is high and stored charge is
low, the relaxation overshoots and rings, and a finer timestep makes it worse rather than better.
This is the same failure `Saturation.kt` documents at length — *"a disturbance there does not
oscillate, it grows exponentially, and it grows faster the finer the grid. No timestep stabilises
it."* — and it is worth reading that file before writing this one.

The out is to give a wire much more capacitance than a wire has, so its settling time is a few ticks
rather than a fraction of one. **Derived from the target, not chosen**, in `HEATER_POWER`'s idiom:

```
SETTLING_TICKS = 8               // the number that is chosen
fraction moved per edge = G / (SETTLING_TICKS * MAX_CONDUCTANCE)
```

⭐ **Eight rather than four, and it is derived (built 2026-09-06).** An explicit relaxation is
non-oscillatory when a node sheds at most *half* its excess per step. A tile has at most four
neighbours, so no single edge may move more than an eighth — and since the fraction scales with the
edge's conductance, the denominator has to be the **most conductive metal in the table** or a silver
bus would breach the bound copper was sized against. `MAX_CONDUCTANCE` is therefore a maximum over
`Species`, not silver's figure written down.

⚠️ **Capacitance is geometric, conductance is material.** A tile of wire is a tile of wire, so every
segment gets the same capacitance and the metal shows up only in `G`. A per-material capacitance
would put a division inside the potential and the ledger would stop closing to the unit.

So the fiction is one dial, in one place, with the honest name on it. ⚠️ **What it costs in play**: a
bus does not respond instantly, and a load switched on takes a few ticks to pull the line down. That
is invisible at this scale and would become visible — and welcome — the moment a capacitor machine
exists to make it deliberate. See decision 3.

## 4. Sources: the solar panel, and one number in `Ambient`

⛔ **There is no sun in the game**, and `Ambient.kt` is emphatic about why: it is *"the only place a
planet exists"*, deliberately without a world map, an altitude or a sphere, because everything a
player would call flying comes out of two numbers at the rim.

So insolation is **one more scalar on `Ambient`** — how bright it is out there — and nothing else. No
sun direction, no shadows, no day/night, no occlusion by the vessel's own hull. Anything more is the
world map that file exists to avoid, and it can be added later by somebody who has a reason.

A panel's output is that scalar times its area. ⚠️ **This does not retire the free-energy fiction, it
gives it a pipe.** Sunlight is unlimited, so what the game gains is energy that is **rate-limited
rather than costly** — scarcity without an economy. That is the honest description of solar power in
space and it is worth having, but it is a different thing from making energy expensive, and a
fuel-burning generator would be the machine that did that.

## 5. ⚠️ The hazard this network must be designed against

**A threshold load is nonlinear.** A cell does nothing whatever below 1.23 V and then draws current
above it. Attach one to a bus that sags under load and the obvious implementation limit-cycles: the
cell switches on, pulls the line below its knee, switches off, the line recovers, it switches on.

This is why the **cell and not the heater is the load this network is designed against** — a
resistive heater takes whatever it is given, linearly, and never says no, so a network built against
one will not have been asked the question that constrains it. §7 sequences accordingly.

Per decision 4 the mitigation is **not** built up front. What increment 2 owes is a test that
notices: a load whose on/off state flips on more than some small number of consecutive ticks is
chattering, and that is a failure, not a texture.

## 6. Resistive heating, which nobody has to write

Charge moving down a potential gradient dissipates I²R into the tile it moves through, and there is
already somewhere to put it — the solid heat ledger the furnace element writes into. So a run of
undersized wire warms up, a short dumps its energy as heat, and a panel wired to nothing does
nothing. ⭐ None of these is a rule; they are all the same rule.

⚠️ **`EnergyLedgers.PARKED` is still `true`,** so nothing in the suite is watching the joules this
creates. The charge ledger (decision 1) is the one that must be live from increment 0, on exactly
`EnergyLedgers`' own reasoning about which conservation check survives a rescale.

## 7. Increments

**Each increment is one commit on `main`**, green before it lands.

⚠️ **This plan interleaves with `PLAN_electrochemistry.md`.** That plan's increments 0–1 come
*first* — they need a voltage dial, not a wire, and they are what produces the threshold load this
network has to be designed against. The full order:

| | |
|---|---|
| 1 | `PLAN_electrochemistry.md` inc 0–1 — half-reactions, competition rule, the cell, voltage as a **dial** |
| 2 | **this plan, inc 0–2** — the network, the panel, the dial becomes a terminal |
| 3 | `PLAN_electrochemistry.md` inc 2+ — copper, the leach, the loop |
| 4 | **this plan, inc 3** — billing |

### Increment 0 — the guard ✅ BUILT (2026-09-06)

`chem/Conductivity.kt` and `ConductivityTest`, 9 tests. The derivation scored against measured σ for
every metal the game has: **the ten a wire would be drawn from land within 15%**, tin exact to three
figures. The poor metals — manganese, bismuth, tungsten — reach 60%, checked at a looser bound rather
than excluded, because a manganese wire should still be bad by roughly the right amount.

⚠️ **The charge ledger moved to increment 1**, where there is charge to conserve. Asserting it here
would be asserting over an empty set.

#### ⛔ What the build found: the metal line no longer has clear air

The obvious gate was `Material.kt`'s `METALLIC_CONDUCTION_MILLIWATTS`, which calls a solid a metal
above 10 W/m/K and whose doc claims *"the table has a factor of four of clear air on either side of
it. The poorest conductor the game calls a metal is titanium at 22, and the best it calls a mineral
is forsterite at 5. Nothing sits near the line."*

**Fourteen species now sit in that gap, and it misclassifies in both directions:**

| Above the line, and a mineral | Below the line, and a metal |
|---|---|
| pyrite 20, cassiterite 12, hematite 11.3, thorianite 10 | mercury 8.3, manganese 8, bismuth 8 |

Deriving conductivity from that threshold would let a vessel **draw wire out of iron ore**. So
`Conductivity.kt` states what a metal *is* — an element that is not one of twenty non-metals, plus
one alloy — which is a fact about chemistry rather than a threshold that drifts as the table grows.
Two tests pin both directions.

⚠️ **This is a live defect in `roughnessOf`, which is not fixed here.** That function reads the same
threshold, so today hematite and pyrite grip like metals and mercury grips like rock. Changing it
re-tunes collision behaviour, which is a decision and not a refactor — see §8.

### Increment 1a — the wire carries charge ✅ BUILT (2026-09-06)

⚠️ **Split from what was one increment.** The original said one commit *"because a network with no
source and no sink is not testable"* — which was wrong. It is not **playable** without a source; it
is entirely testable by injecting charge directly, and the relaxation is the part carrying the design
risk. So it got its own commit and its own tests.

⭐ **`Conduit.Power` already existed** — laid, saved, rendered, defaulting to copper, with a bill of
materials and an inspector line reading *"carries nothing yet."* This is what it carries.

`world/PowerFlow.kt`, `PowerFlowTest`, 9 tests. Charge relaxes between joined segments at
`seriesConductance` of what they are made of — the same harmonic mean heat already crosses a joint
by. Two ledgers asserted as identities: **charge is conserved to the unit**, and **every joule the
field gives up becomes heat**.

#### ⛔ What the build found: per-edge dissipation does not add up

Moving `m` across one edge costs `m × (Δq − m)`, and that is exact *for one move*. Every edge moves
simultaneously from the same snapshot — Jacobi, per the one-tick causality rule — so a tile shedding
to two neighbours at once has a **cross term between them that no per-edge formula sees**. Summed,
the per-edge figures came to **5.2% less than the field actually lost**, and a wire that dissipates
95% of what it takes is a wire quietly minting energy for ever.

⚠️ It would have passed any tolerance-based test. It was caught because the ledger was written as an
*identity*.

The fix keeps the per-edge figures as **weights** and apportions the real drop in `Σ q²/2` across
them — `I²R` says the edge carrying the most current takes the most heat, and `apportion` telescopes
so the shares sum back to the total exactly.

### Increment 1b — sun and panel ✅ BUILT (2026-09-06)

`SolarPanel`, `Ambient.insolation`, `VesselState.charge`, the power pass, save version 27, and the
`POWER` brush turned on. `SolarPanelTest`, 6 tests.

⭐ **The whole of a panel is three lines**, because everything it needed already existed:
`StructureMap.openToSpace` to say which faces see sky, one scalar on `Ambient` to say how bright it
is, and `PowerFlow` to carry what it pushes. *The sun is anywhere outside the vessel* (Stu), so
exposure is counted over the panel's **neighbours** — a machine blocks passage, so space never
reaches the tile it stands on, exactly as `SolidHeat` counts a casing's radiating faces. Bury a panel
and it makes nothing; nothing forbids that, it simply has no sky.

⭐ **Solar heating is emergent.** Nobody wrote a rule that a panel warms the ship. Charge moves down
a resistance and `I²R` is what that costs, banked through the same `heat()` every machine's waste
heat goes through — so it lands in `generatedEnergy` and the existing ledger accounts for it.

**A panel is a current source, not a power source.** It pushes charge at a rate set by the light,
near enough regardless of what the bus is doing, which is what a photovoltaic cell *is* up to its
open-circuit voltage. So `P = I × V` rises as the bus charges, and that falls out rather than being
stated.

⚠️ **`CHARGE_PER_FACE` is sized against the overflow bound, not against a joule.** Charge and the
game's energy unit meet at `PowerFlow.storedEnergy`, and what a panel is *worth* only becomes a real
question when something bills for it — increment 3. Sizing it against `MAX_CHARGE` is what can be
done honestly today; that constant is the lever when the anchor arrives.

#### What the brush was waiting for

`Conduit.Power` had been excluded from the build menu and from the cut tool on the stated grounds
that *"the layer exists and nothing reads it yet, so a brush for it would lay cable that does nothing
and looks like a bug rather than like a feature that has not arrived."* Both exclusions are lifted
here, and the cut tool with them: a network the player can build is one they must be able to cut.

### Increment 2 — the cell as a load ✅ BUILT (2026-09-06)

`PLAN_electrochemistry.md`'s voltage dial becomes a terminal. The cell splits water when the bus can
hold it above 1.23 V and does nothing when it cannot, and **a vessel with too few panels is a vessel
whose cell does not run** — which is the first time in this game that a machine has been short of
anything but matter.

### ⭐ The cell is not a plain threshold — it has an internal resistance, and it is the electrolyte

A cell's solution conducts by its dissolved ions, and **pure water has almost none**: ~5.5e-6 S/m
against 5 for brine and 80 for molar sulfuric acid. Seven orders of magnitude. So the cell's internal
resistance comes off its own electrolyte's composition, in series with the wire run feeding it, and

> ⭐ **a cell full of pure water fails because the network cannot push current through it** — not
> because anything forbids it.

That is the same quality as aluminium losing its cathode to hydrogen, and it is why this increment is
a better exercise of the network than a fixed threshold would have been: the load's resistance is a
function of what is inside it, which is the case a linear heater cannot pose. See
`PLAN_electrochemistry.md` §5 for the chemistry and for what it buys — the acid earning a second job,
the bootstrap through brine, and the salt carrying current without being consumed.

⚠️ **`ElectrolyzerTest`'s fixture gains an electrolyte here**, having survived electrochemistry
increment 1 unedited. Honest: the physics that makes pure water fail arrives in this commit.

`PoweredCellTest`, 6 tests.

⭐ **Voltage gates, current sets the rate.** A cell below 1230 mV does nothing at all; above it, it
runs at whatever the bus can feed. That distinction was a design call taken during the build and it
is the difference between a threshold a player can plan around and a cliff they fall off — a vessel
with too few panels has a *slow* plant, not a dead one, and adding panels visibly speeds it up.

`ELECTRONS_PER_CHARGE` is derived from what it is for, in `HEATER_POWER`'s idiom: **one fully exposed
panel runs a cell at about a tenth of its ceiling**, so a bank of ten runs it flat out.

#### ⛔ What the tripwire found, and why the answer was not hysteresis

Decision 4 declined to build hysteresis until something was measured chattering. Something was, on
the first run: a cell allowed to spend the whole charge on its tile **drained itself under 1230 mV,
went dark, recharged over several ticks and fired again** — a textbook limit cycle, running on five
ticks in forty *with power to spare*.

⭐ **The fix was neither hysteresis nor a dial. The load was simply wrong.** A cell's current is
driven by its *overvoltage*: as the bus falls toward the potential the reaction needs, the current
falls to zero. It cannot pull itself below its own knee. So a cell may spend only the charge **above**
the knee, and the limit cycle stops existing rather than being damped. Decision 4 stands, unused and
vindicated.

#### ⚠️ Two characteristics worth knowing

**A run settles in `L² × SETTLING_TICKS`, not `SETTLING_TICKS`.** The relaxation is diffusive, so a
fifteen-tile trunk takes some eighteen hundred ticks to come up while a three-tile stub takes
seventy. §3 called the delay *"invisible at this scale"*; that is true of a short run and false of a
long one, and it is the capacitance fiction showing through. A capacitor machine would be the handle.

**The electrolyte ceiling is not wired in yet, and that is an ordering problem rather than an
omission.** `chem/Cell.kt` has `electrolyteStrength` and `AQUEOUS_ELECTROLYTES`, and pure water
scores zero — but this machine's appetite is for *pure* water, stated at the route, so salt cannot
ride the feed. An electrolyte is a standing bath, and the bath is the `Inside` store
`PLAN_electrochemistry.md` §5.5 adds. **The gate lands with it**, and `ElectrolyzerTest`'s fixture
gains its electrolyte there rather than here.

### Increment 3 — billing what is already free

Separate and last, for two reasons: a bug in the network would otherwise read as a balance problem
and vice versa, and the day this lands every vessel aboard stops working.

⭐ **The migration is one this codebase has already done.** `Wiring.kt` added `SignalSource.Always`
precisely so *"placing a machine still just works and wiring remains something you add"*, and it
worked because an unwired term read zero and every existing vessel behaved identically. Same move: a
machine with no power terminal draws nothing and runs free, a terminal is opt-in, and the day the
default flips is its own commit with its own argument.

The furnace goes first. `HEATER_POWER` is already joules-per-tick derived from a physical climb rate,
so it is the one machine whose bill is a real number rather than a figure invented for the occasion.

## 8. Explicitly not doing

- **AC, phase, inductance, or anything that is not a resistive DC network.** None of it buys a
  behaviour a player would notice on a vessel this size.
- **Sun direction, shadows, day/night, or a panel that cares which way it faces.** §4. One scalar.
- **Transmission loss as a separate mechanism.** It is I²R and it is already there.
- **A battery**, which is `PLAN_electrochemistry.md`'s cell run backwards and belongs to that plan
  once this one has somewhere for its electrons to go.
- ⛔ **Fixing `roughnessOf`'s metal test.** Increment 0 found that `METALLIC_CONDUCTION_MILLIWATTS`
  misclassifies fourteen species, so hematite and pyrite currently grip like metals and mercury like
  rock. `conductsElectrically` is the correct predicate and the fix is to route grip through it —
  but that re-tunes every friction interaction in the game, which is Stu's call and belongs in a
  commit whose subject is collision rather than power.
