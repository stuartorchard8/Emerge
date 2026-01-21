# Emerge

This repo is a Kotlin Multiplatform foundation for a game engine (simulation + networking) with demo hosts for desktop + Android.

### Repo layout (how to navigate)

- **`engine/`**: reusable engine modules (simulation + networking)
- **`demos/`**: sample implementations that exercise engine modules (currently: physics)
- **`platform/`**: platform hosts / apps (desktop + Android)
- **`buildSrc/`**: Gradle convention plugins
- **`gradle/`**: version catalog + wrapper

### Gradle modules (what depends on what)

#### Core layering

- **Transports**
  - `:engine:net:api` (base `Pipe` + byte codecs)
  - `:engine:net:transports:loopback` → `:engine:net:api`
  - `:engine:net:transports:tcp` → `:engine:net:api`

- **Simulation**
  - `:engine:sim:core` (deterministic tick + torus + toy physics)
  - `:engine:sim:sync` → `:engine:sim:core`, `:engine:net:api`
  - `:engine:sim:codecs:physics` → `:engine:sim:core`, `:engine:sim:sync`, `:engine:net:api`

- **Demos**
  - `:demos:physics` → `:engine:sim:*`, `:engine:net:api`, `:engine:net:transports:tcp`

- **Platform hosts**
  - `:platform:desktop-app` → `:demos:physics` (+ LWJGL)
  - `:platform:android-app` → `:demos:physics`

#### Visual map

```mermaid
graph TD
  subgraph engine
    net_api[engine:net:api]
    net_loop[engine:net:transports:loopback]
    net_tcp[engine:net:transports:tcp]
    sim_core[engine:sim:core]
    sim_sync[engine:sim:sync]
    sim_codec_phys[engine:sim:codecs:physics]
  end

  subgraph demos
    demo_phys[demos:physics]
  end

  subgraph platform
    desktop[platform:desktop-app]
    android[platform:android-app]
  end

  net_loop --> net_api
  net_tcp --> net_api

  sim_sync --> sim_core
  sim_sync --> net_api
  sim_codec_phys --> sim_core
  sim_codec_phys --> sim_sync
  sim_codec_phys --> net_api

  demo_phys --> sim_core
  demo_phys --> sim_sync
  demo_phys --> sim_codec_phys
  demo_phys --> net_api
  demo_phys --> net_tcp

  desktop --> demo_phys
  android --> demo_phys
```

### Build & run

Use the Gradle Wrapper:

- **Build everything**: `./gradlew build`
- **Desktop**: `./gradlew :platform:desktop-app:run`
- **Android debug**: `./gradlew :platform:android-app:installDebug`

### Kotlin Multiplatform notes (source set hierarchy)

This repo **adopts the default Kotlin MPP hierarchy template** (so Android/JVM/JS source sets follow Kotlin’s standard layout).

Some modules intentionally add a single shared source set called **`jvmAndAndroidMain`** (with sources in
`src/jvmAndAndroidMain/kotlin`) for “JVM-only glue” that should compile on both desktop JVM and Android.

### Roadmap

#### Must-do (correctness + maintainability)

- [x] **Make `DelegatingPipe` truly thread-safe in common code** (implemented via `expect/actual` `AtomicRef`)
- [x] **Adopt the default Kotlin MPP hierarchy template**
- [x] **Rename desktop package/main class** (`org.emerge.desktop.AppKt`)

#### Should-do (reduce friction)

- [ ] **Tame Windows build dir workaround**: timestamped `buildDir` avoids file locks but hurts caching; consider a narrower workaround

#### Nice-to-have (engine direction)

- [ ] Consider extracting reusable rendering utilities from `:demos:physics` into `engine/render/*` if they’re meant to be engine features
- [ ] Add a `:platform:web-app` host to match the existing JS targets (even a minimal harness)