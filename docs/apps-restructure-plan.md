# Apps restructure plan — per-app deployables, no shared hosts

**Goal:** every app is self-contained and independently deployable. Apps share only the
engine as a dependency; there is no multi-app deployment (the old `desktop-app` /
`web-app` grab-bag hosts go away).

**Target layout:**

```
engine/                    ← game-agnostic substrate, incl. shared host glue (engine/host/*)
apps/
  cyto/       {core, desktop, android, web}
  scavengers/ {core, desktop, android, web}
  drockets/   {core, desktop}
  norns/      {core, desktop}
```

**Dependency rule (the invariant this buys):** `apps/<game>/*` may depend only on
`apps/<game>/core` and `engine/*` — never on another app.

One commit per step; each step leaves the build green.

## Steps

- [x] **Step 1 — nest game logic as `apps/<game>/core`.**
  Move each `apps/X/{build.gradle.kts,src}` into `apps/X/core/`; game docs (PERF.md etc.)
  stay at `apps/X/`. Coordinates `:apps:X` → `:apps:X:core`; update all references
  (settings, platform build files, CI, docs, `.build/` dir names `demo-X` → `X-core`).

- [x] **Step 2 — move Android shells under their games.**
  `platform/android/{cyto,scavengers}` → `apps/{cyto,scavengers}/android`
  (`:platform:android:X` → `:apps:X:android`). Update settings, CI, README.

- [x] **Step 3 — split `platform/desktop-app` into per-app desktop modules.**
  Sort the ~43 files: per-game mains/benchmarks/probes → `apps/X/desktop`. Finding: there
  was NO genuinely cross-game glue (`DesktopGlSceneView`/`DesktopLauncher` are scavengers-
  only; `InputAxis` is dead) — so no `engine/host/desktop` module was needed; the shared
  LWJGL/application boilerplate became the `buildsrc.convention.desktop-app` convention
  plugin instead. Run tasks are `:apps:X:desktop:run` (+ each app's bench/probe tasks).
  App data files (`.gene`/`.bin`/`.morph` saves, prefs) moved into their app's desktop dir;
  save-path literals updated. `platform/desktop-app` deleted.

- [x] **Step 4 — split `platform/web-app` into per-app web modules.**
  `apps/{scavengers,cyto}/web`, each its own Kotlin/JS browser bundle with its own
  `index.html`; the `?demo=` switch died (CytoWeb got its own `main` doing GPU.init).
  As with desktop, no shared glue worth an `engine/host/web` module — the canvas/GL
  bootstrap is ~4 lines per app. `platform/` deleted entirely.

- [x] **Step 5 — reference sweep.**
  CI workflows, README (layout, module list, mermaid map, run commands), docs/
  (norns DESIGN.md, cyto PERF.md), agent memory all updated to the new coordinates;
  stray root `drockets-save-0.bin` moved home. Verified per-app dependency rule holds:
  every launcher depends only on its own core + engine modules. Module tree now exactly
  matches the target layout.
- 2026-07-10: step 6 tidy-up done + buckets written (above); restructure COMPLETE apart
  from Stu's verdict on the buckets.
- 2026-07-10: buckets executed on Stu's go-ahead (`33be2593` remove, promote follows).
  Removed: 6 cyto probes, 2 norns checks, scavengers profileSim, 4 drockets bench
  flag-variants, `.cursor/plans`. **Save/genome files kept — Stu has plans for saves.**
  Promoted: UIGallery + UIGallerySnapshot → new `:engine:render:ui-gallery` dev-tool
  module (`runUIGallery` = its `run` task, `renderUIGallery` for the PNG snapshot),
  fully decoupled from cyto (CytoControls sample usage stripped); verified via rendered
  snapshot. Sprite-atlas loaders stay per-app (flag only). Plan CLOSED.

- [x] **Step 6 — tidy-up pass + removal/promotion proposal.**
  Tidy-up done: deleted `utils/` (untouched Gradle-template boilerplate, `org.example`,
  not in settings); moved `emerge-modularization-plan.md` → `docs/`. (`InputAxis.kt`
  looked dead but its `axis()` helper is used by `DesktopGlSceneView` — kept.)
  Buckets below await Stu's decision.

## Step 6 buckets — for review

### (a) REMOVE candidates (one-off diagnostics whose findings are already logged)

Each is a standalone main + gradle task; deleting one is a 2-minute, fully reversible
git revert. Ordered by confidence:

- `apps/drockets/desktop`: the 4 flag-variant bench tasks (`benchDrocketsZgc/Jfr/GcLog/
  OverlayGcLog`) — same main as `benchDrockets`, just different jvmArgs; trivially
  recreated when needed.
- `apps/cyto/desktop`: `CytoDragProbe`, `CytoGrabProbe`, `CytoLocomotionProbe`,
  `CytoPopulationProbe`, `CytoSaveAnalysis`, `CytoGrowthProfile` — investigation probes
  from past sessions; outcomes are recorded in PERF.md / plan docs. (`CytoBenchmark`,
  `CytoSaveBenchmark`, `CytoConservationCheck`, and the two image renderers still earn
  their keep.)
- `apps/norns/desktop`: `RigCheck`, `CreatureRendererCheck` — verification one-offs for
  now-landed work.
- `apps/scavengers/desktop`: `ProfileMain`/`profileSim` — JFR profiler predating the
  per-app bench harnesses; superseded?
- The save/genome zoo in `apps/cyto/desktop/` (17 tracked `.gene`, dated `.bin` saves,
  `cyto-save.bak`) — propose: keep the curated genome library, delete dated/`.bak` saves
  (or move the keepers to a `genomes/` subdir and gitignore all `.bin`).
- `.cursor/plans/` — stale editor artifacts referencing the old layout.

### (b) PROMOTE-to-engine candidates

- `UIGallery` + `UIGallerySnapshot` (currently `apps/cyto/desktop`): a shared-UI-toolkit
  gallery whose ONLY cyto import is `CytoControls` as a sample widget. Swap the sample for
  a generic panel and this becomes an engine tool — natural home: a small
  `engine/render/torus` dev-tool module (or jvmTest fixture). Right now the UI toolkit has
  no engine-side showcase at all.
- `DrocketsSpriteAtlas`/`KnightSpriteAtlas` (drockets desktop): PNG-strip → GL atlas
  loading is generic; if scavengers or norns ever need sprite atlases on desktop, extract
  the loader into `engine/render/torus` and keep only the sprite lists per app. Not worth
  it pre-emptively (modularize-over-generalize) — flag only.
- Headless PNG world-renderers (`CytoImageRenderer`, `NornsImageRenderer`): the
  "tick N, render to PNG" harness shape recurs per app but the rendering is app-specific;
  leave as-is.

## Log

- 2026-07-10: plan written; prior commit `92c9e131` already did demos→apps +
  `platform/android/{cyto,scavengers}` (step 2 is now a trivial move).
- 2026-07-10: step 1 done. `apps/X/{build.gradle.kts,src}` → `apps/X/core/`; game docs
  stayed at `apps/X/`; norns `reference/` moved into core (used by tests). Coordinates
  `:apps:X` → `:apps:X:core`, `.build/demo-X` → `.build/X-core`. Side find: cyto
  commonMain had JVM-only `::class.java` (JS compile was silently broken) — fixed
  separately before this step's verification.
- 2026-07-10: step 2 done. `:apps:{cyto,scavengers}:android` build green;
  `platform/` now holds only the legacy desktop/web hosts.
