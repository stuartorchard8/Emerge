# Cyto — UI Redesign: Progressive Disclosure

Status: **proposal, nothing built.** Companion to `MOBILE_READINESS.md` (the audit that motivates it).
Goal: keep **every** feature of the current UI, on a phone, in a form that reads cleanly — by
disclosing depth on demand instead of showing everything at once.

---

## 1. The diagnosis behind the design

The current gene editor isn't dense by accident — it's dense because **it's a form, and a form shows
every field of every facet simultaneously**, including ones that don't apply. A 4-clause Divide gene
today renders ~24 rows: `SOURCE`, then per clause `LHS`/`L VAL`/`CMP`/`RHS`/`R VAL`, then `ACTION`,
`MORPHOGEN`, `KEEP`, `AXIS`, `ORIENT`, `SEVER`, `EFF`, `GROUP`, `DONE/CANCEL/DUP/DEL`. The player
must reconstruct the gene's *meaning* by reading a spreadsheet of its parts.

But a gene **is already a sentence**:

> **WHEN** `BIO > 2000` **DO** `DIVIDE (sever)` **POWERED BY** `Break rg`

That's the whole model. Every one of those ~24 fields is a detail *inside* one of those three
phrases. The redesign is: **show the sentence, tap into a phrase.**

This is also why the fix isn't "scale it up" (`MOBILE_READINESS.md` §2.3) — a form that's 2.3× wider
than the screen doesn't become usable at any font size. It has to stop being a form.

---

## 2. Principles

1. **A gene is a sentence, not a form.** The top-level view of anything is its *meaning*, not its fields.
2. **One screen, one question.** Each level answers a single question; the answer picks the next level.
3. **Show values, hide mechanism.** Collapsed = the current value as readable text. Tap = the control.
4. **Never render an inapplicable control.** (The editor already does this per action type — extend it everywhere.)
5. **Depth beats density.** An extra tap is cheap; a wall of 6dp rows is not.
6. **The world is the hero.** Chrome collapses to a single bar; today's 8 always-on buttons become 4.

---

## 3. The level model

| Level | What it is | Answers | Dismiss |
|---|---|---|---|
| **L0 World** | World + bottom bar | "what's happening?" | — |
| **L1 Cell peek** | Collapsed bottom sheet (~120dp) | "what is this cell?" | tap world / right-click |
| **L2 Cell detail** | Expanded sheet (~60%, scrolls) | "what's it made of & what does it do?" | drag down |
| **L3 Gene detail** | Full-screen modal | "what does this gene mean?" | back / DONE / CANCEL |
| **L4 Field picker** | Sheet over L3 | "what should this one value be?" | pick / back |

Five levels sounds deep; in practice **selecting a cell and reading its genome is L1→L2**, and
**editing is one more tap**. The depth only materialises for the player who's actually authoring.

### L0 — World

Bottom bar, four 48dp targets, replacing today's scattered `SLOW/PAUSE/FAST`, `Stem Cell`,
`Base Mode`, `LIGHT GRID`, `Mut`, `Color`, `Debug`:

```
┌──────────────────────────────────────────────┐
│                                              │
│                  (world)                     │
│                                              │
├──────────────────────────────────────────────┤
│   ⏸        🖌 Brush    ◱ Layers     ☰ Menu   │
└──────────────────────────────────────────────┘
```

- **⏸ / ▶** — tap toggles pause. Tap the TPS readout beside it → **Speed sheet** (SLOW/FAST ladder + TPS/FPS).
- **🖌 Brush** → **Brush sheet**: genome palette (or legacy cell types), touch mode.
- **◱ Layers** → **Layers sheet**: LIGHT GRID, MATTER, COLOR mode, MUT rate, DEBUG. *Five toggles → one button.*
- **☰ Menu** → full-screen menu (title / new / load / save / campaign / about).

### L1 — Cell peek

A cell tap raises a collapsed sheet. Vitals only, plus a one-line genome summary. **No editing affordances.**

```
┌──────────────────────────────────────────────┐
│ ═══                                          │
│ CELL 0 · Collector                     ▲     │
│ Size 0.42   Biomass 2944   Light 0 (0%)      │
│ 5 genes in 3 groups                          │
└──────────────────────────────────────────────┘
```

### L2 — Cell detail

Drag up (or tap ▲) to expand. Scrollable. Genome shown **by group, collapsed** — the genome reads
as a few named subsystems, which is exactly what `GenomeGrouping` already produces.

```
┌──────────────────────────────────────────────┐
│ ═══                    CELL 0 · Collector    │
│ Size 0.42    Biomass 2944    Light 0 (0%)    │
│                                              │
│ ▸ Chemistry (5 species)                      │
│                                              │
│ GENOME                                       │
│ ▸ ● Metabolism (3)                           │
│ ▾ ● Division (2)                             │
│     ┌────────────────────────────────────┐   │
│     │ WHEN BIO > 2000  → DIVIDE      ✓  │   │
│     └────────────────────────────────────┘   │
│     ┌────────────────────────────────────┐   │
│     │ WHEN rg < 3000   → CONVERT rg  ✓  │   │
│     └────────────────────────────────────┘   │
│ ▸ ● Structure (1)                            │
│   + ADD MOVEMENT                             │
│                                              │
│ ⋮  Export genome                             │
└──────────────────────────────────────────────┘
```

Each gene card is **two lines, not one 30-char string** — the current label
(`CONVERT rg IF BIO<3000 (LIGHT)`) is what forces the panel past the screen width. Active/blocking
state stays as it is today: colour + orange spans on the blocking parts, plus a ✓/✗ affordance.

### L3 — Gene detail (the centrepiece)

Full-screen. **The sentence, then its phrases.** This is where the ~24-row form collapses.

```
┌──────────────────────────────────────────────┐
│ ←   GENE 1 · Division                    ⋮   │
├──────────────────────────────────────────────┤
│                                              │
│  WHEN                                        │
│   ┌────────┐ ┌───┐ ┌────────┐                │
│   │  BIO   │ │ > │ │  2000  │                │  ← one clause = ONE row
│   └────────┘ └───┘ └────────┘                │
│   + AND clause                               │
│                                              │
│  DO                                          │
│   ┌──────────────────────────────────────┐   │
│   │ DIVIDE (divide)                     │   │
│   └──────────────────────────────────────┘   │
│   Morphogen    ┌────────┐                    │
│                │ (none) │                    │  ← only Divide shows these
│                └────────┘                    │
│   Sever        [ yes | no ]                  │
│                                              │
│  POWERED BY                                  │
│   ┌──────────────────────────────────────┐   │
│   │ Break rg                             │   │
│   └──────────────────────────────────────┘   │
│                                              │
│  Group         ┌────────────┐                │
│                │ Division   │                │
│                └────────────┘                │
├──────────────────────────────────────────────┤
│         CANCEL              DONE             │
└──────────────────────────────────────────────┘
```

Three structural wins over today:

1. **A clause is one row of three chips** — `[BIO] [>] [2000]` — instead of five stacked rows
   (`LHS` picker, `L VAL` stepper, `CMP`, `RHS`, `R VAL` stepper). **4 clauses: ~24 rows → 4 rows.**
2. **Conditional fields stay conditional** and now *read* as part of the sentence (`Morphogen`,
   `Keep`, `Axis`, `Orient`, `Sever`, `Eff`) rather than as a flat field list.
3. **`DUP` / `DELETE` / `Export` move into the `⋮` overflow** — off the primary bar, away from `DONE`.
   Today `DEL` sits **6px from `DONE`** (audit §3.4). `DELETE` gets a confirm.

### L4 — Field picker

One value, one sheet, big targets. Four kinds cover every field in the editor:

- **List** (`SOURCE` ×10, `ACTION` ×9, operand kind ×6, `GROUP`) — full-width 48dp rows, **with a
  one-line description**. There is room here to explain what `Lyse` or `Retain` *does* — impossible
  in today's dropdown, and a real onboarding win.
- **Number** (`L/R VAL`, `EFF`) — big −/+, a value field, and coarse presets. **Fixes the
  2000-taps-to-set-a-threshold bug** (audit §3.2).
- **Species builder** (`Chem`/`Conc`/operands/morphogen/axis) — the atom-by-atom `+r/+g/+b/<` builder
  at 48dp instead of 37×16px, showing the molecule as it's built.
- **Segmented** (`CMP` `> / <`, `KEEP`, `ORIENT`, `SEVER`, wildcard match) — inline, no sheet needed.

---

## 4. Feature inventory — nothing is dropped

| Current feature | Lands at |
|---|---|
| Cell id / type / size / biomass / light | L1 + L2 |
| Chemistry (ENV/CYT/BIO table) | L2, collapsed row |
| Gene list, flat | L2 (ungrouped genome = one implicit group) |
| Gene list, grouped + expand/collapse | L2 (the default view) |
| `+ ADD <group>` campaign inserts | L2, under the group list |
| Gene active/blocking colouring | L2 gene cards (unchanged semantics) |
| `SOURCE` (Light / Brk ×9) | L3 "POWERED BY" → L4 list |
| Condition, 1–4 AND clauses | L3 "WHEN", one row each |
| Operand kinds ×6 (Const/Chem/Conc/BIO/Touch/Nbrs) | L4 list |
| Const stepper / species builder | L4 number / species |
| `CMP` > < | L3 inline segmented |
| `ACTION` ×9 | L3 "DO" → L4 list **+ descriptions** |
| Per-action operands (Import/Export/Convert/Retain) | L3, conditional |
| `FormBond` left/right + wildcard toggles | L3, conditional + segmented |
| `Divide` morphogen / keep / axis / orient / sever | L3, conditional |
| `EFF` gear | L3 (non-Divide actions) |
| `GROUP` tag + new-group naming | L3 → L4 list (+ soft keyboard) |
| `DONE` / `CANCEL` | L3 bottom bar |
| `DUP` / `DEL` | L3 `⋮` overflow (+ confirm on delete) |
| `EXPORT GENOME` | L2 `⋮` overflow |
| Speed SLOW/PAUSE/FAST + TPS | L0 bar + Speed sheet |
| Brush: genome palette / cell type / touch mode | Brush sheet |
| LIGHT / MATTER / COLOR / MUT / DEBUG | Layers sheet |
| Menu / saves / genome save / campaign select | Full-screen menu |
| Campaign coach + spotlight | see §6 |

---

## 5. Does it fit? (dp budget)

Reference phone: **411×914dp**, minus status + nav ≈ **387×800dp usable**. Rows at **48dp**.

| Screen | Rows | Height | Verdict |
|---|---|---|---|
| L3 typical (1 clause, Convert) | 11 | ~520dp | fits, room to spare |
| L3 worst case (Divide, morphogen+axis, 4 clauses) | 16 | ~808dp | ~1 row over → scrolls |
| L2, 10-gene genome, all groups collapsed | ~12 | ~576dp | fits |
| Clause row: 3 chips across 387dp | — | ~120dp/chip | fits (a 4-char value needs ~48dp) |

Compare today's numbers: the same worst-case gene is **2.3× wider than the screen** at touch size.
The design's worst case overflows by *one row* and scrolls. That's the difference between "doesn't
fit" and "fits with a scroll".

### 5.1 Verified against a real render

`MobileMockRender.kt` (throwaway; `./gradlew :apps:cyto:desktop:mobileMock`) draws L3 at 1080×2400
using the **real** rect shader + bitmap font, so glyph widths and colours are exactly what the game
would draw. Output: `agent-out/mock-l3-{typical,worst}.png`. What it settled:

- **The sentence reads.** `WHEN BIO > 2000` / `DO DIVIDE (MITOSIS)` / `POWERED BY BREAK RG` scans
  top-to-bottom as prose. The framing survives contact with the real font.
- **The clause-as-one-row claim holds.** `[BIO] [>] [2000]` at 48dp across 387dp is comfortable;
  4 clauses cost 4 rows, against ~24 today.
- **The dp budget was right.** Typical leaves ~40% of the screen empty; worst case clips under the
  bottom bar exactly as predicted — **so the scroll container is mandatory, not a nice-to-have.**
- **Scissor clipping works** on this GL path (the mock clips content mid-row while chrome stays
  fixed). That de-risks the scroll primitive — the mechanism is proven before the primitive is built.
- **Corrected by the render:** segmented controls **cannot use a fixed width** — `DAUGHTER` overflows
  a 95dp segment. Segments must size to their widest label (monospace ⇒ exact).
- **Open:** the typical screen's empty lower half is an opportunity — a plain-English restatement of
  the gene, or live "firing / blocked, because X" state, would fill it usefully. Not yet designed.

---

## 6. Known tensions (flagging, not hiding)

1. **The campaign coach and the info sheet both want the bottom.** Proposal: the coach docks *above*
   the bottom bar; when a sheet opens it collapses to a one-line pill ("▸ step 3/5") that re-expands
   on tap. Needs a real playtest — the coach is the onboarding, so it must not be the thing that gets
   buried. See [[project_cyto_campaign]].
2. **Chapter copy is mouse-shaped.** "Drag empty space", "click the cell" — a touch pass is needed
   (`CampaignContent.kt`). Cheap, but easy to forget.
3. **Depth vs. the campaign's spotlight.** `Spotlight` currently points at a widget on one screen. If
   the target now lives at L4, the director needs to either drive navigation or target a *level*.
   This is the biggest unknown in the plan.
4. **Five levels is a real cost for authoring.** Editing 4 clauses = 4 round-trips to L4. Mitigation:
   segmented controls stay inline at L3, and L4 list-picks auto-dismiss. If it still bites, an
   "advanced" inline mode at L3 for wide screens is the escape hatch.
5. **The species builder is a text field in disguise.** Building `abcb` = 4 taps + no keyboard. Fine
   on mobile; slower than typing on desktop. Wide layouts should keep a typed entry path.

---

## 7. Toolkit work this requires

All of `MOBILE_READINESS.md` Phase 2 + 3, made concrete. New primitives in `Ui`:

- **Density / dp** — host-fed scale; desktop passes 1.0 (no visual change).
- **Text size decoupled from row height** (today `textH = rowHeight * 0.68`).
- **Scroll container** — scissor clip + touch scroll + fling + clipped hit-test. *Nothing like this exists.*
- **Sheet** — bottom-anchored, collapsed/expanded detents, drag.
- **Modal** — full-screen, title bar + back, stacked over a sheet.
- **Chip** — a labelled value that opens a picker.
- **Segmented control** — 2–3 exclusive options inline.
- **List row** — full-width, title + description + selected state.
- **Overflow menu** — `⋮`.
- **Confirm dialog** — for `DELETE`.

Plus wiring `Ui` + touch routing into `CytoAndroidView` (audit §4), which today builds none of it.

---

## 8. Desktop: one adaptive UI, or two front-ends?

**DECIDED (2026-07-17): new UI everywhere, with wide inline variants — the old dense form is retired.**
The sentence-model content is one shared builder (`geneBody`, `cellBody`); only the *container geometry*
switches on width. The `modal` and `sheet` primitives take explicit bounds, so:

| | Narrow (phone) | Wide (desktop) |
|---|---|---|
| L2 | `dockBottom` (bottom sheet) | `dockRight` (docked right panel) |
| L3 | full-screen `modal` | `modal` bounded to a column left of the L2 panel |
| L4 | bottom `sheet` | `sheet` bounded to a centred popover |

Built and rendering on both widths (harness: wide at 1400×900, narrow at 560×1000). **Follow-ups:** the
gene-row one-liner still overflows a narrow column (needs the two-line card); the campaign coach is
suppressed while a gene is open rather than docked (§6.1).

---

### Original recommendation (kept for context)

**Recommendation: one widget tree, two layouts, switched on width.** The level model is the same;
only its *presentation* changes:

| | Narrow (<600dp) | Wide (≥600dp) |
|---|---|---|
| L1/L2 | bottom sheet | docked right panel *(≈ today)* |
| L3 | full-screen modal | second column beside it *(≈ today)* |
| L4 | sheet | inline dropdown *(≈ today)* |

This means **desktop keeps its density and its side-by-side columns** — the wide layout is close to
what exists now — while the mobile layout falls out of the same tree. One set of state
(`GeneEditor` already separates draft/`openField` state from layout, so this is layout-only), one
set of features, no divergence.

The alternative — a separate mobile front-end — is faster to a first phone build but permanently
doubles the cost of every future UI feature.

---

## 8a. Desktop inline gene editor — the re-fork (DECIDED 2026-07-18)

**The §8 decision is reversed *for the gene editor only*.** Shipping the shared sentence-model on both
widths surfaced a real asymmetry: on desktop the L3 detail view is a second docked column, which forces
the campaign coach out of its bottom slot and up into a cramped top-left pill (§6.1) whenever a gene is
open — and, more importantly, it makes editing a gene a *drill-down* on a machine that has the pixels and
the pointer precision to edit in place. Mobile has the opposite constraints: limited width and fat-finger
targets push it toward **larger** elements and fewer simultaneous hit targets, so a full-screen modal with
one field at a time is right there and clunky inline would be wrong.

So the two hosts fork deliberately:

| | Narrow (phone) — unchanged | Wide (desktop) — new |
|---|---|---|
| Editing a gene | tap → full-screen L3 `modal`, one field, CANCEL/DONE | **inline on the card** in the genome panel; no second column, no modal |
| A gene "part" | chip → L4 sheet | **inline dropdown** anchored to the part |
| Edit contract | draft; commit on DONE, revert on CANCEL | **live** — every change mutates the gene immediately (no draft, no cancel) |
| Per-clause / per-gene actions | inside the modal + its `⋮` | **hover affordances** on the card |

`geneBody` stays the shared *read* model (the two-line card). The desktop **edit** path becomes its own
builder; narrow keeps the modal. We accept the re-fork: the hosts have fundamentally different constraints
and bespoke UI per standpoint is the point. (This does not re-fork L0/L1/L2 or the cell panel — only how a
gene is edited.)

### The interaction

On desktop the genome panel is the editor. There is no "open the gene" step. **The read-only sentence card
*is* the editor — its words and symbols become the controls in place** (Stu, 2026-07-18); we do not build a
separate editing form. A divide gene reads, and edits, as:

```
WHEN BIO>3500              WHEN [BIO]>[3500]          ([BIO] operand, [>] cmp, [3500] value)
 AND GREED>1000       →     AND [GREED]>[1000]
BREAK FUEL (R/G) TO   [BREAK FUEL] TO     (power source dropdown)
DIVIDE ALONG GREED GRADIENT [DIVIDE] [ALONG] [GREED] GRADIENT
 RETAINING GREED IN CELL 1   RETAINING [GREED] IN [CELL 1]
 SEVERING CELL 2 FREE        [AND STICK / SEVERING CELL 2 FREE]
```

Each bracketed token is a live control: `DIVIDE`→action dropdown, `ALONG`→orientation segmented,
`GREED`(axis)→morphogen dropdown, the retain `GREED`→keep-morphogen dropdown (and only once a keep
morphogen is set does the `CELL 1`/`CELL 2` side control appear), sever→a segmented `AND STICK` ↔
`SEVERING CELL 2 FREE`, efficiency→a `Ex` dropdown. Binary choices stay inline segmented; value lists use
the §8a step-2 `dropdown`.

- **Resolved — hidden-modifier tokens (the case Stu asked me to raise).** The current prose *omits* a
  modifier when it's at its default (a bare divide collapses to just `DIVIDE`), which leaves nothing to
  click to turn it on. **Fix: the prose generator always emits every modifier slot with natural default
  wording** — `DIVIDE AND STICK`, `ACROSS NO GRADIENT`, `RETAINING NOTHING`, `Ex` — so every editable slot
  always has a token. This makes the *read* card (mobile included) more complete, not just the desktop edit
  view; it's the same sentence, always showing all its slots. (`actionProse`/`geneRow` change accordingly.)
- **Hovering a gene reveals per-part and per-gene affordances:**
  - after each **condition clause**, a `+` (duplicate *this* clause) and an `×` (delete this clause, with
    confirm);
  - a `⋮` on the gene, opening a **centred modal** with copy / delete / group (the gene-level actions that
    don't belong on the inline surface).
- **Live, no draft.** Changes hit the real genome as you make them. Undo is explicitly *not* in scope for
  this pass — noted as a future want, not a blocker.

### Special cases this has to handle

Grounded in the current control surface (`geneBody` + the picker sheets). These are the things that do
**not** fall out of "every part is a dropdown," roughly in order of how much they shape the work:

1. **Hover is net-new toolkit surface.** The immediate-mode `Ui` kit has no hover concept — only
   `hitTestDown`/`dragTo`/`hitTestUp` — and the desktop host only feeds cursor position while the primary
   button is held. Needs: a `hover(px, py)` pass in `Ui` that marks the hovered element (and its part),
   plus a cursor-move feed from `CytoSceneView` when *not* dragging. Touch never calls it, so the narrow
   path is unaffected.
2. **Some "parts" aren't dropdowns.**
   - **Species/molecule builder** — building `abcb` is atom-append (`+A +B …`), a mini-editor, not a
     single select. It's the most common inline control (every operand / FormBond side / morphogen / axis)
     and the least dropdown-shaped. This is §6.5's flagged "text field in disguise"; the inline popover has
     to host the builder, not a list.
   - **Numeric fields** (efficiency, numeric clause operands) — stepper + presets; on desktop, also allow
     typed entry.
   - **New-group naming** needs keyboard text capture (`startGroupCapture`) with focus handling — lives in
     the `⋮` modal, not an inline dropdown.
3. **Action-type change cascades.** Switching the action reveals/hides sub-fields *and* clears now-invalid
   modifiers (morphogen / KEEP / axis / ORIENT / SEVER exist only for Divide; the picker already sanitizes
   them on change). The card re-lays-out and changes height live — Divide is the worst case.
4. **Group change re-sections the genome view.** Genes render under collapsible group headers; changing a
   gene's group moves its card to another section — possibly a collapsed one. Rule: auto-expand the target
   group and keep the gene selected/scrolled-into-view, so it never appears to vanish.
5. **Clause +/× rules.** Can't delete the last clause (gene keeps ≥1); add is capped at
   `GENOME_MAX_CLAUSES` so the `+` disables at the cap; the `×` confirms. Note the hover `+` = *duplicate
   this clause*, distinct from the current "+ AND CLAUSE" (which copies the **last** clause).
6. **Two copy semantics coexist** — duplicate-clause (hover `+`) vs duplicate-gene (`⋮` modal). Keep them
   visually distinct so they don't read as the same control.
7. **Inline dropdown positioning.** The genome panel is docked against the right screen edge and scrolls, so
   an anchored dropdown must flip left/up to avoid clipping and close (or reposition) on scroll.
8. **Live validity colouring.** The orange "blocked" spans (no fuel / missing input) must keep updating as
   you edit — that feedback is what makes the card worth reading.

### Build order (desktop inline editor)

1. ✅ **Toolkit: hover** (`8f0b3101`). `Ui.hover`/`clearHover` persist the cursor; `Ui.isHovered(rect)` is a
   clip-aware emit-time query; `PanelBuilder.hoverRow` + `HoverAction` reveal `+`/`×` only while hovered;
   desktop host feeds hover on every move. Proven in the ui-gallery `-hover.png` snapshot.
2. ✅ **Inline dropdown primitive** (`1487048f`). `PanelBuilder.dropdown` drops its list into the overlay
   layer anchored to the field, flips **up** when it won't fit below (`Ui.isWithinClip` suppresses it when
   the row scrolls out of view). Proven in the `-dropdown.png` snapshot.
3. ✅ **Live editing path.** 3a (`e39d23e5`): the shared prose always emits every modifier slot with default
   wording so each has a token (`DIVIDE`/`ALONG NO GRADIENT`/`RETAINING NOTHING`/`AND STICK`, efficiency
   `e0`). 3b (`84965902` toolkit + `73150da9` cyto): `PanelBuilder.tokenLines` + `UiTok` (Text/Toggle/Menu)
   — an inline sentence that wraps at construction against the dock's fixed width (the height-before-width
   resolution). `GeneEditor.geneTokenCard` renders each gene as that sentence on desktop; inline-native slots
   (comparator, orient, sever, keep, action, source, efficiency) edit live via `setHeldGene`, builder/keyboard
   slots (operand, species, group) open the shared pick sheet as a popover. `inlineLive` flushes every change
   straight to the genome (no draft/DONE); narrow keeps its modal. Harness-verified (tokens render, sever
   flips `rejectMother` live, action Menu drops inline, operand popover opens).
4. ✅ **Hover affordances** (`8b7497d9`). `TokenRowItem` groups visual rows by semantic line; per-clause `+`
   (duplicate) and `×` (arm-then-delete, shows `!` when armed) reveal on line hover, and the card-level `...`
   → shared Overflow pick sheet reveals on card hover. Live blocked (orange) colouring + per-gene
   active(green)/inactive(grey) card background restored from `describeGeneSpans` flags in the same commit
   (they had been dropped in the 3b token rewrite). Harness: `hover-ui <label>`/`hover-clear` park the cursor;
   verified `+` reveals after a clause and `...` at the card corner.
5. ✅ **Cascades + re-section** (`2d27a0c4`). Group-change auto-expands the target section; the dead wide
   branch of `renderGeneEditor` is retired (the L3 modal is narrow-only). Clause caps already enforced.
6. ✅ **Re-group by drag-and-drop** (toolkit `96d6c0eb` + cyto `2a1fe571`). New toolkit primitive: a free
   2-D `DragSource`/`DropTarget` (`tokenLines(dragId, onDrop)`, `button(dropTargetId)`, `UiBuilder.dragGhost`).
   The desktop grouping mechanic is now a **drag**, not a click on the GROUP token (which became a display-only
   readout): grab a gene card body (its tokens stay clickable — a short press is a tap, movement past the slop
   commits the drag) and drop it on a group header to re-tag it, or on a **"+ NEW GROUP"** placeholder — shown
   at the genome's end only while a gene is in flight — to open a name-entry dialog for that gene. A ghost
   trails the cursor, the source dims, the hovered target lights up, and the destination auto-expands. Harness:
   `dragto <src> >> <dst>` / `draghover`. All verified via harness shots.
7. ✅ **Duplicate/delete by drag-and-drop** (`61bcf742`). The genome-end dropzones grow two more below NEW
   GROUP — **DUPLICATE GENE** (instant, inserts a copy after) and **DELETE GENE** (red; a drop arms it, a
   confirm dialog settles it, since there's no undo). Retires the card's hover `...` overflow menu on desktop
   (the clause +/× hovers stay; the narrow modal keeps its own overflow). Harness-verified.
8. ✅ **Reorder within a group + duplicate-at-group** (toolkit `358fc54a` + cyto `5d03cf56`). DUPLICATE moves
   to the end of the dragged gene's own group section (NEW GROUP/DELETE stay in the bottom stack). New toolkit
   `dropSlot(id)` (a thin insertion line that brightens under the pointer): while a gene is in flight, slots
   appear between its group-mates keyed by target rank, and `reorderHeldGeneInGroup` permutes only same-tag
   genes so the group section order is untouched (a naive global list-move flipped sections — rejected). Works
   for the flat/ungrouped genome (one implicit group). Harness `dragto`/`draghover` resolve label-less slots
   via `Ui.dropTargetElements()`.

Each step verifies through the harness at 1400×900 (`tap-ui <label>` already reaches the inline controls —
gene cards register their sentence as a label; a new `hover-xy <u> <v>` command drives the hover pass).
Since the coach no longer collapses to the pill on desktop (the gene editor stops stealing the
bottom), §6.1's desktop tension dissolves — the pill stays only as a fallback for any other bottom-owning
panel.

> **2026-07-21 — the pill is DELETED, and so is the wide HUD hide.** The "fallback for any other
> bottom-owning panel" never arrived: nothing on wide owns the bottom, so `collapsed` was false wherever it
> was read and `renderPill` became unreachable. Both it and `CampaignDirector.render`'s `collapsed`
> parameter are gone.
>
> Worth recording *why* it lingered, because the same trap is still live elsewhere: the hosts gated both the
> coach collapse and the whole L0 HUD (speed cluster + BRUSH/LAYERS/MENU) on `GeneEditor.isEditing`, which
> means **"a draft is parked", not "a modal is up"**. Wide edits inline with no DONE step, so the draft is
> never cleared — one gene edit hid the HUD and collapsed the coach *for the rest of the session*. The coach
> case was a progress blocker, not a cosmetic one: the pill carried no Skip/Next/Reset, so a `Gate.Next`
> step became unadvanceable. Wide now shows both unconditionally (a sheet's scrim already blocks
> interaction); narrow is unchanged, since its full-screen modal genuinely does own the screen. **If more
> occlusion bugs turn up, the durable fix is a real "something covers the screen" predicate on `GeneEditor`
> rather than more call sites keyed on `isEditing`.**

---

## 8b. The fork is retired — one gene editor at every width (DECIDED 2026-07-24)

**§8a's deliberate fork is reversed.** The gene card built for desktop is now the gene UI on a phone too:
the card *is* the editor at both widths, every editable word a control that writes straight to the genome.
The full-screen L3 modal, its chip body (`geneBody`), the read-only card (`geneButton`) and the
draft/CANCEL/DONE contract are **deleted** — not disabled, deleted.

Why the reversal, when §8a's reasoning was sound? Because what §8a actually established is that *editing in
place beats drilling down when you can afford it* — and the constraint that said a phone couldn't afford it
turned out to be about **hit-target size, not about the model**. At phone density the card's tokens come out
at ~43dp, comfortably over the 48px minimum; the sentence wraps to the narrower column on its own. Nothing
about the drill-down was buying anything the phone needed. What the fork cost was real: two editors for one
concept, so every gene feature had to be built, tested and kept honest twice — and in practice the phone's
copy silently fell behind (that is how it lost the authoring buttons entirely).

What still differs is only the **container**, which is what §8 said all along:

| | Narrow (phone) | Wide (desktop) |
|---|---|---|
| The gene | the same token card, wrapping to the screen | the same token card, wrapping to the dock |
| A token's choices | the L4 **sheet** (big targets, room for the per-action blurbs) | **inline dropdown** under the word |
| Per-clause `+`/`×` | **always drawn**, pinned right (there is no hover to reveal them) | hover-revealed |
| Whole-gene DUP / DEL / group | the card's `⋮`, **and** long-press-drag | drag to the zones at the group's end |

Two consequences worth remembering:

- **`isEditing` is gone.** Nothing is full-screen any more, so the hosts no longer suppress the coach or the
  HUD behind a modal — they key on "is a cell held" alone. This is the "durable predicate" §8a's occlusion
  note asked for, arrived at by removing the thing that needed predicting.
- **The sheet is the editor**, so it takes `SHEET_FRACTION` 0.72 rather than 0.58, and a genome still scrolls
  past the fold on a real phone. That is inherent, not a bug: one gene card is ~250dp.

---

## 9. Proposed build order

> ### Gene card — blocking affordances + activity glow (2026-07-23)
>
> Three changes to the card, all of which campaign copy now leans on:
>
> - **Blocking colour belongs to the CHEMICAL, not the verb.** An action never blocks on itself — there is
>   no biomass ceiling, no import quota — so the orange sits on the operand token (the species chip, the
>   BREAK/BOND reaction chip) and falls back to the verb only where there is no chemical to carry it
>   (`NOTHING`, `USE LIGHT`). Same rule on the fuel row.
> - **A DIVIDE that cannot afford to split says so, on its fuel.** Division needs `biomass/4` in one tick;
>   the panel checks the source against that cost, counting the per-gene 1/n split across contending
>   Divide genes. This is the affordance the Divide chapter's growth-cap step depends on.
> - **Cards glow on a 12-sim-tick rolling average** of the "would fire" flag rather than the per-tick flag,
>   so an intermittent gene reads as dimmed instead of strobing. Folded once per frame, weighted by ticks
>   actually elapsed, so the window means 12 ticks at any speed and a paused world holds its glow.
>
> ⚠️ Campaign copy names these tokens as bare strings and **nothing checks they still exist** — see
> `PLAN_verification_infrastructure.md` §3.

> ### ⏸ SESSION STATE — 2026-07-17 (resume here)
>
> **The new sentence-model UI is live on every width and is the only gene UI** (old dense form deleted, §8).
> In the desktop host it's the default at normal width (no toggle needed); **F2** forces the narrow phone
> containers on at any width (the auto width-switch uses framebuffer px, which HiDPI doubles, so it rarely
> trips on its own). Steps 1–5 are done; L4 pickers, L2/L3, wide + narrow all verified via the harness.
> Core tests green.
>
> **DONE 2026-07-17 (this session):**
> - ✅ **Two-line left-aligned gene card** — new `PanelBuilder.geneCard` primitive (`Ui.kt`); `geneButton`
>   splits the span sentence on the verbatim " IF "/" (" markers into line 1 `WHEN <cond>`, line 2
>   `→ <ACTION> (source) eN`, keeping orange blocking spans. Never widens the panel — overflow clips at the
>   scroll scissor, right edge only. Wide cell dock widened 330→380dp. `5dbb2105`.
> - ✅ **Delete confirm** — overflow DELETE now arms a "DELETE GENE?" step (DELETE GENE / KEEP IT) via
>   `confirmingDelete`; only the second tap removes. `21e938fd`.
> - ✅ **Coach docking** (§6.1) — `CampaignDirector.render(collapsed=)` draws a top-left `STEP N/M` + hint
>   pill instead of the full bottom panel when a cell/gene editor is up (hidden only behind a full-screen
>   narrow modal). Onboarding stays visible while editing. `5889467e`. **Superseded on narrow** by the top
>   banner below — the pill is now the desktop-only collapse.
> - ✅ **Camera recentre** — a selected cell now eases into the middle of the *un-obscured* world area (top
>   above the narrow sheet, left of the wide docked panels) instead of parking behind the panel. Done by
>   offsetting the follow *target* (a real pan, so light/matter fields stay aligned);
>   `GeneEditor.freeAreaOffsetPx` → `CytoRenderer.setFollowOffsetPx`; `snapFollow()` for harness capture.
>   `4399847e`.
> - ✅ **L1 peek detent** — narrow selection opens a shallow peek (name + biomass + DETAILS) that expands to
>   full L2; recentre tracks the detent height. `54d996f6`.
> - ✅ **Real drag gesture** for the peek — a grab handle drags the sheet (tracking the finger) and snaps to
>   peek / full / dismiss on release; tap still toggles. New `Ui` drag-handle channel + `PanelBuilder.dragHandle`
>   + harness `drag-ui`. `4e876217`.
>
> - ✅ **L0 bar + sheets (everywhere)** — `CytoHud` bottom bar (PAUSE · BRUSH · LAYERS · MENU) + three sheets
>   on the toolkit, driving `CytoControls`; bottom sheets narrow, centred popovers wide. Legacy button overlay
>   retired (not drawn); MENU→title menu gained a Save entry. `d2af8e42` (narrow) + `384cbc91` (wide).
>
> **step 7, the Android host — SLICE 1 DONE (`2c0c9e13`):** `CytoAndroidView` rewritten onto the shared
> `Ui`+`CytoHud`+`GeneEditor` stack (always narrow); single-pointer `hitTestDown`/`dragTo`/`hitTestUp` first,
> miss grabs cell / pans (clears camera focus), tap = select + `cameraFocus`, inline speed/pause,
> `setDensity` from displayMetrics. (Camera gestures since reworked — see slice 3.) Compiles; not
> emulator-verified (harness can't drive the Android host, but the UI it draws is the already-verified
> shared stack).
> **step 7 SLICE 2 — DONE (`26c56892`+`4197fed6`+`b04480c0`):** host shell moved into core and wired on
> Android. `CytoMenu`+`CampaignContent` → `commonMain` `org.emerge.demo.cyto.host`; `CytoSaves`/`CytoGenomes`/
> `CampaignProgress` → a `jvmAndAndroidMain` intermediate source set, storage root via `CytoStorage.baseDir`
> (Android = `filesDir`). `CytoSimDriver` stayed desktop-only (Android ticks inline). `CytoAndroidView` boots
> into the menu + campaign + saves; MENU works; name entry is a native `AlertDialog`. Gotcha: `Files.write/
> readString` are API 33+ (minSdk 26) — used byte forms. `assembleDebug` builds an APK.
> **step 7 SLICE 3 — two-finger camera (`43bc3526`+`67bf93d9`):** ported the original cyto pinch model —
> zoom about the midpoint + pan by the midpoint's movement, which pins both fingers to their world points
> (rotation dropped; the toroidal camera has no such DOF). **While a cell is camera-focused the pan is
> dropped and the zoom anchors on the cell**, so a pinch can't shake it loose — matching desktop, where the
> wheel zooms without clearing focus and only a manual pan releases it. Releasing on a phone = one-finger
> empty-space drag (`be651628`). Verified on-device by Stu.
>
> **DONE 2026-07-17 (later) — step 8, campaign polish (the narrow coach):**
> - ✅ **Coach copy overflow FIXED** — the old `COACH_WRAP = 58` fixed wrap was wide-shaped and clipped at
>   560px. `CampaignDirector.wrapBudget` now *measures* the font (`UiTextRenderer.measureWidthPx` of a sample
>   ÷ length) against `screenW − padding − margin`, so the wrap adapts to width×density; 58 survives only as
>   the desktop cap. `2ea4d999`.
> - ✅ **Phone coach = one top-docked full-width banner** — `render(narrow = true)` draws a single top-left
>   panel with `fillWidth` (reads as an app bar, not a left-hugging box), counter *before* the title so it
>   survives a clip, and keeps the full Skip/More/Next controls. It never collapses to the pill on narrow —
>   it clears the bottom sheet by construction. `9c8b0539`.
> - ✅ **Toolkit support** — `Ui.panel` clamps to the screen (minus margins), takes `fillWidth`, and clips
>   overflow (`a47017fc`); `panel()` now returns its height in px (`8203674b`), which feeds…
> - ✅ **Camera recentre accounts for the coach** — `CampaignDirector.coachTopInsetPx` (the banner's bottom
>   edge, one frame stale — fine for a damped follow) → `freeAreaOffsetPx(topObscuredPx =)`, so a selected
>   cell centres in the band *between* coach and sheet, not behind the coach. Wired in all three hosts
>   (desktop, harness, Android). `c7287530`.
> - ✅ **Collapsed peek is a drag-anywhere card** — new `PanelBuilder.dragCard` (`6d51d216`); the L1 peek is
>   now a non-scrolling full-height drag surface, so the whole card drags rather than just a handle
>   (`2e5b8ead`).
>
> **DONE 2026-07-18 — step 8, coach copy pass (`2d753bb1`):** fixed species references broken by the
> fuel/marker rename (Ch9 muscle steps: BREAK RG→BREAK FUEL, BB→MARKER across text/gate/hint/detail), and
> neutralised `Click the cell` → `Select the cell` (~8 spots) so the copy reads on touch. **spotlight-vs-level
> (§6.3) is MOOT** — `Spotlight` was already reduced to a text-only hint (`class Spotlight(hint: String?)`),
> so there is no widget-targeting to redesign; what remained of that tension was this copy pass.
>
> **DONE 2026-07-18 — host-injected input hints (`abcc7ac8`):** the host-conditional copy mechanism. New
> `InputHints` (commonMain) interpolates `{pan}`/`{zoom}`/`{grab}` tokens; `CampaignDirector.inputHints` holds
> the host's table (MOUSE default) and expands every coach string. Hosts set it once (desktop=MOUSE,
> Android=TOUCH, harness=`-Dcyto.agent.touch`). Ch1's camera primer is now token-authored and renders
> correctly on both (harness-verified mouse + touch). `validateGlyphs` expands both modalities before scanning.
>
> **NEW TRACK (queued 2026-07-18) — §8a desktop inline gene editor.** Decided: re-fork desktop from
> narrow for gene *editing*. Desktop edits inline on the genome card (live, no draft; hover reveals
> per-clause `+`/`×` and a gene `⋮` modal); narrow keeps the full-screen modal. This dissolves §6.1's
> desktop coach-pill tension. Build order + special cases in §8a. Start at step 1 (toolkit hover). This
> supersedes the coach-docking-refinement thread below for desktop.
>
> **DONE 2026-07-19 — authoring cluster (friend's Friday-build feedback).** (a) `e785d113` dropped the
> tautological per-card GROUP readout (the section header names the group). (b) `645ce8d6` **create genes /
> groups from scratch** (not just duplicate): `BLANK_GENE` template; "+ NEW GENE" at each open group's end,
> "+ NEW GROUP" (bottom) drops a blank + raises the name dialog; genome section renders for an empty genome;
> `CytoController.appendHeldGene` (unconditional append). (c) `13e7be45` **gene bank / blueprints**:
> `CytoSnippets` store (`cyto-snippets/`), "SAVE <group> TO BANK", "PASTE GROUP FROM BANK" picker, name-clash
> Replace/Add-on-top/Cancel dialog (`replaceHeldGroup`/`appendHeldGenes`); `GeneSnippet` commonMain view,
> wired desktop + harness (Android on no-op defaults, follow-up). `Ui.tapLabel` now prefers exact match.
>
> **TOP OF QUEUE — remaining step-8 threads:** (1) coach docking refinements beyond the narrow banner.
> Optional follow-ups: port the web host off the legacy `CytoControls` overlay; on-device pass of the Android
> campaign coach at phone width (the banner is harness-verified at narrow, but only Stu's device exercises real
> density). Any *future* per-host gesture copy just needs a `{token}` + an entry in `InputHints.MOUSE/TOUCH`.
>
> **Key files:** `apps/cyto/core/.../ui/GeneEditor.kt` (all render paths: `renderCellPanel`/`cellBody`,
> `renderGeneEditor`/`geneBody`, `renderPickerSheet`/`pickSheet`, `geneButton` → `Ui.geneCard`);
> `apps/cyto/core/.../campaign/CampaignDirector.kt` (`render(narrow=/collapsed=)`, `wrapBudget`,
> `coachTopInsetPx`); `apps/cyto/android/.../CytoAndroidView.kt` (the phone host: touch → shared stack);
> `engine/render/torus/.../ui/Ui.kt` (`modal`/`sheet` take bounds, `dockRight`/`dockBottom`, the
> insertion-ordered base layer); `apps/cyto/desktop/.../CytoSceneView.kt` (width switch, F2, coach
> suppression, scroll/drag input); harness flags `-Dcyto.agent.narrow` + `w/h/density`.
> **Verify wide:** `:apps:cyto:desktop:cytoAgent -Dcyto.agent.w=1400 -Dcyto.agent.h=900` with a script that
> selects a cell, expands a group, taps a gene. **Verify narrow:** add `-Dcyto.agent.narrow=true` at
> `w=560 h=1000`. (Harness label-taps can't reach rows scrolled off-screen — that's not a UI bug.)

Each step ends in something visible at phone size via the harness
(`-Dcyto.agent.w=1080 -Dcyto.agent.h=2400`, `6f5c2ace`).

1. ~~**Density + text decoupling** in `Ui`.~~ **DONE** (`4f606890`). Sizes callers pass are dp × `Ui.scale`;
   `textSize` is its own knob defaulting to the old ratio. Gate held: ui-gallery + cyto's panel, editor
   and coach all render **bit-identical** at 1200×900; at `-Dcyto.agent.density=2.625` rows go 16px → 45px.
2. ~~**Scroll container**~~ **DONE** (`dc76cdcd`) — `scrollArea`, clip index per draw command, same-clip
   runs batched between scissors; clipped rows are neither drawn nor hit-testable; a press that becomes a
   scroll cancels its click. `GPU.setScissor` already existed on JVM/Android/JS. ~~**Chip + Segmented +
   List row**~~ **DONE** (`a73ad823`). All showcased in `ui-gallery`; its snapshot now also captures a
   *scrolled* frame, the only way a static shot proves the clip holds at an offset.
   **Modal** (full-screen: fixed title bar with back + `...`, fixed bottom action bar, a scrolling body
   between them) landed with step 3; **Sheet** (bottom sheet, the L4 host) landed with step 4. **Still to
   do: overflow menu** (`⋮` on the modal title bar, for DUP/DELETE/export), **confirm dialog** (DELETE).
3. ~~**L3 gene detail, vertical slice.**~~ **DONE.** `Ui.modal` + `PanelBuilder.clauseRow` (a clause as
   one row of three chips), and `GeneEditor.render(narrow = true)` — while a gene is open, the whole
   screen becomes the L3 modal instead of the two desktop panels. Wired to the **live draft**: rendered
   at 1080×2400 against the actual `ch10-reproduce` genome (harness `-Dcyto.agent.narrow=true`). Both the
   Divide worst case (3 clauses + morphogen + axis, fits without scroll) and a typical Convert gene match
   §3's wireframe. Inline binary controls (comparator, KEEP, ORIENT, SEVER, FormBond MATCH) are fully
   wired segmented controls; the value chips (operands, action, source, morphogen, group) lay out and read
   live state but their taps are inert until step 4 supplies the L4 pickers. Desktop (narrow=false) renders
   bit-identically — the wide two-column form is untouched. Coach suppressed behind the modal (§6.1). The
   throwaway `MobileMockRender` is deleted, its job done.
4. ~~**L4 pickers** (list / number / species / segmented) + the **Sheet** primitive.~~ **DONE.**
   `Ui.sheet` (bottom sheet: scrim + title bar + scrolling body, stacked over the modal), and the L3 value
   chips now open pickers: **action** and **operand-kind** lists carry a one-line description each (the
   onboarding win — `CONVERT — LOCK A MOLECULE INTO BIOMASS`), **source**/**group** are lists, **operand**
   is a kind list + a value editor (number stepper or species builder), and **efficiency** is a number.
   List-picks auto-dismiss and mutate the live draft; verified end-to-end at 1080×2400 (action-pick flips
   the L3 conditional fields). The hold-to-repeat stepper is the 2000-tap fix.
   **Rendering fix landed here:** the base UI layer is now one insertion-ordered stream (rects + text
   interleaved), so a later opaque rect occludes earlier text — without it the modal's text bled through
   the sheet. Proven bit-identical on the ui-gallery snapshot (static + scrolled), so the change is
   transparent to all existing UI.
5. **L2 cell sheet** **DONE** (partial — L1 collapsed-peek detent still to do). `Ui.dockBottom` (a
   persistent, no-scrim, scrollable bottom sheet over the live world) hosts `renderCellSheet`: the held
   cell's vitals, a collapsible chemistry table, and the genome as collapsible colour-tinted subsystems —
   reusing the same grouping / chemistry / gene-row code as the wide panel, only the container differs.
   Tapping a gene raises the L3 modal. Wired into the desktop host: below `NARROW_MAX_PX` the cell view is
   this sheet, not the wide `TopRight` panel. **Still to do:** the L1↔L2 drag detents, and the two-line
   gene card (a one-line 30-char label still overflows a narrow width).
6. **L0 bar + Brush/Layers/Speed sheets** — **DONE** (`d2af8e42` narrow, `384cbc91` wide). `CytoHud` (core,
   on `UiBuilder`): a four-target bottom bar (PAUSE/PLAY · BRUSH · LAYERS · MENU) whose middle buttons open
   sheets (Speed = pause + SLOWER/FASTER + TPS/FPS; Brush = genome palette + touch mode; Layers = overlay/
   colour/mutation/readouts) — bottom sheets on a phone, centred popovers on desktop. It drives the same
   `CytoControls` state model (added public mutators); the bespoke button overlay is no longer drawn
   (`CytoControls` kept as the state model only). MENU (both widths) opens the title menu, which gained a
   **Save** entry so world-save survives the overlay's removal. **Leftover for step 8:** the narrow coach
   text overflows 560px (COACH_WRAP=58 is wide-shaped).
7. **Android host**: wire `Ui` + touch routing; then menu/saves/sim-driver (audit Phase 4).
8. **Campaign**: coach docking, spotlight-vs-level, touch copy pass.

Steps 1–2 are invisible to the player and gate everything. Step 3 is the first real win, and the
first honest test of whether the sentence model reads.
