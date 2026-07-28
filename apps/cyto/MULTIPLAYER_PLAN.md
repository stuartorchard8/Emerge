# Cyto client–server plan

Goal: a Cyto server runs the simulation; clients own their own menu/UI state and send
sim-affecting inputs (gene edits, cell dragging, brush actions, sim speed) to the server.

**Driving constraint: the phone is too weak to run the sim at the level the server does.**
So the architecture is chosen by "what work can the phone be relieved of", not by elegance.

---

## 1. Verdict up front

**Yes — and Cyto's cost profile is unusually well suited to it, because the expensive phase is
cleanly separable. But the right split for Cyto is the *inverse* of Scavengers', and for the phone
specifically the answer is a render-stream client rather than a semi-thin one.**

Three modes are on the table. `LockstepHost` already supports **a different mode per client**, so
this is not an either/or — a desktop can lockstep while the phone render-streams off the same host.

| Mode | Phone runs | Bandwidth | Verdict |
|---|---|---|---|
| LOCKSTEP | 100% of the sim | ~tens of bytes/tick | Fine for desktop clients. **Doesn't help the phone at all.** |
| SEMI_THIN (impulse analogue) | ~19% of the sim | multi-MB/s, scales with server TPS | Real, but see §3 — it doesn't actually decouple the phone from the server's tick rate. |
| **Render-stream** (new) | **0% of the sim** | ~0.3–0.5 MB/s | **This is the one for the phone.** |

Naive THIN (`stateCodec` every 3 ticks) remains dead: a Cyto snapshot is ~3.1 MB
(`cyto-saves/campaign-ch00-genesis.bin`, dominated by the dense matter field at ~1 MB/species
column × 3). At 64 TPS that's ~65 MB/s per client.

---

## 2. Where Cyto's cost actually is

From `PERF.md` (4145 cells, 200 growth ticks, SEQ tick ~13.2 ms):

```
biology  = 10,705 µs  (81%)   ← exchange 33% / finish 32% / genes 19% / build
interact =    789 µs
lifecycle=    415 µs
contacts =      0
springs  ≈    0.9% of tick
```

**This is the exact inverse of Scavengers.** There, the expensive phase is impulse resolution and
the cheap remainder is integration — so the host ships sparse impulses (`ImpulseCodec`) and
`ScavengersNoImpulseReducer` runs only `lifecycle → effects → integrate` locally.

In Cyto the expensive phase is *biology* — per-cell metabolism — and the physics is nearly free.
So the analogous split exists and is clean:

> **Server runs biology + diffusion + lifecycle (~81%). Client runs contacts + springs + integrate
> (~19%).**

That is a genuine, well-defined seam. The problem is what has to cross it.

---

## 3. Why the direct impulse analogue (SEMI_THIN) is weaker than it looks

Two structural differences from Scavengers, and the second is the one that decides it.

**(a) Biology's output is dense, impulses are sparse.** An impulse exists only for entities that
actually collided this tick — usually a handful. Biology touches *every cell, every tick*: each
cell's cytoplasm and biomass maps change, plus the matter grid cells they exchanged with. A
per-tick delta is therefore O(cells), not O(collisions). Rough sizing at 4000 cells with ~8 live
species each: ~200 KB/tick before delta-encoding. Delta-encoding against the last sent value helps
a lot (most cells change slowly and counts are small integers), but you are still in the multi-MB/s
range at 64 TPS.

**(b) — the decisive one — semi-thin does not decouple the client from the server's tick rate.**
`LockstepHost.step()` sends one packet per tick per client, and `ThinLockstepClient` steps its
local reducer once per packet. So a semi-thin phone must *receive, decode and locally step* once
for every server tick. If the server runs at 512 TPS, the phone does its 19% of the work 512 times
a second — and 19% of a server-speed world is still more than the phone can do. **The stated
problem is "the server runs at a level I can't match", and semi-thin's client cost is proportional
to exactly that level.** It relieves the phone of a constant factor, not of the coupling.

It also still requires the phone to hold the whole world in RAM (all cells + the 3 MB matter grid),
and it still imposes full determinism obligations on the client's phases — with the Kotlin/JS
`Float` hazard and the thread-count sensitivity in §6 still live.

**Conclusion: SEMI_THIN is worth having for a mid-tier desktop that wants full fidelity cheaply.
It is not the answer for the phone.**

---

## 4. The render-stream client (the recommendation)

Invert the question. The phone doesn't need to *simulate* anything — it needs to **draw** the
world and **send inputs**. So ship it a render view-model, not sim state.

What the renderer actually consumes (from `CytoRenderer.draw`): per-cell position, radius, and
biomass-derived colour; the spring/connection edges; the matter field for the overlay; and the
selected cell's detail for the info panel. That's it. Notably `CytoFrameSpringData` already exists
as a flat, columnar, renderer-facing view that deliberately bypasses `SimState` — **the precedent
for a packed view-model is already in the codebase.**

Packed per-cell record: `id` + `x,y` (quantised to world units) + `radius` + packed colour ≈
**~10 bytes**, delta-encoded against the previous frame and clipped to the client's viewport.

Bandwidth:

| Stream | Size | Cadence | Rate |
|---|---|---|---|
| Visible cells (~1000) | ~10 KB | 30 Hz | ~300 KB/s |
| Connection edges (change rarely) | on-change only | — | negligible |
| Matter overlay, downsampled 128×128×3 @ 1 B | ~48 KB | 2–4 Hz | ~100–190 KB/s (droppable on mobile) |
| Selected-cell full detail | ~1 KB | on selection / 4 Hz | negligible |

**~0.3–0.5 MB/s.** Against 65 MB/s for naive THIN.

The three properties that make this the right answer:

1. **It decouples client frame rate from server tick rate.** The server can run at 2000 TPS and the
   phone still receives 30–60 view updates per second. This is the thing neither lockstep nor
   semi-thin can give you, and it is precisely the stated requirement.
2. **The phone runs zero simulation.** No world in RAM, no matter grid, no reducer, no GC pressure.
3. **It touches Cyto's sim not at all.** No determinism obligation on the client, so the JS `Float`
   question and the thread-count question both evaporate for this path — which also makes a **web
   client viable**, where lockstep would not be.

### 4a. Selected-cell detail

O(1) in cell count, so it's cheap — but note it cannot be *computed* on the phone, because the
phone holds no world. What it can do is the **derivation**: send the selected cell's raw state
(genome, cytoplasm, biomass, the local reservoir contents, welded degree, captured light — a few
hundred bytes) and let the phone run `describeGeneSpans` / the metabolism table locally.

That's better than sending rendered strings for two reasons: it's smaller, and the gene editor
needs the raw genome in hand anyway in order to edit it. Send on selection change, then refresh at
~4 Hz. Negligible bandwidth.

### 4b. Viewport culling

Yes — and the broadphase makes it nearly free. But it is **not a diff; it's a per-client visible
set.** A cell entering the viewport has no baseline on the client, so it must be sent in full,
while a cell leaving needs an explicit removal. So the per-frame message is enter / update / leave
against a per-client "what does this client currently know about" set, not a delta against the
previous world state.

- **Torus wrap is already solved.** A viewport near the seam is two disjoint rectangles in flat
  coordinates — the trap `CytoController.cellAt` documents. `SpatialGrid` already wraps cell indices
  by bitmask AND, mirroring `Coord.minus`'s Int-overflow wrap, so a viewport query can reuse it
  directly and inherit correct wrapping.
- **The grid is already built and reused every tick** (`clearForReuse`, allocation-free in steady
  state), so the interest query costs a rectangle walk per client per frame. At 30 Hz and a handful
  of clients, negligible.
- **Margin + hysteresis:** cull to the viewport expanded by a margin, so small pans don't pop cells
  in and out (and each pop is a full-record resend).
- **The zoom-out problem is the real limit.** Cyto players zoom out to watch the whole colony, and
  at full-world zoom the viewport contains every cell — culling buys exactly nothing in the case
  where the cell count is highest. This needs an explicit LOD policy: past a zoom threshold, switch
  to a coarse representation (subsample, or aggregate to per-grid-cell blobs with a count and a
  mean colour). **Decide this early — it shapes the codec**, because the wire format needs to carry
  both a per-cell and an aggregate record type.

### 4c. VALIDATED — the matter signature holds

Measured with `./gradlew :apps:cyto:desktop:checkMatterSignature`, default scenario, checkpoints every
2000 ticks. Judged on contrast in 8-bit display levels, on-screen extent, and field coverage:

```
  tick    cells   delta(levels)  noise(levels)   extent(px)  cover%   verdict
    2000      83         15.516            0.0      170.089    2.48   VISIBLE
    4000     861         20.834            0.0      473.206  19.198   VISIBLE
    6000    2347         20.785            0.0      751.894  48.469   VISIBLE (saturated)
    8000    3472          20.26          16.94      906.263  70.414   VISIBLE (saturated)
   10000    3217         21.196         78.709      884.193  67.027   VISIBLE (saturated)
```

**The premise holds across the entire colony lifetime, from 83 cells to 3472.** The colony reads as a
~20-display-level *drawdown* — a dark void with directional feeding trails streaming off it — against
the seeded background. Confirmed visually in the emitted PNG, not just numerically.

Three findings that came out of measuring it:

1. **The signature is negative, not positive.** A colony appears as a *hole* in the matter field, plus
   trails. Worth knowing for the visual design: zoomed out, life reads as darkness. It is legible, but
   it is the inverse of the intuition that a colony "glows".
2. **A lone cell fails on extent, not contrast.** One cell scores ~5 display levels — passing any pure
   contrast test — while occupying a single texel of 512². At full-torus zoom it is unfindable. So
   **the fade must not be driven by zoom alone**: a player who zooms out to look for their one starting
   cell would be shown an empty world, where today the renderer always draws it at a 2px floor. Gate
   the fade on population/extent as well as zoom, or keep a minimum "life is here" marker.
3. **Don't trust the contrast number at high coverage.** Past ~40% coverage the colony has churned the
   whole field; the "background noise" the metric reports is the colony's own diffused trails, and a
   naive mean-vs-variance test reports a false negative exactly where visibility is least in doubt.
   The tool now suppresses the contrast term above `SATURATION_PCT` and decides on extent. (The first
   version of this check got this wrong in the opposite direction too — see its KDoc.)

Two incidental facts the sweep surfaced, unrelated to networking but worth recording: the **default
scenario's colony goes extinct** between 18k and 25k ticks, and the **campaign saves are opening states**
(`ch00-genesis` has 0 cells, `ch01-divide` has 1), so neither is usable as a mature-colony fixture.

The original worry — the equilibrium autotroph from `LIVING_WORLD_PLAN.md` — was **not** reproducible
here: every live checkpoint showed a strong drawdown. That case may still exist in a hand-authored
campaign world, but it is not the default behaviour of a grown colony.

### 4d. What's lost

Gene specks and the ENV↔CYT transfer particles are spawned per *tick* in proportion to that tick's
actual membrane transfer and per-gene activity — i.e. they depend on exactly the dense biology data
this architecture declines to send. Accepted for now.

Cheap partial buy-back if they turn out to be missed: **one quantised "activity" byte per cell** in
the view-model (total transfer magnitude this frame) would support a coarse version of the flow
particles at +10% payload. Full-fidelity gene specks remain available for the *selected* cell,
whose detail is being sent anyway (§4a).

### 4e. Costs, honestly

- It's the most genuinely *new* code (view-model + delta encoder + per-client interest set),
  though none of it is subtle.
- Every interaction is a full round-trip with no prediction. Gene edits, taps and brush actions are
  not latency-sensitive, so this is fine. **Cell dragging is**, and will rubber-band; mitigate with
  purely local visual prediction of the dragged cell (draw it at the finger, let the server
  correct) — cosmetic, no determinism implications.
- The client can't answer questions the view-model doesn't carry, so the info panel and campaign
  gates need explicit server-side queries rather than reading local state.

---

## 5. The sim/UI boundary audit

Independent of mode — this is the work that has to happen either way.

### 5a. Stays entirely client-local (already done, no work)

Camera/zoom/follow, `CytoControls.colorMode`, `nightLevel` (verified render-only —
`CytoRenderer.nightLevel`, the sim never reads it), `showChemicals`/`showDebug`/layer toggles, HUD
sheets and modals, touch-mode and cell-type selection, brush palette and `selectedGenome`, the gene
editor's in-progress draft, the agent harness. `CytoControls` is already pure view state and
`CytoSceneView` owns the camera. **This half of the ask is essentially already satisfied.**

### 5b. Already input-shaped — travels for free

`CytoInput` carries `spawns`, `taps`, `grab`, `detaches`, and the brush payload rides inside
`Spawn`/`Tap` (`genome`, `biomass`, `cytoplasm`). Needs a `Codec<CytoInput>` and nothing else.

### 5c. Must become serializable input — the real work

| Control | Today | Problem |
|---|---|---|
| Gene edits (set/delete/duplicate/reorder/append/appendGroup/replaceGroup/addGenes) | `pendingWorldEdits`: `(CytoWorld) -> Boolean` **closures** | Closures can't cross a wire. Must become a `CytoCommand` sum type applied inside the tick. |
| Mutation-rate ladder | same closure queue | same |
| Selection / focus | `reducer.noMutateEntityId: Int` | **Sim-affecting** — focusing freezes a cell against mutation. A single `Int` is structurally single-player; must become a per-player set in world state. |
| Sim speed / pause | `CytoSimDriver.targetTps`, `paused` | One world, one clock. Becomes a request the host arbitrates. Can ride as a field on `CytoInput`. |
| New game / load save | `newGame`, `restoreSnapshot` | Host-only; each triggers a full 3.1 MB resync. |
| Campaign director | `CampaignDirector` drives `reseedLineage`, chapter loads, `setSpeciesAliases` | Mutates the world from client-side logic — scope out, see §8. |

### 5d. Multi-player gaps in the sim itself

- `CytoInteractionSystem` does `inputs.values.firstOrNull()` — applies **one** player's input and
  silently drops the rest. Must iterate all players.
- `CytoSoaReducer.tick` takes a single `CytoInput` and pins `PlayerId(0)`.
- Input iteration order must be canonical (sort by `PlayerId`) — see §7.
- `Grab` is per-player continuous state; concurrent grabs of one cell need a deterministic rule
  (last-writer-wins **by player id**, not by arrival).

---

## 6. Determinism risks

These bind **only on lockstep/semi-thin clients**. A render-stream phone is exempt from all of them.

1. **Kotlin/JS `Float`.** Cyto's sim is fixed-point `Frac`/`Coord`, but there are ~21
   Float/`toFloat()` sites in the SoA package and `CytoInput` positions are `Float`. Kotlin/JS has
   no true 32-bit float. Validate with `CytoGoldenTest` on the JS target before assuming a web peer
   can lockstep. (Render-stream sidesteps this entirely.)
2. **Thread-count sensitivity.** `springParallelThreshold = 2048` fans the spring solver across
   `ParallelExecutor` and claims bit-identity. Server and phone have very different core counts —
   the parallel==sequential gate needs to run at *several worker counts*, not just par-vs-seq.
3. **Entity-id allocation** follows input-application order, so §7's ordering fix is load-bearing.
4. **Mutation PRNG** is already in world state and persisted (`randomSeed`) — fine.

Regardless of mode, build a **desync detector** early: the host broadcasts a `CytoGoldenTest`-style
world digest every N ticks; a disagreeing client requests a resync and logs it.

---

## 7. Engine changes needed

Both are genuinely engine-level and both benefit Scavengers too:

1. **Canonical input ordering** in `LockstepHost.step()` — sort `encodedInputs` by `PlayerId`
   before encoding. Currently a `LinkedHashMap` in client-arrival order, so two peers can allocate
   entity ids differently. This is a latent divergence bug in Scavengers today.
2. **Opt-out of join/leave resync.** `acceptClient` encodes and broadcasts full state on every join
   *and* leave. Cyto's join policy is identity — nothing changes — so that's a 3.1 MB broadcast to
   every client for nothing. Make it conditional on the join policy actually mutating state.

Then, for the new mode: **(3) a `RENDER_STREAM` client mode** — a `ClientMode` entry, a protocol
message carrying an opaque per-frame payload at its own cadence (decoupled from `step()`), and a
host-side hook to produce it. The existing `thinEventsEncoder` hook is the shape to follow, but it
must fire on a *display* timer rather than per tick.

---

## 8. What I'd scope out

- **The campaign / story mode.** `CampaignDirector` is client-side logic that mutates the shared
  world (chapter loads, `reseedLineage`, resets, alias pushes). What a chapter transition means with
  two people in the world is a design question, not an engineering one. Multiplayer launches
  sandbox-only; campaign stays single-player over a loopback pipe (which costs nothing).
- **Rollback/prediction.** Delay-based only, in every mode. See §4 on cell dragging.

---

## 9. Proposed phases

**Phase 0 — de-risk.** Add the world-digest function. Extend the parallel==sequential gate across
worker counts. Run `CytoGoldenTest` on JS. Outcome: which platforms can be *simulating* peers.
(Skippable if you go render-stream-only for now.)

**Phase 1 — make every sim-affecting action into data.** Introduce `CytoCommand` (gene edits ×8,
mutation ladder, selection/focus, speed request); replace the `pendingWorldEdits` closure queue with
a command queue applied inside the tick; move `noMutateEntityId` into the world as a per-player set.
No networking. Golden gate stays GREEN throughout. **This is the bulk of the work, it's required by
every mode, and it stands on its own merits** — it makes edits replayable and testable.

**Phase 2 — multi-player-shape the reducer.** `tick(world, Map<PlayerId, CytoInput>)`;
`CytoInteractionSystem` iterates all players in id order; grab conflict rule. Still local.

**Phase 3 — codecs.** `Codec<CytoInput>` (commands + genome payloads via `GeneCodec`),
`StateCodec<CytoWorld>` wrapping `CytoSaveCodec` (join/resync only), and the **render view-model
codec + delta encoder**.

**Phase 4 — controllers.** `CytoHeadlessHostController` (modelled on
`ScavengersHeadlessHostController`), `CytoRenderStreamJoinController`, and a `SimReducer` adapter
for `CytoSoaReducer`. Desktop lockstep join can follow later or never.

**Phase 5 — hosts and the loop.** Wire Android to the render-stream join path; add cyto to
`tools/dev-cycle.sh` alongside scavengers (same ufw'd-port pattern; heed the Scavengers
`JOIN_IMPULSE` lesson about the phone's hello). Agent-harness script asserting a joined client's
view matches the host's after N ticks.

**Phase 6 — collapse the local path** onto host+client over a loopback pipe, so single-player and
multiplayer share one code path and single-player can't silently rot.

**Later, optional — SEMI_THIN for desktop clients.** Split `CytoSoaReducer` into
`biology+diffusion+lifecycle` (host) and `contacts+springs+integrate` (client), with a biology-delta
codec. Only worth it if a full-fidelity, low-latency desktop peer becomes a goal.

---

## 10. Honest cost estimate

Phase 1 is the big one and the risky one: it touches `CytoController` (1024 lines) whose threading
contract is subtle and hard-won — the command refactor must preserve it exactly (**commands still
queue; the draw thread still never takes `stepLock`**, as pinned by `CytoEditLatencyTest`).

Phases 2–4 are mechanical. The render view-model is new code but not subtle, and `CytoFrameSpringData`
shows the shape. Phase 5 is where reality bites.

The good news for your actual constraint: **the render-stream path skips Phase 0 entirely and carries
no determinism risk**, so the phone can be served without ever resolving the JS-Float or
thread-count questions.
