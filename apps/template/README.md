# App template

The starting point for a new Emerge app. Copy it with:

```bash
tools/new-app.sh mygame
./gradlew :apps:mygame:desktop:run
```

That gives you a running window with a toroidal world of drifting bodies, a working HUD, pan/zoom,
click-to-spawn, an APK target, a web target, and seven passing sim tests — all of it yours to
delete. Nothing links back here afterwards.

The template is itself in the build (`settings.gradle.kts`), so an engine API change breaks it here,
where it is noticed, rather than the next time somebody copies it.

## What you get

```
apps/template/
  core/      commonMain — the sim, the renderer, the UI. Runs on all three platforms.
    src/commonMain/kotlin/org/emerge/demo/template/
      TemplateSim.kt          the reducer: config, input, state, rules   ← start here
      TemplateController.kt   real time -> fixed-timestep sim ticks
      TemplateRenderer.kt     camera + instanced disc drawing
      TemplateHud.kt          the immediate-mode UI panels
    src/commonTest/…          determinism, wrapping, input-ordering tests
  desktop/   a GLFW window (LWJGL)
  android/   an Activity + GLSurfaceView
  web/       a canvas + WebGL2
```

Each file's header comment explains the rule it is demonstrating; that is where the real
documentation lives.

## The one idea to keep

The simulation is a **pure reducer**: `reduce(cfg, state, inputs) -> state`. It may not read
wall-clock time, platform `Random`, mutable globals, or the filesystem, and nothing outside it may
mutate the state. Player intent enters as input *values*, never as direct writes.

Hold that line and you get, without further work:

- **Replay and save/load** — a seed plus an input log reconstructs any moment exactly.
- **Headless tests** — the whole game runs in `jvmTest` in milliseconds, no window, no device.
- **Golden tests** — hash the world after N ticks and any unintended change to the sim fails loudly.
- **Multiplayer** — lockstep peers running the same reducer over the same inputs stay in sync.

Break it and all four break at once, usually silently and much later.

Two corollaries the template shows in code: fold per-player inputs **sorted by `PlayerId`** (map
iteration order differs between peers), and advance in **fixed timesteps** (a variable step makes
the result depend on frame rate).

## Where to look when you outgrow it

| You want | Read |
| --- | --- |
| Thousands to millions of entities | `engine/sim/core/…/ecs/soa` — structure-of-arrays ECS. Cyto is the worked example. |
| Your own shader | `apps/cyto/core/.../CytoCellShader.kt`; add `registerShaderCodegen(...)` to your core build file and drop `.vert`/`.frag` files in `src/commonMain/shaders/`. |
| Every widget the UI toolkit has | `./gradlew :engine:render:ui-gallery:run`, and `UIGallery.kt` beside it. |
| Physics: springs, contacts, fixed-point | `engine/sim/core/…/physics`. Note `Frac` holds roughly ±2 — large constants overflow. |
| Multiplayer | `engine/net`, `engine/sim/sync`; uncomment the deps in the core build file. |
| A sim thread separate from the draw thread | Cyto's `CytoSimDriver`. The draw thread must never block on the sim's lock. |
| A headless, scriptable harness for CI or an agent | Cyto's `cytoAgent` task and `apps/cyto/agent-scripts/`. |

## Conventions worth keeping

- Build output goes to `.build/<module>/`, not into `apps/`.
- Apps never depend on other apps. Shared code belongs in `engine/`, and only if it is genuinely
  game-agnostic.
- No test slower than five seconds.
