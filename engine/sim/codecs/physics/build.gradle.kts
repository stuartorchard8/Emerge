plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

// Build dir strategy (Windows-friendly):
// - Default: stable build dir for good caching and predictable disk usage.
// - If you hit Windows file locks (AV/indexers holding intermediates), set:
//   `-Pemerge.uniqueBuildDir=true`
//   (and consider excluding `.build/` from real-time scanning).
val uniqueBuildDir: Boolean = (findProperty("emerge.uniqueBuildDir") as String?)?.toBoolean() ?: false
val buildDirName = if (uniqueBuildDir) "sim-physics-codec-${System.currentTimeMillis()}" else "sim-physics-codec"
buildDir = file("$rootDir/.build/$buildDirName")

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
        commonMain {
            dependencies {
                api(project(":engine:sim:core"))
                api(project(":engine:sim:sync"))
                api(project(":engine:net:api"))
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
    namespace = "org.emerge.sim.codec.physics"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

