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

