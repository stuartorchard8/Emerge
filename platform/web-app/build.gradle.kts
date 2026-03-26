plugins {
    id("buildsrc.convention.kotlin-mpp")
}

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
                implementation(project(":engine:sim:codecs:physics"))
                implementation(project(":engine:render:torus"))
                implementation(project(":engine:net:api"))
                implementation(project(":engine:net:transports:websocket"))
                implementation(project(":demos:physics"))
            }
            resources.srcDir("$rootDir/assets")
        }
    }
}
