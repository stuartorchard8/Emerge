# Cyto — verification infrastructure plan

> **Written 2026-07-23**, out of a session that built the gene-affordance changes, the Divide chapter
> reorder, the campaign branch and the lineage/extinction work. Most of that session's overrun was process,
> not debt — but four things cost real time *more than once*, and all four are structural. This is the
> write-up for tackling them in a fresh session.
>
> Ordered by time bought back per hour spent. Each item states the evidence, the fix, and what "done" looks
> like, because a cold session shouldn't have to re-derive why it matters.

## Status (2026-07-23, second session)

| # | item | state |
|---|------|-------|
| 1 | harness cannot reach the UI it tests | **open** — the big one, untouched |
| 2 | no cheap way to construct a world state | **open** — but see the note below; two of this session's fixes were exactly this problem |
| 3 | coach copy names UI tokens unchecked | **DONE** `80345934` |
| 4 | `CytoEditLatencyTest` is a noisy metric | **DONE** `f402435b` |
| 5 | the test suite took 6m27 | **DONE** `25af3af7` — added this session, see below |

Item 2 is now the top of the list by payback. It kept surfacing while fixing 5: the touch-count test grew a
400-tick colony to find one cell in contact, and `parallelMatchesSequential` seeds a world that never
divides. Both are "I need a specific world state and the only tool is to grow one and hope".

---

## 1. The harness cannot reach the UI it exists to test

**Evidence (four separate dead ends in one session).** Synthetic taps fall *through* popovers and pick
sheets to the world — tapping the action menu's CONVERT row spawned a cell instead of choosing an action.
So none of these could be driven headlessly:

- picking an action type from the inline gene card's dropdown,
- authoring the `BIO < 3000` condition the new Divide chapter step instructs,
- satisfying the growth-cap gate,
- playing `ch01-divide` to its end to see which branch chapter it routes into.

The workaround was `authorgenome`, which writes a genome **bypassing the editor entirely**. It unblocked
verification of the *gates*, but it means the UI path the campaign actually instructs players through has
no test at all. For a campaign whose entire job is "tap this, then that", that is the wrong gap to have.

Related symptom, probably the same root cause: `elements` does not list coach buttons either, so the agent
has to read a screenshot to find them.

**Suspected cause** (unconfirmed — start here): the popup/sheet is drawn outside the registered input
region of its parent panel, or drawn after input collection for the frame, so its rows never enter the
hit-test registry that `tap-ui`/`elements` walk. `GeneEditor.renderPickerSheet` / `UiTok.Menu` and the
`Ui` hit-test registry are where to look.

**Done when:** `elements` lists every on-screen interactive row *including* open menus, pick sheets and
coach buttons; `tap-ui "CONVERT"` picks it from an open action menu; and a harness script can author
`BIO < 3000` on a gene through the same taps the coach tells the player to make.

**Then:** add a script that plays `ch00-genesis` → `ch01-divide` end-to-end through the UI, both branch
choices. That script is the regression test for every future campaign edit.

---

## 2. No cheap way to construct a specific world state

**Evidence.** Most of the session's wasted runs were hunts for conditions, not tests of behaviour: a cell
in daylight; a gene that flickers on and off; a cell that can *just* afford to divide (fuel pool between
`cost` and `2 × cost`); an extinct world. Each hunt was several ~1-minute gradle runs, and two were
abandoned — the "strict 1/n split" change ended up pinned by a unit test on the arithmetic because no live
world could be steered into the window that discriminates it.

`kill <u> <v>` and `authorgenome <path>` were added mid-session and each turned a hard problem into one
line. That is the shape of the fix; there just isn't enough of it.

**Fix.** A world-state builder usable from BOTH `commonTest` and the harness — set a cell's biomass,
cytoplasm, genome and the local light level directly, on a named cell, without growing a world into the
state. Roughly:

```kotlin
CytoTestWorld.of(scenario)
    .cell(biomass = 3000, cytoplasm = mapOf("g" to 900, "b" to 900), genome = genes)
    .light(quanta = 0)
```

**Done when:** the discriminating case for the DIVIDE affordance (one gene funded, two contending genes
not) is expressible as a test, and the harness can reach an arbitrary named state in one command.

---

## 3. Coach copy names UI tokens as bare strings, unchecked — **DONE** (`80345934`)

**Evidence.** The Divide chapter's new step tells the player to tap `(ALWAYS)`, set the left side to
`(BIO)`, flip `(>)` to `(<)`, and the Genesis steps name `(NOTHING)`, `(CONVERT)`, `(NONE)`. Nothing
asserts those labels exist. `CampaignContent.validateGlyphs` proves the *characters* render; no test
proves the *words* are real. This session changed token labels in the gene card, which is exactly the
change that would silently make the copy lie — and every test would still pass.

**Fix.** A test that extracts `(TOKEN)` occurrences from every step's `text`/`detail` and asserts each is
in the editor's label vocabulary (`actionTypeLabel`, `sourceTypeLabel`, operand labels, `ALWAYS`, the
comparators, `NONE`). Where a token is dynamic (a species name), skip by pattern rather than by exception
list, so a new chapter can't quietly opt out.

**Done when:** renaming a gene-card token label fails a test that names the chapter and step whose copy
went stale. Cheapest item here — probably an hour — and it guards copy written in this session.

**As built.** `GeneCardLabels` (commonMain) is the single vocabulary, derived from the label functions
wherever it can be so a new `ActionType` extends it for free; `GeneEditor` delegates to it.
`CampaignCopyTokensTest` checks every `(TOKEN)` in every playable chapter, skipping dynamic tokens by
pattern as planned. It found two stale instructions on its first run: `ch01-divide` said `(LIGHT)` where
the label is `USE LIGHT`, and `ch03-anatomy`'s worked example still described the pre-redesign card.

---

## 4. `CytoEditLatencyTest` is a good tripwire wrapped in a noisy metric — **DONE** (`f402435b`)

**Evidence.** It measures **sim ticks elapsed inside one `setHeldGene`**, budget 2. Two different things
make it fail and the failure message cannot tell them apart:

- **A real regression.** Adding a `@Volatile` store to the edit-drain path (`lastAuthoredGenome = next`)
  made it fail 2 runs in 3 *in isolation*. That is the test doing its job, and it caught a genuine defect.
- **Full-suite load.** It also fails under parallel full-suite load at commits that predate the change —
  verified in a clean worktree at `cd86115a`. Same numbers (3 ticks / 1.3ms).

Because the two look identical, ~40 minutes went on establishing which was which, and the load failures
had already trained the assumption "flake" — which nearly let the real regression through. Its own doc
records two prior recalibrations, so this is a recurring cost.

**Fix (pick one).** Either assert the actual contract rather than a proxy for it — instrument
`stepLock` and assert the editing thread never acquires it — or force the test to run isolated/serially so
the tick-rate proxy stops competing with the rest of the suite for cores.

**Done when:** a failure of this test means exactly one thing, and the message says which.

**As built** — the first option, the real contract. A helper thread takes `stepLock` and *keeps* it (a tick
that never ends); each draw-thread write runs on its own thread. One that queues through `inputLock` returns
in microseconds, one that takes `stepLock` cannot return at all. Binary, and the scheduler has no say. The
timeout is not a budget, so there is nothing left to recalibrate. Verified in both directions: wrapping
`setHeldGene` in `stepLock` makes it fail. Coverage widened from gene edits to every interactive-rate write
(spawn, tap, mutation ladder, all the genome ops), with the failure naming which one broke the rule.

---

## 5. The test suite took 6m27, and four tests were 90% of it — **DONE** (`25af3af7`)

Added 2026-07-23 after Stu cancelled a suite run at 5 minutes. Rule set then: **no test may take more than
5 seconds**; a test that runs long is useless.

**Evidence.** 215 tests, 377s. Median test ~10ms — the suite was not broadly slow, it had a handful of
whole-ecosystem sims in it. `acrossOrientedDivisionGrowsA2DSheetNotAThread` alone was 268s (71%).

**What was actually wrong** — in every case, a tick/pass count nobody had measured:

| test | before | after | cause |
|---|---|---|---|
| `acrossOrientedDivision…2DSheet` | 268s | 0.5s | 2×300-tick colonies to ~1100 cells; separates at tick 20 |
| `parallelMatchesSequential` | 47s | 2s | materialised + digested both worlds *every* tick |
| `panelReportsTheRealTouchCount` | 16s | 0.3s | grew 400 ticks, then looked once — contact is transient |
| `exactlyOneSpeciesDiffusesPerPass` | 9.8s | 0.2s | `SpeciesRegistry.size` is **1884**, so ~45,000 full-grid diffuses |
| `diffusionSettlesAndThenZeroFlux` | 8.2s | 1.2s | 3,500 passes on a guess; the field stops changing at pass 40 |
| `diffusionRefillsAWideCrater` | 6.2s | 2.0s | 3,000 passes; the centre stops moving at 497 |

**The pattern, worth keeping:** every replacement number was *measured with a throwaway probe* and the
measurement written into the comment. The old numbers were all guesses with "slack" on top, and slack
compounds — 3,000 passes for a 40-pass phenomenon.

**Left open — a coverage gap, not a speed one.** `parallelMatchesSequential` **never grows past one cell**:
`createCytoInitialState()` with `mutationRateDenom = 0` sits at the founder for all 250 ticks, so with
`springParallelThreshold = 2` it likely never enters a parallel path at all. Its sibling
`parallelMatchesSequentialWeldedColony` does the real work (25 welded cells) in 0.49s. Either reseed it or
delete it — but decide, don't leave a gate that may be asserting nothing.

---

## Not debt — recorded so it isn't mistaken for debt

Three of that session's bigger time sinks were process, and no refactor addresses them:

- Reading `./gradlew -q --tests X` printing nothing as "passed", when it meant **UP-TO-DATE**. Use
  `--rerun-tasks` whenever a result matters. (Recorded in memory.)
- Trusting a tick/pass count in a test because a comment justified it. Every one audited in item 5 was
  wrong by 1-2 orders of magnitude. A throwaway probe that prints when the phenomenon actually settles
  costs one run and replaces the guess permanently.
- Reaching for a screenshot to verify logic that is a pure function of state. Renders answer *does this
  read well*; tests answer *is this right*.
- Writing a behaviour claim into a commit message without checking it (`SpeciesNames.name("")` already
  returned `(NONE)`, so a "fix" was dead code).

## Already paid off, and the pattern to keep

Campaign gate readings used to be derived inline inside `CytoController.worldStats` — which is why
`convertBiomassCap` and `divideFuelConflicts` were bolted on one at a time, and why genome facts and live
cell state were conflated on one type. They are now `lineageOf(genome)`, a pure function.

**Keep it that way: a campaign reading is a pure function of a genome, never derived at the call site.**
The extinction fix fell out of that almost for free.
