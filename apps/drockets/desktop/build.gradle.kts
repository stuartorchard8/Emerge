plugins {
    id("buildsrc.convention.desktop-app")
}

buildDir = file("$rootDir/.build/drockets-desktop")

dependencies {
    implementation(project(":apps:drockets:core"))
    implementation(project(":engine:render:torus"))
    implementation(project(":engine:sim:core"))
    implementation(project(":engine:sim:sync"))
    implementation(project(":engine:sim:codecs:ecs"))
    implementation(project(":engine:net:api"))
    implementation(project(":engine:net:transports:loopback"))
}

application {
    mainClass = "org.emerge.desktop.DrocketsMainKt"
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
