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
val buildDirName = if (uniqueBuildDir) "demo-physics-${System.currentTimeMillis()}" else "demo-physics"
buildDir = file("$rootDir/.build/$buildDirName")

kotlin {
    applyDefaultHierarchyTemplate()

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
        val commonMain by getting
        val androidMain by getting
        val jvmMain by getting

        // Android + desktop are both JVM-based; share the TCP/threading glue here.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(project(":engine:net:transports:tcp"))
            }
        }

        androidMain.dependsOn(jvmAndAndroidMain)
        jvmMain.dependsOn(jvmAndAndroidMain)

        jvmMain {
            dependencies {
                // Desktop GL shader compile/link helpers (LWJGL)
                implementation(platform("org.lwjgl:lwjgl-bom:3.3.3"))
                implementation("org.lwjgl:lwjgl-opengl")
            }
        }

        commonMain {
            dependencies {
                api(project(":engine:sim:core"))
                api(project(":engine:sim:sync"))
                api(project(":engine:sim:codecs:physics"))
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
    namespace = "org.emerge.demo.physics"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

