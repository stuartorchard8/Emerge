plugins {
    id("buildsrc.convention.kotlin-mpp")
}

base {
    archivesName.set("demo-cyto")
}

buildDir = file("$rootDir/.build/demo-cyto")

// Cyto is JVM-only for now (Phase A): its simulation is driven by a vendored Box2D
// (gdx-box2d) backend that has no JS/Android-friendly form here. Phase B replaces
// Box2D with native Emerge physics and re-adds the other targets.
kotlin {
    applyDefaultHierarchyTemplate()

    jvm()

    sourceSets {
        commonMain {
            dependencies {
                // Engine render API — needed by the generated *ShaderSources objects
                // (they reference org.emerge.render.torus.GPU) and by CytoRenderer.
                api(project(":engine:render:torus"))
                api(project(":engine:net:api"))
                // Native ECS sim (Phase B): components, systems, the spring constraint,
                // and the save/state codecs.
                api(project(":engine:sim:core"))
                api(project(":engine:sim:sync"))
            }
        }

        val jvmMain by getting {
            dependencies {
                // Vendored Box2D physics (Phase A). See plan: throwaway in Phase B.
                val gdxVersion = "1.12.1"
                val ktxVersion = "1.12.1-rc1"
                implementation("com.badlogicgames.gdx:gdx:$gdxVersion")
                implementation("com.badlogicgames.gdx:gdx-box2d:$gdxVersion")
                implementation("io.github.libktx:ktx-box2d:$ktxVersion")
                implementation("io.github.libktx:ktx-math:$ktxVersion")
                implementation("io.github.libktx:ktx-collections:$ktxVersion")
                runtimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
                runtimeOnly("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-desktop")
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// Generates *ShaderSources.kt from .vert / .frag files under src/commonMain/shaders/.
registerShaderCodegen(packageName = "org.emerge.demo.cyto.shader")
