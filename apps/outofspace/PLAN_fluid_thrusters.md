# Fluid thrusters, and fluids on rails

Status: **REDIRECTED 2026-09-04 — steps 1–2 built, step 3 built and to be reverted.**

A thruster stops being a hole that eats gravel at a flat 3 km/s and becomes a rocket: what it burns
decides how fast the exhaust leaves. Hot chamber, light molecule, a fourteenfold spread in what a
propellant is worth.

**The propellant reaches it by rail.** That is a reversal — this plan was written around the pipe
network, and §8 records why it turned — and it means the vessel gets bulk fluid handling out of the
logistics system it already has: demand, filters, bridges, storage, and twenty-tonne tanks.

**The pipe network is deleted.** Not bypassed, not scoped down. See §9.

---

## 1. Why not pipes

⛔ **The pipe network cannot transport, by construction, and that was decided on purpose.**
`PLAN_fluid_extraction.md` step 5 removed advection, projection and momentum from the fluid model.
What is left is `diffuseFluid`, which **equalises**: it has no direction, so it can even out but
cannot deliver. Souping up the pump adds a *source*; it does not add direction. Getting bulk flow
back means bringing the momentum solver home from fluidlab — the thing that was extracted for being
expensive and impossible to tune per fluid against reality.

**Measured, on the built pipe-fed motor** (`8dad7374`):

| | |
|---|---|
| A pipe cell at one atmosphere holds | **125 g** |
| A motor at one atmosphere asks for, per tick | **515 g** (H₂) · **1446 g** (H₂O) · **1928 g** (N₂) |
| A rail line delivers (100 kg a packet, `RAIL_PERIOD` 8, 64 Hz) | **800 kg/s** |
| A motor at one atmosphere wants | 33 kg/s (H₂) – 123 kg/s (N₂) |

A pipe cell holds a *thousandth* of what a motor asks for in a single tick. **One rail line feeds six
to twenty-four motors.** The supply problem does not get tuned away, it disappears — and the engine
needs no nerf, so the calibration decision §8 raised stops existing.

## 2. Fluids ride rails

A fluid moves as a `Mixture` in a packet, exactly as ore does. Nothing about demand, filtering,
bridges, storage or the flow graph needs to know it is a fluid.

⚠️ **Most of this exists already and was never wired up.** `FluidPacket` and `packFluid` are in
`logistics/Packet.kt` with tests — *"fluids always merge into an amalgam"*, *"a solid and a fluid
never merge"*. And **`RailLayer` stores a bare `Mixture`**: `SolidPacket` is a wrapper applied at the
boundaries, not the way track holds things. So this is mostly *removing an assertion*, not building
transport. The places that assert it:

- `RailLayer.packetAt`, which wraps every load as a `SolidPacket`
- `takePacket` in `OutofspaceSim`, which mints one, and the two call sites that downcast to it
- `Save`'s reader, which **silently drops** a `FluidPacket` — though the writer already emits `F:`
  for one

### 2.1 A machine's store is a tank

⛔ **Off-gassing is what stopped a store being a tank, and it becomes opt-in.** `offGas`
(`world/AmbientChemistry.kt`) has exactly **two** call sites — `offRails` and `offHoppers`, both
cargo layers — and it moves every fluid species into the room around it. That is what makes a hopper
of ore stop being "gas in a sack", and it is also what makes hoarding a tonne of liquid oxygen
impossible today.

- **A hopper never off-gasses.** A storage tank is a tank; that is the whole point.
- **A run of track off-gasses only where the player says so** — see §3.2.

⚠️ **The physics is relocated, not deleted.** Saturation, the vapour headroom, the latent heat that
makes a boiling liquid cool itself — all of it stays and runs where it is asked to. **Gas fires stay
emergent**: a player dumping volatiles off an asteroid to concentrate the ore more cheaply is making
a deliberate trade that can still fill a corridor with methane. What goes away is the frustrating
accident, not the fire.

### 2.2 A store reacts with itself, and with nothing else

Decided 2026-09-04, and it is the other half of §2.1. **Reagents must be in the same store as the
principal.** Oxygen in the room can no longer burn carbon on a belt; a store's rows draw on that
store's contents alone.

⛔ **Why it had to follow.** Gating off-gassing made the coupling one-way: a reaction's gaseous
product stays in cargo (`react` deliberately never decides phase), so a fire on a belt took the
room's oxygen and gave nothing back, and a sealed room headed for vacuum. Either the products go
back to the air — which reverses that rule and reopens the gas-sealed-in-a-bulkhead bug
`SealedTileGasTest` exists for — or the coupling closes. It closed.

**What went with it, deliberately:** iron no longer rusts in the air it passes through, steel no
longer gives its carbon up to a hot room, and a fire no longer dies because the room ran out. Those
were the mechanic `AmbientChemistry` was built around. A player now *blends* a charge — oxygen is a
cargo species you deliver — which is a thing they control rather than a thing that happens to them.

⚠️ **`supplyOf` is where it lived**, and it now reads one store rather than pooling the tile. The
apportionment survives and still matters, because two rows in one packet still contend for its
oxygen; what stopped existing is contention *between* stores. `OxygenWellTest` was entirely about
that and is deleted — the surviving rule is guarded by `AmbientChemistryTest`'s two contention cases.

✅ **Two things fell out that are better than what they replaced.** `BoudouardTest` had complained
that `CO₂ + C → 2 CO` was "a real behaviour, correctly measured, in a configuration the simulation
cannot produce" — a cargo layer could not hold CO₂ above 304 K because `offGas` evicted it. With
off-gassing opt-in that configuration is producible, so the row now fires where it always should
have: inside one hot lump, making carbon monoxide out of its own exhaust. And a packet of iron,
carbon and oxygen held hot together **makes steel**, which was unreachable while the room stole the
carbon first.

⏸ **Deferred, Stu's idea:** packets reacting with the *rail* they are standing on. Kept simple for
now.

## 3. The three machines

### 3.1 Pump — a 1×1 source that packetises the room

Draws gas out of the room it faces and hands **whole packets** to the rail network through an output
port. A continuum-to-packet converter, which is what an extractor already is for rock.

- Keeps a `Product` store, fills it at a rate, ships whole packets and never a runt — the arrangement
  `reference_oos_extractor_one_buffer` and `reference_oos_packets_never_merge` already argue for.
- Ships through the ordinary door, so it obeys demand: nothing is packed for a network that cannot
  use it.
- **Grabs whatever the room holds**, mixed. It does not separate — see §3.3.
- ⚠️ Loses `MILLIMOLES_PER_TICK`'s molar framing. A packet is a mass, so a pump's rate is a mass;
  molar was right when the destination was a pressure, and it is not the unit a belt counts in.

✅ **BUILT.** `MASS_PER_TICK` is a quarter of a kilogram — 16 kg/s, a packet every six seconds, some
fifty pumps to saturate one belt.

⛔ **And it is diffusion-limited in thin air, which is the machine's real shape.** Measured: a pump in
ordinary cabin air manages about **1.5 g a tick** — it strips the one tile it faces and then waits for
the room to refill it — against **250 g a tick**, its full rate, in a hold at forty atmospheres. The
dial is not what bounds it; *what is around it* is. That is the right story for the thing it is for —
a bay an asteroid is off-gassing into, which stays thick — and it makes an intake something worth
building well rather than a fitting to bolt on. Pinned by `a pump in thin air is limited by what
reaches it, not by its own rate`.

### 3.2 Valve — the one place a run may off-gas

A permeable deck machine standing over **track**, marking that tile as a place volatiles may leave
cargo for the room. Everything §2.1 gated is ungated here and only here.

- Reuses the machine, its build cost and its **wiring** — so venting can be switched by a signal,
  which makes "pressurise the cabin on demand" and "dump the volatiles at this bay" the same device.
- Two uses fall straight out: **degassing an asteroid's ore** on the way to a concentrator, and
  **filling a room with something** on purpose.

### 3.3 Concentrator — gas separation, unchanged

⚠️ **Nothing to build.** It separates by `Mixture.dominant` and is phase-blind, so a charge of air
concentrates to nitrogen with the rest to tailings, exactly as ore concentrates to iron. Confirmed
with Stu 2026-09-04: *"it will work identically."* The oxygen loop is therefore
pump → concentrator → storage → thruster, out of machines that all already exist.

Worth **one test** rather than an assumption, since nothing has ever handed it a gas.

### 3.4 Thruster — a rate, a filter, and a real exhaust velocity

Back to what it was, with the physics added:

- **Rail input port and an `Input` store**, as before `8dad7374`.
- **`massPerTick` returns.** Thrust is `massPerTick × v_e`, so propellant choice now changes thrust
  *directly* rather than only efficiency — hydrogen gives 3.7× nitrogen's push at the same rate.
- **A `SpeciesFilter`, tunable like a storage's.** This is the acceptance rule the standing `fire()`
  TODO asked for, it stops a motor being fed gravel, and it hands over the material-restriction lever
  from item 4 of the original list as a setting rather than a ban. Nearly free: `SpeciesFilter` plus
  `sinkAdmits` is the machinery the bridge work just finished.

⛔ **The trade moves from the chamber to the logistics, and that is the good outcome.** With pressure
gone, what makes hydrogen expensive is no longer a thin pipe — it is that a hydrogen line has to
*carry* it. See §7 for the lever that makes that bite.

## 4. Exhaust velocity — built, and unaffected by any of this

✅ `e6f44812`, `107ea1c0`. It reads a `Mixture`; a store holds a `Mixture`. **Nothing here changes.**

The ideal rocket into vacuum, with the expansion term at its limit:

```
v_e = √( 2γ/(γ−1) · R·T/M )
```

Everything was already in the codebase except γ, which is derived from the atom counts in `MINERALS`
rather than tabulated — monatomic, diatomic, polyatomic.

### The integer form is exact

`2γ/(γ−1)` for those three γ is **5, 7 and 8** — equivalently `f + 2` for f degrees of freedom, which
is the arithmetic to check it against. And `Species.molarMass` is already in grams per mole, the unit
`R × 1000` wants:

```
v_e(m/s) = isqrt( K · 8314 · T_kelvin / M_gPerMol )        K ∈ {5, 7, 8}
```

No scale constant, no fixed point, nothing to calibrate. A mixture's K is the **mole-weighted mean**
of its species', exactly rather than approximately, because `K = 2·Cp/R` and a molar heat capacity is
additive over moles — so there is no averaging of γ anywhere.

⛔ **`2γ/(γ−1)` was written as 4 for the monatomic case first time round** (`e6f44812`, corrected in
`92cc1b56`). `f + 2` cannot produce a 4, and that is what the test asserts against now: an expected
value reached by redoing the division that produced the wrong one is not a check.

⚠️ **`millimolesOf` overflows `Long` *negative* on a store-sized heap** — twenty tonnes of hydrogen is
10¹⁹ millimoles — so the single-heap overload goes through `scaledRatio`. It also **floors to zero
below about a millimole**, so a nearly-empty parcel is worth a velocity of 0 rather than a small one,
and anything dividing by v_e must guard that rather than divide by it.

### What it pays out

| propellant | K | T | v_e |
|---|---|---|---|
| CO₂, cold | 8 | 293 K | 0.67 km/s |
| N₂, cold | 7 | 293 K | 0.78 km/s |
| He, cold | 5 | 293 K | 1.74 km/s |
| H₂, cold | 7 | 293 K | 2.92 km/s |
| steam, burned | 8 | 3500 K | 3.60 km/s |
| H₂, heated | 7 | 3000 K | 9.34 km/s |

The old flat 3000 m/s is almost exactly room-temperature hydrogen, so the physics lands on top of
today's behaviour for the best propellant and makes everything else worse — the right direction.

## 5. What this still breaks elsewhere

1. ✅ **`FlightControls` weights motors by thrust, not mass flow** (`8dad7374`). Keep it: with v_e
   varying per motor, weighting by mass would throttle back the engine pushing hardest, and a ship
   with two propellants would turn while flying straight. Only the value it reads changes, from
   chamber pressure back to `massPerTick × v_e`.
2. ✅ **The exhaust's kinetic energy is no longer minted** (`8dad7374`). `½v_e²` *is* the specific
   enthalpy the velocity was derived from, so the old extra `½mv²` on a blocked motor counted the
   same joules twice. Keep it.
3. **`fire()` must read the propellant's real temperature**, not rebuild it at `AMBIENT_KELVIN`. For a
   motor whose v_e depends on T that is the whole mechanic, and it touches the orphan-energy
   invariant.
4. **Save version.** Two format changes land together: a motor's store and rate return, and the pipe
   layer leaves (§9).

## 6. Steps

Each ends at a green gate. Commit directly to main, one focused commit per step.

1. ✅ **DONE — γ from atomicity** (`e6f44812`, corrected `92cc1b56`).
2. ✅ **DONE — `exhaustVelocity(Mixture)`**, pure (`107ea1c0`).
3. ⛔ **BUILT AND TO BE REVERTED — the pipe cell as the chamber** (`8dad7374`). See §8. Three pieces
   of it are keepers and must survive the revert: thrust-weighted flight balance, the un-minted
   kinetic energy, and the `Species.atomsPerMolecule` / `millimolesOf` work.
4. **Gate off-gassing** (§2.1): hoppers never, track only under a valve. ⚠️ **First, because nothing
   else can be tested without it** — a fluid packet evaporates off the belt on its first tick
   otherwise. Gate: a tonne of liquid oxygen sits in a storage indefinitely; ore over a valve tile
   still degasses; the air and cargo ledgers both stay closed.
5. ✅ **DONE — fluids ride rails end to end**, with the pump as the source (§3.1).
   ⚠️ **The `SolidPacket` assertions needed no removing.** `RailLayer` stores a bare `Mixture` and
   everything on a belt is wrapped as a `SolidPacket` whatever it holds, so a gas already rode and
   already survived a save — the `F:`/`FluidPacket` path is simply never taken. Stu called this
   ("I'd imagine this is just pump work") and was right; it is checked rather than assumed by
   `a gas packet survives a save round trip`.
   `applyPumps`, `PumpDemand` and `pumpDemands` are deleted early — nothing else ever called them.
6. ✅ **DONE — the motor back on rails, with a filter** (§3.4). All four parked tests are back and
   green. ⚠️ **The autopilot fixture had to be given hydrogen**: on water (1.04 km/s against the old
   flat 3 km/s) it cut a spin 255× and simply had not finished inside its window. The old constant
   *was* room-temperature hydrogen, so that is the fixture that measures the autopilot rather than
   the propellant — and it is the first place a propellant's worth has shown up as something a ship
   can feel.
7. **Delete the pipe network** (§9). Last, because by here nothing needs it.
8. **Valve as the vent marker** (§3.2), if it has not already fallen out of step 4.

⚠️ **Four tests are `@Ignore`d** on step 3's calibration and come back at step 6:
`the autopilot stops a spin and then stops burning`, `an asymmetric ship goes forward on its rearward
motors alone`, `an off-centre thruster spins the ship`, `a reading stops the machine outright`.

## 7. Packets stay fixed-mass — decided, and parked

**Should a gas packet hold a fixed volume rather than a fixed mass?** It was going to be the lever
that keeps density interesting: make a packet a volume and a hydrogen canister carries far less mass
than an oxygen one, so a hydrogen line delivers fewer kg/s and §3.4's "hydrogen gives 3.7× the push"
stops being a free lunch.

⛔ **It does not survive contact with a real packet, because a packet is mixed phase.** A `Mixture` is
a heap of whatever was scooped, solid and fluid together — a lump of ore carrying its own volatiles
is the ordinary case, not the corner one. So "volume for the solids, mass for the gases" has no
answer for the single number a packet's capacity has to be.

⚠️ **`Capacity.quantityOf`'s own doc has the same flaw**, and it is worth knowing before anybody
reaches for it as the ready-made hook: *"solids/liquids: volume; gases: mass"* is written as though a
packet were one or the other, and no packet is.

**Fixed volume for *everything*** — solids included — is the coherent version, and it is a bigger
question than it looks: it needs a **pressure to store gas at**, which is a design decision with
nothing in the game to derive it from, and it re-prices every belt in the vessel.

**Decided 2026-09-04 (Stu): leave it fixed mass, and come back another day.** Recorded rather than
dropped, because the density trade is real and its absence is a known gap rather than an oversight —
until it is closed, the only thing making a light propellant expensive is that there is less of it
about.

## 8. History — the pipe-fed chamber, and why it turned

Kept because it is the argument for the current shape, and because two of its findings outlived it.

**First attempt: a store fed from the pipe.** Reverted at once. `offGas` walks the buffer layer and
empties every fluid into the room, so propellant drawn into a chamber was back in the air before it
could burn — measured, with the draw running (122 kg, then 1.7 t, then 3 t on successive passes) and
the chamber reading empty at every check while both ledgers stayed closed. It was going pipe →
chamber → room → valve → pipe in a circle. **That finding is now §2.1's foundation**: a machine
buffer could not hold a fluid, so off-gassing had to move before anything else could.

**Second: the pipe cell *as* the chamber** (`8dad7374`). This worked, and deleted a great deal — no
store, no ports, no `gasBecameSolid`, no new term in `massBalance`. What it could not do is supply a
motor: §1's table. A motor emptied its cell every tick, so it had **no rate, only a gulp**, and a
throttle, a flight balance and a wire all need a rate.

**The pivot.** Rails were already carrying 100 kg lumps through a mature demand system with filters,
bridges and twenty-tonne tanks, while plumbing was moving grams by diffusion. Stu, 2026-09-04:
*"the trickle of diffusive pipe transport feels unfit for this purpose… I feel like we'll always be
pushing against the fundamental nature of this system."* §1 is why that reading is right rather than
impatient.

## 9. Deleting the pipe network

Decided 2026-09-04: **entirely**, not scoped down. Half-alive is the worst outcome — a second,
strictly worse way to move a fluid, which players will keep trying because it is there.

Goes: `PipeField.kt`, `Pumping.kt`'s `applyPumps`, `exchangeLayers` (`Interlayer.kt`),
`pipeApertures`, `pipeVolumes`, `PIPE_VOLUME`, `VesselState.pipeAir`, `Conduit.Pipe` and its layer,
the pipe pass in the tick, the pipe inspector layer and overlay, `DeleteLayer.Pipe`, and the pipe
half of `Valve` and `Pump`.

⚠️ **The migration must not leak.** `atmosphereMass` is `air.totalMass + pipeAir.totalMass`, so a load
that moves a pipe cell's gas into the **room on the same tile** leaves `atmosphereMass` — and
therefore `airBalance` — untouched, with no ledger term at all. Only a sealed tile with no room has
to vent, and that books to `airVentedMass` like any other departure. ⛔ Do not simply drop `pipeAir`:
every save with plumbing in it would read as a leak for the rest of its life.

## 10. Explicitly not doing

- **Not** combustion in the chamber. Burning is a reaction run on the parcel before the temperature
  is read — an increment, not a rewrite. Cold gas first, because it decides every shape downstream.
- **Not** selectable nozzle shape. Every nozzle is a perfect vacuum nozzle until something flies where
  there is a back-pressure to expand against; until then expansion ratio is a dial with one correct
  setting, and dropping the term is what keeps §4 in integers.
- **Not** restoring momentum to the gas. A thruster mints its impulse from `mass × v_e` directly and
  is unaffected by the fluid solver's absence — see `PLAN_fluid_extraction.md` §3.
- **Not** exhaust plumes, bloom or thruster audio. Separate work, and it wants this settled first so
  the effect has a real velocity and a real mass flow to read.
