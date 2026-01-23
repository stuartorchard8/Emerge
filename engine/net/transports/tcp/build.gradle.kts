plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

// Stable build dir (expect AV exclusions instead of per-run build dirs).
buildDir = file("$rootDir/.build/net-tcp")

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

