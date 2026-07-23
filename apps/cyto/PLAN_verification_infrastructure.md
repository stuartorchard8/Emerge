# Cyto — verification infrastructure plan

> **Written 2026-07-23**, out of a session that built the gene-affordance changes, the Divide chapter
> reorder, the campaign branch and the lineage/extinction work. Most of that session's overrun was process,
> not debt — but four things cost real time *more than once*, and all four are structural. This is the
> write-up for tackling them in a fresh session.
>
> Ordered by time bought back per hour spent. Each item states the evidence, the fix, and what "done" looks
> like, because a cold session shouldn't have to re-derive why it matters.

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

## 3. Coach copy names UI tokens as bare strings, unchecked

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

---

## 4. `CytoEditLatencyTest` is a good tripwire wrapped in a noisy metric

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

---

## Not debt — recorded so it isn't mistaken for debt

Three of that session's bigger time sinks were process, and no refactor addresses them:

- Reading `./gradlew -q --tests X` printing nothing as "passed", when it meant **UP-TO-DATE**. Use
  `--rerun-tasks` whenever a result matters. (Recorded in memory.)
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
