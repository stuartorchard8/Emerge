plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget()
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

android {
    namespace = "org.emerge.sim.sync"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
}
