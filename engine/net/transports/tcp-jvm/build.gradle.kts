plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    api(project(":engine:net:api"))
    testImplementation(kotlin("test"))
}

