# Cyto — Campaign / Story Mode (design plan)

*A staged, problem-driven onboarding that teaches the world, then the gene, then how genes compose
into emergent bodies. This doc is the design contract for the in-game tutorial; `TUTORIAL.md` is its
prose companion (the campaign is the interactive path through the same material).*

> **Agent harness (2026-07-11):** `CytoAgentHarness` (desktop, `./gradlew :apps:cyto:desktop:cytoAgent
> --args="<script> [outDir]"`) drives Cyto **headlessly** — script commands: `scenario`/`campaign`/`run`/
> `runs`/`camera`/`tap`/`select`/`spawn`/`clickcell`/`dragcell <id> <u> <v> <ticks>`/`cells`/`elements`/
> `tap-ui <label>`/`overlay`/`next`/`shot`/`state`/`echo` (see the `CytoAgentHarness` KDoc for the full
> list + per-command tick advance). Renders cells+coach
> to PNG via `java.awt` (`controller.agentCells()`) and dumps world+coach JSON (`director.snapshot()`),
> so an agent can iterate without the GL window. Also fixed: bitmap font is uppercase-only + limited
> punctuation (added `'`; `;`/`—`/`◆▸✓🔒` render as `?` — `UiTextRenderer.supports()` + `CampaignContent.
> validateGlyphs()` guard against regressions); per-step `WorldRun.Frozen/Live` so a slow reader isn't
> overtaken by later-concept events (world holds still on reading beats).
>
> **Status (2026-07-14): Ch1–10 built and committed** (Act I + II + the full locomotion arc through
> reproduction). This section and **§2.1 (the spine)** are the canonical current record; the deeper design in
> **§5–6 is the ORIGINAL sketch and is now stale** (its chapter numbering never matched what shipped) — trust
> this block + §2.1 over §5–6 where they disagree. **Next build target: Ch11 capstone (optional, unspecced)**
> — the core arc is complete; a capstone would turn on mutation/selection and graduate to sandbox (scope TBD).
>
> **Engine (core commonMain `org.emerge.demo.cyto.campaign`):** `CampaignModel` (`Chapter`/`Step`/`Gate`/
> `PlayerAction`/`ControlMask`/`Spotlight`/`WorldRun`), `CampaignQuery`+`WorldStats`+`FocusedCell`,
> `CampaignDirector` (coach overlay via the shared `Ui` toolkit, gate eval, step advance, `snapshot()` for
> the harness). Desktop host: `CampaignContent.kt` (the authored chapters + genomes + grouping),
> `CampaignProgress.kt` (unlock persistence), wiring in `CytoSceneView.kt` (coach render, control masking,
> player-action detection, Campaign menu branch), and `CytoAgentHarness.kt` (headless driver).
>
> **As-built arc (the campaign runs on a single grow-only autotroph *substrate* the player authors, NOT
> the busy default ecosystem):**
> - **Ch1 First Contact** — camera, select, info panel, torus. Paused, grow-only cell.
> - **Ch2 Let There Be Light** — light feeds it; it grows to full size and *holds steady* (stationary,
>   self-repairing, never spreads). Matter is DELIBERATELY not introduced here (a lone cell never visibly
>   starves — Stu's call); it lands in Ch4.
> - **Ch3 Anatomy of a Gene** — opens the gene editor; genome shown **by function** (collapsed groups),
>   teaches purpose-first then the grammar `ACTION IF CONDITION (SOURCE)`.
> - **Ch4 Give It Life** — player inserts the **Reproduce** group (one tap "+ ADD REPRODUCE"); the static
>   cell divides and spreads; the colony's depleted "comet" patch introduces the finite-matter budget.
> - **Ch5 Hold Together** — first **direct gene edit**: toggle the divide gene's `SEVER: yes→no` so daughters
>   stay welded. Welded cells stall (severing was locomotion!), so the player **drags** the body to feed it —
>   a towed, connected body.
> - **Ch6 Under Strain** — welded body tears when yanked (welds now half-durable); player inserts the
>   **Repair** "Hold Together" group; body becomes tougher (damage-gated Repair, tougher-not-invincible).
> - **Ch7 A Muscle** — inserts the **Move** group (a single light-powered `Contract` gene); the body clenches
>   by day and relaxes at night, but an *even* squeeze goes nowhere — the deliberate lesson that sets up
>   asymmetry. (Substrate switches here from the r/g autotroph toward the swimmer lineage.)
> - **Ch8 A Sense of Place** — Act III opener. Inserts the **Polarise** group: a `bb` morphogen handed whole
>   to one daughter → marked/bare cells contract unevenly → the body **crawls** (asymmetric contraction = real
>   travel). Growth-limiting is **smuggled in here** (the same morphogen gradient lets the body sense its size
>   and stop at a small cluster). Introduces time controls + the tap-empty-space re-seed affordance.
> - **Ch9 A Beat of Its Own** — a metabolic **Clock** group (internal oscillator) frees the muscle's beat from
>   sunlight, then the player edits a **3-generation lineage by hand**: clock → muscle-fuel Light→Break rg
>   (swims at night) → marker flip bb<1→bb>0 (better swimmer), each generation tapped out as a **live copy of
>   the selected cell** ("last-modified brush", `Chapter.spawnCopiesHeldCell`). Uses Stu's swimmerx/swimmerxX
>   genomes. (Growth-limiting NOT needed here — already in Ch8.)
> - **Ch10 Spread** — arc close. Substrate = the Ch9 end-state swimmer (growth-capped); player inserts a
>   **Reproduce** group (Stu's `reproducer.gene` = a reserve Convert + a `Divide gr mother sever` division,
>   rejectMother) so a freed daughter buds off unwelded, escapes the size cap, and grows a new swimmer — one
>   creature becomes a spreading, colonising lineage. Closes the "single speck → living lineage" through-line.
>
> **Gene grouping = a persistent per-gene TAG** (`Gene.group: String`, §10 — see the updated §10 for the
> as-built design). No matching: the tag survives editing/division/mutation/save (round-tripped by
> `GeneCodec` as an optional 4th `:`-part). Editor (`GeneEditor`) renders collapsed groups + a "+ ADD
> <group>" affordance for absent insertable groups; `GenomeGrouping.sections()` buckets by tag.
>
> **Sim changes made for the campaign:** `AUTOTROPH_GROW_ONLY_GENES`, `AUTOTROPH_REPAIR_GENE`,
> `FounderSpec.genome` override (per-founder genome), `FocusedCell.divideWelds`, and
> `CONNECTION_BREAK_DAMAGE` **halved 5→2.5** (welds were too durable to tear on drag) — golden re-baselined
> (only the 2 weld goldens moved; trajectory/determinism gates unchanged), weld spec-test fixtures
> recalibrated. All committed.
>
> **Tests green:** `CampaignDirectorTest`, `GenomeGroupingTest`, `GeneCodecTest` (tagged round-trip),
> `CytoGoldenTest` (re-baselined), full `CytoSoaSpecTest`.
>
> **⚠️ Not done / open:** (1) **Ch6 drag FEEL is unverified** — the harness `dragcell` moves too smoothly to
> over-stretch welds, so the tear-vs-hold visual + threshold tuning need a **live GL drag playtest** (Stu
> confirmed manual tearing works). (2) region-targeted spotlights/arrows still text-hint-only. (3) **Ch8–9
> behaviour is harness-verified for FLOW ONLY** (chapters load glyph-clean, group-inserts take the right gene
> counts, coach + editor render) — the actual locomotion payoffs need a **live GL playtest**: does Ch8's body
> crawl once polarised; does Ch9's body pulse+crawl on the clock, does the Break-rg variant swim past
> nightfall, does the marker-flip out-swim it. The biology is Stu's validated swimmer save; only the campaign
> wiring is auto-verified. (4) The Genome Workshop / group-library / move-between-groups UI (§10.5) — data
> model supports re-tagging, no UI yet. (5) No live in-app visual playtest of the whole arc yet.
>
> **Ch10 Spread — BUILT (`f49522b4`).** Substrate = the Ch9 end-state swimmer (Stu's SwimmerxX, growth-capped
> by the Ch8 morphogen); player inserts a REPRODUCE group (Stu's `reproducer.gene` payload = a reserve Convert
> + a `Divide gr mother sever` division, rejectMother) so a freed daughter buds off unwelded, escapes the cap,
> and grows a new swimmer. Objective `cellCount ≥ 20`. Harness-verified for flow (glyph-clean, 19→21 genes,
> sever-division fires 1→2→3 once pushed to fresh matter). The full colonisation payoff joins Ch8/Ch9 on the
> **live-GL-playtest** list — it needs autonomous swimming over many day/night cycles to see the lineage spread.
>
> **Next session — Ch11 capstone (optional, unspecced).** The core arc (single grow-only speck → clocked,
> differentiated, self-reproducing swimmer lineage) is COMPLETE at Ch10. A capstone would turn on
> mutation/selection and graduate to the open sandbox with minimal coaching; scope is TBD and it may not be
> needed. Higher-value near-term work is the **live-GL playtest pass** over Ch8–10 (the locomotion/colonisation
> payoffs are only harness-flow-verified) rather than a new chapter.
>
> **Living-world visuals note (still open, lower priority):** the `LIVING_WORLD_PLAN.md` metabolic flows only
> pay off on an actively-metabolising body — the swimmer lineage (Ch7+) now provides one, so a future pass can
> **add campaign copy naming each transfer** as it appears. See [[project_cyto_living_world]].
>
> **Architecture grounding (unchanged):** the immediate-mode `Ui` toolkit (`engine/render/torus/.../ui/
> Ui.kt`), the `CytoMenu` shell + `Callbacks`, the `CytoScenario` recipe system, the genome library
> (`CytoGenomes`), and the controller's world-query surface (`heldCellInfo`, `worldStats()`,
> `CytoCellComponent` table, `newGame`).

---

## 1. Design goals & principles

The problem we're solving: Cyto is *impenetrable on first contact*. A new player sees a coloured blob
pulsing on a black field and has no way in. The campaign is the way in.

Five principles drive every decision below:

1. **Problem before mechanism.** Never explain a feature in the abstract. Pose a *situation the
   player wants to resolve* ("this cell is starving in the dark — fix it"), and let the mechanism be
   the tool that resolves it. Engagement comes from a goal, not a lecture.
2. **One new idea per beat.** Each step introduces exactly one concept and immediately exercises it.
   The player never holds more than one new thing in working memory at a time.
3. **Show the emergence, don't assert it.** The payoff of Cyto is that simple local rules produce
   complex global behaviour. The campaign is structured so the player *builds the simple rule and
   then watches the complex thing happen* — the "aha" is earned, not told.
4. **Depth is opt-in.** Every chapter has a *required spine* (the minimum to progress) and *optional
   depth* (info cards, side-objectives, "try this"). A player who wants to rush learns the essentials;
   a player who wants mastery can go arbitrarily deep. The campaign is a ladder, not a rail.
5. **Graduate to the sandbox.** The campaign's endpoint is *competence in the existing free-play
   tools* (New/Custom worlds, the gene editor, the genome library). It hands the player off to
   open-ended play, it doesn't wall them inside a scripted track.

Non-goals: we are **not** rebuilding the sim, the physics, or the gene model. We are **not** replacing
the existing New/Custom/Load flow — the campaign is a sibling to it. We reuse the `Ui` toolkit,
`CytoScenario`, and `.gene` genomes wholesale.

---

## 2. The shape of the whole thing

The campaign is **four acts, ~11 chapters**, each chapter a hand-authored world + a scripted coaching
sequence + one or more objectives. Difficulty and conceptual load rise monotonically.

| Act | Theme | Chapters | What the player leaves knowing |
|---|---|---|---|
| **I — The World** | Interaction literacy | 1–2 | Camera, selection, the info panel, light vs matter, that cells live and die on their own |
| **II — Building a Body** | Subsystem literacy | 3–7 | The gene grammar; grow → reproduce → cohere → mend → move, each added as a functional group to solve a problem |
| **III — A Creature** | Emergence + locomotion | 8–10(11) | Differentiation (asymmetric contraction = travel), a metabolic clock (own beat), editing a lineage by hand, reproduction (colonise) |
| **IV — The Wild** | Graduation | 11 + sandbox | The full swimmer; turning on mutation/selection; free play |

Acts I–II are tightly scripted (high hand-holding). Act III loosens (the player is composing, we
coach less). Act IV is essentially the existing sandbox with an optional challenge list.

### 2.0 The rework spine (Genesis-first) — AS BUILT, and where the campaign actually starts now

> The chapters in §2.1 are the **pre-inversion** spine (Ch1–10, built 2026-07-11…14). They still run and
> still ship, but the campaign is being re-authored from a far more basic opening: an empty world the
> player seeds by hand, one primitive at a time. Those rework chapters live in
> `CampaignContent.SCRATCH_CHAPTERS` and are surfaced ahead of the old ones by `PLAYABLE_CHAPTERS`.
> **This is the part under active authoring** — read it before §2.1.

- **Genesis** (`ch00-genesis`) ✅. Empty world on a real day/night cycle. The player places a gene-less
  cell, watches it decay and rupture, places another, and authors their first gene: `+ NEW GENE` →
  action `CONVERT` → pick a chemical. Payoff: it locks that chemical into biomass and starts to climb.
  The chosen chemical becomes the coach's `{chem}` token for the rest of the campaign.

- **Divide** (`ch01-divide`) ✅, **restructured 2026-07-23**. Adds MITOSIS, and teaches the two separate
  reasons it does not fire, **in this order**:

  1. add the MITOSIS gene (inert),
  2. *the bill scales with the body* — division costs `biomass/4` in one tick while the cytoplasm that
     pays it does not grow with the body, so an uncapped grower outruns its own ability to divide. The
     player caps growth with the first condition they ever author (`BIO < 3000`),
  3. *and light is a trickle, not a lump sum* — switch the energy source to `BOND` and pick a pair.

  **Order matters and is load-bearing**: with the old order the player made the chemistry edit and watched
  nothing happen, because the cell had already outgrown any reaction. Gated on
  `Lineage.convertBiomassCap <= GROWTH_CAP_MAX` (3500). Measured: capped at 3000 a cell divides within
  ~2500 ticks; at 4000 it never does.

  **The chapter ends POISED on the split, not past it.** The first division is the next chapter's opening
  beat, because which chapter that is depends on the pair just chosen — see §12.

- **The branch** (`ch02-photosynthesis` / `ch02-conversion`) — first-draft opening beats only, ✅ routing,
  ❌ copy and fixes. Which one the player gets is read off the fuel pair they chose in Divide and nothing
  else. **See §12** for the mechanism, the table, and the open list.

### 2.1 Chapter list (spine) — AS BUILT (canonical)

> This is the spine that actually shipped (Ch1–9 built 2026-07-11…14). It **replaces** the original design
> sketch that used to live here (preserved in §6, now flagged stale). The campaign runs on a **single
> author-able organism** the player grows into a creature — a grow-only autotroph for Act I–II, transitioning
> to the **swimmer lineage** at Ch7. Each Act-II/III chapter adds/edits ONE functional **group** (§10).

- **Ch 1 — First Contact** ✅. *Problem:* "There's a lone cell. Find it and learn to look at it." Pan/zoom,
  select, the info panel, the toroidal world. Grow-only autotroph, paused.
- **Ch 2 — Let There Be Light** ✅. *Problem:* "Watch a cell feed on sunlight and hold its ground." Light
  field + day/night; it grows to full size and holds steady (never spreads). Matter deferred to Ch4.
- **Ch 3 — Anatomy of a Gene** ✅. *Problem:* "Why does this cell do what it does? Read its program." Opens
  the editor; genome shown **by function** (groups); grammar `ACTION IF CONDITION (SOURCE)`.
- **Ch 4 — Give It Life** ✅. *Problem:* "It grows but can't reproduce." Insert the **Reproduce** group; it
  divides + spreads; the depleted "comet" patch introduces the finite-matter budget.
- **Ch 5 — Hold Together** ✅. *Problem:* "A scattering swarm isn't a body." First direct gene edit — toggle
  `SEVER: yes→no` so daughters stay welded; the player **drags** the towed body to feed it.
- **Ch 6 — Under Strain** ✅. *Problem:* "Welds snap when you pull." Insert the **Hold Together** (Repair)
  group; damage-gated Repair mends welds under strain (tougher, not invincible).
- **Ch 7 — A Muscle** ✅. *Problem:* "It's been dragged to its food — time it moved itself." Insert the
  **Move** group (light-powered `Contract`); it clenches by day, but an *even* squeeze goes nowhere → sets
  up asymmetry. (Substrate transitions to the swimmer lineage here.)
- **Ch 8 — A Sense of Place** ✅. *Problem:* "An even squeeze goes nowhere — teach the cells which side
  they're on." Insert the **Polarise** group; a `bb` morphogen handed to one daughter → asymmetric
  contraction → it **crawls**. Growth-limiting is folded in (the gradient also caps body size). Introduces
  time controls + the tap-to-re-seed affordance.
- **Ch 9 — A Beat of Its Own** ✅. *Problem:* "It swims on sunlight and stalls every night — give it an inner
  clock, then breed it better." Insert the **Clock** group (oscillator), then edit a **3-generation lineage
  by hand** (fuel Light→Break rg = night-swimming; marker bb<1→bb>0 = better swimmer) via the **last-modified
  brush** (`Chapter.spawnCopiesHeldCell`). Uses Stu's swimmerx/swimmerxX genomes.
- **Ch 10 — Spread** ✅ (`f49522b4`). *Problem:* "Your swimmer grows to a set size and stops — one creature.
  Make it spread." Substrate = the Ch9 end-state swimmer (Stu's SwimmerxX, growth-capped by the Ch8
  morphogen); player inserts a **Reproduce** group (Stu's `reproducer.gene` payload = a reserve-building
  Convert + a `Divide gr mother sever` division, rejectMother) so a freed daughter buds off unwelded,
  escapes the size cap, and grows into a new swimmer. Objective: `cellCount ≥ 20` (new independent swimmers
  colonise). **Flow-verified in harness** (loads glyph-clean, ADD REPRODUCE = 19→21 genes, sever-division
  fires 1→2→3 once pushed to fresh matter); the full colonisation payoff shares the Ch8/Ch9 live-GL-playtest
  item (autonomous swimming over many day/night cycles).
- **Ch 11 — Capstone / Hopeful Monster** (optional, unspecced). *Problem:* "You've built a whole swimmer —
  now turn on mutation and let selection act." Minimal coaching; graduates to sandbox. Scope TBD.

### 2.2 Progression & unlocking

- Chapters unlock from their **predecessors**, not from a list index: a chapter is unlocked once any
  chapter that can lead to it is completed (`CampaignContent.predecessorsOf` + `CampaignProgress.isUnlocked`,
  2026-07-23). For a linear chapter that is exactly "completing *N* unlocks *N+1*"; it is the branch (§12)
  that needed the generalisation. Any unlocked chapter is replayable.
- Progress persists to a small file (`campaign-progress`, alongside `cyto-saves/` and
  `cyto-genomes/`): highest unlocked chapter + per-chapter completion flags.
- Genomes the player builds during the campaign can be EXPORTed to the genome library (existing flow),
  so campaign work carries into sandbox play.
- A "Skip to sandbox" escape hatch is always available from the campaign menu (respect the player's
  time; some players arrive already knowing cellular automata).

---

## 3. Architecture

The campaign is a thin, self-contained layer over the existing game. It introduces one new subsystem
(the **director**), one new menu branch, and a handful of read-only query methods on the controller.
Nothing in the sim changes.

```
                 ┌─────────────────────────────────────────────┐
   CytoSceneView │  frame loop (existing)                        │
   (desktop host)│    renderer.draw(frame)                       │
                 │    controls.draw()                            │
                 │    ui.frame { geneEditor.render(...)          │
                 │               menu / campaign overlay  }  ◄───┼── NEW: campaign overlay
                 │    ui.draw()                                   │
                 └───────────────┬──────────────────────────────┘
                                 │ drives
                 ┌───────────────▼──────────────────────────────┐
                 │  CampaignDirector           (NEW, commonMain) │
                 │   - current chapter + step index              │
                 │   - evaluates step gates & objectives         │
                 │   - renders the coach overlay (Ui toolkit)    │
                 │   - restricts/enables controls per step       │
                 └───────┬───────────────────────┬──────────────┘
             reads       │                        │  reads/writes
        ┌────────────────▼─────┐        ┌─────────▼───────────────┐
        │ CampaignQuery (NEW)  │        │ CampaignProgress (NEW)  │
        │  read-only world     │        │  persisted unlock state │
        │  stats on controller │        │  (desktop file I/O)     │
        └──────────────────────┘        └─────────────────────────┘
```

### 3.1 New types

**`campaign/Campaign.kt` (commonMain, `org.emerge.demo.cyto.campaign`)** — the authored content model:

```kotlin
/** A full chapter: a world recipe, a scripted coaching sequence, and its win condition(s). */
class Chapter(
    val id: String,                 // stable key for progress persistence, e.g. "ch02-light"
    val act: Int,
    val title: String,
    val blurb: String,              // one-line teaser on the chapter-select card
    val scenario: CytoScenario,     // reuses the existing recipe system
    val seededGenomes: List<NamedGenome> = emptyList(),  // genomes this chapter provides as brushes
    val steps: List<Step>,          // the ordered coaching beats
)

/** One coaching beat. Holds the instruction, how it advances, and what the player may do meanwhile. */
class Step(
    val text: String,               // the coach panel copy (short — one idea)
    val detail: String? = null,     // optional "more" card, opt-in depth
    val spotlight: Spotlight? = null,   // where to point the player's eye
    val gate: Gate,                 // what advances to the next step
    val allow: ControlMask = ControlMask.ALL,  // which controls are live this step
    val onEnter: (StepContext) -> Unit = {},    // e.g. spawn a helper cell, focus a target
)

/** What advances a step. */
sealed interface Gate {
    object Next : Gate                                   // manual: the "Next" button
    class World(val desc: String, val met: (CampaignQuery) -> Boolean) : Gate  // a world condition
    class Did(val action: PlayerAction) : Gate           // player performed an interaction
    class All(val gates: List<Gate>) : Gate              // every sub-gate met
}

/** Interactions the director can wait on (observed via controller/host state deltas). */
enum class PlayerAction { SelectedCell, OpenedInfoPanel, OpenedGeneEditor, PaintedCell,
                          ToggledLight, ToggledMatter, Paused, ZoomedOrPanned, EditedGene }

/** Where to draw attention. Named UI regions avoid needing to introspect arbitrary widget rects. */
sealed interface Spotlight {
    enum class Region { Palette, InfoPanel, SpeedControls, MutButton, MenuButton, LightMatterButtons, GeneEditor, Screen }
    class Ui(val region: Region, val arrow: Boolean = true) : Spotlight
    class WorldCell(val pick: (CampaignQuery) -> Int?) : Spotlight   // ring a specific entity
    class WorldPoint(val x: Float, val y: Float) : Spotlight
}
```

**`campaign/CampaignQuery.kt`** — a read-only snapshot the objective predicates run against. Backed
by a scan of the `CytoCellComponent` table (exactly what `readouts()` / `heldCellInfo()` already do):

```kotlin
class CampaignQuery(
    val tick: Long,
    val cellCount: Int,
    val countByType: Map<CellType, Int>,
    val maxBiomass: Int,
    val speciesPresentAnywhere: Set<String>,     // union of cytoplasm+biomass species over all cells
    val distinctBiomassProfiles: Int,            // ~ how many visually distinct "tissues" exist
    val focusedCell: FocusedCell?,               // the currently-selected cell, if any
    val paletteSelection: String?,               // selected brush genome name
    val lightOverlayOn: Boolean,
    val matterOverlayOn: Boolean,
    val paused: Boolean,
) {
    class FocusedCell(val type: CellType, val biomass: Int, val genome: List<Gene>,
                      val cytoplasm: Map<String, Int>, val geneCount: Int)
}
```

This is the single new read surface on the controller: a `fun campaignQuery(host: HostFlags): CampaignQuery`
that iterates the component table once. `distinctBiomassProfiles` is a cheap heuristic (bucket cells by
their dominant biomass molecule) that lets us detect "the colony differentiated into two tissues"
without any new sim state.

**`campaign/CampaignDirector.kt`** — the runtime. Owns `chapterIndex`, `stepIndex`, evaluates the
current step's gate/objective each frame, renders the coach overlay, and reports which controls are
masked. Immediate-mode, same lifecycle as `GeneEditor`:

```kotlin
class CampaignDirector(val campaign: Campaign, val progress: CampaignProgress) {
    fun render(ui: UiBuilder, controller: CytoController, host: HostFlags)   // draw coach + spotlight
    fun update(query: CampaignQuery)                                         // advance gates/objectives
    val controlMask: ControlMask     // host consults this to grey out disabled buttons
    val activeChapter: Chapter?
    fun startChapter(c: Chapter, controller: CytoController)
    fun exitToMenu()
}
```

**`CampaignContent.kt` (desktop)** — the authored chapters themselves, in a small Kotlin DSL (below).
Kept in desktop for now (like `CytoGenomes`/`CytoSaves`); the *engine* (`CampaignDirector`) is
commonMain so Android/web can host the same content later.

### 3.2 Authoring DSL

Chapters are data, but Kotlin-authored (type-safe, refactor-safe, and it can reference `CytoScenario`
/ `CellType` / `.gene` text directly). A builder keeps them readable:

```kotlin
val CH02_LIGHT = chapter("ch02-light", act = 1, title = "Let There Be Light") {
    blurb("Watch a cell feed on sunlight — and survive the night.")
    scenario(CytoScenario.DEFAULT)                       // single autotroph at origin

    step("This green cell is an autotroph — it eats light. Press [Light] to see the sun.") {
        spotlight(Ui(Region.LightMatterButtons))
        gate(Did(ToggledLight))
    }
    step("The bright band is daylight sweeping across the world. Where it's dark, the cell can't feed.") {
        gate(Next)
    }
    step("Speed things up and watch it grow, then split.") {
        spotlight(Ui(Region.SpeedControls))
        gate(World("Reach 8 cells") { it.cellCount >= 8 })
    }
    step("It stopped multiplying — it ran out of matter nearby. Turn on [Matter] to see what's left.") {
        spotlight(Ui(Region.LightMatterButtons))
        gate(All(listOf(Did(ToggledMatter), World("plateau") { it.cellCount in 8..40 && it.tick > START + 4000 })))
    }
    complete("Matter is finite; light is free. That tension is the whole game.")
}
```

### 3.3 Host integration (CytoSceneView)

Minimal, additive changes to the existing frame loop:

1. **Menu branch.** Add `Page.Campaign` (chapter select) and a `Campaign` button on the Title screen.
   New callbacks: `onStartChapter(Chapter)` (builds the scenario via existing `onStart` plumbing, then
   activates the director) and `onExitCampaign()`.
2. **Overlay.** Inside the existing `ui.frame { … }` in-game block, after the gene editor, call
   `director.render(this, controller, hostFlags)` when a chapter is active. The coach panel is a new
   **bottom-center** anchored panel (§4.1).
3. **Objective tick.** Once per frame build a `CampaignQuery` and call `director.update(query)`.
   `HostFlags` carries the host-owned booleans the query needs (light/matter overlay, paused,
   selected genome) — these already live in `CytoControls`/`CytoSceneView`.
4. **Control masking.** Where controls are drawn, consult `director.controlMask` to skip/grey disabled
   ones (e.g., Ch 1 hides the palette and gene editor so the player isn't overwhelmed). This is the
   only slightly invasive change; it's a set of `if (mask.allows(X))` guards around existing draws.
5. **Observing `PlayerAction`s.** The director detects interactions by diffing controller/host state
   between frames (selection changed → `SelectedCell`; `heldCellInfo() != null` first time →
   `OpenedInfoPanel`; a genome edit committed → `EditedGene`; overlay toggles → `ToggledLight`/
   `ToggledMatter`). No new event bus needed — it's all observable from the query + host flags.

### 3.4 What must be added vs reused

| Reused as-is | New (small) | New (the work) |
|---|---|---|
| `Ui` toolkit (panels, steppers, overlay) | `Anchor.BottomCenter` / `TopCenter` (2 enum cases + layout) | `CampaignDirector` runtime |
| `CytoScenario` + `newGame` | `controller.campaignQuery()` (one table scan) | The authored chapter content (Ch 1–11) |
| `CytoGenomes` `.gene` genomes | `HostFlags` struct | Coach overlay + spotlight rendering |
| `CytoMenu` shell pattern | `campaign-progress` persistence (mirror `CytoSaves`) | Control-mask plumbing in the host |
| `GeneEditor` (Ch 3+ reuse it directly) | `Page.Campaign` + 2 callbacks | Playtesting/tuning each chapter's thresholds |

No sim changes. No physics changes. The gene model is untouched.

---

## 4. The coach UI

### 4.1 The coach panel

A persistent **bottom-center** panel while a chapter is active:

```
┌──────────────────────────────────────────────────────────────┐
│  ◆ Ch 2 · Let There Be Light                          (2/5)   │
│                                                                │
│  Speed things up and watch it grow, then split.                │
│                                                                │
│  ▸ Objective: reach 8 cells            ▓▓▓▓▓░░░  5/8            │
│                                                                │
│  [ More ]                              [ Skip ]     [ Next ▸ ] │
└──────────────────────────────────────────────────────────────┘
```

- **Header:** chapter title + step counter, so the player always knows where they are.
- **Body:** the one-idea instruction. Short. Second person, imperative.
- **Objective row:** only shown for `World` gates — the live predicate rendered as a progress bar +
  count, so the player sees themselves making progress (this is the engagement engine). Built from the
  toolkit's rects; the count comes from the same predicate, exposed as `progress: (Query) -> Pair<Int,Int>?`.
- **Buttons:** `More` (opens the opt-in `detail` card as a stacked panel), `Skip` (advance without the
  gate — always available, respects player time), `Next` (only enabled for `Gate.Next`, or once a
  `World`/`Did` gate is satisfied, so the player confirms they've read it before moving on).

`Anchor.BottomCenter` is the one new toolkit primitive — a straightforward addition mirroring `Center`
(centre horizontally, stack up from the bottom edge). The existing `Center` code path shows exactly
how to do it.

### 4.2 Spotlight & attention

To point the eye without introspecting arbitrary widget rects, we use **named regions** whose screen
positions the host already knows (the palette is bottom-left, info panel top-right, speed controls in
the controls bar, etc.). The director asks the host for the pixel rect of a `Spotlight.Region`, then:

- **Dim** the rest of the screen with a translucent full-screen rect (toolkit `background`), and
- draw a **pulsing outline** around the target rect (an animated colour — the director carries a phase
  clock), plus an optional **arrow** (a small triangle rect) pointing at it.

For world targets (`WorldCell`/`WorldPoint`) the ring is drawn at the projected screen position via
`renderer.worldToScreen` (already used for the debug readouts) and follows the cell each frame.

Dimming is **opt-in per step** (only when we truly want to force focus, e.g. "click *this* button" in
Ch 1). Most steps just show the coach panel and maybe an arrow — heavy dimming every step is
patronising and we avoid it (principle 4).

### 4.3 Tone & copy rules

- Second person, present imperative: "Click the cell." Not "The user should click…".
- Name the payoff, not the mechanism, in the *problem* framing; name the mechanism in the *resolution*.
- ≤ ~14 words per instruction line where possible; overflow goes in `More`.
- Every chapter ends on a **one-sentence "why this matters"** that ties the mechanic back to
  emergence (the `complete(...)` line).

---

## 5. Minutia — Act I, fully specified

This is the part the rest of the campaign is patterned on. Two chapters, every step: copy, spotlight,
gate, control mask, and the query each objective needs. (World tick offsets shown as `START` = the
chapter's start tick.)

### Chapter 1 — First Contact

*Goal: the player can move the camera, select a cell, and read the info panel. No genetics, no
painting — pure interaction literacy. Controls masked down to camera + selection so nothing distracts.*

**Scenario:** `CytoScenario.DEFAULT` (one autotroph at origin), **sim paused on entry** so the world is
still and unintimidating. **Control mask:** camera (pan/zoom) + click-select only. Palette, gene
editor, speed, mutation, save — all hidden.

| # | Coach text | Spotlight | Gate | Notes / query |
|---|---|---|---|---|
| 1 | "Welcome to Cyto. This is a single living cell, floating in an empty world." | `WorldCell{ founder }` (gentle ring, no dim) | `Next` | `onEnter`: `controller.focus(founder)` then clear, camera centred on it |
| 2 | "Drag empty space to move around. Scroll to zoom. Try it — find the cell again." | none | `Did(ZoomedOrPanned)` | detect via camera delta from host |
| 3 | "Click the cell to select it." | `WorldCell{ founder }` + arrow, **dim on** | `Did(SelectedCell)` | `query.focusedCell != null` |
| 4 | "This panel is the cell's dossier: its size, its chemistry, and its genes. You'll live in here." | `Ui(InfoPanel)` | `Next` | info panel already opens on select |
| 5 | "The world wraps around — walk off one edge and you arrive at the other. It's a doughnut." | none | `Next` | optional `detail`: why a torus (no walls, homogeneous) |
| — | *complete:* "That's the whole world: cells, light, and matter. Next, let's watch this one live." | | | unlock Ch 2 |

Design notes: pausing the sim in Ch 1 is deliberate — a moving target while learning to pan/zoom is
frustrating. Step 3 is the one place we hard-dim, because "click the cell" must not be missable. Step 2
accepts *any* camera movement as success (generous gate — we're building confidence, not testing
precision).

### Chapter 2 — Let There Be Light

*Goal: the player understands energy (light, free) vs matter (finite), sees the autonomous
grow→divide→plateau loop, and can use the overlays + speed controls. Still no painting/editing.*

**Scenario:** `CytoScenario.DEFAULT`, **sim running** (slow). **Control mask:** camera, selection,
speed controls, light/matter overlays. Gene editor + palette still hidden.

| # | Coach text | Spotlight | Gate | Notes / query |
|---|---|---|---|---|
| 1 | "This green cell is an *autotroph* — it eats sunlight. Press Light to see the sun." | `Ui(LightMatterButtons)` + arrow | `Did(ToggledLight)` | `query.lightOverlayOn` |
| 2 | "The bright band is daylight, sweeping across the world. In the dark, the cell can't feed." | none | `Next` | `detail`: exposure — interior cells are shaded too |
| 3 | "Watch it work. Speed the sim up." | `Ui(SpeedControls)` + arrow | `Did(Paused)`-family: any speed change | host speed delta |
| 4 | "It's converting light and matter into its own body — growing." | `WorldCell{ founder }` | `World("grow") { it.maxBiomass > 1500 }` | progress bar on biomass |
| 5 | "Big enough, it splits in two. Keep watching." | none | `World("reach 4 cells") { it.cellCount >= 4 }` | progress 1→4 |
| 6 | "A colony! But it's slowing down. Turn on Matter to see why." | `Ui(LightMatterButtons)` | `Did(ToggledMatter)` | `query.matterOverlayOn` |
| 7 | "The cells have eaten the matter around them. Nothing is created from nothing here — matter is finite." | `WorldPoint(colony)` | `World("plateau") { it.cellCount in 4..60 && it.tick - START > 5000 }` | the carrying-capacity beat |
| — | *complete:* "Light is free and endless; matter is scarce and recycled. Every creature you build lives inside that budget." | | | unlock Ch 3 |

Design notes: Ch 2 is *observational* — the player mostly watches and toggles. That's intentional
pacing: after the interaction drills of Ch 1, Ch 2 lets the world perform for them and seeds the two
resources they'll spend the rest of the campaign managing. Step 7's plateau gate has a time floor so
the player actually witnesses the slowdown rather than blowing past it. If the colony dies out early
(possible under harsh light), the director detects `cellCount == 0` and offers a "reset chapter"
prompt rather than soft-locking — a general safety net (§7).

### Bridge into Act II

Ch 2 → Ch 3 is the hinge from *watching* to *authoring*. Ch 3 ("Anatomy of a Gene") re-uses the exact
world the player just watched, but now un-masks the gene editor and walks the three parts of one gene
on the very autotroph they've been observing — so their first look at code is code they already
understand the *behaviour* of. That ordering (behaviour first, then its program) is the core teaching
move of the whole campaign.

---

## 6. Minutia — how the later chapters teach (ORIGINAL sketch — STALE, superseded by §2.1)

> ⚠️ **STALE — do not build from this.** This was the original Ch3–11 design sketch, written before the arc
> was built. **The as-built arc diverged from it** (the campaign became a single author-able organism grown
> into a swimmer, not the food-web/two-creature spine below), and the **chapter numbers here do NOT match the
> shipped chapters**. The canonical spine is **§2.1 (AS BUILT)**; the next-chapter contract (Ch10 Reproduction)
> is in the fresh-session handoff at the top. Kept only for historical design intent (some ideas — Food Web,
> Shape, capstone — may still be mined for Act IV / later chapters).

Act I is specified to the step; Acts II–IV are specified to the *chapter contract* (problem, the one
gene/idea, the objective predicate, the "watch it happen" payoff). Each will be expanded to Act I's
resolution during implementation.

- **Ch 3 Anatomy.** World: the running autotroph. Un-mask the gene editor. Steps walk source→condition
  →action on gene 1 (`Light : rg < 3000 : FormBond r g`), each field spotlighted in the editor.
  Objective: open the editor and view each of the 3 genes (`Did(OpenedGeneEditor)` + a per-gene "seen"
  tally). No edits yet — reading before writing.
- **Ch 4 Grow.** World seeds a **crippled** genome (production only: `FormBond` but no `Convert`) as
  the founder. Problem: "it makes `rg` but never grows." Player adds `Convert rg` via the editor.
  Objective: `focusedCell.biomass` climbs past a threshold *and* the genome contains a Convert gene.
  Payoff: the cell visibly swells. Teaches the cytoplasm→biomass lock + threshold gating.
- **Ch 5 Divide.** From Ch 4's now-growing cell, add `Divide`. Coach the `Break`-powered bulk cost and
  the halving brake; offer `sever` as an optional toggle ("bud off a colonist"). Objective:
  `cellCount >= 2`. Payoff: the first division.
- **Ch 6 Food Web.** Scenario seeds an autotroph colony + one heterotroph (`CellType.Muscle`) a short
  distance away. Problem: "this one can't photosynthesise — keep it alive." Teaches passive diffusion
  (move it *next to* the autotroph and it feeds) and, as depth, `Import` to hoard. Objective: the
  heterotroph survives *N* ticks / grows. `query` needs per-type survival — available from
  `countByType` deltas.
- **Ch 7 Hold & Move.** Three short beats on one welded colony: `Repair` (stop the fray — objective:
  connection damage stops rising, or simply "add a Repair gene and it stops shedding cells"),
  `Contract` (add it and watch a cell flex), a *taste* of `Lyse` (a predator nibbles a neighbour).
  Kept light — these are actuators the player will combine later.
- **Ch 8 The Clock.** Two-part. (a) Dilution timer: seed a cell with a determinant bolus, add a gene
  gated `Conc(m) < k` that changes colour; watch the fate trip after a fixed growth interval. (b) Load
  a ring-oscillator genome (the user's own `simple-clock`), inspect the phase species cycling in the
  info panel, tune the `@gear` on the `Contract` and watch the pulse rate change. Objective (a): a cell
  changes biomass profile on schedule; (b): observe/produce a pulsing colony. This is where "emergence
  from feedback" lands.
- **Ch 9 Knowing Where You Are.** The differentiation keystone. Provide a scaffolded genome with the
  source/sink loop pre-wired but the **readout bands blank**; the player fills in two `Conc(M)` bands to
  drive two different `Convert`s. Objective: `distinctBiomassProfiles >= 2` sustained across the colony
  (two stable tissues = two colours). Payoff: the colony visibly stripes. Coach sensing≠permeability
  ("reading the morphogen doesn't drain it — that's why the pattern holds").
- **Ch 10 Shape.** From Ch 9's differentiated blob, introduce `Divide ... across/along <axis>`.
  Objective: grow a non-round body (a shape metric — e.g. bounding-box aspect ratio from cell
  positions, or simply "a thread of length ≥ L"). Payoff: the colony elongates into a thread or widens
  into a sheet.
- **Ch 11 Hopeful Monster (capstone).** Minimal coaching. A blank-ish world and a checklist of
  sub-goals drawn from Ch 8–10 (a gradient, two tissues, cohesion via Repair, a shape). When the body
  holds, the final beat: "Now turn on Mutation and walk away." Spotlight the `Mut` button; the chapter
  "completes" but the world keeps running as the player's first sandbox. Graduation.

**Act IV / Sandbox.** Not a chapter — completing Ch 11 unlocks the normal New/Custom/Load flow framed
as "free play", plus an optional **Challenges** list (self-contained objectives with no coaching:
"colony of 100", "a swimmer that crosses the world", "three tissues", "survive 50k ticks on Long
Nights"). Challenges reuse the exact `Gate.World` predicate machinery with no step scripting — cheap to
author, high replay value.

---

## 7. Cross-cutting concerns

- **Soft-lock safety.** Every `World`/`Did` gate is escapable via `Skip`. Additionally the director
  watches for dead-end states (colony extinct, cell the objective referenced destroyed) and surfaces a
  "Restart chapter" prompt. A chapter can never trap the player.
- **Determinism / saves.** Campaign worlds are ordinary `SimState`s — they save/load through the
  existing codec. Entering a chapter mid-progress can re-seed from its scenario (chapters are short) or,
  later, snapshot progress. v1: re-seed on entry (simplest, and chapters are designed to reach their
  objective in a few minutes).
- **Reduced-noise physics for Act III.** Ch 8–10 want a calmer substrate (the `MORPHOGENESIS.md`
  caveat: physics is noisy for gradients). Chapters can carry scenario overrides (slower day cycle,
  fewer founders); if needed we expose one or two calm-physics knobs on the scenario, but v1 leans on
  scenario tuning already available.
- **Accessibility of depth.** The `detail`/`More` cards are where the `TUTORIAL.md` prose lives —
  literally reuse its section text so the two artifacts stay in sync (single source of truth: the
  campaign links to tutorial sections by anchor for players who want the full write-up).
- **Localisation-readiness.** All coach copy is data on the `Step`, not inline in code, so a future
  pass could externalise strings. Not a v1 concern, but the DSL keeps it free.

---

## 8. Build order (when we implement)

Staged so there's a runnable, playtestable thing after every phase:

1. **Phase 0 — skeleton.** `Anchor.BottomCenter`; `CampaignQuery` + `controller.campaignQuery()`;
   `CampaignDirector` with a hard-coded 2-step chapter; coach panel rendering; wire into the frame loop
   behind a debug key. *Exit criteria:* a coach panel appears over a live world and a `Next` gate works.
2. **Phase 1 — Act I end-to-end.** The DSL; `Spotlight` (Ui-region + dim + arrow); `PlayerAction`
   detection; control masking; Ch 1 + Ch 2 authored and tuned. *Exit criteria:* a new player can go
   from cold boot through Ch 2 and understand light vs matter. **This is the first shippable slice.**
3. **Phase 2 — menu + persistence.** `Page.Campaign` chapter-select; `CampaignProgress` file;
   unlock/replay; "Skip to sandbox". *Exit criteria:* campaign is reachable from the title screen and
   remembers progress.
4. **Phase 3 — Act II (the gene).** Ch 3–7, reusing the `GeneEditor`. The crippled-genome seed pattern
   (Ch 4) and per-type objectives (Ch 6) are the new bits. *Exit criteria:* a player learns every action
   by using it.
5. **Phase 4 — Act III (composition).** Ch 8–10. The scaffolded-genome pattern (fill-in-the-blank
   genes) and shape/tissue metrics. Heaviest tuning load — these are where the sim fights back.
6. **Phase 5 — capstone + challenges.** Ch 11; the Challenges list; graduation hand-off; polish pass on
   copy and pacing across the whole arc.

Phases 0–2 deliver a genuinely useful onboarding (the impenetrability problem is *mostly* an Act I
problem — most people bounce in the first minute). Phases 3–5 deepen it toward mastery.

---

## 9. Open questions for Stu

1. **Editor-first vs pre-baked genomes in Act II.** Do you want players *typing/clicking* genes into
   the editor from Ch 4 (higher friction, deeper learning), or mostly *toggling pre-made genes on/off*
   (lower friction, faster)? The plan assumes editor-first with heavy scaffolding; a "gene toggle"
   simplification is easy to fold in if you'd rather. **See §10 — gene groups reshape this answer:
   the deepest friction (editing individual gene fields) is deferred to last, and Act II teaches
   through named *functional groups* first.**
2. **How much hand-holding in Act III?** Fill-in-the-blank scaffolds (plan's assumption) vs "here's a
   working example, now tinker" vs "build it from scratch". Steeper = more satisfying but more
   drop-off. Could vary per chapter.
3. **Challenges as the retention layer?** Worth investing in the post-campaign Challenges list, or keep
   v1 focused on the linear campaign and let the sandbox be the endpoint?
4. **Voice.** The `complete(...)` lines lean poetic ("matter is scarce and recycled"). Keep that
   register, or drier/more technical?

---

## 10. Gene groups — functional grouping in the editor

> **AS BUILT (2026-07-12) — read this before §10.1–10.8 below, which record the ORIGINAL design and are
> partly superseded.** Grouping shipped as a **persistent per-gene tag**, not the match/`GenomeDoc`/`GeneRef`/
> "painted-from provenance" scheme §10.3–10.4 proposed:
> - **`Gene.group: String = ""`** is a real (inert) field on the sim gene. Membership is this tag alone — **no
>   matching of any kind**. The tag survives editing (`copy` keeps it), division + mutation inheritance, and
>   **save/load**: `GeneCodec` round-trips it as an optional **4th `:`-part** (`… : Convert rg : Grow`),
>   omitted when empty so untagged genomes are byte-identical (golden + old `.gene`/saves unaffected). This
>   replaced §10.8's "UI-only, not saved" decision — Stu asked for the proper persistent tag (it's needed
>   later anyway).
> - **`GeneGroup(name, color, insert)`** (in `ui/GeneGrouping.kt`) is display style + an optional pre-tagged
>   insert-template; **`GenomeGrouping.sections(genome)`** buckets live genes by `gene.group` (registry order
>   → unregistered tags → untagged "Other"). `GeneEditor.render(grouping, insertableGroups)` shows collapsed
>   group headers (`+ Grow (2)`, tap to expand) and a `+ ADD <group>` affordance for absent insertable groups
>   (inserts `group.insert` via `CytoController.addHeldGenes`). Genes can be **re-tagged to move between
>   groups** (data model ready; §10.5 Genome Workshop / move-UI / group-library still unbuilt).
> - The campaign seeds/inserts **pre-tagged** genes (`CampaignContent`: `GROUP_GROW`/`GROUP_REPRODUCE`/
>   `GROUP_HOLD`), preserving gene order. `Chapter.grouping` + `Chapter.insertableGroups` drive it.
>
> The idea, motivation, hard order-preservation constraint (§10.2), and Act-II teaching reshape (§10.6) all
> still hold — only the *mechanism* (tag vs match) and *persistence* (saved vs UI-only) changed.

*Added after playtest feedback: the flat gene list is the single hardest wall for a new player. A
19-gene genome reads as 19 disconnected conditions and actions. But a hand-crafted genome isn't
flat — it's a set of **subsystems** that each do one job. Exposing that structure is the biggest
single comprehension win available, and it unlocks a family of authoring features (copy/insert genes
and whole subsystems between creatures).*

### 10.1 The idea

Let genes be tagged into **named functional groups** — purely a UI/authoring layer, with **no effect
on how genes run**. Stu's `Swimmer` (19 genes) partitions cleanly into six purposes:

> **Energy Storage · Metabolic Clock · Locomotion · Differentiation · Growth · Reproduction**

Grouped, the impenetrable list becomes six individually-understandable subsystems. This is *exactly*
the campaign's progressive-disclosure philosophy applied to the genome itself, and it gives a natural
three-level depth ladder:

```
  Swimmer                              ← level 1: the creature
  ├─ ▸ Energy Storage      (2 genes)   ← level 2: six purposes (collapsed) — the campaign lives here
  ├─ ▸ Metabolic Clock     (5 genes)
  ├─ ▾ Locomotion          (3 genes)
  │    ├─ Break gg : gr<gg & gr<1000 & gg>6000 : Contract @15   ← level 3: individual genes
  │    └─ …                                                      ← level 3b: expand a gene → its fields
  ├─ ▸ Differentiation     (4 genes)
  ├─ ▸ Growth              (2 genes)
  └─ ▸ Reproduction        (3 genes)
```

A player expands only as deep as they want. The campaign introduces the *six purposes* long before it
ever asks anyone to touch a single gene field — which is the correct deferral (per Stu: individual
gene/group design is the deepest layer and should come **last**).

### 10.2 The hard constraint: grouping must never reorder

> **Update 2026-07-14: round-robin removed.** `ROUND_ROBIN_GENES` and the `_cachedActive` cache are
> gone — every non-division gene now re-evaluates its condition **every tick**. So the old `rrIdx =
> tick % genomeSize` index-sensitivity no longer exists, and gene order is now *largely* behaviourally
> neutral (condition timing is index-independent; apply is order-independent by design). The
> order-preserving rule below is kept as a **safe convention**, not a hard trajectory constraint —
> future order-coupled logic (mutation PRNG, etc.) could reintroduce sensitivity, and a stable stored
> order keeps the golden gate and save diffs clean.

Consequence: **grouping is still not modelled as "reorder genes so a group is contiguous."** Even
though reordering no longer silently changes how Stu's Swimmer swims, contiguity-by-reorder would
churn stored order for no benefit. Therefore:

- **Group membership is an order-preserving label.** The genome's stored order (= sim order) is never
  touched by grouping. Groups may be **non-contiguous** in storage; the editor **collates by group for
  display** (display order ≠ storage order).
- **Assigning, renaming, recolouring, collapsing, or reordering *groups* is a guaranteed behavioural
  no-op.** Only adding/deleting/editing actual genes changes behaviour (as it does today).

### 10.3 Data model — zero sim changes

Groups live in the **authoring/library layer**, not in `Gene`, not in `CytoCellComponent`, not in the
sim save codec. Nothing the reducer or golden tests touch is modified.

```kotlin
/** A named, coloured functional subsystem: an order-independent set of member genes. */
class GeneGroup(val name: String, val color: Long, val members: Set<GeneRef>)

/** An authored genome = the flat list the sim runs, PLUS its grouping overlay. This is what a
 *  .gene library file and the editor draft hold; a live cell still just carries List<Gene>. */
class GenomeDoc(val genes: List<Gene>, val groups: List<GeneGroup>) {
    // genes not in any group render in an implicit "Ungrouped" bucket.
}
```

`GeneRef` identifies a gene *within a doc* stably across edits — a small positional-index-plus-fingerprint
handle (index for O(1) access, a hash of the gene's fields to survive re-parsing / detect the same gene
after neighbours change). Membership is a `Set`, so it's inherently order-free.

**Why library-level, not per-cell.** Grouping is a property of *authored* genomes — the human
categories a designer imposed. Evolved cells in the wild have no meaningful human grouping (evolution
doesn't respect our purposes), so they simply show ungrouped, which is honest. The editor shows groups
whenever it's editing a genome of known provenance:

- Editing a **library genome** (the Genome Workshop, §10.5) — groups always present.
- Inspecting a **live cell painted from a library genome** — the host keeps a best-effort,
  non-persisted `Map<EntityId, GenomeDocId>` "painted-from" association (UI-only, never enters the sim
  snapshot or determinism), so the editor can re-attach the doc's groups. A cell that has since mutated
  past a structural match falls back to ungrouped ("custom").

### 10.4 Persistence — a backward-compatible codec extension

Extend the `.gene` text format with **group section headers**, parsed order-preservingly:

```
# genome: Swimmer
# color: a09effff
## group: Energy Storage #efd040
Break rg : Biomass < 4000 & b < r & b < g & gr < 31 : Convert rg @15
Break rb : Biomass < 4000 & g < r & g < b & gr > 30 : Convert rb @15
## group: Metabolic Clock #40efd0
Light : rg < 10000 & rb < 400 & gb < 400 : FormBond r g
…
## group: Locomotion #dd3333
Break rb : gb < 2000 & gb > rb & gb > 1000 & gr > 30 : Contract @15
```

- A `## group: <name> [#rrggbb]` line opens a group; following genes belong to it until the next
  header. Genes before any header are ungrouped.
- **Storage order is preserved exactly** — the file lists genes in sim order; a header is just a label
  boundary. When a group is *non-contiguous* in sim order (rare in hand-authored genomes, common after
  edits), serialization falls back to an inline per-line tag (`… : Contract @15   ## Locomotion`) so
  order is never sacrificed to keep a group's lines together.
- The existing parser already strips `#` comments, so **every current `.gene` file loads unchanged**
  (all genes ungrouped). `GeneCodec.parse` keeps returning `List<Gene>`; a new
  `GeneCodec.parseDoc(text): GenomeDoc` reads the group layer alongside. `GeneCodecTest` gains
  round-trip cases; the flat-parse path is untouched, so the golden gate is unaffected.

### 10.5 The editor, reworked around groups

Two surfaces:

1. **Live-cell inspector (existing info panel).** Unchanged for debugging evolved cells (flat list),
   but when a group overlay is available it renders the **collapsed group view** by default — six rows,
   not nineteen. Tap a group to expand its genes; tap a gene to expand its fields (today's editor).
   This is the depth ladder of §10.1 with no new interaction model — just a collapse level on top of
   what `GeneEditor` already does.
2. **Genome Workshop (new, library-level).** Edit a `.gene` library entry *as a document*: create/
   rename/recolour groups, drag genes between groups (a label change — no reorder), add/delete genes,
   and save back to the library. This is where deliberate authoring happens, kept separate from the
   in-world inspector.

New editor operations, all label-level and behaviour-safe except where they add/remove genes:

- **Collapse/expand** a group (view only).
- **Rename / recolour** a group.
- **Move a gene** to another group (label change only).
- **Copy** a gene or a whole group to a clipboard (`List<Gene>` + optional group label).
- **Insert** clipboard genes into another genome (appends genes in sim order + carries the group
  label). This is the "lift the Locomotion subsystem out of Swimmer and drop it into a new creature"
  feature — the payoff Stu called out. Inserting genes *does* change behaviour (you added genes), which
  is correct and expected; the group label just travels with them.
- **Enable/disable** a whole group at once (bulk — implemented as add/remove of its genes, so it's a
  real behavioural change; useful for A/B-ing a subsystem: "turn the Metabolic Clock off and watch the
  swimmer stop pulsing").

### 10.6 How this reshapes Act II (and answers open question #1)

Grouping lets the campaign teach at the **subsystem** level for most of Act II and defer raw
gene-field editing to the very end:

- **Ch 3 (Anatomy)** now introduces the genome as *a set of named purposes* first (expand the founder's
  groups), and only then drills one group down to a single gene. Purpose before syntax.
- **Ch 4–7** teach one action apiece by **enabling/disabling or inserting a group**, not by hand-writing
  gene fields: "your cell can't grow — insert the *Growth* group" (drag the pre-made group in) rather
  than "author a `Convert rg` gene from blank dropdowns." Far lower friction; the player manipulates
  *meaningful units*.
- **Individual gene-field editing becomes its own late chapter** (or an Act III/IV capstone skill),
  reached only once the player fluently composes at the group level — matching Stu's "leave gene design
  till much later, or last."

This also seeds a **group library** (a palette of reusable subsystems — "Energy Storage", a
"Metabolic Clock", etc.) that a player assembles creatures from, which is a gentler on-ramp than the
blank-genome editor and a genuinely useful sandbox tool in its own right.

### 10.7 Build-order placement

Gene groups slot into the plan as follows:

- **Phase 3′ (folds into Phase 3, Act II):** `GenomeDoc` + `GeneGroup`, `parseDoc`/`serializeDoc`,
  the collapsed group view in the inspector, and enable/disable/insert-group. This is the machinery
  Act II's low-friction teaching depends on, so it lands *with* Act II, not after.
- **Phase 3.5 (new):** the Genome Workshop (full group authoring + clipboard) and the group library.
  Useful for sandbox authoring; not strictly required to ship Act II, so it can trail slightly.
- Raw per-field gene editing (today's `GeneEditor`) is already built — it simply becomes the deepest,
  last-taught rung rather than the entry point.

### 10.8 Settled decisions (2026-07-11, Stu)

- **Group provenance = UI-only association.** The non-persisted "painted-from" map (§10.3) is the v1
  approach; grouping is **not** stored per cell and does **not** survive world save/load. The one firm
  requirement: wherever a genome's groups are known, the editor must render them as **collapsed
  segments that are expandable/openable** (the depth ladder of §10.1) — collapsed-by-default is the
  default view, not an afterthought. A cell of unknown provenance shows the flat list.
- **Grouping is always manual.** No auto-suggested/heuristic bucketing. Evolved genomes are chaotic and
  their genes don't partition into curated-style purposes, so auto-grouping would produce misleading
  categories — grouping is a deliberate act a *human author* performs on a *curated* genome, and the UI
  treats it that way.

---

## 11. Note for when multicellular reproduction returns — DIVIDE energy contention

> **Written 2026-07-23, alongside `0cf82847`.** Nothing to do now; read this before authoring a chapter
> in which a cell carries **more than one DIVIDE gene**.

Division is the one all-or-nothing action: it needs `biomass/4` energy units in a **single tick**, and
energy can't be banked (`CytoBiologyCore`, `ActionType.Divide` — `k = if (k >= cost) cost else 0`). The
division phase then splits the cell's means across **every Divide gene whose gate is open that tick**
(`quantaShare = work.quanta / dn`, each reactant's share `count / n`).

So two DIVIDE genes that would each fire alone can both be unfunded together. That has always been true;
what changed is that the panel now **says so** — the fuel token on such a gene reads orange rather than
letting the gene glow active while never once dividing.

The campaign has never had to think about this, because every chapter to date runs exactly one DIVIDE
gene at a time. The moment a chapter hands the player two, two things follow:

1. **Prefer mutually-exclusive gates.** If two divides are meant to be alternatives (a symmetric growth
   split vs a sever-off-a-swimmer split, say), gate them so only one is ever open on a given tick. Then
   `dn == 1` and there is no contention to explain.
2. **Otherwise the copy owes the player an explanation.** A player who authors two sensible divides and
   watches both go orange has been told the truth by the UI and nothing by the coach. Either a step
   teaches "divisions compete for one tick's energy — stagger them", or the chapter's genome avoids the
   situation. Silence is the one option that isn't available.

Also worth re-checking at that point: the cost scales with **biomass** while the fuel pool doesn't, so
big cells need a correspondingly big hoarded reserve to divide at all. A chapter that grows the organism
before asking it to reproduce may need to teach hoarding first (this is what `Ch10 Spread` does via the
swimmer's reserve; a from-scratch multicellular chapter would need its own version).

Relevant code: `CytoController.energyUnits` / `describeGeneSpans` (the affordance), pinned by
`GeneEnergyUnitsTest`.

---

## 12. The branch after Divide — as built 2026-07-23, and what is still open

The campaign now branches. `ch01-divide` ends **poised on the split rather than past it**, and which chapter
follows is read off the world the player leaves behind: `Chapter.next` (a `(CampaignQuery) -> String?`),
declared statically as `Chapter.branchesTo` so the selector knows what a chapter can unlock.

**The reading is `FocusedCell.divideFuelConflicts`** — does the fuel reaction the player chose to power
division consume the very monomer their CONVERT gene grows on?

| choice | lineage | chapter |
|---|---|---|
| fuel pair excludes the growth monomer | stable; colonises into the hundreds until the world's loose chemistry thins | `ch02-photosynthesis` — recycle the waste |
| fuel pair includes it | growth and division bid for one atom; dies back a few divisions in | `ch02-conversion` — eat the waste |

The player is never asked. They pick two chemicals and the consequence picks the chapter, which is the
"secret level reached by going an unconventional way" shape — nothing announces the branch, and a player
who never chooses the conflicting pair never learns the other chapter exists.

### Still open

0. **Routing, unlock and the extinction fallback are DONE** (`cd86115a`, `3fdc120a`). The branch reads
   `Lineage.divideFuelConflicts`, so a player who sits and watches their colony die out still lands in the
   chapter their genome is about (§13) — the earlier "fall to the stable path when nothing is selected"
   default is gone.
1. **Both chapters are opening beats only.** Each runs to the point where its fix would be authored and
   stops with a "that is where this goes next" line. The fixes need: a `FocusedCell` reading for "has a
   gene that consumes the waste molecule" (both paths gate on a version of it), and Stu's voice on the copy.
2. ~~**⚠️ The recorded premise for the conversion path does not survive contact with the code.**~~
   **RESOLVED 2026-07-30 — the premise was right and this note was wrong.** It checked `canDiffuseIn`, which
   governs **cell-to-cell weld diffusion**. The environment junction is `passiveEnvExchange`, and there a
   species is transferable only if it is a **monomer** or carries an import/export bias:

   ```kotlin
   if (ib != 0 || eb != 0 || SpeciesRegistry.atomCount(sp) == 1) transferN++
   ```

   A two-atom waste molecule is therefore **genuinely unreachable without an Import gene** — not slow, zero.
   `degrade` sheds it whole and unsplit into the cell's own footprint, so a colony steadily converts the
   world's free monomers into something none of its cells can take back in. Measured (default world, one
   founder, the three-gene end state): free `b`/`g` fall 32.8M → 6.0M while the duomer climbs 0 → 26.8M, and
   `r` — which that lineage never touches — sits at 32.8M throughout. The world is still full of food, just
   not food those cells can eat. This is also the mechanism behind the ⚠️ long tails recorded on both
   `ch04-leftovers` and `ch04-lockedup`: "faster than the membrane brings it back" is really "the membrane
   brings back none of it". It is now the subject of `ch05-reclaim`.
3. **Unlock is predecessor-based (done), but only bites once the scratch chapters graduate.** `predecessorsOf` defaults to the authored
   `CHAPTERS`; the branch lives in `SCRATCH_CHAPTERS`, and a scratch id has no predecessors there, so it
   reads as always-unlocked — deliberate while these are WIP and iterated from the menu. The *routing* is
   live regardless; it is only the selector's padlock that waits.
4. ~~**Merge point undecided.**~~ **DECIDED 2026-07-30: they rejoin.** Both tails arrive at the same
   three-gene shape (grow / divide / recycle on the duomer), differing only in which chemical — so both now
   declare `branchesTo = listOf(RECLAIM)` and lead to `ch05-reclaim`, the chapter about what that shape does
   to the world around it. §11's contention note still applies to whichever chapter first hands the player
   two DIVIDE genes; `ch05` does not.

---

## 13. Extinction is a state, not a dead end — as built 2026-07-23

Every campaign goal used to be keyed on the **selected cell**. When that cell died — and two chapters are
now *about* a lineage failing — `focused` went null, every genome-shaped gate became permanently
unsatisfiable, and the only way on was a Reset the coach never mentioned.

### The two readings

| | what it answers | who asks |
|---|---|---|
| `CampaignQuery.focused` | is a cell selected **right now** (type/biomass/cytoplasm) | the 6 `Select a cell` gates |
| `CampaignQuery.lineage` | what the player has **built** — a pure function of a gene list | the other 18 gates, the branch, `{chem}`/`{bond}` |

`Lineage` is sourced, in order, from: the selected cell's genome → **the genome the player last authored**
→ the largest survivor's. A goal about the player's work therefore no longer stops being true because they
clicked away, or because the cell they wrote it on died.

### The remembered genome

`CytoController.lastAuthoredGenome` is captured **each time an edit lands**, not when a cell is selected —
so it means their intent rather than wherever the mouse went. It outlives the cell, the chapter and
`newGame`.

> **⚠️ Do not make it `@Volatile`.** A volatile store in the edit-drain path costs a barrier on the sim
> thread's tick loop and regresses `CytoEditLatencyTest` (2 failures in 3, in isolation). It is a plain
> field republished through the one volatile store per publish this class already runs on. See
> `PLAN_verification_infrastructure.md` §4.

### The offer

`CampaignDirector.extinctionOffer` (nothing alive **and** a genome in hand) replaces the step text with the
two ways forward, and ORs `Control.Spawn` into whatever the step allowed — a step that masked spawning off
did so to keep the player on task, and the task is gone. Both hosts and the harness brush that tap with the
remembered genome. `snapshot()` reports the override too, so a headless observer sees the coach the player
sees.

With no genome there is no offer: nothing to put back, so the coach must not promise one.

### Consequences for authoring

- **Write gates against `lineage`, not `focused`.** Reach for `focused` only when the step is genuinely
  teaching selection.
- New gate readings belong in `lineageOf(genome)` as pure functions — never derived at the call site.
- A chapter may now assume the player can always get back to a living cell carrying their own genome. A
  chapter whose lineage is *expected* to die (the conversion branch) can lean on that.

---

## 14. `ch05-reclaim` — the merge, as built 2026-07-30

Both branches now lead here. The lineage they arrive with works and is still terminal, for the reason §12.2
records: it sheds a **two-atom** molecule that cannot cross a membrane without an Import bias, so it converts
its world into matter it cannot eat. The chapter is the first one about the **world** rather than the cell.

Measured in the chapter's own pocket universe (one founder, the genome authored onto the placed cell):

|          | t2k | t4k | t6k | t9k | t13k | t18k | t24k |
|----------|-----|-----|-----|-----|------|------|------|
| 3 genes  | 155 | 474 | 466 |  25 |  **0** | 0 | 0 |
| + import | 185 | 567 | 595 | 613 | 646 | 695 | **710** |

One gene turns a boom-and-crash into a carrying capacity.

### Two things this chapter needed

- **It starts a fresh world** (`startsFreshWorld = true`), against the continuous-world rule, because the
  curve *is* the lesson and both predecessors hand it a world that has already been spent. The founder
  carries the player's own genome via `spawnCopiesHeldCell` → `lastAuthoredGenome` (§13), so the merge works
  whichever branch they walked.
- **The die-off beat gates on an empty world**, which is also what keeps `extinctionOffer` off it
  (`goalIsExtinction`): the step asked for this death, so the recovery prompt must not replace its copy.

### The reveal needed new UI

The combined matter ground looks much the same whether a world is thriving or spent — the accumulation is
already saturating at the population peak and simply stays there, so "watch the waste build up" is not a
legible beat. The `LAYERS` sheet therefore grew a **per-species matter layer**: `ALL SPECIES` (default) plus
every species actually present, richest first. Layers share the combined view's **absolute** density scale,
so a dark layer means "there is none of this left" — toggling the duomer against its monomer is the whole
explanation, with no copy required.

### Still open

1. **The chapter after it is unwritten.** `ch05` declares `branchesTo = emptyList()`.
2. **⚠️ Efficiency does not yet have a chapter that earns it.** The intended `ch06` was "paying for import
   with chemistry is ruinous at gear 0, so gear up". **That did not survive measurement.** Colony at t24k by
   import gear: `0→619, 4→624, 8→666, 12→660, 15→673, 16→656` — flat within noise; and a single cell switched
   to bond-import inside a settled colony holds its cap at every gear. In a **spent** world bond-import never
   recovers *at any gear* (stuck at 1 cell at both 0 and 15), which confirms light is the only bootstrap.

   The likely reason, and a better chapter: **`FormBond` as an energy source forms the bond**
   (`work.cytoplasm.inc(formProductId, bondsFormed)`), so `Bond b g : Import bg` *manufactures* the very
   molecule it imports. The import is the lesser of its two sources, which is why scaling it does almost
   nothing — and in a spent world there are no loose atoms to bond, so the gene cannot fire at all. A chapter
   about **circular fuel** is supported by the measurements; one about efficiency is not, and would need a
   scenario where the gear genuinely bites (probably an import whose energy source does *not* also produce
   its operand).

   Note also that gear trades throughput for economy **geometrically**: `energyCap = EFFICIENCY_REF ushr g`,
   so gear 16 permits 1 energy unit/tick (17 ops) against gear 15's 2 (32 ops). Gear 16 is the most throttled
   setting in the game, which can read as a bug without being one.

3. **The light-contention idea for ch06 also measures flat — and the mechanism is not what it looks like.**
   The idea was: two light genes split the day, so moving IMPORT onto chemistry gives the recycler the whole
   day back. **It cannot.** `runGenes` computes `quantaShare = work.quanta / n` where **`n` is every active
   gene, not every active *light* gene** — so an active bond-powered gene still shrinks the light share, and
   (since it never draws that share) simply wastes it. Moving IMPORT from light to bond leaves `n` unchanged
   and therefore leaves the recycler's light unchanged; all it adds is monomer consumption, which is why it
   measured *worse* (619-673 vs 710).

   What *does* free light is the gene being **inactive** — `isActive` is re-evaluated every tick and
   "inactive genes don't reserve a share". So the lever is the gate, not the fuel. Tested by narrowing the
   import ceiling toward the recycler's `bg > 100` floor (overlap = the band where both are active):

   | import ceiling | 120 | 200 | 400 |
   |---|---|---|---|
   | cells @ t24k | 693 | 710 | 706 |

   Flat. So the 1/N dilution, while provably real arithmetic, is **not the binding constraint** on colony
   outcome in this scenario — something else is. Three candidate ch06 premises (gear, fuel source, gate
   overlap) have now all measured flat on cell count, which suggests the metric is saturated rather than that
   all three mechanisms are inert. **Before authoring any of them, isolate the effect on a single cell**
   (no population dynamics) - e.g. how fast one cell drains a {bond} pile with and without a second active
   light gene. A worthwhile beat regardless: an always-active gene that does nothing still takes its 1/N cut
   of the daylight.
