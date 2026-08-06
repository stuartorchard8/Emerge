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

From §9: *in the reducer's edit pass, if an edit would place anything within the pad of an edge, grow
that edge to restore it. Far sides only, per §6.* Half a day.

**Far sides only is the whole design, not a simplification.** Growing the `+x`/`+y` edge leaves the
origin where it is, so every coordinate already in the world — every test, every script, every saved
camera position — stays valid. Growing `−x`/`−y` shifts all of them, which is precisely the silent
drift that cost us six red scripts in P2. Do not let this get "generalised" into symmetric growth.

**There is a genuine open question here, and it is Stu's to answer, not qwen's:** what happens when
an edit lands within the pad of the *near* edge? Far-sides-only means the pad cannot be restored
there. The options are to grow anyway and accept the remap, to refuse the edit, or to let the pad be
violated on that side. Ask before speccing — a wrong answer here is architecture, not a bug.

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

1. **Placing within the pad of the far edge grows it.** Assert the pad is *restored* — recompute the
   bounding box and check the margin — not that the grid reached some width.
2. **Growth never moves anything.** Pick a machine, record `grid.xOf`/`yOf`, grow, assert unchanged.
   This is the test that stops symmetric growth being introduced later.
3. **All six ledgers stay zero across a growth:** `airBalance`, `airJouleBalance`, `massBalance`
   (`inTransitGrams + ventedGrams - extractedGrams`, **not** `massGrams` — that includes the ship's
   own fabric), `momentumBalance`, `rockBalance`, `heatBalance`.
4. **`motion` is the new grid's size** after a growth. The bug above, pinned.
5. **Idempotence.** An edit well inside the pad grows nothing.
6. **Rocks are undisturbed.** They are in world coordinates and far-side growth does not move the
   origin, so their positions must be bit-identical.
7. **A growth mid-run does not perturb the sim.** Digest a world 300 ticks after a growth against the
   same world grown at construction; §10's determinism check, and the one most likely to catch a
   subtly wrong edge-field remap.

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
