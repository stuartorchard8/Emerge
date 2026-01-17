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
                api(project(":net-api"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "org.emerge.net.loopback"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
}
