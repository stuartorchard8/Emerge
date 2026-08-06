# Grid vs. non-grid: what we're building on top of

*Plan, 2026-08-07. Nothing built. Scoping document — not an implementation plan.*

---

## 1. The tension

The grid is `Grid(96, 60)` today. It was chosen as "a generous bound with the hull drawn inside it"
and everything grid-dependent was built on top of it. It works well for what it does. But as we push
toward rigid bodies, deformable shapes, arbitrary geometry, and dynamic destruction, the question
arises: **is the grid the right foundation anymore?**

The user's position (2026-08-07):

> The grid gives us locality and clear rules for free. But rigid bodies don't fit naturally into
> it, and the constraints are blocking several directions we might want to explore.

This document captures that position, analyses each item, and pushes back on assumptions where
appropriate.

---

## 2. What the grid IS buying us

### 2.1 Fluid simulation (Eulerian)

The atmosphere solver is **Eulerian** — fields live on the grid. `AirField` (grams per tile per
species), `MomentumField` (momentum per face), `ApertureField` (fractional openness per face),
`VolumeField` (gas capacity per tile). The tick is: drift by species → compute pressure → apply
pressure force → Helmholtz-Hodge projection → sub-stepped advection. This is a **staggered MAC
grid** (faces store vectors, tiles store scalars), a classic CFD technique that prevents
checkerboard pressure decoupling.

**Verdict: ✅ Grid is correct here.** A particle-based (Lagrangian / SPH) approach would be
dramatically more complex and loses the clean conservation guarantees of the Eulerian formulation.
There is no reason to abandon the grid for fluids. The rigid body question is orthogonal — bodies
interact with the fluid via buoyancy and drag forces that are already computed at grid scale
(`Buoyancy.kt`, `Drag.kt`). These forces don't require bodies to be grid-aligned; they require
bodies to report their volume and position, which any shape can do.

### 2.2 Transport system

The transport system is BFS-based flow routing on the conduit graph. Packets move along rails and
pipes. The graph is **derived from the grid** — adjacency is `grid.neighbour()`, segments are
indexed by tile.

**Verdict: ⚠️ The grid provides the graph, but the graph is the abstraction, not the grid.**
The transport system already operates at a higher level: `FlowField` (BFS distances), `order`
(topological), `Segment.links` (bitmask). The grid is the input, not the output. If machines
were placed on a non-grid graph, transport would still work — it already uses a graph internally.
The cost: losing tile-aligned placement would mean machines can't be "seen" by the fluid solver
and heat solver in the same way. But the graph itself is game-agnostic.

### 2.3 Heat conduction

`SolidHeat.kt` builds a **contact graph** from grid adjacency: for each body, walk its tiles and
`grid.neighbour()` to find other bodies on adjacent tiles. The Jacobi solver operates on this
graph, not on the raw grid. The grid is used to derive adjacency, then the adjacency is abstracted
away into `Contacts` (a compressed row-format graph).

**Verdict: ⚠️ Grid provides adjacency, but the solver works on a graph.** The contact graph is
already decoupled from the grid. If bodies had arbitrary shapes that overlapped tiles partially,
the contact graph would need to weight contacts by overlap area rather than by "neighbour tile."
This is a measurable extension, not a paradigm shift. The grid's role here is as an adjacency
source, not as a constraint on body shape.

### 2.4 Machine overlap avoidance

`Occupancy.kt` maps `tile → machineIndex`. Each machine has a square footprint `size × size` stored
at its centre tile. Overlap is `originOf[tile] != -1`.

**Verdict: ⚠️ Grid provides locality, but any spatial index would work.** A tile-based occupancy
map is simple and fast for the current machine sizes (1×1 to 5×5). A grid-free approach would need
an AABB / quadtree / sweep-and-prune for machine bounding boxes. The grid is the simplest correct
solution for the current problem. But if machines gain rotation, articulation, or variable geometry,
the square-tilled occupancy model breaks.

### 2.5 Readability

Grid-aligned placement is visually clear. The player can see where a smelter goes because it snaps
to a 3×3 grid. Non-grid placement (continuous coordinates) would make the layout less predictable
and the UI harder to reason about.

**Verdict: ⚠️ This is a design choice, not a technical constraint.** Grid alignment helps the
player, but it doesn't prevent bodies from having continuous shapes underneath. A body can be
placed at continuous coordinates and rendered with smooth geometry while still snapping to grid
centres for gameplay clarity. The tradeoff is: gameplay predictability vs. physical expressiveness.

### 2.6 Ledger of where everything is

The grid is the **address space** for all conservation ledgers:
- `massGrams` — sum of machine buffers + debris + in-transit packets, all indexed by tile
- `airBalance` — atmosphere grams + vented grams, per-tile
- `solidJoules` — heat in machines + conduits + bridges + bodies, per-tile
- `momentum` — vessel + fluid faces + rocks, each with a grid address

**Verdict: ⚠️ Grid provides the address space, but ledgers don't require a grid.** A ledger is a
mapping from "location" to "quantity." The grid makes location a tile index. But the same ledger
identity holds if location is an AABB, a shape ID, or a body ID. The cost of switching: every
system that currently reads/writes by tile index would need a new addressing scheme. The benefit:
bodies are no longer forced into grid shapes. The grid is not *required* for ledgers — it's the
simplest addressing scheme for the current problem set.

### 2.7 What we might be overlooking

- **Debris settling.** `Debris.kt` uses `grid.neighbour()` to move loose material toward gravity.
  This is tile-based and grid-dependent. A continuous approach would need a different settling
  model.
- **Dynamic grid growth.** `GridGrowth.kt` remaps the entire world when the vessel grows. This
  works because everything is grid-indexed. A non-grid world would need a different mechanism
  (spatial partition resize, body re-indexing).
- **Room detection.** `StructureMap` flood-fills `Vacuum` from grid boundaries to determine which
  tiles are interior. This is inherently grid-based.
- **Permeable machines.** Plates and extractors are permeable to gas but not to bodies. The
  permeability is per-tile (`StructureMap.isImpermeable`). A non-grid world would need per-body
  permeability.

---

## 3. What the grid IS NOT buying us (and where it conflicts)

### 3.1 Rigid bodies + Eulerian fluid are not inherently incompatible

**User's concern (#8):** "Rigid bodies don't intuitively interact with the grid-based fluid
simulation; pushing me towards a particle-based implementation."

**Pushback:** They interact fine. The fluid solver computes forces on bodies via buoyancy and drag,
which are already computed at grid scale. Buoyancy = `gravity × volume × density_difference`. Drag =
`velocity × drag_coefficient`. These are scalar forces derived from a body's volume and position.
A body does not need to be grid-aligned to report its volume.

Moving to particle-based fluid (SPH) is a **massive rewrite** that loses:
- The clean Helmholtz-Hodge projection (conservation guarantee)
- The staggered MAC grid (pressure stability)
- The sub-stepped advection (CFL safety)
- The ledger structure (per-tile mass/energy tracking)

**Alternative:** Keep Eulerian fluids. Let rigid bodies interact via forces. This is how most
games do it. Bodies don't need to be grid-aligned — they just need to report their centroid and
volume to the buoyancy/drag functions.

**Verdict: ❌ User's concern is overstated.** The fluid simulation doesn't need rigid bodies to be
grid-aligned. The grid provides the fluid field; bodies interact with it through forces. This is
already the case.

### 3.2 Grid-based bodies already support arbitrary shapes

**User's concern (#10):** "Grid-based bodies are very blocky which limits our options significantly."

**Pushback:** The body shape is a `BooleanArray` — a bitmask. This can represent **any 2D shape**
that can be rasterised to a rectangular bounding box. The "blockiness" is a rendering/visual
concern, not a simulation constraint.

The current `Rock.blob()` factory already rasterises a disc. A `BodyFragment` rasterises a
tolerance-shaved rectangle. Neither is geometrically limited to a square. The limit is the
**bounding box** — the shape must fit in a rectangle. But that's true for any 2D physics engine.

What the user might actually be concerned about:
- **Non-rectangular bounding boxes** (L-shapes, circles that don't fill their bbox) — already
  possible via the cell bitmask.
- **Non-axis-aligned shapes** — not possible because the bbox is axis-aligned. This is a real
  limitation for bodies that need to be drawn at angles.
- **Smooth curved boundaries** — not possible because the rasterisation is per-cell. The boundary
  is always a staircase. This is a visual limitation, not a physical one (the collision uses the
  rasterised shape, which is fine for gameplay).

**Verdict: ⚠️ Partially correct.** The grid doesn't limit shape expressiveness (the BooleanArray
handles arbitrary shapes), but it does limit orientation (no rotation) and boundary smoothness
(staircase rasterisation). These are real limitations, but they are visual/design limitations,
not physics limitations.

### 3.3 Rigid bodies for variable geometry (robot arms, doors, hinges, pistons)

**User's concern (#11):** "Rigid bodies would be a better fit for variable-geometry fixtures like
robot arms, cargo bay doors, hinges, pistons, servos etc."

**Pushback:** This is a **real** concern. Grid-placed machines are square footprints at fixed
positions. Robot arms, doors, and pistons need:
- **Rotation** (doors swing, arms pivot)
- **Variable extent** (pistons extend/retract)
- **Joints** (articulated links)

The current grid system can handle rotation (the `BodyFragment` plan considers V2 rotation), but
it cannot handle joints or variable extent without significant changes. A jointed system needs:
- Multiple rigid bodies per machine
- Constraint forces between bodies (joint equations)
- Kinematic chains (A drives B drives C)

This is a fundamentally different system from what the grid provides. The grid can be the
address space, but the physics would be a **constraint-based rigid body system** (like Box2D,
which the Cyto sim already uses indirectly via its spring solver).

**Verdict: ✅ Correct.** Grid-placed machines cannot naturally express articulated joints or
variable geometry. A rigid body system with constraints is the right approach for these. This
does not require abandoning the grid entirely — the grid can still be the address space while
bodies have their own coordinate systems and joint constraints.

### 3.4 Soft bodies under high load

**User's concern (#12):** "Perhaps soft bodies would make sense under high load for elastic and
plastic deformation."

**Pushback:** Soft bodies are a **different physics domain entirely**. They require:
- Finite element method (FEM) or similar
- Per-vertex displacement tracking
- Stiffness matrices
- Stress/strain computation
- Yield criteria for plastic deformation

This is not "rigid bodies on a grid" vs "rigid bodies without a grid." This is a completely
different simulation paradigm. A soft body can exist in any coordinate system — the grid is
irrelevant to the physics.

**Verdict: ⚠️ This is a long-term vision item, not an immediate concern.** If the user wants soft
bodies, they need FEM, which is a separate system that can coexist with rigid bodies. The grid's
role would be limited to "where does this soft body sit" (its bounding box), not "what shape is
it." This is out of scope for any near-term work.

### 3.5 Cracking, shearing, cutting along arbitrary axes

**User's concern (#13):** "Cracking/sheering/cutting/deforming along arbitrary axes would make
the world feel more dynamic when collecting resources from asteroids, or crashing into things, or
perhaps when in combat with other players."

**Pushback:** Arbitrary-axis cutting requires **mesh decomposition** — splitting a shape along a
line and producing two new valid shapes. This is:
- Hard to do correctly (producing watmeshes, preserving topology)
- Expensive (O(n²) for brute force, O(n log n) with spatial acceleration)
- A different problem from rigid body dynamics (it's computational geometry)

The current approach (fragmentation via BodyFragment) handles "breaking a machine into a rigid
body" but not "cutting a rock along an arbitrary diagonal." That would require:
- A signed distance field (SDF) or polygon mesh for each body
- A clipping algorithm (Sutherland-Hodgman, Weiler-Atherton)
- Topology repair after clipping

**Verdict: ⚠️ This is a medium-term vision item.** The current BodyFragment approach (one body
per machine) is a stepping stone. Arbitrary-axis cutting is a separate problem that can be built
on top of rigid bodies. The grid is not the constraint — the shape representation is. If bodies
used polygon meshes instead of BooleanArrays, cutting would be possible regardless of the grid.

---

## 4. The core misconception

The grid is being conflated with **two separate abstractions**:

1. **The grid as an addressing scheme.** Tiles are addresses. Ledgers map addresses to quantities.
   This is useful because machines, fluids, heat, and transport all naturally live at locations.

2. **The grid as a shape constraint.** Bodies must be rasterised to tiles. Collision checks
   tile-by-tile. Shapes are BooleanArrays in AABB bounding boxes.

These are **independent**. The grid could be the addressing scheme while bodies are arbitrary 2D
shapes (polygons, SDFs, continuous masks). Bodies would still interact with the grid (their
footprint overlaps tiles, their volume displaces fluid), but their shapes wouldn't be limited to
grid-rasterised masks.

---

## 5. Viable design paths

### Path A: Grid as address space, continuous bodies (hybrid)

- Keep the grid for fluids, heat, transport, and ledgers.
- Bodies are **continuous 2D shapes** (polygons, masks) that overlap tiles.
- Collision with hull uses continuous geometry (SAT, GJK, or tile-based sweep).
- Body footprint overlaps tiles → contributes to `solidJoules`, `airBalance`, etc.
- No grid-imposed shape constraints on bodies.

**Cost:** Significant. Body-hull collision becomes continuous. Body-grid overlap becomes partial
(0.7 tile of a body overlaps a tile → contributes 70% of its mass/heat to that tile's ledger).
Heat conduction contact graph needs overlap-weighted contacts.

**Benefit:** Arbitrary body shapes, rotation, smooth boundaries. Joints become natural (multiple
bodies with constraints). Cutting becomes possible (split a polygon).

**Risk:** The grid is the most battle-tested part of the system. Decoupling bodies from it
introduces complexity in every subsystem that touches bodies.

### Path B: Grid as address space, polygon bodies, grid rasterisation (pragmatic)

- Keep the grid for fluids, heat, transport, and ledgers.
- Bodies are **polygons** (arbitrary convex/concave shapes) but still rasterised to tile masks
  for ledger purposes.
- Collision uses polygon geometry (SAT for convex, polygon clipping for concave).
- Rendering uses polygon geometry (smooth edges, rotation).
- Ledger contributions are still tile-based (rasterised overlap).

**Cost:** Moderate. Polygon collision replaces tile-based sweep. Rendering already uses polygons.
Ledger contributions are unchanged (rasterise to tiles for bookkeeping).

**Benefit:** Arbitrary body shapes, rotation, smooth rendering. Collision is more accurate.
Ledger structure is preserved.

**Risk:** Less than Path A — the grid's role in ledgers and fluids is preserved. Only collision
and rendering change.

### Path C: Grid as address space, grid bodies (current, evolve gradually)

- Keep bodies as grid-rasterised BooleanArrays.
- Add rotation as a separate attribute (angle stored alongside position, applied during sweep).
- Add fragmentation (breaking one body into multiple smaller bodies).
- Add joints for articulated machines (V3+).

**Cost:** Low. This is the current trajectory — BodyFragment, rotation V2, joints V3.

**Benefit:** Minimal change to existing systems. Grid stays the single source of truth.

**Risk:** Limited expressiveness. Bodies are always blocky (staircase rasterisation). No smooth
deformation. Joints are grafted on rather than native.

---

## 6. Recommendations

### For the immediate term (next 1-2 increments):

**Path C is correct.** The BodyFragment plan is the right next step. It establishes rigid bodies
without disrupting the grid's role in fluids, heat, or ledgers. The blockiness of grid-rasterised
shapes is acceptable for V1 — it's a visual limitation, not a functional one. The BodyFragment
type (unified with Rock via `RigidBody`) is already planned and scoped.

### For the medium term (3-6 months):

**Path B is the natural evolution.** Once rigid bodies are stable (Path C), adding polygon-based
collision and rendering is the next step. Bodies keep their tile-based ledger contributions but
gain arbitrary shapes, rotation, and smooth rendering. This enables:
- Robot arms, doors, pistons (rotation + articulated shapes)
- Dynamic destruction (fragmentation into polygon pieces)
- Combat (arbitrary-angle impacts)

The grid remains the address space. Bodies are no longer constrained by grid shapes, but they
still report their tile overlaps for ledger purposes.

### For the long term (6-12+ months):

**Path A is possible but expensive.** If the user wants true soft bodies, arbitrary-axis cutting,
and continuous deformation, the body representation needs to become fully continuous (Path A).
This is a major architectural shift that should only be done when the use cases justify it.

---

## 7. Summary table

| Item | User's concern | Pushback | Grid's actual role |
|---|---|---|---|
| Fluid sim | Rigid bodies conflict with grid fluid | Forces (buoyancy/drag) already decouple bodies from grid | Grid is the fluid field, not the body constraint |
| Transport | — | — | Grid provides graph topology, but graph is the abstraction |
| Heat conduction | — | — | Grid provides adjacency, solver works on abstract graph |
| Machine overlap | — | — | Grid provides spatial indexing, simplest correct solution |
| Readability | Grid alignment is good | Grid is the address space, not the shape constraint | Design choice, not technical requirement |
| Ledger | Grid is the address space | Any addressing scheme works; grid is simplest | Address space, not shape constraint |
| Rigid bodies + fluid | Conflict, push toward particles | Forces decouple them; SPH is a massive rewrite | No conflict |
| Deformation | Grid blocks deformation | Deformation needs different physics (FEM), not different grid | Grid is irrelevant to deformation |
| Blocky shapes | Grid limits shapes | BooleanArray supports arbitrary shapes; limit is visual, not simulation | Grid provides rasterisation, not shape expressiveness |
| Variable geometry | Grid can't express joints/rotation | Correct — grid can't express joints; this needs rigid body system | Grid is the address space, not the physics |
| Soft bodies | Grid blocks soft bodies | Soft bodies need FEM, independent of grid | Grid is irrelevant to soft body physics |
| Arbitrary cutting | Grid blocks arbitrary cuts | Cutting needs mesh decomposition, independent of grid | Grid is irrelevant to cutting |

---

## 8. What we would know it worked

- Every existing test green (no regression).
- The fluid solver produces identical results (Eulerian grid is untouched).
- Heat ledger balances identically (contact graph is unchanged).
- A `RigidBody(BodyKind.ROCK, ...)` drifts identically to the old `Rock.blob(...)`.
- A `RigidBody(BodyKind.FRAGMENT, ...)` drifts correctly with tolerance-shaved shape.
- `bodyGrams == baselineBodyGrams + bodyCapturedGrams − bodyExtractedGrams` on every tick.
- Bodies never appear in `massGrams` or `inTransitGrams`.
- Save with bodies loads identically.
- Determinism: same input → same output.

---

*Nothing built. This is a scoping document, not an implementation plan.*
