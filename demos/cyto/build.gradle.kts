plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

base {
    archivesName.set("demo-cyto")
}

buildDir = file("$rootDir/.build/demo-cyto")

// Phase B: native, Box2D-free. All code is multiplatform commonMain, so Cyto targets the
// same platforms as the other demos (Android / JVM / JS).
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
                api(project(":engine:render:torus"))
                api(project(":engine:net:api"))
                api(project(":engine:sim:core"))
                api(project(":engine:sim:sync"))
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
    namespace = "org.emerge.demo.cyto"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Forward -Dcytobench to the JVM test JVM so the (otherwise-skipped) CytoBench perf probe can be run with
//   ./gradlew :demos:cyto:jvmTest --tests "*CytoBench*" -Dcytobench=1
tasks.withType<Test>().configureEach {
    System.getProperty("cytobench")?.let { systemProperty("cytobench", it) }
    System.getProperty("cytocells")?.let { systemProperty("cytocells", it) }
    System.getProperty("cytovariant")?.let { systemProperty("cytovariant", it) }
}

// Generates *ShaderSources.kt from .vert / .frag files under src/commonMain/shaders/.
registerShaderCodegen(packageName = "org.emerge.demo.cyto.shader")
