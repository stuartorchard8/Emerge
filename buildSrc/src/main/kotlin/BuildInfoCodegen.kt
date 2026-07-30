import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.of
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Wires a build-stamp codegen task into a Kotlin Multiplatform module: generates an object
 * `BuildInfo` in [packageName] carrying the git commit the sources were built from, so a running
 * build can say *which* build it is (see Cyto's title screen).
 *
 * Automatic by construction. The git state is read through a [GitStampSource] **value source**,
 * which the configuration cache re-evaluates on every build to decide whether it is still valid —
 * so a new commit invalidates the stamp without anyone re-running anything. Reading git ad hoc at
 * configuration time would instead be frozen into the config cache and silently serve a stale hash,
 * which is worse than showing none.
 *
 * The stamp is deliberately the **commit** date, not the build time: a build timestamp changes on
 * every build, which would leave this task (and every compile downstream of it) permanently out of
 * date. Local edits on top of the commit are covered by the `-dirty` marker instead.
 *
 * Degrades rather than fails: with no `git` on PATH or no repository (a source tarball), the stamp
 * reads `unknown`.
 */
fun Project.registerBuildInfo(packageName: String) {
    val stamp = providers.of(GitStampSource::class) {
        parameters.repoDir.set(rootDir)
    }
    val outputDirProvider = layout.buildDirectory.dir("generated/source/buildinfo/commonMain")

    val generateTask = tasks.register("generateBuildInfo") {
        group = "build"
        description = "Generates BuildInfo.kt stamped with the git commit the sources were built from."
        // The stamp as a task input: a new commit (or dirtying the tree) reruns codegen; nothing else does.
        inputs.property("stamp", stamp)
        outputs.dir(outputDirProvider).withPropertyName("outputDir")

        val outDir = outputDirProvider.get().asFile
        doLast {
            val (commit, date, dirty) = GitStamp.parse(stamp.get())
            val targetDir = outDir.resolve(packageName.replace('.', '/')).also { it.mkdirs() }
            targetDir.resolve("BuildInfo.kt").writeText(
                generateBuildInfoSource(packageName, commit, date, dirty)
            )
        }
    }

    extensions.configure<KotlinMultiplatformExtension> {
        sourceSets.named("commonMain") {
            kotlin.srcDir(generateTask.map { outputDirProvider.get() })
        }
    }
}

/** The three fields of a stamp, and their flat `commit|date|dirty` wire form. */
internal object GitStamp {
    const val UNKNOWN = "unknown||false"

    fun encode(commit: String, date: String, dirty: Boolean) = "$commit|$date|$dirty"

    fun parse(encoded: String): Triple<String, String, Boolean> {
        val parts = encoded.split('|')
        return Triple(
            parts.getOrElse(0) { "unknown" },
            parts.getOrElse(1) { "" },
            parts.getOrElse(2) { "false" }.toBoolean(),
        )
    }
}

/**
 * Reads the current git commit as a configuration-cache-aware value source. Re-run on every build
 * to check cache validity, so it is the mechanism that makes the stamp self-updating — but it must
 * therefore stay cheap: two short-lived `git` invocations.
 */
abstract class GitStampSource : ValueSource<String, GitStampSource.Params> {
    interface Params : ValueSourceParameters {
        val repoDir: Property<File>
    }

    @get:Inject
    abstract val execOps: ExecOperations

    override fun obtain(): String {
        val dir = parameters.repoDir.get()
        val commit = git(dir, "rev-parse", "--short", "HEAD") ?: return GitStamp.UNKNOWN
        val date = git(dir, "show", "-s", "--format=%cd", "--date=short", "HEAD").orEmpty()
        // --porcelain lists tracked modifications; untracked files are excluded (`-uno`) because a
        // scratch file lying in the tree does not change what was compiled.
        val dirty = !git(dir, "status", "--porcelain", "-uno").isNullOrBlank()
        return GitStamp.encode(commit, date, dirty)
    }

    /** Runs a git command, returning its trimmed stdout, or null if git is absent / the call fails. */
    private fun git(dir: File, vararg args: String): String? = try {
        val out = ByteArrayOutputStream()
        val result = execOps.exec {
            workingDir = dir
            commandLine("git", *args)
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        if (result.exitValue == 0) out.toString().trim() else null
    } catch (_: Exception) {
        null
    }
}

private fun generateBuildInfoSource(
    packageName: String,
    commit: String,
    date: String,
    dirty: Boolean,
): String = """
    // Generated by registerBuildInfo. Do not edit by hand.
    package $packageName

    /** The git commit these sources were built from. Generated at build time; see registerBuildInfo. */
    object BuildInfo {
        const val COMMIT = "$commit"
        const val COMMIT_DATE = "$date"

        /** True when the build included uncommitted changes on top of [COMMIT]. */
        const val DIRTY = $dirty

        /** One-line build stamp for display, e.g. `9883704e-dirty · 2026-07-30`. */
        val LABEL: String = buildString {
            append(COMMIT)
            if (DIRTY) append("-dirty")
            if (COMMIT_DATE.isNotEmpty()) append(" · ").append(COMMIT_DATE)
        }
    }
""".trimIndent() + "\n"
