import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    application
}

dependencies {
    implementation(project(":demos:physics"))
    implementation(project(":engine:sim:core"))
    implementation(project(":engine:sim:sync"))
    implementation(project(":engine:sim:codecs:physics"))
    implementation(project(":engine:net:transports:loopback"))
    implementation(project(":engine:net:transports:tcp"))

    // LWJGL (desktop GPU rendering)
    // Minimal set: glfw + opengl + core + natives (Windows).
    implementation(platform("org.lwjgl:lwjgl-bom:3.3.3"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    runtimeOnly("org.lwjgl:lwjgl::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-glfw::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-opengl::natives-windows")
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "org.emerge.desktop.AppKt"
}

// Avoid failing packaging when two dependencies contribute same-named jars
// (seen with some multiplatform variants in the distribution lib/ folder).
tasks.withType<Tar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.withType<Zip>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
