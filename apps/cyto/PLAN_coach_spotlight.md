# Coach spotlight — directing attention in the info panel

The cell panel is dense, and the coach could only *describe* where to look ("the GROW group, in the cell's
panel"), leaving the player to find it. A `Spotlight` can now name a widget: the coach draws a box around it
and a connector back to itself.

**Built (2026-07-30):** P1–P5. Genesis and the rehomed Act II both point at their widgets; the box clamps
to its clip; readouts are targetable. **Remaining:** P6 polish only — see the bottom.

---

## What exists

| piece | where |
|---|---|
| `Spotlight(hint, target, occurrence)` | `campaign/CampaignModel.kt` |
| `CampaignDirector.renderSpotlight(ui)` — box + elbow, late pass | `campaign/CampaignDirector.kt` |
| `Ui.element(label, occurrence)` — `tapLabel`'s lookup, returning a rect | `engine/.../ui/Ui.kt` |
| `Ui.lastPanelRect` — the connector's anchor end | `engine/.../ui/Ui.kt` |
| `panel(offsetX)` — nudge a panel off its anchor | `engine/.../ui/Ui.kt` |
| 4 targets on Genesis + 13 in the rehomed Act II | `host/CampaignContent.kt` |
| `Ui.readouts()` / `noteReadout` — non-clickable named rows | `engine/.../ui/Ui.kt` |
| `UiElement.clip` / `.visible` — the scroll viewport it sits in | `engine/.../ui/Ui.kt` |
| `campaign-spotlight.txt` — the four beats, shot + tapped | `apps/cyto/agent-scripts/` |
| `UiElementLookupTest` (12) | `engine/render/torus/src/commonTest/` |

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

## P3 — visibility ✅ done (`7fb809fb`)

Not the gap it looked like. A row scrolled **fully** out of its viewport is culled at layout, so it never
becomes a region: the lookup returns null and the coach falls back to hint text unaided. What was real is the
row **straddling** the viewport edge — emitted, drawn clipped, but decorated unclipped, so the box spilled
onto the world behind the panel. `UiElement` now carries the clip it was laid out in (plus `visible`), and the
director clamps the box the same way the renderer clamps the widget.

So autoscroll was never needed, and the question of whether to fight a player who scrolled deliberately does
not arise. Collapsed containers remain untouched (a target inside a collapsed group is not laid out and
resolves to nothing → hint text): still the cleanest place for a target *chain* if a beat ever needs one.

## P4 — named non-interactive regions ✅ done (`3c0c9502`, `81d2d76e`, `3ba68161`)

Panel text and key/value rows now record their rect as a **readout region**. `element` resolves those too, so
"the cell's LIGHT reading" is a target; `elements()` still lists only what can be tapped, so a tap driver's
view of the frame is unchanged. A key/value row is named by its **key** — the value changes every tick.

Two ordering rules, each of which was a live bug first:

- **Exactness outranks clickability** (exact widget → exact readout → substring widget). A gene card is
  labelled by its whole text, so `USE LIGHT` captured "LIGHT" as a substring and the coach ringed the card.
- **A readout matches only its whole text.** Substring matching on readouts rang whichever *sentence*
  contained the word — including the coach's own prose, so on a narrow screen with the cell sheet collapsed
  the coach boxed itself and drew a connector to its own copy.

## P5 — authoring ✅ done (`20299cd6`, `f6114dd8`)

All 13 spotlights that name a widget now point at it, verified in-game on Genesis and Hold Together. Group
headers use the **collapsed** form (`+ GROW`, `+ MOVE`), so the ring disappears the instant the player expands
the group — which is what the step was asking for. `+ ADD X`, `+ NEW GROUP`, `PAUSE` and the `LIGHT` readout
are unique on screen, so no `occurrence` was needed anywhere; the fragility warning below still stands for
anything added later.

Deliberately left as hint text:

- the **world gestures** (drag a cell, tap empty space) — no widget exists to ring, and a spotlight without a
  target is still a hint;
- the **SEVER toggle**, whose label reads `AND STICK` until the player flips it, so a target would resolve
  only after the step was already done;
- **"the cell's panel"** — a panel is not a named region, and pointing at a whole panel is not what the
  mechanism is for;
- the **chains** ("MOVE -> the muscle gene -> SOURCE -> BREAK FUEL") point at their first hop only. No beat
  has yet proved that insufficient.

⚠️ **`occurrence` is fragile.** It counts matches on screen, so "the DIVIDE gene's energy source" is *the
second* `USE LIGHT` only until the genome grows. Prefer labels unique on screen; if a beat genuinely needs the
n-th, pin it with a scripted step.

## P6 — polish

- Pulse/glow. Needs a time source the director doesn't have (no `dt`, doesn't read `controller.tick`), and
  `Ui` has no frame clock either — `updateHold`'s `dt` only ticks while a pointer is down. Plumbing a clock
  through four hosts for a cosmetic pulse was judged not worth it; the static ring reads clearly in the
  screenshots. Revisit only if a beat is observed to be missed.
- Glow = nested translucent rects; the rect primitive is axis-aligned, **no rotation** — which is also why the
  connector is an elbow and not a diagonal. A true diagonal means a new engine primitive.
- Narrow: the cell sheet can cover the coach entirely. Harmless today (a target inside a collapsed sheet
  isn't laid out, so nothing is drawn), but once the sheet is expanded over the coach the connector has
  nowhere sensible to run. Unverified: no script drives the narrow sheet open yet.

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
