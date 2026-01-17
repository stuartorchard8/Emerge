plugins {
    id("buildsrc.convention.kotlin-mpp")
}

kotlin {
    jvm()
    js(IR) { browser() }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":sim-core"))
                api(project(":net-api"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":net-loopback"))
            }
        }
    }
}

