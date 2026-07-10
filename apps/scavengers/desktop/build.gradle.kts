plugins {
    id("buildsrc.convention.desktop-app")
}

buildDir = file("$rootDir/.build/scavengers-desktop")

dependencies {
    implementation(project(":apps:scavengers:core"))
    implementation(project(":engine:render:torus"))
    implementation(project(":engine:sim:core"))
    implementation(project(":engine:sim:sync"))
    implementation(project(":engine:sim:codecs:ecs"))
    implementation(project(":engine:net:api"))
    implementation(project(":engine:net:transports:loopback"))
    implementation(project(":engine:net:transports:tcp"))
    implementation(project(":engine:net:transports:websocket"))
}

application {
    // `Main.jvm.kt` → JVM facade class `Main_jvmKt` (file-level `main()`).
    mainClass = "org.emerge.desktop.Main_jvmKt"
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
