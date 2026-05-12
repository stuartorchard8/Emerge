import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File

/**
 * Wires a shader codegen task into a Kotlin Multiplatform module.
 *
 * Convention: each pair `<name>.vert` + `<name>.frag` under `src/commonMain/shaders/`
 * generates a Kotlin object `<CamelCase>ShaderSources` in [packageName] (where
 * `name_with_underscores` becomes `NameWithUnderscores`). The generated object exposes
 * the same `vertex(): String` / `fragment(): String` API the hand-written objects had,
 * so consumers (`<X>Shader.kt`) need no changes.
 *
 * Runtime version + precision template substitution: the .vert / .frag files contain
 * a literal `#version 330 core` line at the top. At runtime the generated
 * `renderShader` swaps that for the target's actual `#version`, plus precision
 * declarations for GLES fragment shaders.
 *
 * If `glslangValidator` is present on PATH, every shader file is validated as part of
 * the task action and the build fails on GLSL syntax errors. If absent, the task
 * still generates Kotlin sources and emits a one-line warning per build.
 */
fun Project.registerShaderCodegen(packageName: String) {
    val shaderDir = layout.projectDirectory.dir("src/commonMain/shaders")
    val outputDirProvider = layout.buildDirectory.dir("generated/source/shaders/commonMain")

    val generateTask = tasks.register("generateShaderSources") {
        group = "build"
        description = "Validates GLSL shader files (if glslangValidator present) and " +
            "generates *ShaderSources.kt Kotlin objects from src/commonMain/shaders/*.{vert,frag}."
        inputs.dir(shaderDir).withPropertyName("shaderDir")
        outputs.dir(outputDirProvider).withPropertyName("outputDir")

        doLast {
            val outputDir = outputDirProvider.get().asFile
            outputDir.deleteRecursively()
            generateShaderSources(shaderDir.asFile, outputDir, packageName, logger::lifecycle, logger::warn)
        }
    }

    extensions.configure<KotlinMultiplatformExtension> {
        sourceSets.named("commonMain") {
            kotlin.srcDir(generateTask.map { outputDirProvider.get() })
        }
    }
}

private fun generateShaderSources(
    shaderDir: File,
    outputDir: File,
    packageName: String,
    log: (String) -> Unit,
    warn: (String) -> Unit,
) {
    val packagePath = packageName.replace('.', '/')
    val targetDir = outputDir.resolve(packagePath).also { it.mkdirs() }

    val pairs = (shaderDir.listFiles() ?: emptyArray())
        .filter { it.extension == "vert" || it.extension == "frag" }
        .groupBy { it.nameWithoutExtension }

    val hasValidator = hasGlslangValidator()
    if (!hasValidator) {
        warn(
            "glslangValidator not on PATH — generating shader sources without GLSL validation. " +
                "Install with `sudo apt install glslang-tools` to enable build-time syntax checks."
        )
    }

    for ((baseName, files) in pairs.toSortedMap()) {
        val vertFile = files.firstOrNull { it.extension == "vert" }
            ?: error("Missing vertex shader for '$baseName' (expected $baseName.vert)")
        val fragFile = files.firstOrNull { it.extension == "frag" }
            ?: error("Missing fragment shader for '$baseName' (expected $baseName.frag)")

        if (hasValidator) {
            validateShader(vertFile, isFragment = false)
            validateShader(fragFile, isFragment = true)
        }

        val className = "${toUpperCamelCase(baseName)}ShaderSources"
        val outFile = targetDir.resolve("$className.kt")
        outFile.writeText(
            generateKotlinSource(
                packageName = packageName,
                className = className,
                vertRaw = vertFile.readText(),
                fragRaw = fragFile.readText(),
            )
        )
        log("  shader codegen: $baseName -> $packageName.$className")
    }
}

private fun toUpperCamelCase(snakeOrFlat: String): String =
    snakeOrFlat.split('_', '-').joinToString("") { part ->
        part.replaceFirstChar { ch -> ch.uppercase() }
    }

/** Returns true if `glslangValidator` resolves on the current PATH. */
private fun hasGlslangValidator(): Boolean = try {
    val p = ProcessBuilder("glslangValidator", "--version")
        .redirectErrorStream(true)
        .start()
    val finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
    if (!finished) {
        p.destroyForcibly()
        false
    } else {
        p.exitValue() == 0
    }
} catch (_: Exception) {
    false
}

private fun validateShader(file: File, isFragment: Boolean) {
    val stage = if (isFragment) "frag" else "vert"
    val process = ProcessBuilder("glslangValidator", "-S", stage, file.absolutePath)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        error("glslangValidator timed out validating ${file.name}")
    }
    if (process.exitValue() != 0) {
        error("GLSL validation failed for ${file.name}:\n$output")
    }
}

private fun generateKotlinSource(
    packageName: String,
    className: String,
    vertRaw: String,
    fragRaw: String,
): String {
    val vertEscaped = escapeForKotlinTripleQuoted(vertRaw)
    val fragEscaped = escapeForKotlinTripleQuoted(fragRaw)
    // Single-char `$` to splice into the codegen template. Letting the template's own
    // `$version` / `$precision` references survive into the output (as Kotlin template
    // expressions for the generated `renderShader` function) requires emitting a literal
    // `$` here, not an escaped `${'$'}` (which would be re-evaluated as a `$` literal
    // *string* at the generated file's compile time and break runtime substitution).
    val d = "\$"
    return """
        // Generated by registerShaderCodegen. Do not edit by hand; edit the .vert / .frag
        // files in src/commonMain/shaders/ instead.
        package $packageName

        import org.emerge.render.torus.GPU

        object $className {
            fun vertex(): String = renderShader(VERTEX_RAW, GPU.shaderVersion, isFragment = false)
            fun fragment(): String = renderShader(FRAGMENT_RAW, GPU.shaderVersion, isFragment = true)

            private const val VERTEX_RAW = ${'"'}${'"'}${'"'}$vertEscaped${'"'}${'"'}${'"'}
            private const val FRAGMENT_RAW = ${'"'}${'"'}${'"'}$fragEscaped${'"'}${'"'}${'"'}

            private fun renderShader(raw: String, version: String, isFragment: Boolean): String {
                val precision = if (isFragment && version.contains("es")) {
                    "\nprecision highp float;\nprecision highp int;"
                } else {
                    ""
                }
                return raw.replace("#version 330 core", "#version ${d}version${d}precision")
            }
        }
    """.trimIndent() + "\n"
}

/**
 * Escapes a raw shader source for inclusion inside a Kotlin triple-quoted string literal.
 * GLSL doesn't normally use `$` or `"""`, but a stray `$` in a comment would otherwise be
 * interpreted as a Kotlin string template; embedded `"""` would close the literal.
 */
private fun escapeForKotlinTripleQuoted(raw: String): String = raw
    .replace("\$", "\${'\$'}")
    .replace("\"\"\"", "\"\"\${'\"'}")
