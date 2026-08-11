// :apps:fluidlab:web — the browser host (WebGL2), bundled to a single emerge.js beside index.html.
//
// Dev server:  ./gradlew :apps:fluidlab:web:jsBrowserDevelopmentRun
// Bundle:      ./gradlew :apps:fluidlab:web:jsBrowserDistribution
plugins {
    id("buildsrc.convention.kotlin-mpp")
}

buildDir = file("$rootDir/.build/fluidlab-web")

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
                implementation(project(":apps:fluidlab:core"))
                implementation(project(":engine:render:torus"))
                implementation(project(":engine:sim:core"))
            }
            // Shared asset folder at the repo root, served alongside the bundle.
            resources.srcDir("$rootDir/assets")
        }
    }
}
