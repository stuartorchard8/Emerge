plugins {
    id("buildsrc.convention.kotlin-mpp")
}

buildDir = file("$rootDir/.build/cyto-web")

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
                implementation(project(":engine:render:torus"))
                implementation(project(":apps:cyto:core"))
            }
            resources.srcDir("$rootDir/assets")
        }
    }
}
