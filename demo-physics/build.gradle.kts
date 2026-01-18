plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

// Workaround for Windows file locking on Gradle/AGP intermediates (classes.jar/R.jar).
buildDir = file("$rootDir/.build/demo-physics-${System.currentTimeMillis()}")

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
        val commonMain by getting
        val androidMain by getting
        val jvmMain by getting

        // Both Android + desktop are JVM-based, so we can share socket/threading demo glue here.
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(project(":net-tcp"))
            }
        }

        androidMain.dependsOn(jvmAndAndroidMain)
        jvmMain.dependsOn(jvmAndAndroidMain)

        commonMain {
            dependencies {
                api(project(":sim-core"))
                api(project(":sim-sync"))
                api(project(":sim-physics-codec"))
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

