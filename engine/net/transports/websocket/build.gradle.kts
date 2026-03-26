plugins {
    id("buildsrc.convention.kotlin-mpp")
}

kotlin {
    jvm()
    js(IR) { browser() }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":engine:net:api"))
            }
        }
        jvmMain {
            dependencies {
                implementation("org.java-websocket:Java-WebSocket:1.6.0")
            }
        }
    }
}
