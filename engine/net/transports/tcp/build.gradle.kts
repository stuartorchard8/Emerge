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
val buildDirName = if (uniqueBuildDir) "net-tcp-${System.currentTimeMillis()}" else "net-tcp"
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

        // Both Android + desktop are JVM-based, so we can share Java-socket code here
        // without duplicating it in androidMain + jvmMain.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain)
        }

        androidMain.dependsOn(jvmAndAndroidMain)
        jvmMain.dependsOn(jvmAndAndroidMain)

        commonMain {
            dependencies {
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

