# Fluidlab

A grid-based, momentum-solving fluid simulation: advection, a pressure projection, buoyancy, drag,
species drift, thermal transport, and van der Waals phase transitions.

It was Out of Space's atmosphere. **It is not any more** — that game replaced it with a much cheaper
rapid-diffusion model, and the solver was moved here rather than deleted so it stays runnable. See
`apps/outofspace/PLAN_fluid_extraction.md` for the reasoning and the file-by-file split.

## Why it left

Stated plainly, because it is the first thing anyone returning here will want to know:

1. It is expensive to run.
2. Tuning each fluid to behave like a gas or a liquid at the right temperature and pressure — let
   alone freezing into a solid — is near impossible.
3. Real-time fluid-driven thrusters are a pipe dream. Venting atmosphere at realistic pressures and
   densities imparts negligible momentum on a vessel of any sensible mass.
4. Convection, stratification, continuous phase transitions and vapour pressure are genuinely
   interesting, but they did not earn their computational or developmental cost in a game about
   running a ship.

None of that means the simulation is wrong. It means it was the wrong thing to have *inside a game
about vessels*. Here, where the solver is the whole program rather than a subsystem, those same
properties are the point.

## What is here

- `core/.../world/fluid/` — the solver. `stepFluid` is the entry point.
- `core/.../chem/` — species, mixtures, the van der Waals equation of state, saturation. Phase is a
  *reading* of density and temperature, never a stored enum.
- `core/.../world/` — the slab of vessel world model the solver reaches back into (grid, structure,
  conduits, machines). Inherited so this compiles standalone; it is not a game.
- `core/.../FluidlabSim.kt` — the lab itself: a grid, walls, air, and a tick that calls the solver.

## Running it

```sh
./gradlew :apps:fluidlab:desktop:run          # the window
./gradlew :apps:fluidlab:core:jvmTest         # the suite
```

In the window: click a tile to toggle a wall (breach the room, or seal it back up), `O` cycles the
overlay, `.` single-steps, `space` pauses, `F` fits the view, `[`/`]` change speed.

### The harness is the better tool

```sh
./gradlew :apps:fluidlab:desktop:fluidlabAgent --args="apps/fluidlab/agent-scripts/breach.fl"
```

Headless, scriptable, no GL. For fluid work this beats looking at it, and that is a lesson inherited
rather than a limitation: a screenshot says "there is more pressure over there", while `field
pressure` prints the numbers, so "the plume is asymmetric" becomes something you can read instead of
squint at. Every model correction this simulation has had came from two quantities disagreeing.

Scripts live in `agent-scripts/`. `expect` makes one an acceptance test — it exits non-zero on
failure, so CI notices. Full command reference is in the KDoc at the top of `FluidlabAgentHarness.kt`.

## Things that will bite

- **`latentHeat` breaks the ledgers on purpose.** With it on, boiling and condensing move energy into
  a reservoir nothing here counts, so mass/energy totals stop balancing. That is not a bug; teaching
  the ledger the third term is unfinished work. `cohesionUnpaid` staying at zero is the property that
  *is* meaningful.
- **`subSteps` and `undelivered` are error terms.** The solver reports rather than hides them. Either
  one growing means what you are looking at is discretisation, not physics.
- **Don't pin literals in tests.** Assert a total against its own parts, or a direction, or a
  neighbourhood. A pinned magnitude here is pinning today's discretisation.
- **Three of Out of Space's fluid tests did not come across** — `BreachSymmetryTest`, `ThermalTest`
  and `ThrustBalanceTest` are whole-*vessel* integration tests and need the game. They stayed there.
