plugins {
    id("buildsrc.convention.kotlin-mpp")
}

kotlin {
    jvm()
    js(IR) { browser() }

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

