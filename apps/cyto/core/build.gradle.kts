plugins {
    id("buildsrc.convention.kotlin-mpp")
    alias(libs.plugins.androidLibrary)
}

base {
    archivesName.set("demo-cyto")
}

buildDir = file("$rootDir/.build/cyto-core")

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
        // Host-shell code that needs java.nio.file / java.util.concurrent — available on both the
        // desktop JVM and Android (minSdk 26). Shared here so saves/genomes/progress live in one copy.
        val jvmAndAndroidMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)
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
//   ./gradlew :apps:cyto:core:jvmTest --tests "*CytoBench*" -Dcytobench=1
tasks.withType<Test>().configureEach {
    System.getProperty("cytobench")?.let { systemProperty("cytobench", it) }
    System.getProperty("cytocells")?.let { systemProperty("cytocells", it) }
    System.getProperty("cytovariant")?.let { systemProperty("cytovariant", it) }
    System.getProperty("cytospread")?.let { systemProperty("cytospread", it) }
    System.getProperty("cytospringthresh")?.let { systemProperty("cytospringthresh", it) }
    System.getProperty("cytosave")?.let { systemProperty("cytosave", it) }
    System.getProperty("cytorepro")?.let { systemProperty("cytorepro", it) }
    System.getProperty("inspectcell")?.let { systemProperty("inspectcell", it) }
    System.getProperty("geneprobe")?.let { systemProperty("geneprobe", it) }
    System.getProperty("savefile")?.let { systemProperty("savefile", it) }
    System.getProperty("sandboxgenome")?.let { systemProperty("sandboxgenome", it) }
    System.getProperty("sandboxticks")?.let { systemProperty("sandboxticks", it) }
    System.getProperty("sandboxseed")?.let { systemProperty("sandboxseed", it) }
    System.getProperty("sandboxwatch")?.let { systemProperty("sandboxwatch", it) }
    System.getProperty("clockprobe")?.let { systemProperty("clockprobe", it) }
    System.getProperty("clockmode")?.let { systemProperty("clockmode", it) }
    System.getProperty("clockticks")?.let { systemProperty("clockticks", it) }
    System.getProperty("clockgenome")?.let { systemProperty("clockgenome", it) }
    System.getProperty("divbug")?.let { systemProperty("divbug", it) }
    System.getProperty("divticks")?.let { systemProperty("divticks", it) }
    System.getProperty("clockwatch")?.let { systemProperty("clockwatch", it) }
    System.getProperty("clockseed")?.let { systemProperty("clockseed", it) }
    System.getProperty("clockenv")?.let { systemProperty("clockenv", it) }
    System.getProperty("weldticks")?.let { systemProperty("weldticks", it) }
    System.getProperty("swimprobe")?.let { systemProperty("swimprobe", it) }
    System.getProperty("swimcell")?.let { systemProperty("swimcell", it) }
    System.getProperty("swimticks")?.let { systemProperty("swimticks", it) }
    System.getProperty("collprobe")?.let { systemProperty("collprobe", it) }
    System.getProperty("collticks")?.let { systemProperty("collticks", it) }
    System.getProperty("ctrl")?.let { systemProperty("ctrl", it) }
    System.getProperty("ctrlgenome")?.let { systemProperty("ctrlgenome", it) }
    System.getProperty("ctrlseed")?.let { systemProperty("ctrlseed", it) }
    System.getProperty("ctrlticks")?.let { systemProperty("ctrlticks", it) }
    System.getProperty("cytospread")?.let { systemProperty("cytospread", it) }
}

// Generates *ShaderSources.kt from .vert / .frag files under src/commonMain/shaders/.
registerShaderCodegen(packageName = "org.emerge.demo.cyto.shader")
