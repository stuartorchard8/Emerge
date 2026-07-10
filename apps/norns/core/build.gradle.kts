plugins {
    id("buildsrc.convention.kotlin-mpp")
}

base {
    archivesName.set("demo-norns")
}

buildDir = file("$rootDir/.build/norns-core")

// :apps:norns:core — a spiritual successor to Creatures (1996): a deterministic artificial-life
// sim (biochemistry + genetics + neural-net brain + biology) on the Emerge engine.
//
// JVM-only for now: the whole simulation is pure commonMain Kotlin and is built/verified
// headlessly via jvmTest. JS/Android targets + a render host are added later, when the
// visual layer is wired (deliberately deferred — see DESIGN.md). Depends only on
// :engine:sim:core (the deterministic tick/ECS/primitives); rendering/net deps come later.
kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":engine:sim:core"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
