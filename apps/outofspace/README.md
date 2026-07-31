# Out of Space

A 2D side-on management/automation game about building and running space vessels.

Design and phasing: **`docs/out-of-space-plan.md`**. Read that first — it records the decisions
(square lattice, vessel-local gravity kept as an explicit parameter, no inhabitants yet, first
playable is one vessel interior with no flight) and what is deliberately deferred.

## State of the app

**Phases 1 and 2 are built.** The game runs: a refinery line digs ore, concentrates it, smelts it,
banks iron ingot and vents the waste — and you can build more of it with the mouse.

| File | What it is |
| --- | --- |
| `chem/Species.kt` | `Species` (what matter is made of) and `Phase`. Solid vs fluid is the split logistics cares about. |
| `chem/Mixture.kt` | `Mixture` — integer grams per species. Plus `apportion`, the exact proportional split every other operation rests on. |
| `chem/Form.kt` | `Form` (what matter has been made into), the smelt table, and the binary crafting tree as data. |
| `chem/Chemistry.kt` | `smelt`, `process`, `craft`, `merge`, `takeFrom`, `conservationOf`. |
| `logistics/Packet.kt` | 1 kg packets (`SolidPacket` / `FluidPacket`), `Capacity` (the one home for the eventual volume switch) and `Rate` (integer carry, so 1 kg/s at 60 Hz stays exact). |
| `world/Grid.kt`, `world/Machine.kt` | The square lattice, `Direction`, and the machines: belt (four slots), miner, processor, smelter, node, vent. |
| `world/Vessel.kt` | `VesselState` (the snapshot) and `Stockpile` (the global construction inventory the node feeds). |
| `world/StarterVessel.kt` | The world the game opens on: one complete line, already running. |
| `OutofspaceSim.kt` | The reducer — five ordered passes per tick. `OutofspaceController` is the real-time boundary; the renderer and HUD sit beside them. |

Nothing of the app template's placeholder world remains.

```bash
./gradlew :apps:outofspace:core:jvmTest    # 67 tests, well under a second
./gradlew :apps:outofspace:desktop:run     # the game
```

Click to place, drag to paint a line of them, right-click to remove. `R` rotates the brush, `1`–`6`
pick a machine, middle-drag pans, wheel zooms, space pauses.

## The invariant to keep

`mined == in transit + banked + vented`, on every tick. A miner is the only place matter legitimately
enters the world and a vent the only place it leaves, so that one line catches a whole category of
logistics bug — a packet duplicated on handoff, a jam that eats a slot, a buffer overwritten instead
of merged. It is asserted in the tests and shown live in the HUD, and it is the first thing to look
at when something feels wrong.

## The two rules of the chemistry layer

**Mass is an integer.** Floats would make the same world diverge between two machines and make
conservation approximate. "Where did the mass go" is the only bug this simulation is really capable
of having, so it is made exactly checkable.

**Conservation is structural, not tested-for.** Every operation that divides matter computes *one*
output and derives the other as `input - output`. There is no arithmetic path that can lose or
invent a gram. `conservationOf` exists so tests can assert it mineral by mineral — a total can
balance while iron quietly turns into copper.

## Where the ideas came from

The blended resource model is carried over from the Godot game at `~/out-of-space` (which stands on
its own and is not being replaced): ore is never "iron ore", it is a composition of minerals, and
refining efficiency is capped by the input's own purity. See `docs/out-of-space-plan.md` §2 for what
was taken, what was left behind, and the one deliberate divergence from its recipe table (the named
`*_ore` forms are gone; smelting blended ore is the only route into tier one).
