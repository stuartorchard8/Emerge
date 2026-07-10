plugins {
    id("buildsrc.convention.desktop-app")
}

buildDir = file("$rootDir/.build/norns-desktop")

dependencies {
    implementation(project(":apps:norns:core"))
    implementation(project(":engine:sim:core"))
}

application {
    mainClass = "org.emerge.desktop.NornsConsoleMainKt"
}

tasks.register<JavaExec>("runNornsSwing") {
    group = "application"
    description = "Watch Norns live in a Java2D window (the renderer whose look is iterated via PNG). " +
        "Arrow/A-D pan (free look), F follow, left-click a Norn to follow, right-click drop food, P pause, [ / ] speed. --args=\"<seed>\"."
    mainClass = "org.emerge.desktop.NornsSwingViewKt"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runNornsAnim") {
    group = "application"
    description = "Norn animation viewer/tweaker: render one procedural Norn and tune every AnimParams " +
        "dial live (proportions, shading, per-action motion), then Export the values as Kotlin. " +
        "Space play/pause, ←/→ scrub, [ / ] speed."
    mainClass = "org.emerge.desktop.NornsAnimViewerKt"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("renderNorns") {
    group = "application"
    description = "Render PNG frames of the Norns world headlessly (CPU/Java2D). " +
        "--args=\"<outDir> <seed> <tick1,tick2,...> [baked]\" (pass 'baked' for the SDF-baked creatures)"
    mainClass = "org.emerge.desktop.NornsImageRendererKt"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("renderNornsBaked") {
    group = "application"
    description = "Render the Norns world headlessly with the SDF-baked side-profile creatures " +
        "(genes→3D→2D sprite pipeline). --args=\"<outDir> <seed> <ticks>\" (baked is forced on)."
    mainClass = "org.emerge.desktop.NornsImageRendererKt"
    classpath = sourceSets["main"].runtimeClasspath
    args("build/norns-baked", "7", "250,700,1200", "baked")
}

tasks.register<JavaExec>("benchNorns") {
    group = "verification"
    description = "Headless Norns performance probe: sim tick, SDF bake, and frame (baked vs flat)."
    mainClass = "org.emerge.desktop.NornsBenchmarkKt"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Dnorns.prof")
}

tasks.register<JavaExec>("runMorphLab") {
    group = "application"
    description = "MorphLab: live authoring tool for the creature baseline — sculpt the genome (parts, " +
        "offsets, size, mirror), pick a mood to watch it emote, set fur, save/load .morph genomes."
    mainClass = "org.emerge.desktop.MorphLabKt"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("checkCreatureRender") {
    group = "verification"
    description = "Verify the consolidated CreatureRenderer: bake the baseline genome across moods. --args=\"<png>\""
    mainClass = "org.emerge.desktop.CreatureRendererCheckKt"
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runRigCheck") {
    group = "verification"
    description = "Verify part-bake → NornRig: bake a genome's parts + composite a walk cycle. --args=\"<png> <morph>\""
    mainClass = "org.emerge.desktop.RigCheckKt"
    classpath = sourceSets["main"].runtimeClasspath
}
