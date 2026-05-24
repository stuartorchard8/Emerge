plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

base {
    archivesName.set("demo-scavengers")
}

// Stable build dir (expect AV exclusions instead of per-run build dirs).
buildDir = file("$rootDir/.build/demo-scavengers")

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
    js(IR) { browser() }

    sourceSets {
        val commonMain by getting
        val androidMain by getting
        val jvmMain by getting

        // Android + desktop are both JVM-based; share the TCP/threading glue here.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(project(":engine:net:transports:tcp"))
                implementation(project(":engine:net:transports:websocket"))
            }
        }

        androidMain.dependsOn(jvmAndAndroidMain)
        jvmMain.dependsOn(jvmAndAndroidMain)

        commonMain {
            dependencies {
                api(project(":engine:sim:core"))
                api(project(":engine:sim:sync"))
                api(project(":engine:sim:codecs:ecs"))
                api(project(":engine:render:torus"))
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
    namespace = "org.emerge.demo.scavengers"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

