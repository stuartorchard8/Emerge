# Decoupled ticks — bitmask frequency selection

*Written 2026-08-13. Supersedes nothing — this is new infrastructure that sits alongside the existing
single-rate `reduce()` and does not change it until the gate is green.*

**NOT BUILT.**

---

## Motivation

`OutofspaceConfig.ticksPerSecond` currently governs **everything** — physics, fluid diffusion, heat
conduction, machines, rails — in one `reduce()` call per tick. At `tps = 4`, the fluid runs four
times a second, the smelter four times a second, and the belt four times a second. At `tps = 64`,
the smelter fires 64 times a second, which is wrong (it should be once per simulated second).

The goal is **decoupled rates**: a fixed 64 TPS base sim loop where each subsystem has an activity
fraction that determines how often it runs within each 64-tick block. No rate scaling — if a system
needs more fidelity it runs more often and gets more tick time.

## Design: bitmask frequency selection

```kotlin
data class OutofspaceConfig(
    val ticksPerSecond: Int = 64,
)
```

64 is chosen as the base rate because every subsystem rate divides it evenly and 64 gives enough
range — signals/flight at full 64 Hz, machines at 1 Hz, fluid at 2 Hz, heat at 16 Hz.

Each subsystem has a period (number of sim ticks between activations). On each tick:

```kotlin
if (state.tick % subsystemPeriod == 0) {
    // subsystem runs this tick
}
```

A subsystem at period `P` runs every `P` ticks, i.e. at `tps / P` Hz. Periods are powers of 2.

| Subsystem | Period | Hz | Rationale |
|-----------|--------|-----|-----------|
| Signals, structure, valves | 1 | 64 | Pure derivation from grid state; should see latest state every tick |
| Fluid diffusion | 1 | 64 | Implicit Euler, stable at any rate — tuned later if needed |
| Heat conduction | 1 | 64 | Conduction at physics speed — tuned later if needed |
| Pumps | 1 | 64 | Read signals and valve state; should track signal changes |
| Pressure force | 1 | 64 | Depends on fluid state which runs at physics speed |
| Machines (extractor, processor, smelter, vaporizer) | 1 | 64 | Coincide with physics for continuous buffer consumption and impulse |
| Thrusters | 1 | 64 | Continuous buffer consumption and impulse application at physics speed |
| Rails/bridges | 1 | 64 | Packet movement at physics speed — tuned later if needed |
| Flight (pose integration, bodies) | 1 | 64 | Numerical integration needs full rate for stability |

**Note:** All systems run at 64 Hz for now. The bitmask structure is in place for you to tune individual periods to your desired rates (e.g., fluid at period 2 = 32 Hz, machines at period 64 = 1 Hz) once the sim is stable.

### Example

With `tps = 64`:
- Tick 0:  **everything** runs (all periods divide 0)
- Tick 1:  signals, valves, pumps, pressure, flight run
- Tick 2:  signals, pumps, pressure, flight + fluid
- Tick 3:  signals, pumps, pressure, flight
- Tick 4:  signals, pumps, pressure, flight + heat
- Tick 63: signals, pumps, pressure, flight
- Tick 64: **everything** runs again (tick 64 % period == 0 for all periods)

Tick 0 is special — everything runs at tick 0 because `0 % P == 0` for all P. This is correct:
the first tick should initialize all systems simultaneously.

## Implementation

The reducer stays as a single function that returns `VesselState` once per call. The loop structure
is flat — no nested sub-tick loops. Each subsystem is guarded by its period check:

```kotlin
object OutofspaceReducer : SimReducer<OutofspaceConfig, VesselState, OutofspaceInput> {

    // Periods — powers of 2, must divide ticksPerSecond evenly.
    companion object {
        const val FLUID_PERIOD     = 1   // 64 Hz
        const val HEAT_PERIOD      = 1   // 64 Hz
        const val PUMP_PERIOD      = 1   // 64 Hz
        const val PRESSURE_PERIOD  = 1   // 64 Hz
        const val MACHINE_PERIOD   = 1   // 64 Hz (machines coincide with physics)
        const val FLIGHT_PERIOD    = 1   // 64 Hz
    }

    /** Runs on tick 0 (all periods divide 0). */
    private fun shouldRun(tick: Long, period: Int): Boolean = tick % period == 0L

    override fun reduce(
        cfg: OutofspaceConfig,
        state: VesselState,
        inputs: Map<PlayerId, OutofspaceInput>,
    ): VesselState {
        val w = Work(state)

        // ── Edits ───────────────────────────────────────────────────────────
        var heldKeys = 0
        for ((_, input) in inputs.entries.sortedBy { it.key.value }) {
            for (edit in input.edits) w.apply(edit)
            heldKeys = heldKeys or input.heldKeys
        }

        // ── Machines ────────────────────────────────────────────────────────
        // Signals/networks/structure/openness only change when machines change,
        // so we compute them here and reuse between machine ticks.
        var networks = SignalNetworks.none(w.grid.size)
        var signals = SignalField.none(w.grid.size)
        var openness = IntArray(w.machines.size) { 0 }
        var structure = StructureMap.derive(w.grid, w.machines, openness)
        if (shouldRun(state.tick, MACHINE_PERIOD)) {
            networks = SignalNetworks.derive(w.grid, w.conduitsSnapshot())
            w.networks = networks
            signals = SignalField.build(networks) { ... }
            w.signals = signals
            openness = airlockOpenness(w.machines, signals)
                ?: IntArray(w.machines.size) { 0 }
            structure = StructureMap.derive(w.grid, w.machines, openness)

            for (tile in w.grid.tiles) {
                val m = w.machines[i] ?: continue
                val activation = m.wiring.activation(Action.Run, signals.at(i))
                w.machines[i] = when (m) {
                    is Extractor -> w.leech(m, activation, i)
                    is Processor -> w.refine(cfg, m, activation, i)
                    is Smelter -> w.melt(cfg, m, activation, i)
                    is Vaporizer -> w.vaporize(m, activation, i)
                    is Thruster -> w.fire(cfg, m, activation, i, structure)
                    else -> m
                }
            }

            val ports = w.portsByTile(Conduit.Rail)
            if (state.tick % Bridge.STEP_TICKS == 0L) w.advanceRails(ports)
            for ((tile, at) in ports) for (port in at) {
                if (port.kind == PortKind.Output) w.pushOut(tile, port)
            }
        }

        // ── Heat ────────────────────────────────────────────────────────────
        var conductedRadiated = 0L, conductedToAir = 0L
        if (shouldRun(state.tick, HEAT_PERIOD)) {
            for (tile in w.grid.tiles) {
                val added = w.heatAdded[i]
                if (added == 0L) continue
                val m = w.machines[i] ?: continue
                w.machines[i] = m.withEnergy(m.energy.plusSpread(added))
            }
            val bodies = bodiesOf(state.grid, w.machines, w.conduitsSnapshot(), w.bridges)
            val result = stepSolidHeat(state.grid, bodies, structure, w.airEnergy, ...)
            conductedRadiated = result.radiated
            conductedToAir = result.toAir
            w.applyBodyHeat(bodies, result.energy)
        }

        val edges = EdgeGrid(state.grid)
        val conduits = w.conduitsSnapshot()
        val roomApertures = ApertureField.derive(edges, structure, openness)
        val plumbing = pipeApertures(edges, conduits)
        val volumes = pipeVolumes(state.grid, conduits)

        // ── Valves + Pumps ──────────────────────────────────────────────────
        if (shouldRun(state.tick, PUMP_PERIOD)) {
            exchangeLayers(..., roomMass = w.airMass, ...)
            applyPumps(..., roomMass = w.airMass, demands = pumpDemands(..., signals), ...)
        }

        // ── Pressure ────────────────────────────────────────────────────────
        // Results used by flight below, so computed unconditionally.
        val roomPressure = tilePressure(..., w.airMass, ...)
        val pushed = applyPressureForce(edges, roomApertures, w.momentumX, w.momentumY, ...)
        val pipePressure = tilePressure(..., w.pipeMass, ..., volumes)
        val pipePushed = applyPressureForce(edges, plumbing, w.pipeMomentumX, w.pipeMomentumY, ...)

        // ── Fluid ───────────────────────────────────────────────────────────
        var fluidAir = state.air, pipeAirResult = state.pipeAir
        var fluidVentedMass = 0L, fluidVentedEnergy = 0L
        if (shouldRun(state.tick, FLUID_PERIOD)) {
            val result = diffuseFluid(edges, roomApertures, w.airMass, w.airEnergy)
            fluidAir = result.air; fluidVentedMass = result.ventedMass; fluidVentedEnergy = result.ventedEnergy
            val pipes = diffuseFluid(edges, plumbing, w.pipeMass, w.pipeEnergy)
            pipeAirResult = pipes.air
            require(pipes.ventedMass == 0L && pipes.ventedEnergy == 0L) { ... }
        }

        // ── Flight ──────────────────────────────────────────────────────────
        // Pose integration, body drift, collision, gravity, torque
        // (always runs — period 1)
        val mass = vesselMass(w.machines.toList(), conduits, w.bridges.toList())
        // ... rest of flight code using pushed, pipePushed, structure, etc.

        return state.copy(
            machines = w.machines.toList(),
            radiatedEnergy = state.radiatedEnergy + conductedRadiated,
            solidToAirEnergy = state.solidToAirEnergy + conductedToAir,
            air = fluidAir, pipeAir = pipeAirResult,
            airVentedMass = state.airVentedMass + fluidVentedMass + w.exhaustAirMass,
            airVentedEnergy = state.airVentedEnergy + fluidVentedEnergy + w.exhaustAirEnergy,
            // ... etc.
        ).bookedFrameTurn(state.pose).resized(w.fitRequested)
    }
}
```

### Key design decisions

**No rate scaling.** Machines move `massPerTick` kg per machine-tick. Each system moves its full
amount when it fires. The *relative* rates between subsystems are fixed by their periods.

**Tick 0 is warm-up.** All periods divide 0, so everything runs at tick 0. This is correct:
the first tick initializes all systems simultaneously.

**Signals inside machine block.** Signals are computed alongside structure/openness inside the
machine block. Signals only drive machines, so they only need to be valid when machines fire.
Between machine ticks, signals are stale but nothing reads them. `airlockOpenness` can return
`null` when there are no airlocks — use `?: IntArray(...) { 0 }` to handle this.

**Dependencies still hold.** Systems that depend on other systems' outputs run at least as
often as the systems they depend on. Heat depends on `structure` (which lives in the machine
block) and `w.heatAdded` (written by machines). Heat runs less often than machines, so it
reads structure that's at most one machine-tick old — fine since structure only changes on
machine ticks.

**Flight always runs.** Pose integration is a numerical integrator that must run every tick for
stability. It reads `structure` and `pushed`/`pipePushed` which are always up to date.

**Heat and fluid carry forward.** When heat/fluid don't fire, their output variables retain
their previous tick's values (carried forward from `state.air`, etc.). This keeps the ledger
closed.

## Controller changes

No changes needed. The controller still calls `tick(delta)` once per frame accumulator step,
and `reduce()` returns one `VesselState`. `tickAlpha` interpolation works unchanged.

## Tests to update

| Test file | What changes | Risk |
|-----------|-------------|------|
| `ThrusterTest` | Machines run at 1 Hz instead of 4 Hz (or 64 Hz); fewer total machine actions | Medium — may need rebaseline |
| `PumpTest` | Same — fewer pump activations per real second at default | Medium — may need rebaseline |
| `ValveTest` | Valves at 64 Hz — no behavioral change | Low |
| `TransportTest` | Rail packets advance at 1 Hz — fewer advances per tick | Medium — may need rebaseline |
| `VaporizerTest` | Vaporizer at 1 Hz — slower gas production | Medium — may need rebaseline |
| `BodyHeatTest` | Heat at 16 Hz — slower equilibration | Medium — may need rebaseline |
| `AtmosphereTest` | Fluid at 32 Hz — smoother gradients than before | Low — same total diffusion |
| `FlightTest` | Flight at 64 Hz — smoother trajectory | Low — same physics |
| `MotionTest` | Unchanged | None |
| `RotationTest` | Same physics, full rate for pressure/torque | Low |

Tests that assert specific tick counts need to account for the new period-based execution.
Tests that assert physical quantities after a fixed *real time* (not sim ticks) should be
unaffected.

## What comes next (out of scope for this plan)

After this bitmask approach is green:
1. **Tune periods** — you will adjust individual periods to desired rates (e.g., fluid at 32 Hz,
   machines at 1 Hz) based on gameplay feel.
2. **Configurable periods** — expose period constants in `OutofspaceConfig` so they can be
   tuned per deployment.
3. **Variable base rate** — if the sim needs to run at different base rates (e.g., 32 TPS for
   low-end devices), periods need to divide evenly into that rate too.

## ⚠️ Constraints

- **Periods must divide `ticksPerSecond` evenly.** With `tps = 64`, valid periods are powers of 2
  up to 64. The current set (1, 2, 4, 64) all divide 64.
- **Tick 0 runs everything.** `0 % period == 0` for all periods. This is correct — the first tick
  initializes all systems simultaneously.
- **Systems at lower frequency see stale data.** A system at period P sees data that is at most
  P-1 ticks old from its dependencies. For the current period choices, the maximum staleness is:
  - Machines reading signals: 0 ticks (signals at 1 Hz, machines at 64 Hz → signals always fresh)
  - Pressure reading fluid: 1 tick (fluid at 2 Hz, pressure at 1 Hz → pressure reads fluid from 1 tick ago max)
  - Fluid reading structure: 0 ticks (structure at 1 Hz, fluid at 2 Hz → structure always fresh)
  - Heat reading machine energy: 3 ticks (machines at 64 Hz, heat at 4 Hz → heat reads machine energy from up to 3 ticks ago max)
- **Lowering `tps` without adjusting periods breaks things.** If `tps` is lowered to something
  that doesn't divide the periods (e.g., `tps = 3`), the periods no longer divide evenly and
  systems will run at incorrect effective rates. With `tps = 64` this is not a concern.
- **`tickAlpha` is trivially correct.** The reducer always returns state at the sim rate. No
  sub-tick interpolation needed.

## Summary

| Before | After (tps=64, bitmask) |
|--------|--------------------------|
| One `tps` drives everything | 64 TPS base, each subsystem at its own period |
| Machines run `tps` times/sec | Machines run `tps/64` times/sec (1 Hz at 64 TPS) |
| Fluid runs `tps` times/sec | Fluid runs `tps/2` times/sec (32 Hz at 64 TPS) |
| Heat runs `tps` times/sec | Heat runs `tps/4` times/sec (16 Hz at 64 TPS) |
| All subsystems at same rate | Subsystems at different rates based on activity |
| Rate scaling needed | No rate scaling — each system moves full `massPerTick` when it fires |

**Incremental risk**: low. The reducer still returns `VesselState` once per call. The state space
is unchanged. The only behavioral difference is that some systems run less often than before (machines)
and some run more often (fluid, heat at 64 TPS). All systems still execute at least once per
machine-tick-equivalent (64 sim ticks = 1 simulated second).
