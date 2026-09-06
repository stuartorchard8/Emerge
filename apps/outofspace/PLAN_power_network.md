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
WIRE_SETTLING_TICKS = 4          // the number that is chosen
capacitance = WIRE_SETTLING_TICKS * conductance
```

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

### Increment 0 — the guard

The conductivity derivation scored against known figures for every metal in the table, and a
**charge ledger** asserted closed from the first commit. `FormationTest`'s argument: a number nobody
can check is a number that is eventually wrong.

### Increment 1 — sun to heat, end to end ⭐ the hard shape for the network

Wire as a `Conduit`, the relaxation, hull-ground, the `Ambient` scalar, a solar panel, and a
resistive load. One commit, because a network with no source and no sink is not testable and each
half alone is not worth a commit.

What must be true at the end: a panel in sunlight warms a heater through a wire; a copper run
delivers measurably more than an iron one of the same length; a run to nothing carries nothing; the
charge ledger closes; the relaxation settles rather than rings.

⚠️ **The load interface is specified for a threshold load from the start** even though this
increment's load is linear. Not building the mitigation is decision 4; painting into a corner it
cannot fit is a different mistake.

### Increment 2 — the cell as a load ⭐ the hard shape for the contract

`PLAN_electrochemistry.md`'s voltage dial becomes a terminal. The cell splits water when the bus can
hold it above 1.23 V and does nothing when it cannot, and **a vessel with too few panels is a vessel
whose cell does not run** — which is the first time in this game that a machine has been short of
anything but matter.

Ships the chatter tripwire per decision 4. ⛔ Not hysteresis.

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
