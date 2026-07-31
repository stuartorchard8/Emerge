// :apps:template:core — the shared, platform-free heart of the app: simulation, renderer, UI.
//
// Everything here is `commonMain` Kotlin, which is what lets one codebase run as a desktop window,
// an APK and a web page. Nothing platform-specific belongs in this module — no java.io, no
// android.*, no kotlinx.browser. If you need those, put them in the host module that has them, or
// add a `jvmMain` / `androidMain` / `jsMain` source set here behind an `expect`/`actual`.
plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

base {
    archivesName.set("demo-template")
}

// Build output lives outside the module, next to every other module's, so `apps/` stays readable.
buildDir = file("$rootDir/.build/template-core")

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
        commonMain {
            dependencies {
                // The GPU abstraction + shaders + immediate-mode UI toolkit.
                api(project(":engine:render:torus"))
                // Deterministic tick/reducer contract, fixed-point primitives, ECS.
                api(project(":engine:sim:core"))
                // Uncomment when you want lockstep/client-server multiplayer:
                // api(project(":engine:net:api"))
                // api(project(":engine:sim:sync"))
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
    namespace = "org.emerge.demo.template"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Turns .vert / .frag files under src/commonMain/shaders/ into Kotlin string sources, so a custom
// shader works identically on desktop GL, Android GLES and WebGL. Uncomment when you add one.
// registerShaderCodegen(packageName = "org.emerge.demo.template.shader")

// Generates BuildInfo.kt (git commit + date + dirty flag) — worth wiring into a title screen so a
// running build, desktop or APK, can be identified without guessing.
// registerBuildInfo(packageName = "org.emerge.demo.template.build")
