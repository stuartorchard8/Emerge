plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

base {
    archivesName.set("demo-drockets")
}

buildDir = file("$rootDir/.build/drockets-core")

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

        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
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
    namespace = "org.emerge.demo.drockets"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Generates *ShaderSources.kt from .vert / .frag files under src/commonMain/shaders/.
registerShaderCodegen(packageName = "org.emerge.demo.drockets.shader")
