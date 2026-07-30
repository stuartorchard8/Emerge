# Coach spotlight — directing attention in the info panel

The cell panel is dense, and the coach could only *describe* where to look ("the GROW group, in the cell's
panel"), leaving the player to find it. A `Spotlight` can now name a widget: the coach draws a box around it
and a connector back to itself.

**Built (2026-07-30):** P1 + P2, piloted on Genesis, plus two layout fixes the pilot exposed.
**Next:** P3 (visibility) — do this before authoring more targets; the Act II chapters spotlight groups
partway down a long genome, which is exactly where the gap bites.

---

## What exists

| piece | where |
|---|---|
| `Spotlight(hint, target, occurrence)` | `campaign/CampaignModel.kt` |
| `CampaignDirector.renderSpotlight(ui)` — box + elbow, late pass | `campaign/CampaignDirector.kt` |
| `Ui.element(label, occurrence)` — `tapLabel`'s lookup, returning a rect | `engine/.../ui/Ui.kt` |
| `Ui.lastPanelRect` — the connector's anchor end | `engine/.../ui/Ui.kt` |
| `panel(offsetX)` — nudge a panel off its anchor | `engine/.../ui/Ui.kt` |
| 4 targets on Genesis | `host/CampaignContent.kt` |
| `campaign-spotlight.txt` — the four beats, shot + tapped | `apps/cyto/agent-scripts/` |
| `UiElementLookupTest` (5) | `engine/render/torus/src/commonTest/` |

Commits: `a897a7e6` (toolkit) · `04641d48` (coach + Genesis) · `bf114b80` (coach centring) · `e5a90c7b`
(HUD bar centring).

### The three decisions worth not re-deriving

1. **A target is a widget label, resolved through `tapLabel`'s own lookup.** Not a parallel matcher. The thing
   the coach circles is therefore by construction the thing a harness script taps — a label that drifts breaks
   both at once, instead of leaving the coach pointing confidently at the wrong word. Matching: case-
   insensitive, **exact before substring**, an open sheet's rows before the panel behind it, `occurrence` for
   the n-th match.
2. **It renders as a second pass, last in the host's frame.** The coach panel is drawn *early* (so the gene
   editor's dropdowns and sheets sit over it), but the widget it points at only exists once the editor has laid
   itself out, and a box drawn before it would be painted over. Emitting last fixes the lookup and the z-order
   together. Each host calls `director.renderSpotlight(this)` as its final statement inside `ui.frame { }`.
3. **The connector routes over open world, not through the panel.** Running to the target's centre-x and
   turning draws a line down the middle of the cell panel across every card in between. It leaves the coach's
   near horizontal edge **at the coach's centre** — a panel's rect is not what you can see of it (see below) —
   climbs clear, then comes in horizontally to the box's near edge.

### The layout fixes

Pre-existing, made obvious by the spotlight: **a `BottomCenter` panel centres on the screen**, while the cell
panel docks right and is drawn after it. At 1200px the coach spanned 272..928 with the dock starting at 808,
so the tail of every line sat behind the panel — and the coach's rect really is 656 wide even though you can
see 536 of it, which is why the connector's first routing attempt turned *inside* the panel.

Both the coach and the HUD bar now take the host's `GeneEditor.freeAreaOffsetPx` dx — the documented source of
truth for how much of the world each layout occludes, and the same value the camera uses to hold a followed
cell — and shift *and re-wrap* to what is left. Each host reads it **once** into a local both calls share.

> **If you ever want the coach in FRONT of the panel instead** (Stu's first instinct, and reasonable for a
> modal-feeling beat): the reorder is one line, but the gene editor's dropdowns and pick sheets currently draw
> above the coach on purpose and would have to move to the overlay layer to compensate. The centring approach
> was taken because it costs only horizontal room.

---

## P3 — visibility ⚠️ the real cost, do next

`Ui.element` enumerates labelled regions and **does not intersect the scroll clip**, so a target scrolled out
of its viewport still resolves and the box draws outside the panel. Hit-*testing* already intersects the clip
(`ClickRegion.clip`); enumeration doesn't. Genesis is a safe pilot only because its panel doesn't scroll at
that length.

1. Carry the clip through to `UiElement` (or expose `isVisible`), so a caller can tell "off-viewport" from
   "absent".
2. Then choose per case: **suppress** the box (fall back to hint text), or **autoscroll** the target into view.
   Precedent for the latter: cards autoscroll while dragged (`71204b4e`).
   *Open question for Stu:* autoscrolling is friendlier but fights a player who has scrolled deliberately.
3. Collapsed containers are the sibling problem: a target inside a collapsed group, or the narrow layout's
   collapsed cell sheet, isn't laid out at all. Needs an ordered fallback ("point at the container instead"),
   which `Spotlight` would express as a target *chain*.

## P4 — named non-interactive regions

`elements()` lists **only clickable** labelled regions. So "the cell's LIGHT reading", "the chemistry table",
a metabolism row, a `WHEN BIO 5988 < 3000` clause — none can be targeted. Given the whole point is directing
attention in a dense *readout*, this is not optional for long. Needs a way for a panel to emit a named region
that isn't a button (engine-level, `PanelBuilder`).

## P5 — author the remaining spotlights

34 spotlights exist; 4 (Genesis) have targets. Of the 30 hint-only ones, all in the rehomed Act II:

- **~9 are already a widget label** and are near-mechanical: `+ ADD REPRODUCE` ×2, `+ ADD HOLD TOGETHER`,
  `+ ADD MOVE`, `+ ADD POLARIZE`, `+ ADD CLOCK`, `+ GROW (2)`, `SEVER toggle, then DONE`, `PAUSE in the bottom
  bar`. **Blocked on P3** — these sit below a long genome and will be scrolled away.
- **~4 are multi-step chains** ("MOVE -> the muscle gene -> SOURCE -> BREAK FUEL"). Either target only the
  first hop, or teach `Spotlight` a sequence. Prefer the first until a beat proves it insufficient.
- **~11 are world gestures** ("Press and drag a cell to tow the body", "Select the cell") — no widget, keep as
  text. That is a feature: a spotlight without a target is still a hint.
- **2 need P4** ("the cell's LIGHT reading", "the cell's panel").

⚠️ **`occurrence` is fragile.** It counts matches on screen, so "the DIVIDE gene's energy source" is *the
second* `USE LIGHT` only until the genome grows. Genesis gets away with occurrence 1 on a single-gene genome.
For Act II, prefer labels unique on screen; if a beat genuinely needs the n-th, pin it with a scripted step.

## P6 — polish

- Pulse/glow. Needs a time source the director doesn't have (no `dt`, doesn't read `controller.tick`).
- Glow = nested translucent rects; the rect primitive is axis-aligned, **no rotation** — which is also why the
  connector is an elbow and not a diagonal. A true diagonal means a new engine primitive.
- Narrow: the cell sheet can cover the coach entirely; the connector currently drops straight down into it.

---

## Verifying

The spotlight is a *drawing* — only a screenshot says whether it landed on the right widget. What a script can
pin is the half that fails silently: every target label is also tapped, so a label that stops existing fails
the run.

```
DISPLAY=:0 ./gradlew -q :apps:cyto:desktop:cytoAgent \
  --args="apps/cyto/agent-scripts/campaign-spotlight.txt /tmp/cyto-agent"
# narrow (coach docks top, connector drops into the sheet):
DISPLAY=:0 ./gradlew -q :apps:cyto:desktop:cytoAgent -Dcyto.agent.narrow=true \
  -Dcyto.agent.w=560 -Dcyto.agent.h=1100 --args="apps/cyto/agent-scripts/campaign-spotlight.txt /tmp/cyto-agent"
```

Then `imv` the shots. Also re-run `campaign-genesis-divide-*`, `campaign-eager-player`, `rehomed-insert` and
`narrow-gene-drag` — they tap the same labels the coach points at.
