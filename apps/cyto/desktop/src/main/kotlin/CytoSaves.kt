package org.emerge.desktop

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.CytoWorldConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/**
 * Named-save store: each save is a `<name>.bin` snapshot plus a `<name>.world` geometry sidecar (world size +
 * day/night, which the `.bin` codec doesn't carry) under [DIR]. Replaces the old single `cyto-save.bin`.
 * Pure file IO + the geometry-apply-before-restore dance; the menu drives it via [CytoMenu.Callbacks].
 */
object CytoSaves {
    private val DIR: Path = Path.of("cyto-saves")

    /** Save names, newest first (by last-modified). */
    fun list(): List<String> {
        if (!Files.isDirectory(DIR)) return emptyList()
        return runCatching {
            Files.list(DIR).use { stream ->
                stream.filter { it.toString().endsWith(".bin") }
                    .sorted(compareByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) })
                    .map { it.nameWithoutExtension }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    fun exists(name: String): Boolean = Files.exists(binPath(name))

    /** The most recently modified save name, or null if none. */
    fun mostRecent(): String? = list().firstOrNull()

    /** Trim a user-typed name to a safe, non-empty filename stem. */
    fun sanitize(raw: String): String =
        raw.trim().map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("").trim().take(40).ifEmpty { "save" }

    fun save(controller: CytoController, rawName: String): String {
        val name = sanitize(rawName)
        Files.createDirectories(DIR)
        Files.write(binPath(name), controller.snapshotBytes())
        val c = CytoWorldConfig
        Files.writeString(worldPath(name), "${c.cellsPerAxis} ${c.orbitPeriod} ${c.dayFraction}")
        println("[cyto] saved '$name'")
        return name
    }

    /** Apply the save's geometry to [CytoWorldConfig], then restore its snapshot (which rebuilds the reducer). */
    fun load(controller: CytoController, name: String): Boolean {
        if (!exists(name)) { println("[cyto] no save '$name'"); return false }
        return runCatching {
            applyGeometry(name)
            controller.restoreSnapshot(Files.readAllBytes(binPath(name)))
            println("[cyto] loaded '$name'")
            true
        }.getOrElse { println("[cyto] load '$name' failed: ${it.message}"); false }
    }

    fun delete(name: String) {
        runCatching { Files.deleteIfExists(binPath(name)); Files.deleteIfExists(worldPath(name)) }
        println("[cyto] deleted '$name'")
    }

    private fun applyGeometry(name: String) {
        val p = worldPath(name)
        if (!Files.exists(p)) return
        runCatching {
            val parts = Files.readString(p).trim().split(Regex("\\s+"))
            if (parts.size >= 3) CytoWorldConfig.applyFrom(parts[0].toInt(), parts[1].toLong(), parts[2].toFloat())
        }
    }

    private fun binPath(name: String): Path = DIR.resolve("${sanitize(name)}.bin")
    private fun worldPath(name: String): Path = DIR.resolve("${sanitize(name)}.world")
}
