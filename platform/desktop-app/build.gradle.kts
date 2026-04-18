import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    application
}

dependencies {
    implementation(project(":demos:physics"))
    implementation(project(":demos:drockets"))
    implementation(project(":engine:render:torus"))
    implementation(project(":engine:sim:core"))
    implementation(project(":engine:sim:sync"))
    implementation(project(":engine:sim:codecs:physics"))
    implementation(project(":engine:net:transports:loopback"))
    implementation(project(":engine:net:transports:tcp"))
    implementation(project(":engine:net:transports:websocket"))

    // LWJGL (desktop GPU rendering)
    // Minimal set: glfw + opengl + core + natives (Windows).
    implementation(platform("org.lwjgl:lwjgl-bom:3.3.3"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-openal")
    implementation("org.lwjgl:lwjgl-stb")
    runtimeOnly("org.lwjgl:lwjgl::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-glfw::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-opengl::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-openal::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-stb::natives-windows")
}

application {
    // `Main.jvm.kt` → JVM facade class `Main_jvmKt` (file-level `main()`).
    mainClass = "org.emerge.desktop.Main_jvmKt"
}

// Avoid failing packaging when two dependencies contribute same-named jars
// (seen with some multiplatform variants in the distribution lib/ folder).
tasks.withType<Tar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.withType<Zip>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    from("$rootDir/assets") {
        into("assets")
    }
}

tasks.register<JavaExec>("runDrockets") {
    group = "application"
    description = "Run the Drockets demo"
    mainClass = "org.emerge.desktop.Main_jvmKt"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Demerge.mode=drockets")
}

tasks.register<JavaExec>("profileSim") {
    group = "application"
    description = "Run headless sim profiler with per-system timing breakdown"
    mainClass = "org.emerge.desktop.ProfileMainKt"
    classpath = sourceSets["main"].runtimeClasspath

    val jfrFile = layout.buildDirectory.file("profile.jfr").get().asFile
    jvmArgs(
        "-XX:StartFlightRecording=duration=60s,filename=${jfrFile.absolutePath}",
    )

    doLast {
        if (jfrFile.exists()) {
            println("\nJFR recording saved to: ${jfrFile.absolutePath}")
            println("Open in IntelliJ: Run > Open Profiler Snapshot")
        }
    }
}

tasks.register<JavaExec>("benchDrockets") {
    group = "verification"
    description = "Headless Drockets simulation benchmark (sequential vs parallel, per-phase timings). " +
        "Pass --args=\"<drocketCount> [warmupTicks] [measureTicks]\""
    mainClass = "org.emerge.desktop.DrocketsBenchmarkKt"
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

tasks.register<JavaExec>("benchDrocketsZgc") {
    group = "verification"
    description = "Headless Drockets benchmark running under ZGC (low-pause collector) for tail-latency comparison"
    mainClass = "org.emerge.desktop.DrocketsBenchmarkKt"
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`

    jvmArgs("-XX:+UseZGC", "-XX:+ZGenerational")
}

tasks.register<JavaExec>("benchDrocketsJfr") {
    group = "verification"
    description = "Headless Drockets benchmark with a JFR recording (for IntelliJ / JMC inspection)"
    mainClass = "org.emerge.desktop.DrocketsBenchmarkKt"
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`

    val jfrFile = layout.buildDirectory.file("drockets-bench.jfr").get().asFile
    jvmArgs(
        "-XX:StartFlightRecording=duration=300s,filename=${jfrFile.absolutePath},settings=profile",
    )

    doLast {
        if (jfrFile.exists()) {
            println("\nJFR recording saved to: ${jfrFile.absolutePath}")
            println("Open in IntelliJ: Run > Open Profiler Snapshot")
        }
    }
}

tasks.register<JavaExec>("benchDrocketsGcLog") {
    group = "verification"
    description = "Headless Drockets benchmark with GC logging to stdout (fast GC sanity check)"
    mainClass = "org.emerge.desktop.DrocketsBenchmarkKt"
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`

    jvmArgs("-Xlog:gc*:stdout:time,uptime,level,tags")
}
