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

- [ ] **Step 3 — split `platform/desktop-app` into per-app desktop modules.**
  Sort the ~35 files: per-game mains/benchmarks/probes → `apps/X/desktop`; genuinely
  cross-game host glue (GL scene view, input, window plumbing) → `engine/host/desktop`.
  Per-game run tasks become `:apps:X:desktop:run`. Delete `platform/desktop-app`.

- [ ] **Step 4 — split `platform/web-app` into per-app web modules.**
  Per-app Kotlin/JS entry points (`apps/{scavengers,cyto}/web`); shared canvas/host glue
  → `engine/host/web`. The `?demo=` switch dies — each app is its own bundle. Delete
  `platform/web-app` and the now-empty `platform/`.

- [ ] **Step 5 — reference sweep.**
  CI workflows, README (layout, module list, mermaid map, run commands), docs/, agent
  memory. Add/verify nothing depends across apps.

- [ ] **Step 6 — tidy-up pass + removal/promotion proposal.**
  With the structure clean, walk the per-app desktop/tool files and propose two buckets
  for Stu's review (no unilateral deletion): (a) candidates to REMOVE entirely
  (stale probes/benchmarks/one-off checks), (b) candidates to PROMOTE into the engine
  (generic tooling like UIGallery / sprite atlas plumbing / image renderers).

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
