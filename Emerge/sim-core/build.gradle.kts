plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget()
    jvm()
    js(IR) { browser() }

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "org.emerge.sim.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
}
