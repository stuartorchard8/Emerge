// :apps:template:desktop — the desktop host. A GLFW window around :apps:template:core.
//
// The `desktop-app` convention brings in the JVM toolchain, the `application` plugin and the LWJGL
// natives for whatever machine is doing the building, so this file only names the app's own
// dependencies, its main class, and any extra entry points worth a Gradle task.
plugins {
    id("buildsrc.convention.desktop-app")
}

buildDir = file("$rootDir/.build/template-desktop")

dependencies {
    implementation(project(":apps:template:core"))
    implementation(project(":engine:render:torus"))
    implementation(project(":engine:sim:core"))
}

application {
    mainClass = "org.emerge.desktop.TemplateMainKt"
}

// Extra entry points go here, one `JavaExec` each — this is how the other apps get their headless
// renderers, benchmarks, conservation checks and agent harnesses. Registering them as Gradle tasks
// (rather than leaving them as classes you remember) is what makes them usable from CI and by an
// agent. Cyto's build file is worth copying from when you want a scripted, screenshotting harness.
//
// tasks.register<JavaExec>("benchTemplate") {
//     group = "verification"
//     description = "Headless tick profiler: runs N ticks and prints the per-phase breakdown."
//     mainClass = "org.emerge.desktop.TemplateBenchmarkKt"
//     classpath = sourceSets["main"].runtimeClasspath
//     workingDir = rootDir
// }
