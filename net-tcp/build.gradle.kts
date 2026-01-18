plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

// Workaround for Windows file locking on Gradle/AGP intermediates (classes.jar/R.jar).
// Use a fresh build directory per invocation so tasks don't need to overwrite previously-locked outputs.
buildDir = file("$rootDir/.build/net-tcp-${System.currentTimeMillis()}")

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
    namespace = "org.emerge.net.tcp"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

