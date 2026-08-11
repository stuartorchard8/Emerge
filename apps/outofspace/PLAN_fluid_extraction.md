# Fluid sim extraction (Option A: freeze in place)

Status: **PLANNED, nothing built** — 2026-08-11.

Move the momentum-solving fluid sim out of Out of Space into its own app, where it stays runnable
and can be returned to. Out of Space keeps a rapid-diffusion air model in its place.

Rationale (Stu, 2026-08-11): the sim is expensive, near-impossible to tune per-fluid against reality,
real-time fluid-driven thrust is a pipe dream (venting imparts negligible momentum on a real vessel),
and convection/stratification/vapour pressure aren't worth their cost. The one thing worth keeping is
**pressure+temperature-dependent phase transitions**, tuned to conform with reality for player legibility.

Docs (`PLAN_phase_transitions.md`, `PLAN_phase_velocity.md`, …) **stay in outofspace for now**.
Revisit after the migration.

---

## 1. The key finding: `world/fluid/` is two different things

The package is not one subsystem. Roughly a third of it is **thermodynamic state readings** the game
still needs after the transport solver is gone — and one of them is load-bearing for `AirField` itself:

```
AirField.pressureAt(tile) → millimolesOf(...)   // world/Atmosphere.kt → world/fluid/Pressure.kt
```

So "delete world/fluid" would take out the pressure reading behind every gauge, sensor and HUD line.
The cut has to run *through* the package, not around it.

| Stays in Out of Space | Leaves for the new app |
|---|---|
| `Pressure.kt` — `AMBIENT_PRESSURE`, `tilePressure`, `millimolesOf` | `StepFluid.kt`, `Advection.kt`, `MomentumAdvection.kt`, `MomentumField.kt` |
| `Thermal.kt` — `gasCapacity`, `gasCapacityAt`, `gasKelvin`, `ambientGasJoules` | `Projection.kt`, `PressureForce.kt`, `Buoyancy.kt`, `Drag.kt` |
| `EdgeGrid.kt`, `Apertures.kt` — face connectivity, which diffusion still needs | `Drift.kt`, `Interlayer.kt`, `Pump.kt`, `PipeField.kt` |
| `PressureForce.kt` — vessel thrust from blocked flux (§3) | `Volume.kt` — *decide at cut time* (§6.1) |
| `FaceMass.kt` — needed by `PressureForce.kt` | |
| `FlowField.kt` — the overlay keeps reading `momentum` (§3.3) | |

The survivors should move up into `world/` (as `world/Gas.kt` or similar) so that `world/fluid/`
ends up **empty and deleted**, rather than lingering as a half-package.

**`chem/` is not affected.** It has zero dependency on `fluid/` (its only outward import is a colour
helper for the renderer) and the game already uses it directly from `Composition`, `Storages`,
`Vaporizer`, `RockSpawner` and `Edit`. Phase transitions — the part worth keeping — survive untouched.
This is the lucky part: the thing Stu wants to keep is already the least entangled thing in the codebase.

## 2. The coupling being cut

Two-way, and the game side is concentrated in one function:

- **game → fluid**: `OutofspaceSim.kt` (13 imports, all in the tick body, lines ~148–356), `Vessel.kt`,
  `Save.kt`, `OutofspaceRenderer.kt`.
- **fluid → game**: `Grid`, `AirField`, `StructureMap`, `Conduits`/`Conduit`, `Pump`, `Machine`,
  `Airlock`, `SignalField`, `Temperature`, `Direction`, `Action`, plus `chem` and `Frac2`.

That second column is why the new app can't be a pure lift: it inherits a slab of vessel world model
just to compile. That is the accepted tax of Option A (§8).

**Measured at step 2** (the estimate above was ~950 lines; the transitive closure is bigger):
**1,565 lines** across 19 `world/` files + `logistics/Packet.kt`, carrying 2,614 lines of fluid and
1,116 of chem. The closure pulled in more than the direct-import list predicted — `StructureMap` needs
`Structure` + `coveredTiles` (`Occupancy.kt`), `Machine` needs `MachineKind`/`Footprint`/`Composition`/
`Wiring`, `Segment` needs `logistics/Packet`, `SignalField` needs `SignalNetworks`. Worth knowing
before step 5: **those same files are what the diffusion model will still need**, so none of this is
wasted — the tax is duplication, not dead weight.

## 3. Momentum: the fields stay, and thrust survives via blocked flux

**Revised 2026-08-11 (Stu).** The earlier version of this section removed the momentum fields and let
vessel thrust go to zero. That is no longer the plan: **leave the fields in place, they may well be
reused.**

The reason it works is that vessel thrust can be computed *without* the momentum solver, from the
portion of the fluid that tries to push into solid tiles and can't. That mechanism is
**already implemented** — `applyPressureForce` (`world/fluid/PressureForce.kt:12`):

```kotlin
} else {
    vesselX += drop          // closed face: the whole pressure drop goes to the hull
}
// partially-open face:
vesselX += drop - toGas      // "what the solid part of a restriction took"
```

and its own doc comment states the property being relied on: *"Reaction on hull (rocket thrust emerges
from breach). Internal terms telescope exactly (sealed vessel → zero net force)."*

So `PressureForce.kt` **stays in Out of Space**, along with its dependencies — `EdgeGrid`,
`ApertureField`, the pressure array (`Pressure.kt`) and `FaceMass.kt`. Cost is O(faces): no projection,
no Jacobi iterations, no momentum advection. This resolves open question §6.1: **`FaceMass.kt` stays.**

Consequences, stated honestly:

1. **`VesselState.momentum` / `.pipeMomentum`** (`Vessel.kt:213,263`) — **kept**. No save-format change,
   no version bump, no migration. `Save.kt` untouched.
2. **`exhaustMomentumX/Y`, `undeliveredImpulseX/Y`** — **kept**.
3. **The flow overlay** (`Vessel.kt:378`) — **kept as-is**; it still reads `momentum`. No need to
   re-derive it from flux. But see (6): if nothing writes momentum the overlay renders zeros, so it
   goes quiet rather than wrong.
4. **Thrust magnitude is unchanged, not improved.** This computes the same term as today, so venting
   still imparts negligible momentum on a real vessel — Stu's point 3 stands. What is gained is
   keeping the physics at near-zero cost, not making it larger.
5. **No gravity ⇒ exactly zero, not approximately zero.** Without `Buoyancy.kt` there is no hydrostatic
   gradient, so in a sealed room at uniform pressure `drop` is 0 across every face and net force is
   identically zero. Force arises only from genuine asymmetry — a breach, or a real pressure gradient.
   Consistent with dropping stratification, but it means no gravity-driven settling until buoyancy
   returns.
6. **Open decision — unbounded momentum.** `applyPressureForce` also does `mx[e] += toGas`, depositing
   the gas share into the momentum field. With no advection or projection to dissipate it, momentum
   accumulates without bound. Options: zero the fields each tick (simplest, overlay stays quiet), skip
   the deposit and compute only `vesselX/Y`, or keep a decay term. **Stu's call.**
7. **Magnitude will be tuned to feel, not measured.** (Stu, 2026-08-11.) The pressure field feeding
   this comes from a different transport model, so thrust won't match today's figures — and that is
   fine. No measurement gate, no expected-value comparison against the old solver. Tune it once the
   migration is done.

## 4. The replacement: rapid diffusion

Stu's specification: cyto's welded-cell model, where cells dump practically all their contents into
their neighbours expecting the neighbours to do the same, which implicitly maintains a fast-moving
equilibrium. Species selection will later be tweaked to interface with gravity/phase/density; that is
explicitly **out of scope here**.

The reference implementation is `CytoBiologyCore.kt:628–688`. Its shape, worth copying exactly:

- **Two passes.** Pass 1: every tile *gathers* its own net Δ, reading its own and its neighbours'
  pre-diffusion contents, writing only its own delta scratch. Pass 2: apply own delta to own contents.
  Disjoint by index ⇒ trivially parallel, and order-independent (no arrival-order bug).
- **Conservation is by construction, not by correction.** The RECEIVE and SEND loops use the same
  directed-edge gate and the same integer quantum `out = count / DENOM`, so what one tile sheds is
  exactly what its neighbours take. No ledger fixup pass.
- **A fixed divisor, not a per-degree one.** Cyto's comment (`CytoTuning.kt:250–255`) is the hard-won
  part: dividing by the tile's own degree biases high-degree tiles into piling up ~2× their neighbours.
  A fixed divisor is edge-symmetric (Fickian) ⇒ steady state is uniform, and the divisor sets only the
  *speed*, never the bias. It must stay `≥ max degree` so the integer floor guarantees
  `out·degree ≤ count` and no tile goes negative. In 2D on a grid max degree is 4, so **`DENOM = 5`**
  is the tightest value with no convergence transient — the "practically all" Stu is describing.

Grams and joules both diffuse (joules with the same quantum, so temperature rides along with mass).
Face eligibility comes from the existing `EdgeGrid` + `ApertureField` + `structure`, unchanged — walls,
airlocks and valves keep working, since aperture is already a per-face openness.

**Venting to the rim** stays: a rim face with no neighbour sheds its quantum into space, booking
`airVentedGrams`/`airVentedJoules` as today. That keeps breach behaviour and the vent ledger alive —
it just no longer produces thrust.

**Pipes** run the same pass on the pipe layer with `plumbing` apertures. `exchangeLayers` (valves) and
`applyPumps` become simple mass/joule transfers between layers with the momentum arguments dropped.

## 5. Steps

Each step ends at a green gate; commit directly to main, one focused commit per step.

1. **Stamp the new app.** `tools/new-app.sh fluidlab`. Verify `:apps:fluidlab:desktop:run` before
   touching anything else.
2. **Copy** (do not move) `world/fluid/` + the 14 `commonTest/.../fluid/` tests into fluidlab, plus the
   world-model types they need (§2, second column). Copy `chem/` too — the sim needs it and fluidlab
   must not depend on outofspace. Renaming packages `demo.outofspace` → `demo.fluidlab`.
   Gate: fluidlab's own tests green, in isolation. **Out of Space is untouched at this point** — the
   freeze is complete and safe before any deletion happens.
   ✅ **DONE 2026-08-11.** 123 tests, 0 failures (2 pre-existing `@Ignore`s), JVM + JS both compile.
   `chem` was severed from the renderer by inlining `speciesColor`'s 13 palette constants into
   `chem/SpeciesColor.kt` — cheaper than carrying a 1,000-line renderer across for one colour lookup.
   ⚠️ Out of Space had **pre-existing** test failures before this work began (`RockTest`, `FlightTest`,
   `RockContactTest`, `PumpTest`, `ProcessorChainTest` — bodies "hung in the air", a gravity fault).
   Confirmed by Stu as predating the extraction. **Not caused by, and not in scope for, this work** —
   but it means "Out of Space is green" is not available as a gate for steps 5–7.
3. **Give fluidlab a harness.** A minimal driver (headless tick + the existing overlays) so it is a
   *runnable* sim and not an archive. This is the whole point of A over "tag and delete", so it isn't
   optional. `BoilingTest`, `BreachSymmetryTest` and `ThrustBalanceTest` come along as its
   acceptance tests.
4. **Build rapid diffusion in Out of Space**, behind the existing call sites, with the momentum
   arguments still present but ignored. Gate: new `RapidDiffusionTest` (equilibrium reached, mass and
   joules conserved exactly, no negative tiles, rim venting books correctly).
5. **Cut over.** Replace `stepFluid`/`exchangeLayers`/`applyPumps` in the tick, promote the surviving
   helpers out of `world/fluid/`, delete the package.
6. **Wire thrust to blocked flux** (§3). Momentum fields, save format and ledger fields are all left
   alone; the work is keeping `applyPressureForce` fed from the diffusion model's pressure field, and
   settling §3.6 (unbounded momentum). No measurement gate (§3.7).
7. **Sweep the tests** (§7). Delete the solver tests; keep thin feature coverage.

## 6. Open questions — answer before step 4

1. ~~`FaceMass.kt` / `Volume.kt`~~ — **resolved by §3**: `FaceMass.kt` stays (`PressureForce.kt` needs
   it), as does `FlowField.kt`. Only `Volume.kt`/`VolumeField` is still open, and it follows the pipe
   layer: it stays iff pipes keep a volume-aware capacity under diffusion.
2. **§3.6 — the unbounded-momentum decision.** Blocks step 6.
3. ~~Which Out of Space tests survive?~~ — **resolved by §7.**
3. **The `@Ignore`d solid-mass test** from the dynamic-grid work (pending a rigid-body rework) — check
   whether it is fluid-coupled and so becomes fluidlab's problem.

## 7. The old tests

**Stu, 2026-08-11:** migrate to the new app or delete outright; leaning delete. Recorded as: **delete
by default, copy to fluidlab only where it's free.**

The 20 affected tests are not one group, and the split matters because one group tests something that
is going away while the other tests things that are staying:

**Group A — the 14 in `commonTest/.../fluid/`.** These test the departing solver: `AdvectionTest`,
`ProjectionTest`, `MomentumAdvectionTest`, `BuoyancyTest`, `DriftTest`, `ThermalTest`, `VolumeTest`,
`FluidFieldTest`, `SparseFieldTest`, `FlowFieldTest`, `PressureForceTest`, `BoilingTest`,
`BreachSymmetryTest`, `ThrustBalanceTest`. **Delete from Out of Space.** They come along to fluidlab in
step 2 as a byproduct of copying the package — that copy is free, so take it; nothing is maintained on
the Out of Space side.

> **Step 2 correction:** the copy was free for 11 of the 14. `BreachSymmetryTest`, `ThermalTest` and
> `ThrustBalanceTest` are whole-*vessel* integration tests — they need `OutofspaceController`,
> `VesselState`, `OutofspaceReducer` and `starterVessel`, i.e. the entire game — so taking them would
> have dragged Out of Space into fluidlab and defeated the extraction. **Not copied.** This lands
> tidily: the sealed-vessel invariant carve-out below wants to live in Out of Space anyway, which is
> exactly where those two tests already are.

**Group B — the 6 that assert *through* the solver at the game level:** `AtmosphereTest`,
`PipeFluidTest`, `ValveTest`, `PumpTest`, `AirlockTest`, `GridVentTest`. These are not solver tests —
they cover **features Out of Space keeps**: a valve opens, a pump moves mass, an airlock seals, a
breach books the vent ledger. Deleting them outright drops coverage of surviving features, which is a
different trade from deleting Group A.

Default taken: **rewrite Group B thin against diffusion** — assert the feature, not the flow field
(`mass moved from A to B`, not `it moved this much this fast`). That is a much smaller job than the
original step 7, since the fiddly solver-shaped assertions are exactly what gets dropped, and it
carries no tuning figures to fight with later. Say the word and they go entirely instead.

**One carve-out worth keeping** (§3): the sealed-vessel-nets-to-zero invariant. It isn't a feel
question or a magnitude — if the telescoping in `applyPressureForce` breaks, a sealed ship accelerates
from nothing, and that reads as a mystery bug rather than as something needing tuning. Suggest keeping
it as a single cheap assertion (sealed hull, N ticks, net impulse still 0) and deleting the rest of
`BreachSymmetryTest`/`ThrustBalanceTest` around it. Overrule and it goes with the others.

## 8. Explicitly not doing

- **Not** extracting behind a `FluidHost` interface (Option B). That interface would be designed
  against a hypothetical future game whose needs aren't known yet. Freeze it working; B stays
  available later from a better starting point.
- **Not** carrying the plan docs to fluidlab yet (§ header).
- **Not** tuning species/gravity/phase/density interaction in the diffusion model — later, per Stu.
