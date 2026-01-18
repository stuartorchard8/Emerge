plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    api(project(":net-api"))
    testImplementation(kotlin("test"))
}

