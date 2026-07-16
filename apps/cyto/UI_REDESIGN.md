# Cyto — UI Redesign: Progressive Disclosure

Status: **proposal, nothing built.** Companion to `MOBILE_READINESS.md` (the audit that motivates it).
Goal: keep **every** feature of the current UI, on a phone, in a form that reads cleanly — by
disclosing depth on demand instead of showing everything at once.

---

## 1. The diagnosis behind the design

The current gene editor isn't dense by accident — it's dense because **it's a form, and a form shows
every field of every facet simultaneously**, including ones that don't apply. A 4-clause Mitosis gene
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
│   │ DIVIDE (mitosis)                     │   │
│   └──────────────────────────────────────┘   │
│   Morphogen    ┌────────┐                    │
│                │ (none) │                    │  ← only Mitosis shows these
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
| `Mitosis` morphogen / keep / axis / orient / sever | L3, conditional |
| `EFF` gear | L3 (non-Mitosis actions) |
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
| L3 worst case (Mitosis, morphogen+axis, 4 clauses) | 16 | ~808dp | ~1 row over → scrolls |
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

## 9. Proposed build order

Each step ends in something visible at phone size via the harness
(`-Dcyto.agent.w=1080 -Dcyto.agent.h=2400`, `6f5c2ace`).

1. **Density + text decoupling** in `Ui`. Gate: desktop screenshots unchanged; ui-gallery is the surface.
2. **Scroll container + Modal + Chip + Segmented + List row.** Showcase each in `ui-gallery` first —
   that's what it's for, and it's cheaper than debugging them inside the game.
3. **L3 gene detail, vertical slice.** The densest, highest-risk screen — proving it here de-risks
   the rest. Render at 1080×2400 and compare against §3's wireframe.
4. **L4 pickers** (list / number / species / segmented). Kills the 2000-tap bug.
5. **L1/L2 sheets**; retire the current info panel.
6. **L0 bar + Brush/Layers/Speed sheets**; retire the scattered `CytoControls` buttons.
7. **Android host**: wire `Ui` + touch routing; then menu/saves/sim-driver (audit Phase 4).
8. **Campaign**: coach docking, spotlight-vs-level, touch copy pass.

Steps 1–2 are invisible to the player and gate everything. Step 3 is the first real win, and the
first honest test of whether the sentence model reads.
