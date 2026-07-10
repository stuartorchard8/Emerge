// Convention for a per-app desktop launcher module (JVM application + LWJGL natives).
// Each app's desktop module applies this and adds only its own app/engine dependencies
// and mainClass.
package buildsrc.convention

import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

/** LWJGL native classifier for the host this build is running on. */
val lwjglNatives: String = run {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    when {
        osName.contains("windows") ->
            if (osArch == "aarch64") "natives-windows-arm64" else "natives-windows"
        osName.contains("mac") || osName.contains("darwin") ->
            if (osArch == "aarch64") "natives-macos-arm64" else "natives-macos"
        osName.contains("linux") || osName.contains("freebsd") -> when {
            osArch == "aarch64" -> "natives-linux-arm64"
            osArch.startsWith("arm") -> "natives-linux-arm32"
            else -> "natives-linux"
        }
        else -> error("Unsupported OS for LWJGL natives: '$osName' / '$osArch'")
    }
}

dependencies {
    // LWJGL (desktop GPU rendering). Native libraries are platform-specific; classifier
    // picked from the build host so a Linux build pulls liblwjgl.so, macOS .dylib, etc.
    "implementation"(platform("org.lwjgl:lwjgl-bom:3.3.3"))
    "implementation"("org.lwjgl:lwjgl")
    "implementation"("org.lwjgl:lwjgl-glfw")
    "implementation"("org.lwjgl:lwjgl-opengl")
    "implementation"("org.lwjgl:lwjgl-openal")
    "implementation"("org.lwjgl:lwjgl-stb")
    "runtimeOnly"("org.lwjgl:lwjgl::$lwjglNatives")
    "runtimeOnly"("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    "runtimeOnly"("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    "runtimeOnly"("org.lwjgl:lwjgl-openal::$lwjglNatives")
    "runtimeOnly"("org.lwjgl:lwjgl-stb::$lwjglNatives")
}

// Avoid failing packaging when two dependencies contribute same-named jars
// (seen with some multiplatform variants in the distribution lib/ folder).
tasks.withType<Tar>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
tasks.withType<Zip>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }

tasks.processResources {
    from("$rootDir/assets") {
        into("assets")
    }
}
