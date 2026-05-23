# Emerge

This repo is a Kotlin Multiplatform foundation for a game engine (simulation + networking) with demo hosts for desktop + Android.

### Repo layout (how to navigate)

- **`engine/`**: reusable engine modules (simulation + networking)
- **`demos/`**: sample implementations that exercise engine modules (currently: scavengers, drockets)
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
  - `:demos:scavengers` → `:engine:sim:*`, `:engine:net:api`, `:engine:net:transports:tcp`
  - `:demos:drockets` → `:engine:sim:*`, `:engine:render:torus`

- **Platform hosts**
  - `:platform:desktop-app` → `:demos:scavengers`, `:demos:drockets` (+ LWJGL)
  - `:platform:android-app` → `:demos:scavengers`

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
    demo_scav[demos:scavengers]
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

  demo_scav --> sim_core
  demo_scav --> sim_sync
  demo_scav --> sim_codec_phys
  demo_scav --> net_api
  demo_scav --> net_tcp

  desktop --> demo_scav
  android --> demo_scav
```

### Build & run

Use the Gradle Wrapper:

- **Build everything**: `./gradlew build`
- **Desktop**: `./gradlew :platform:desktop-app:run`
- **Android debug**: `./gradlew :platform:android-app:installDebug`

### Kotlin Multiplatform notes (source set hierarchy)

This repo **adopts the default Kotlin MPP hierarchy template** (so Android/JVM source sets follow Kotlin’s standard layout).

Some modules intentionally add a single shared source set called **`jvmAndAndroidMain`** (with sources in
`src/jvmAndAndroidMain/kotlin`) for “JVM-only glue” that should compile on both desktop JVM and Android.

### Windows notes (AV / file locking)

On some Windows setups, real-time AV/indexers can hold Gradle/AGP intermediates open (common symptom: failing to delete something like `R.jar`).

This repo uses **stable** `.build/<module>` directories (no per-run timestamped build dirs).
If you hit file locks, the intended fix is to **exclude the repo’s `.build/` directory from real-time scanning**.


### Roadmap

- [ ] Set up a remote server to host the backend and web frontend
- [ ] Merge Drockets repo into Emerge

#### Experiments

- [ ] Integer sine and cosine functions for converting angle+magnitude to a vector
- [ ] Networking connection that transfers only transform positions per force-affected entity. 
Client-side can infer velocity and possibly forces to apply when packets are sparse.
- [ ] Merge Cyto repo into Emerge

