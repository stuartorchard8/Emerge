# Fluid thrusters

Status: **SCOPED, nothing built** — 2026-09-03.

A thruster stops being a hole that eats gravel at a flat 3 km/s and becomes a rocket: it drinks a
fluid out of the plumbing, and what that fluid *is* decides how fast the exhaust leaves. Hot chamber,
light molecule. Propellant choice becomes a real decision with a twelvefold spread in it.

This is the piece that unblocks the pipe network having a *purpose*, and after that the starter ship.

---

## 1. What exists today, verified

The seam is narrower than it looks from the outside.

| | today |
|---|---|
| **Pump** (`world/Pumping.kt`) | room → pipe. Has **no ports**; works on the atmosphere `MassArray`, never on a store. |
| **Valve** (`world/PipeField.kt`) | pipe ↔ room, by pressure equalisation. Also no ports. |
| **Every port in the game** | `Conduit.Rail`. `Port.localPorts` never sets `conduit`, so the `Conduit.Pipe` branch of the port system has no members. |
| **Machine stores vs. the pipe** | `Mixture` in `BufferLayer` vs. a `MassArray`+`EnergyArray` continuum on the same lattice. **Nothing crosses between them.** |
| **Thruster** | `massPerTick = PACKET_MASS/200`, flat. `EXHAUST_METRES_PER_SECOND = 3000`, flat and species-blind. Propellant is a solid off a belt. |

So gas can get *into* plumbing and *out into a room*, but **nothing can drink from a pipe**. The
thruster is the first machine that will.

⚠️ **Pipe transport is diffusive, not pumped.** Since `PLAN_fluid_extraction.md` step 5, the pipe
layer runs the same `diffuseFluid` pass as a room, on `pipeApertures`. There is no bulk flow toward a
consumer — a network *equalises*. That is load-bearing for §4: a motor that drains its chamber cell
is refilled at the diffusion rate from its neighbours, so **a long thin run genuinely starves a
motor** without anyone writing a rule saying so.

⛔ **Not a constraint, despite an earlier claim:** rail/pipe exclusion is **gone**. `spokenFor`
returns `false` unconditionally and the `Conduits` invariant is deleted, because a pipe ghost is fed
by rail on its own tile and the two must coexist. Plumbing crosses track freely; routing a pipe to a
motor on an existing ship is unconstrained.

## 2. The coupling: a motor drinks from the cell it stands on

**A thruster draws from the pipe cell on its own chamber tile**, in the shape `applyPumps` already
has — a bounded draw per tick that stalls below a pressure floor.

⛔ **Deliberately not pipe ports, and deliberately not fluid demand.** Demand is a rail concept
because a packet is discrete and has to *choose* a route; that is what `Whitelist`, `Acceptance` and
the `FlowGraph` walk are all for. A continuum does not choose — it flows down a gradient. Building a
demand system for plumbing would be inventing a mechanism rather than modelling one, and it would
mean two answers to "where does this go" living in one game.

The consequence is worth stating as a feature rather than an omission: **there is no propellant
filter, and there does not need to be.** A pipe network only connects where the player drew the
joins, so keeping hydrogen out of the water supply is a *layout* problem. That is the game.

### 2.1 A thruster has no rail port — decided, Stu, 2026-09-03

**One propellant path.** `localPorts` returns nothing for a `Thruster`, so a belt arriving at a motor
has nothing to hand over and the rail network routes around it — the same as it already does for a
pump or a valve.

Three things fall out, and they are the reason this is the cheap option rather than the brave one:

- **It is still buildable.** `constructionPortOf` gives every ghost a rail port at its centre
  whatever its kind, and `standingPortsOf` returns that alone while the ghost stands. Motors are
  built by track exactly as they are now.
- **No save work.** Ports are derived from the machine kind in `localPorts` and never written to
  disk, so a legacy motor loses its port on load with nothing to migrate.
- **No stranded matter, and no special case for it.** Leave the existing solid branch of `fire()`
  alone: a legacy motor throws whatever is already in its store on the next burn, exactly as today,
  and then runs dry because nothing can refill it. The ledger never hears about a change.

## 3. Exhaust velocity

The ideal rocket into vacuum, with the expansion term at its limit:

```
v_e = √( 2γ/(γ−1) · R·T/M )
```

Everything is already in the codebase except γ:

- **M** — `millimolesOf(mass, tile)` already gives moles from a `MassArray`; mass/moles *is* M. No
  new table, and it works for a mixture without special-casing one.
- **T** — `gasKelvin(energy, mass)`.
- **√** — `num.isqrt`, already used by `Prices` and `RigidBody`.
- **γ** — **missing.** Derive it from atomicity rather than tabulating: monatomic ⁵⁄₃, diatomic ⁷⁄₅,
  polyatomic ⁴⁄₃, read off the atom counts already in `MINERALS`. ⛔ Do not add a column — a
  measured-looking number nobody measured is exactly what `SpeciesInfo` warns about.

### The integer form is exact, which is the good part

`2γ/(γ−1)` for those three γ is **5, 7 and 8** — equivalently `f + 2` for f degrees of freedom, which
is the arithmetic to check it against. Whole numbers. And with M in g/mol — which is what
`Species.molarMass` already holds — `R/M` in SI is `8314/M_g`. So:

```
v_e(m/s) = isqrt( K · 8314 · T_kelvin / M_gPerMol )        K ∈ {5, 7, 8}
```

No scale constant, no fixed point, no `Frac`, nothing to calibrate. Worst case is `8 · 8314 · 10000 / 2`
≈ 3.3×10⁸, which is nowhere near `Long`.

⚠️ **A mixture's K is the mole-weighted mean of its species', and that is exact.** `K = 2·Cp/R` and a
molar heat capacity is additive over moles, so there is no averaging of γ anywhere — which is the
second reason this group is what gets stored and γ itself never is.

⛔ **`2γ/(γ−1)` was written as 4 for the monatomic case first time round** (`e6f44812`, corrected in
`8f0a...`). `f + 2` cannot produce a 4, and that is now what the test asserts against: an expected
value reached by the same division that produced the wrong one is not a check.

⚠️ **`tilesPerTick` must keep more resolution than it does.** At 780 m/s the current
`v · 1000 / (TILE_MILLIMETRES · ticksPerSecond)` floors to 207 and throws away ~0.5%. Carry
milli-tiles per tick and divide once at the end — the same lesson `f02179dc` taught `kelvinOf`.

### What it pays out

| propellant | K | T | v_e |
|---|---|---|---|
| CO₂, cold | 8 | 293 K | 0.67 km/s |
| N₂, cold | 7 | 293 K | 0.78 km/s |
| He, cold | 5 | 293 K | 1.74 km/s |
| H₂, cold | 7 | 293 K | 2.92 km/s |
| steam, burned | 8 | 3500 K | 3.60 km/s |
| H₂, heated | 7 | 3000 K | 9.34 km/s |

A factor of fourteen, every number arrived at rather than chosen. Note the current flat 3000 is
almost exactly **room-temperature hydrogen** — so the physics lands on top of today's behaviour for
the best cold propellant and makes everything else worse, which is the right direction.

## 4. Burn rate, and why thrust is *not* the interesting number

Choked flow through a throat is `ṁ = p_c·A_t·√(γ/(R_specific·T_c))·(2/(γ+1))^((γ+1)/(2(γ−1)))`. Two
of those factors are near-constant across every γ in range — the bracket is 0.65–0.68, and `√γ·√K` is
2.6–3.3 — and the rest is `1/v_e` by the definition in §3. So, honestly and not as a fudge:

```
ṁ  ≈  C · p_c / v_e            thrust = ṁ · v_e  ≈  C · p_c
```

**Thrust is nearly propellant-independent; efficiency varies fourteenfold.** That is the real trade
and it is a much better game than "hydrogen pushes harder": switching propellant does not change how
hard your ship shoves, it changes how long it can keep shoving and how much tankage the shove costs.

So: **one new dial**, thrust per unit of chamber pressure. Mass flow is then `thrust / v_e`, drawn
from the chamber. `massPerTick` stops being a stored constant and becomes a derived reading.

**Chamber pressure** is the pipe cell's moles over its volume — `millimolesOf(pipeMass, tile)` against
`pipeVolumes.at(tile)`, compared by cross-multiplication as `applyPumps` already does rather than by
forming two pressures. Combined with §1's diffusive refill this makes **plumbing a performance
system**: `PIPE_VOLUME` (currently `FULL/8`) becomes a dial that matters, and a motor at the end of a
long run makes less thrust than one beside its supply.

## 5. What this breaks elsewhere

Four of these are silent if missed.

1. ⚠️ **`FlightControls` assumes one exhaust velocity for the whole ship.** It balances motors on
   `activation × massPerTick × cross` (`FlightControls.kt:223`) — thrust as a proxy for mass flow.
   Once v_e varies per motor that term needs the velocity in it, or **a ship with a hydrogen motor to
   port and a nitrogen motor to starboard spins under a straight burn**. Check the `Budget.GRAM`
   scaling for overflow while touching it.
2. ⚠️ **`fire()` throws the propellant's own temperature away.** It rebuilds `propellantEnergy` at
   `Temperature.AMBIENT_KELVIN` rather than reading the chunk's real energy. For a fluid motor T is
   the entire point, so this must read what is actually there — and it touches the orphan-energy
   invariant (`StuffLayer.checkInvariants`), so 0 matter ⇒ 0 energy has to keep holding.
3. **The solid path, and the standing TODO — answered by deletion.** `fire()` carries the question:
   *"a thruster fed gravel should arguably refuse to fire rather than throw it away. That is an
   acceptance rule, not an arithmetic one, and it is Stu's call."* The call is **§2.1**: a motor
   loses its rail port, so nothing can hand it gravel and there is no acceptance rule to write. A
   question that stops existing is a better answer than a rule that resolves it.
4. **Save version.** Currently v21. A motor whose `massPerTick` is derived and whose store holds a
   fluid is a format change; existing thrusters migrate.

## 6. Steps

Each ends at a green gate. Commit directly to main, one focused commit per step.

1. ✅ **DONE — γ from atomicity.** `Species.adiabaticK` derived over `MINERALS` atom counts,
   returning 4, 7 or 8. Nothing reads it yet.
2. **`exhaustVelocity(mass, energy)` as a pure function**, over a parcel. Gate: the §3 table
   reproduced to ±2%, plus the degenerate cases — no mass, no energy, one species, a mixture.
   ⚠️ This is where a wrong answer is cheapest to find; do not fold it into the tick.
3. **The draw.** The thruster takes from the pipe cell on its chamber tile into its own store, with a
   stall floor, modelled on `applyPumps`. Nothing changes about firing yet. Gate: a motor on a
   charged pipe fills its store and stalls on an empty one; mass ledger unmoved.
4. **Fire on it, and drop the rail port** (§2.1). `massPerTick` becomes `thrust / v_e`; impulse
   becomes `ejectedMass × v_e` in milli-tiles per tick; propellant energy is read rather than
   rebuilt (§5.2); `localPorts` returns nothing for a `Thruster`. ⚠️ **The port goes in this step and
   not before** — a motor with no port and no draw yet is a motor that cannot be fed at all, and the
   two halves have to land together or the starter vessel stops flying mid-migration. Gate: momentum
   ledger still balances over a burn; a hydrogen motor and a nitrogen motor at equal chamber pressure
   make **equal thrust and unequal mass flow**; a belt run at a motor routes past it instead of
   stalling at it.
5. **Flight balance by thrust, not flow** (§5.1). Gate: a ship with two different propellants either
   side of its axis burns straight.
6. **Panel + save bump.** Chamber pressure, propellant, v_e, ṁ on the inspector. ⛔ **Not done until
   screenshotted** — three economy bugs were found that way and none by a test.

## 7. Answered — Stu, 2026-09-03

All four are settled. Kept as a record of what was decided against, not as work.

1. ~~Does a thruster keep its rail port?~~ — **no.** See §2.1.
2. ~~Does the bell mean anything?~~ — **every nozzle is a perfect vacuum nozzle today.** Selectable
   nozzle shape is deferred until there is non-vacuum flight to select it *for*; until a ship flies
   somewhere with a back-pressure, expansion ratio is a dial with one correct setting. This is what
   lets §3 drop the `1 − (p_e/p_c)^((γ−1)/γ)` term and stay in integers.
3. ~~Where does propellant come from?~~ — **hydrogen off-gassed by captured asteroids**, caught by a
   pump in the bay. It is already produced, already vented to space when the doors reopen, and it is
   the best propellant in the game by §3. ⚠️ **So the first real propellant loop needs no new
   production machinery at all** — a pump, a run of pipe, and a motor. The furnace-to-atmosphere path
   stays available and unbuilt; nobody has to be told to boil ice.
4. ~~Is there a tank?~~ — **the pipe network is the tank.** Build a bigger one. `PIPE_VOLUME` is the
   dial if a run turns out to hold too little to be worth plumbing.

## 8. Explicitly not doing

- **Not** combustion in the chamber. Once §6.4 lands, burning is a reaction run on the parcel before
  the temperature is read — an increment, not a rewrite. Cold gas first, because it decides every
  shape downstream and needs no chemistry at all.
- **Not** pipe ports or fluid demand (§2).
- **Not** a fluid storage machine (§7.4).
- **Not** restoring momentum to the gas. A thruster mints its impulse from `mass × v_e` directly and
  is unaffected by the fluid solver's absence — see `PLAN_fluid_extraction.md` §3.
- **Not** exhaust plumes, bloom or thruster audio. Separate work, and it wants this settled first so
  the effect has a real velocity and a real mass flow to read.
