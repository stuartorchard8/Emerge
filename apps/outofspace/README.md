# Out of Space

A 2D side-on management/automation game about building and running space vessels.

Design and phasing: **`docs/out-of-space-plan.md`**. Read that first — it records the decisions
(square lattice, vessel-local gravity kept as an explicit parameter, no inhabitants yet, first
playable is one vessel interior with no flight) and what is deliberately deferred.

## State of the app

**Phases 1, 2 and 3 are built.** The game runs: a refinery line digs ore, concentrates it, banks it
and vents the waste; you can build more of it with the mouse; and you can wire machines to sensors so
the vessel runs itself.

| File | What it is |
| --- | --- |
| `chem/Species.kt` | `Species` (what matter is made of) and `Phase`. Solid vs fluid is the split logistics cares about. |
| `chem/Mixture.kt` | `Mixture` — integer grams per species. Plus `apportion`, the exact proportional split every other operation rests on. |
| `chem/Form.kt` | `Form` (what matter has been made into), the smelt table, and the binary crafting tree as data. |
| `chem/Chemistry.kt` | `process`, `craft`, `merge`, `takeFrom`, `conservationOf`. |
| `logistics/Packet.kt` | 1 kg packets (`SolidPacket` / `FluidPacket`), `Capacity` (the one home for the eventual volume switch) and `Rate` (integer carry, so 1 kg/s at 60 Hz stays exact). |
| `world/Grid.kt`, `world/Machine.kt` | The square lattice, `Direction`, and the machines: belt (four slots), extractor, processor, storage, sensor, node, vent. |
| `world/Structure.kt` | `Structure` and `StructureMap` — hull, interior and vacuum, derived by flood fill from the grid edge. |
| `world/Heat.kt` | `HeatField` (joules per tile), conduction, radiation to space, and per-gram machine heat. |
| `world/Atmosphere.kt` | `AirField` (grams of each gas per tile), pressure flow, and `stratifyColumns`. |
| `world/Signal.kt` | The trigger grammar: colour `Channel`s, `Signals`, and `Wiring` — `activation = Σ(signal × weight)`. |
| `world/Vessel.kt` → `contentsBreakdown` | What the inspector reads: a machine's buffers, named separately. |
| `world/Vessel.kt` | `VesselState` (the snapshot) and `Stockpile` (the global construction inventory the node feeds). |
| `world/StarterVessel.kt` | The world the game opens on: one complete line, already running. |
| `OutofspaceSim.kt` | The reducer — five ordered passes per tick. `OutofspaceController` is the real-time boundary; the renderer and HUD sit beside them. |

Nothing of the app template's placeholder world remains.

```bash
./gradlew :apps:outofspace:core:jvmTest    # 122 tests, well under a second
./gradlew :apps:outofspace:desktop:run     # the game
```

Click to place, drag to paint a line of them, right-click to remove. `R` rotates the brush, `1`–`9`
pick a machine, middle-drag pans, wheel zooms, space pauses. `W` switches between the **build** and
**wire** tools; in wire mode, click a machine to open its wiring.

## Reading the world

Ore is a *mixture*, and nothing about a running refinery says so out loud — which is exactly how a
correct simulation comes to look like a broken one. Two things fix that:

- **Point at anything.** The inspector panel shows every buffer of the tile under the cursor with its
  full composition: a processor reads `INPUT 40% iron` / `CONCENTRATE 75% iron` / `TAILINGS 7% iron`,
  which is the direction contract made visible. On touch there is no hover, so it shows whatever was
  last tapped in wire mode instead.
- **The analyzer.** A belt tile that measures: material passes through, and it reports the dominant
  species and its share — on the tile, in the inspector, and as a signal on a channel, so purity can
  drive machinery. Its reading persists after the packet leaves, so an idle line still tells you what
  went down it.

The starter world has one either side of the processor, reporting on AMBER and CYAN. Watching those
two numbers in the signals panel is the whole explanation of what a processor does.

## Structure and heat

Build **hull**; the inside is whatever it encloses. A flood fill inward from the grid edge marks
everything space can reach, and what it cannot reach is interior — so a breach needs no special
handling, it is just a hole the fill pours through.

Each enclosed tile stores **joules**, and temperature is `joules / capacity`. Machines charge waste
heat per gram of work they do, so a throttled machine warms the room proportionally less and the
heat model needs no clock of its own. Hull tiles touching space radiate — slowly, because vacuum is
an excellent insulator and a spacecraft's real problem is rejecting heat, not keeping it.

## Atmosphere

Every enclosed tile holds grams of each gas. Pressure is total mass — tiles are all the same volume,
so mass is density and gas flows from dense to sparse. What moves is a proportional sample of the
source, so a draught carries the room's real mix rather than skimming one gas off it.

Gravity sorts the column: heavy gas trades places with light gas below it, as a **swap**, so
stratification rearranges composition without touching pressure. `stratifyColumns` is the only
function in the codebase allowed to assume gravity is axis-aligned — hand it a diagonal and it
declines to sort rather than guessing.

A breach needs no code of its own: the tile stops being enclosed, so its air vents and its heat
radiates, both onto their ledgers.

Press `H` to cycle **plain → heat → air**. Each field has its own balance shown live in the corner:
`stored + radiated − generated == baseline` for energy, `aboard + vented == baseline` for air.

## Wiring

`RUN = Σ(signal × weight)`, clamped to ±100%. Sensors read the fullness of the tile they face and
broadcast it on one of six colour channels; `ALWAYS` is a constant every machine is wired to by
default, so a machine you place simply works.

**Activation is a throttle, not a switch** — half a signal is half a machine. So `ALWAYS − RED` is a
proportional controller: a miner filling a tank slows as it fills rather than stopping dead. The
starter world ships that loop as a live demonstration, on the short line below the refinery.

What the grammar cannot say is a *threshold*: "stop when past 90%" needs a comparison, and there
isn't one yet.

## The invariant to keep

`extracted == in transit + banked + vented`, on every tick, paired since increment H3 with
`rockGrams == baselineRockGrams + captured − extracted`. An extractor is the only place ore enters
the world and a vent the only place it leaves, and `extracted` is in **both** identities because ore
only exists by coming off a rock — so the pair catches a whole category of
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
