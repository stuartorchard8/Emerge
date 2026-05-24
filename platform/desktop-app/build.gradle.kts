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

/** LWJGL native classifier for the host this build is running on. */
val lwjglNatives: String = run {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    when {
        osName.contains("windows") ->
            if (osArch == "aarch64") "natives-windows-arm64" else "natives-windows"
        osName.contains("mac") || osName.contains("darwin") ->
            if (osArch == "aarch64") "natives-macos-arm64" else "natives-macos"
        osName.contains("linux") || osName.contains("freebsd") -> when {
            osArch == "aarch64" -> "natives-linux-arm64"
            osArch.startsWith("arm") -> "natives-linux-arm32"
            else -> "natives-linux"
        }
        else -> error("Unsupported OS for LWJGL natives: '$osName' / '$osArch'")
    }
}

dependencies {
    implementation(project(":demos:scavengers"))
    implementation(project(":demos:drockets"))
    implementation(project(":engine:render:torus"))
    implementation(project(":engine:sim:core"))
    implementation(project(":engine:sim:sync"))
    implementation(project(":engine:sim:codecs:ecs"))
    implementation(project(":engine:net:api"))
    implementation(project(":engine:net:transports:loopback"))
    implementation(project(":engine:net:transports:tcp"))
    implementation(project(":engine:net:transports:websocket"))

    // LWJGL (desktop GPU rendering).
    // Native libraries are platform-specific; classifier picked from the build host
    // so a Linux build pulls liblwjgl.so, a macOS build pulls liblwjgl.dylib, etc.
    implementation(platform("org.lwjgl:lwjgl-bom:3.3.3"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-openal")
    implementation("org.lwjgl:lwjgl-stb")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-openal::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-stb::$lwjglNatives")
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

tasks.register<JavaExec>("benchDrocketsOverlay") {
    group = "verification"
    description = "Headless overlay-cost benchmark: loads a save and times the filter cache + monotone wrapper + force solver. " +
        "Pass --args=\"<savePath> [warmup] [measure] [filter]\""
    mainClass = "org.emerge.desktop.DrocketsOverlayBenchmarkKt"
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    workingDir = rootDir
}

tasks.register<JavaExec>("benchDrocketsOverlayGcLog") {
    group = "verification"
    description = "Overlay benchmark with GC logging to stdout — diagnose whether spikes are GC pauses."
    mainClass = "org.emerge.desktop.DrocketsOverlayBenchmarkKt"
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
    workingDir = rootDir
    jvmArgs("-Xlog:gc*:stdout:time,uptime,level,tags")
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