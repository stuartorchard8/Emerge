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

tasks.register<JavaExec>("runUIGallery") {
    group = "application"
    description = "Run the UI widget gallery"
    mainClass = "org.emerge.desktop.UIGalleryMainKt"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("renderUIGallery") {
    group = "application"
    description = "Render UI Gallery as Java2D PNG (no OpenGL) → build/ui-gallery.png"
    mainClass = "org.emerge.desktop.UIGallerySnapshotKt"
    classpath = sourceSets["main"].runtimeClasspath
    args("build/ui-gallery.png")
    workingDir = rootProject.layout.buildDirectory.get().asFile
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

tasks.register<JavaExec>("probeCytoPopulation") {
    group = "verification"
    description = "Headless population probe: seed a self-sufficient (collect+divide) genome on a " +
        "light source, print population over time (does exposure cap growth?). --args=\"<ticks> <every>\"."
    mainClass = "org.emerge.desktop.CytoPopulationProbeKt"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("probeCytoLocomotion") {
    group = "verification"
    description = "Headless locomotion diagnostic: print total momentum / COM drift speed / kinetic " +
        "energy over time to tell a real gait from a momentum-creating artifact. " +
        "--args=\"<ticks> <every> <mutationRateDenom> <repulsion 0|1>\"."
    mainClass = "org.emerge.desktop.CytoLocomotionProbeKt"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("analyzeCytoSave") {
    group = "verification"
    description = "Load a Cyto save and dissect its self-propelling colonies (physics momentum vs " +
        "growth-creep) + dump their genomes. --args=\"<savePath> <ticks>\"."
    mainClass = "org.emerge.desktop.CytoSaveAnalysisKt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
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

tasks.register<JavaExec>("profileCytoGrowth") {
    group = "verification"
    description = "Grow a colony on the live SoA reducer at scaled nutrient levels, profiling the steady " +
        "tick vs population to find the real bottleneck near the 60fps budget. --args=\"<factorsCsv> [grow] [measure]\"."
    mainClass = "org.emerge.desktop.CytoGrowthProfileKt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}

tasks.register<JavaExec>("probeCytoGrab") {
    group = "verification"
    description = "Headless drag-stability diagnostic: grab a welded cluster and orbit the target, " +
        "logging max speed / kinetic energy / spring stretch / follow-lag (does dragging spike?). " +
        "--args=\"<ticks> <every> <cells> <orbitRadius> <orbitPeriod>\"."
    mainClass = "org.emerge.desktop.CytoGrabProbeKt"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("probeCytoDrag") {
    group = "verification"
    description = "Headless exposed-surface drag diagnostic: push a welded chain and log speed decay " +
        "(lone = full drag; push along-axis = slippery; push across-axis = draggy). " +
        "--args=\"<ticks> <every> <nCells> <vx> <vy>\"."
    mainClass = "org.emerge.desktop.CytoDragProbeKt"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("benchCytoSave") {
    group = "verification"
    description = "Benchmark Cyto save/encode/decode: phase-by-phase timing for encode, decode, and round-trip. " +
        "--args=\"<savePath|fresh> [warmupIters] [measureIters]\""
    mainClass = "org.emerge.desktop.CytoSaveBenchmarkKt"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}
