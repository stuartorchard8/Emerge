// :apps:outofspace:desktop — the desktop host. A GLFW window around :apps:outofspace:core.
//
// The `desktop-app` convention brings in the JVM toolchain, the `application` plugin and the LWJGL
// natives for whatever machine is doing the building, so this file only names the app's own
// dependencies, its main class, and any extra entry points worth a Gradle task.
plugins {
    id("buildsrc.convention.desktop-app")
}

buildDir = file("$rootDir/.build/outofspace-desktop")

dependencies {
    implementation(project(":apps:outofspace:core"))
    implementation(project(":engine:render:torus"))
    implementation(project(":engine:sim:core"))
}

application {
    mainClass = "org.emerge.desktop.OutofspaceMainKt"
}

// Extra entry points go here, one `JavaExec` each — this is how the other apps get their headless
// renderers, benchmarks, conservation checks and agent harnesses. Registering them as Gradle tasks
// (rather than leaving them as classes you remember) is what makes them usable from CI and by an
// agent.
tasks.register<JavaExec>("outofspaceAgent") {
    group = "application"
    description = "Headless, script-driven Out of Space harness for agents/CI: build, run the world " +
        "for a stated number of ticks, and observe it as ASCII fields, per-tile probes, JSON totals or " +
        "a real GL screenshot. --args=\"<scriptFile|-> [outDir]\" (outDir default: agent-out)."
    mainClass = "org.emerge.desktop.OutofspaceAgentHarnessKt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir   // so save paths and agent-out resolve from the repo root
    standardInput = System.`in`   // so `--args=-` can take a script on stdin
    for (key in listOf("oos.agent.w", "oos.agent.h"))
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
}

tasks.register<JavaExec>("benchOutofspace") {
    group = "verification"
    description = "Headless tick profiler: where the tick's time goes, and how much of it scales " +
        "with Species.COUNT. Run it, append filler species to the Species enum, run it again — the " +
        "subsystem that grows fastest is the one worth making sparse. --args=\"[ticks] [innerReps]\"."
    mainClass = "org.emerge.desktop.OutofspaceBenchKt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}
