// :apps:fluidlab:desktop — the desktop host. A GLFW window around :apps:fluidlab:core.
//
// The `desktop-app` convention brings in the JVM toolchain, the `application` plugin and the LWJGL
// natives for whatever machine is doing the building, so this file only names the app's own
// dependencies, its main class, and any extra entry points worth a Gradle task.
plugins {
    id("buildsrc.convention.desktop-app")
}

buildDir = file("$rootDir/.build/fluidlab-desktop")

dependencies {
    implementation(project(":apps:fluidlab:core"))
    implementation(project(":engine:render:torus"))
    implementation(project(":engine:sim:core"))
}

application {
    mainClass = "org.emerge.desktop.FluidlabMainKt"
}

// Extra entry points go here, one `JavaExec` each — this is how the other apps get their headless
// renderers, benchmarks, conservation checks and agent harnesses. Registering them as Gradle tasks
// (rather than leaving them as classes you remember) is what makes them usable from CI and by an
// agent. Cyto's build file is worth copying from when you want a scripted, screenshotting harness.
//
tasks.register<JavaExec>("fluidlabAgent") {
    group = "application"
    description = "Headless, script-driven Fluidlab harness for agents/CI: build a situation, run it " +
        "for a stated number of ticks, and read it back as ASCII fields, per-tile probes or totals. " +
        "--args=\"<scriptFile|->\""
    mainClass = "org.emerge.desktop.FluidlabAgentHarnessKt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
    standardInput = System.`in`   // so `--args=-` can take a script on stdin
}

// tasks.register<JavaExec>("benchFluidlab") {
//     group = "verification"
//     description = "Headless tick profiler: runs N ticks and prints the per-phase breakdown."
//     mainClass = "org.emerge.desktop.FluidlabBenchmarkKt"
//     classpath = sourceSets["main"].runtimeClasspath
//     workingDir = rootDir
// }
