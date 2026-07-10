plugins {
    id("buildsrc.convention.desktop-app")
}

buildDir = file("$rootDir/.build/cyto-desktop")

dependencies {
    implementation(project(":apps:cyto:core"))
    implementation(project(":engine:render:torus"))
    implementation(project(":engine:sim:core"))
    implementation(project(":engine:sim:sync"))
    implementation(project(":engine:net:api"))
    implementation(project(":engine:net:transports:loopback"))
}

application {
    mainClass = "org.emerge.desktop.CytoMainKt"
}

tasks.register<JavaExec>("renderCyto") {
    group = "application"
    description = "Render the Cyto world headlessly (light-field heatmap + cells) to a PNG. " +
        "--args=\"<outPng> <ticks>\" (defaults: build/cyto-field.png, 400)."
    mainClass = "org.emerge.desktop.CytoImageRendererKt"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("renderCytoMatter") {
    group = "application"
    description = "Render the Cyto matter quad-tree headlessly (bordered leaf squares + cells) to a PNG. " +
        "--args=\"<outPng> <ticks>\" (defaults: build/cyto-matter.png, 1200)."
    mainClass = "org.emerge.desktop.CytoMatterImageRendererKt"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("checkCytoConservation") {
    group = "application"
    description = "Load a Cyto save, tally per-element atoms, run N ticks, re-tally — reports any leak. " +
        "--args=\"<savePath> <ticks>\" (defaults: apps/cyto/desktop/cyto-save.bin, 1000)."
    mainClass = "org.emerge.desktop.CytoConservationCheckKt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir   // so the default relative save path resolves from the repo root
}

tasks.register<JavaExec>("benchCyto") {
    group = "verification"
    description = "Headless per-phase Cyto tick profiler: loads a save (or 'fresh'), warms the JIT, " +
        "and prints the per-phase time breakdown + GC pressure, sequential and parallel. " +
        "--args=\"<savePath|fresh> [warmupTicks] [measureTicks]\"."
    mainClass = "org.emerge.desktop.CytoBenchmarkKt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}

tasks.register<JavaExec>("benchCytoSave") {
    group = "verification"
    description = "Benchmark Cyto save/encode/decode: phase-by-phase timing for encode, decode, and round-trip. " +
        "--args=\"<savePath|fresh> [warmupIters] [measureIters]\""
    mainClass = "org.emerge.desktop.CytoSaveBenchmarkKt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}
