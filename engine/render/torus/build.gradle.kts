plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

// Stable build dir (expect AV exclusions instead of per-run build dirs).
buildDir = file("$rootDir/.build/render-torus")

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
                api(project(":engine:sim:core"))
            }
        }
        jvmMain {
            dependencies {
                // Desktop GL program compilation/linking (LWJGL)
                implementation(platform("org.lwjgl:lwjgl-bom:3.3.3"))
                implementation("org.lwjgl:lwjgl-opengl")
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
    namespace = "org.emerge.render.torus"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

