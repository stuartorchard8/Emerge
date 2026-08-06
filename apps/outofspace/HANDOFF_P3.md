# Handoff: P3, grow on demand

For the next Claude session. The plan is `PLAN_dynamic_grid.md`; read §6, §9 and §10 before anything
else. This file is the part the plan cannot tell you: what the last three rounds of working with qwen
actually taught, and what to do differently.

---

## The division of labour

**Claude writes the acceptance tests and reviews. Qwen writes the implementation.** Stu has unlimited
qwen and limited Claude, so every token spent writing an implementation Claude could have specified
is a token wasted. Three rounds of this landed P1 and P2 with sound structure every time.

From latitude, no ssh — opencode routes to former:

```bash
cd /home/stu/emerge && opencode run --auto -m llamacpp-local/qwen3_6 \
  --title "oos-p3-grow" "<prompt>"
```

`AGENTS.md` at the repo root is what it reads for conduct. It has never once broken an explicit
constraint — told not to touch tests, it didn't; told to stop at a boundary, it did.

**The one rule that matters: a wrong assertion is far more expensive than a wrong prompt.** Qwen
does not question a failing test, it builds machinery to satisfy it. A `massBalance` assertion that
said `massGrams` where it meant `inTransitGrams` cost a whole session, and produced a venting pass
bolted into `fitGrid` that double-booked discarded air into two ledgers. Derive expected values as an
oracle inside the test; never pin a literal you have not computed twice.

Leave a `TODO()` stub so the module still compiles with the tests red. Qwen needs a build that runs.

---

## What P3 is

In the reducer's edit pass: if an edit would place anything within the pad of an edge, grow that
edge to restore it. **Any of the four edges.**

### Side-agnostic, decided 2026-08-06 — this overrides §9's "far sides only, per §6"

§6's mitigation 2 preferred far-side-only growth on the grounds that `+x`/`+y` leaves the origin
where it is, so no written-down coordinate moves. That reasoning is sound as far as it goes and it is
now superseded, for two reasons:

1. **It does not buy what it claims.** `index = y * width + x`. A far-side `+x` growth changes
   `width`, so every *stored tile index* outside `VesselState` — `controller.selected`,
   `injectTile`, the conduit drag anchor, `hovered` in `OutofspaceMain` — silently addresses a
   different tile afterwards. Stable `(x, y)` is not stable indices. The remap-the-holders machinery
   has to exist for far-side growth anyway.
2. **Given that machinery, near-side growth is it plus a camera shift.** `remapped` already takes an
   arbitrary offset and is tested at negative ones (P1). Near-side growth is `remapped(dx, dy)` with
   `dx`/`dy` non-zero and the same holders adjusted by the same delta. Building a far-only path
   means building an asymmetry we would delete in P5.

So build it side-agnostic. **Sequencing far-then-near inside P3 is fine** — get `+x`/`+y` green
first because `dx = dy = 0` is the easy half — but the *shape* is side-agnostic from the first
commit: growth returns the offset it applied and everything that holds a coordinate consumes it.
Do not write a code path whose contract is "far only".

### The one thing this pulls forward

§9 parks "camera and controller indices remapped alongside" in **P4**. Side-agnostic growth needs it
in **P3**, because a near-side growth without it jumps the view and misaddresses the selection. Take
it here. P4 then reduces to the explicit-fit trigger — a key, a HUD button, a harness `fit` command —
with nothing new underneath it.

Concretely, growth should hand back a delta rather than mutating in place, e.g.
`fun VesselState.growToFit(pad: Int): GrowResult` carrying `(state, dx, dy)`, with `dx == 0 && dy == 0`
on a far-side or no-op growth. Consumers to update, all of them holding a frame the state no longer
has:

- `OutofspaceRenderer.camX`/`camY` — tile units, so `camX += dx`.
- `OutofspaceController.selected`, `injectTile`, and the conduit drag anchor — indices, so re-derive
  through the *old* grid's `xOf`/`yOf` and the *new* grid's `index`, not by arithmetic on the raw int.
- `OutofspaceMain.hovered` / `lastPainted` — same, or invalidate to `-1`; a stale hover for one frame
  is harmless, a stale `lastPainted` is a missed paint at worst.

The harness already reads `state.grid` live everywhere it matters and gained `originX`/`originY` in
P0, so a script can assert the frame it thinks it is in.

### What this makes cheap, and what it makes expensive

Cheap: the near-edge question the previous draft flagged as architecture-for-Stu is **dissolved**.
An edit inside the near pad grows that side and remaps. No refusal path, no uneven pad, no rule
about which direction the player may build in.

Expensive: absolute coordinates in scripts and tests now rot on *any* growth, not just on an explicit
fit. That is the P2 lesson arriving early rather than a new risk — **landmarks, not tiles**, in
everything new. And `GridFitTest.the starter vessel lands in a known frame` pins `(+3, −3)` at
construction only; nothing may pin a frame *after* a growth.

---

## Fix this first, before growth is live

P1 left a deliberate gap that P3 is the first thing to trigger:

> `motion` is carried through by `copy()` despite the comment saying it is dropped. It is
> `Motion.NONE` at construction so it is harmless today, but it is a per-tile array sized to the
> *old* grid and will hand the renderer the wrong length the first time a resize happens mid-play.

P3 *is* the first mid-play resize. Fix it in `remapped` before adding growth, as its own commit with
its own test, or you will be debugging a renderer length mismatch while also debugging growth.

The other P1 gap — `remapped` silently discards on shrink instead of venting per §5 — stays parked.
P3 only grows. If anything in P3 wants to shrink, stop: §5 has to arrive with it.

---

## The acceptance tests to write

Put them in `GridFitTest.kt` alongside the P2 ones, or a sibling `GridGrowTest.kt`. Each should
re-derive its expectation rather than assert a number you typed:

1. **Placing within the pad grows that edge — run it for all four.** Table-drive it; a case per
   edge, not one far case. Assert the pad is *restored* — recompute the bounding box and check the
   margin — not that the grid reached some width.
2. **Growth preserves relative geometry.** Pick two machines, record their separation and each one's
   offset from a third landmark, grow, assert unchanged. Then assert absolute position moved by
   exactly the reported `(dx, dy)` — `0` on a far-side growth, positive on a near one. This replaces
   "growth never moves anything", which was only true under the far-only rule.
3. **The reported delta is the truth.** Whatever `growToFit` says it shifted by is what every
   machine, segment, bridge, pile and diverter actually shifted by. This is the test that keeps
   consumers correctable.
4. **All six ledgers stay zero across a growth:** `airBalance`, `airJouleBalance`, `massBalance`
   (`inTransitGrams + ventedGrams - extractedGrams`, **not** `massGrams` — that includes the ship's
   own fabric), `momentumBalance`, `rockBalance`, `heatBalance`. All four edges.
5. **`motion` is the new grid's size** after a growth. The bug above, pinned.
6. **Idempotence.** An edit well inside the pad grows nothing and reports `(0, 0)`.
7. **Rocks track the origin.** On a far-side growth their grid positions are bit-identical; on a near
   one they move by exactly `(dx, dy)` like everything else, because they are in the vessel's frame
   (§8 — they may sit outside the box, which is not the same as outside the frame). Getting this
   backwards puts a rock through the hull, so test both directions.
8. **The camera and the selection survive a near-side growth.** Record the world tile under the
   camera centre and the tile `selected` refers to, grow by a near edge, assert both still refer to
   the same tile. This is the P4-pulled-forward work, and it is the only test here that touches
   anything outside `core`.
9. **A growth mid-run does not perturb the sim.** Digest a world 300 ticks after a growth against the
   same world grown at construction; §10's determinism check, and the one most likely to catch a
   subtly wrong edge-field remap. Do it for a near-side growth too — a far-side one leaves the fields
   at the same offsets and so exercises much less.

---

## Traps, all of which have bitten

- **`yEdgeCount = width * (height + 1)`.** X-faces are `(w+1) × h`, y-faces are `w × (h+1)`. Those
  loops run `0..height`, not `0 until height`. This was the predicted P1 trap and it landed.
- **Baselines are constructor defaults and silently recompute on `data class copy()`.** Any copy that
  should preserve a ledger must pass them explicitly. This is the easiest way in the codebase to make
  a conservation test pass while the physics is wrong.
- **`MachineKind.size` is an extension property** in `Footprint.kt:16`, not a member. It needs an
  explicit `import org.emerge.demo.outofspace.world.size` and the error without it is an unhelpful
  unresolved reference.
- **`commonMain`/`commonTest` compile to JS too.** `Map.merge` and `computeIfAbsent` build on JVM and
  fail on JS. A green `jvmTest` is not a clean build — run `compileTestKotlinJs`.
- **Prefer landmark coordinates in scripts.** `extractor+3`, not `18`. P3 changes grid sizes at
  runtime; absolute tiles will rot.

---

## Verifying

`allTests` needs a headless Chrome this box does not have. Use:

```bash
./gradlew -q :apps:outofspace:core:jvmTest :apps:outofspace:core:compileTestKotlinJs \
             :apps:outofspace:desktop:compileKotlin
for f in apps/outofspace/agent-scripts/*.txt; do
  out=$(./gradlew -q :apps:outofspace:desktop:outofspaceAgent --args="$f" 2>&1)
  echo "$(basename $f): exit=$?"; echo "$out" | grep '^\[agent\]   - '
done
```

All ten scripts are green as of `8ff86e6e`. If P3 reds any of them, that is P3's doing — check
against a worktree at that commit before assuming otherwise. Script status is measured, never
inherited: a stale memory claiming `rocks.txt` was pre-existing-red cost real time in P2.

---

## What review has caught every round

Qwen's structure has been right every time; the defects were details, and **none would have been
caught by a green gate**. An invented venting pass. `indexOfFirst` with no `-1` guard, where `yOf(-1)`
is a valid row and so the wrong tile reads as success. A `!!` beside a hand-kept list, safe only while
the two agreed. Read the diff against intent, not against the test results.

It follows rules reliably and judges quality unreliably. Point it at work with a hard external signal
and a reviewer at the end. Do not let it decide what a number should be.
