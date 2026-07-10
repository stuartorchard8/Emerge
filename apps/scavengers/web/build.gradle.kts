plugins {
    id("buildsrc.convention.kotlin-mpp")
}

buildDir = file("$rootDir/.build/scavengers-web")

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "emerge.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(project(":engine:sim:core"))
                implementation(project(":engine:sim:sync"))
                implementation(project(":engine:sim:codecs:ecs"))
                implementation(project(":engine:render:torus"))
                implementation(project(":engine:net:api"))
                implementation(project(":engine:net:transports:websocket"))
                implementation(project(":apps:scavengers:core"))
            }
            resources.srcDir("$rootDir/assets")
        }
    }
}
