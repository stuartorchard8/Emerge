# Cyto — Mobile Readiness Audit

Audit date: 2026-07-16. Scope: `apps/cyto/android` + the shared UI it depends on.

**Verdict: the Android build is a tech demo, not a playable version of the game.** The sim,
renderer and the legacy touch controls (`CytoControls`) are fine on a phone. Everything built on
the newer shared UI toolkit — **the cell info panel, the gene editor, the menu, saves, and the
whole campaign** — is either absent from the Android host or laid out at a size no finger can hit.

The info panel is not a "scale it up" job. Section 2 shows why: at any legible size the current
design does not fit a portrait phone, so it needs a different *shape*, not different numbers.

---

## 1. Method — how these numbers were obtained

The UI kit lays out in **raw framebuffer pixels**, so its geometry only tells the truth at a real
device size. The agent harness now renders at an arbitrary size and dumps every interactive rect
(`6f5c2ace`):

```bash
printf 'scenario swimmer\nrun 200\nclickcell 0\ntap-ui DIVIDE\nelements\nshot phone-editor\n' > /tmp/s.txt
./gradlew :apps:cyto:desktop:cytoAgent --args="/tmp/s.txt" -Dcyto.agent.w=1080 -Dcyto.agent.h=2400
```

This drives the **real game code** (same `Ui`, `GeneEditor`, `CytoControls` the app builds) at a
Pixel-class portrait framebuffer. Reference device throughout: **1080×2400 px, density 2.625
(420dpi) → a 411×914 dp screen**. Android's minimum touch target is **48dp = 126px**; minimum
comfortable body text ~**14sp = 37px**.

Caveat: this measures the *desktop* host's widget tree. The Android host doesn't build most of
these widgets at all (§4) — the geometry is what you'd get *if* you wired them up as-is.

---

## 2. The cell info panel — the main event

### 2.1 What it measures today

Rendered at 1080×2400 with a cell selected (`agent-out/phone-panel.png`):

| Element | Measured (px) | In dp | vs. 48dp target |
|---|---|---|---|
| Panel row / gene button height | **16** (18 pitch) | **6.1dp** | **7.9× too small** |
| Row text height | 12.2 | 4.7dp | ~3× under 14sp |
| Gene button width | 348 | 133dp | fine |
| `+r` / `+g` / `+b` atom buttons | **37 × 16** | 14 × 6dp | **3.4× / 7.9×** |
| `<` wildcard toggle | **28 × 16** | 10.7 × 6.1dp | **4.5× / 7.9×** |
| DONE / CANCEL / DUP / DEL | 56/74/47/47 × **16** | ~21 × 6dp | **7.9×** |

The whole two-column editor occupies the **top ~12%** of the phone screen; the other 88% is empty
world. Meanwhile `CytoControls` buttons render at 120px (46dp) right beside it. One screenshot
contains both a touch-scaled UI and a desktop-pixel UI — that contrast *is* the bug.

### 2.2 Why it's this way

Two sizing systems, only one of which was built for touch:

- `CytoControls` (ported from the original touch game) sizes **relative to resolution**:
  `bs = (min(resW,resH)/7).coerceIn(64f,120f)` — `CytoControls.kt:213`. Touch-correct by construction.
- The shared `Ui` toolkit uses **fixed pixel constants**: `rowHeight = 18f`, `margin = 12f`,
  `padding = 8f` — `Ui.kt:285-292`. It has **no concept of display density**. It was written for a
  desktop monitor where 18px happens to be ~5mm, and nothing has ever challenged that.

### 2.3 Why scaling alone does not work

The toolkit **couples text size to row height**: `textH = rowHeight * 0.68` (`Ui.kt:296`), and panel
width is then driven by the longest label, monospace, at `width = chars × textH × 0.75`
(`UiTextRenderer.kt:171`). So asking for touch-sized rows also inflates the text and therefore the
width:

- **48dp rows** (`rowHeight = 126px`) ⇒ 33dp text ⇒ the 38-char header
  `"GENES (TAP TO EDIT. ORANGE = BLOCKING)"` becomes **2451px ≈ 934dp wide — 2.3× wider than the
  411dp screen.**
- Decouple them and ask only for **legible 16dp text** (42px): that header is still **1197px ≈
  456dp — wider than the screen.** Gene rows themselves (~27-30 chars) land at 324–360dp, which
  *just* fits a 411dp screen with almost nothing to spare; any longer species name overflows.

And the killer, structural one:

- The editor is **two side-by-side columns** (`GeneEditor.kt:164`, `newColumn = true` — deliberate,
  so a tall editor doesn't stack under the info panel). Today they total ~600px of 1080. At merely
  legible text (3.4×) that's **~2040px ≈ 776dp — nearly 2× the phone's width.** **Side-by-side
  columns cannot exist on a portrait phone.**
- **Genome length is unbounded** — there is no `GENOME_MAX_GENES` in `CytoTuning.kt` (only
  `GENOME_MAX_CLAUSES = 4`). Panel height is therefore unbounded. At 48dp rows a 10-gene genome
  needs ~864dp of an 914dp screen, before the editor. **The list must scroll — and the toolkit has
  no scrolling, no clipping, and no scissor rect anywhere.**

### 2.4 What the redesign actually is

Not a re-skin. The mobile info panel needs:

1. **Density awareness in `Ui`** — a `scale` / dp unit fed from the host, replacing fixed px constants.
2. **Text size decoupled from row height** — 48dp rows with 16sp text and real padding.
3. **A scrollable, clipped container** — a new toolkit primitive (needs a scissor rect + touch
   scroll + fling, and hit-testing that respects the clip).
4. **One-thing-at-a-time navigation** instead of columns — the info panel as a **bottom sheet**
   (thumb-reachable; the top-right corner of a 914dp screen is not), and the gene editor as a
   **full-screen modal pushed over it**.
5. **Shorter labels** — the 38-char captions and `"CONVERT rg IF BIO<3000 (LIGHT)"` gene rows must
   be restructured (two-line, or icon + value) to survive a 411dp width.
6. **Bigger atomic controls** — `+r`/`+g`/`+b`/`<` at 37×16px are the single worst offenders.

This is effectively a second front-end for the same state. The good news: `GeneEditor` already
separates *state* (draft, `openField`, commit-on-DONE) from *layout*, and all mutation goes through
`CytoController`'s thread-safe API — so a mobile layout can reuse the model without touching the sim.

---

## 3. Confirmed touch bugs (independent of the redesign)

**3.1 Every pinch-zoom ends by spawning a cell.** `CytoAndroidView.kt:80-93` routes to `handlePinch`
only while `pointerCount >= 2`. Lifting the second finger (`ACTION_POINTER_UP`, count 2) sets
`uiConsumed = false`; the last finger then arrives as `ACTION_UP` with count **1**, falls through to
`onUp`, and — with `dragged` still false, because pinch moves never call `onMove` — fires
`controller.tap(...)`. So a two-finger zoom terminates in a world tap at the first finger's
position. **This is the highest-severity functional bug found.**

**3.2 Hold-to-repeat is dead on Android**, making thresholds uneditable. The ± stepper accelerates
only from `Ui.updateHold`, which the host must call each frame (`Ui.kt:162`). The Android view never
calls it. Every tap is therefore **±1**: setting `BIO > 2000` takes **2000 taps**.

**3.3 A deliberate tap will silently do nothing** once `updateHold` *is* wired. `hitTestUp` rejects
any release later than `INITIAL_DELAY = 0.35s` after the press (`Ui.kt:145`). That's a reasonable
mouse heuristic and a bad touch one — a considered finger-press routinely exceeds 350ms.

**3.4 DELETE sits ~6px from DONE.** Measured: DONE `x=439..495`, DEL `x=636..683`, all 16px tall with
~6px (2.3dp) gaps. All four actions span 244px ≈ 93dp — under two fingertip widths, with the
destructive one in the row. Needs separation and confirmation on touch.

**3.5 Drag slop is too tight.** `DRAG_THRESHOLD_PX = 12` (`CytoAndroidView.kt:173`) = **4.5dp**;
Android's `touchSlop` is ~8dp (~21px). Finger jitter promotes taps to drags, so cell selection will
feel unreliable.

---

## 4. The Android host is missing most of the game

`CytoAndroidView` constructs **only** `CytoController`, `CytoRenderer` and `CytoControls`. Compared
with `CytoSceneView` (desktop) it has **no**:

- `Ui` / `GeneEditor` → **no info panel, no gene editing at all** (so §2 is currently hypothetical —
  it's not that the panel is too small on Android, it's that *there is no panel*).
- `CytoMenu` → no title screen, no scenarios, no chapter select.
- `CytoSaves` / `CytoGenomes` → **no saving, no loading, no genome palette** (so the brush falls
  back to legacy `CellType` swatches, not the player's genomes).
- `CampaignDirector` → **the entire onboarding/campaign is desktop-only.** Given the campaign exists
  precisely to fix first-contact, shipping mobile without it means shipping the impenetrable version.
- `CytoSimDriver` → sim runs on the GL thread, so **no speed control, no pause, and tick rate is
  welded to frame rate**. On a phone that also means the sim stops being frame-paced under thermal
  throttle.

Wiring `Ui` in also means routing `hitTestDown` / `hitTestUp` / `updateHold` / `releaseHold` from
touch, which the view does not do today (it only calls `CytoControls.hitTest`).

---

## 5. Platform hygiene

**5.1 No lifecycle handling.** `CytoActivity` never forwards `onPause`/`onResume` to the
`GLSurfaceView` — the documented contract. The render thread keeps running when backgrounded
(battery, likely surface-destruction crash). Combined with no save system, **backgrounding the app
loses the world.** For a sim you're meant to leave running, save-on-pause is table stakes.

**5.2 The panel renders under the status bar.** Theme is `NoActionBar` but not immersive, and there
is no `WindowInsets` handling. The info panel anchors at `margin = 12px` from the top — the status
bar is ~24dp (63px), so **the panel title sits behind it**, as would a notch/cutout.

**5.3 No orientation lock.** The manifest handles `orientation` in `configChanges` (so no recreate),
but the layout is landscape-shaped. Either lock, or design portrait properly.

**5.4 GL version mismatch.** Manifest declares `glEsVersion 0x00020000` (2.0); the view requests
`setEGLContextClientVersion(3)`. Play Store filtering would be wrong — should declare 3.0.

**5.5 No text input path.** Gene *group naming* needs typed characters (`GeneEditor.capturingGroupName`),
fed by desktop key callbacks. Android has no key handling and no soft-keyboard integration.

---

## 6. Roadmap

Ordered so each phase is independently shippable and visible.

### Phase 1 — Make the existing Android build correct (small)
Fixes real bugs; no redesign. Nothing here is blocked by anything else.
1. Fix the pinch→spawn bug (§3.1). *Highest severity, smallest fix.*
2. Forward `onPause`/`onResume` (§5.1).
3. Raise drag slop to ~8dp, density-derived (§3.5).
4. Declare GL ES 3.0 (§5.4).
5. Handle insets / go immersive (§5.2); decide orientation (§5.3).

### Phase 2 — Density in the toolkit (medium)
The unlock for everything after it. Nothing renders correctly on a phone until this lands.
1. Add a `scale`/dp concept to `Ui`, host-fed from `DisplayMetrics` (desktop passes 1.0 — no visual change).
2. Decouple `textH` from `rowHeight`; introduce explicit padding + a 48dp minimum row.
3. Replace the `updateHold` timeout heuristic with a touch-safe rule (§3.3).
4. **Gate:** desktop screenshots must be byte-identical; the ui-gallery is the regression surface.

### Phase 3 — Scrolling + a mobile layout for the info panel (large — the real work)
1. New toolkit primitive: clipped, scrollable container (scissor + touch scroll + fling + clipped hit-test).
2. Bottom-sheet anchor; full-screen modal push for the gene editor; kill the two-column model on narrow screens.
3. Shorten/restructure gene labels (§2.4.5).
4. Wire `Ui` + touch routing into `CytoAndroidView`; enlarge the atom/wildcard controls; separate DEL from DONE.

### Phase 4 — Make it a game, not a sandbox (large)
1. Port `CytoSimDriver` (sim off the GL thread) → speed/pause control.
2. Saves + save-on-pause; genome library + palette.
3. `CytoMenu`.
4. **The campaign** — the biggest single chunk, and the one that decides whether mobile is playable
   by a newcomer. Chapter copy assumes mouse verbs ("drag", "click") and will need a touch pass.
5. Soft-keyboard path for group naming (§5.5).

---

## 7. Open questions for Stu

1. **Is mobile a port or the primary target?** If phones are the eventual home, Phase 2's dp work
   should land before more desktop UI is built on the fixed-pixel toolkit — every new panel adds to
   the conversion debt.
2. **Does the gene editor belong on a phone at all?** It's a spreadsheet-shaped tool. A viable
   cheaper path: mobile is **view + play** (info panel read-only, grouped genes, no field editing),
   with authoring staying on desktop. That deletes most of Phase 3 and all of §5.5. Worth deciding
   before building the modal flow.
3. **Tablet vs phone.** A 10" tablet in landscape is ~1024dp wide — the current two-column design
   *would* fit there with only Phase 2's density work. If tablets are acceptable as "mobile", Phase 3
   collapses dramatically.
4. **Campaign on mobile** — port, or desktop-only onboarding? This is the difference between a
   sandbox and a game on the platform where most newcomers would meet it.
