// :engine:audio:synth — sound made from numbers, with no idea what game is asking.
//
// Common code only: an oscillator, a noise source and a filter are arithmetic, and the same
// arithmetic has to run on a desktop, a phone and a browser tab. What a *host* supplies is somewhere
// to put the samples — see the streaming sinks beside `:engine:audio:openal`.
plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

buildDir = file("$rootDir/.build/engine-audio-synth")

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
    }
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
    namespace = "org.emerge.audio.synth"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
