package org.emerge.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

/**
 * Named-save store for outofspace.
 *
 * Each save is a `<name>.txt` file (the same text format `Save.kt` writes) under [DIR].
 * Replaces the old single `outofspace.save`.
 *
 * Unlike cyto's binary codec, outofspace's save is already human-editable text, so there is no
 * geometry sidecar — one file per save.
 */
object OoSaves {
    private val DIR: Path = Path.of("outofspace-saves")

    /** Save names, newest first (by last-modified time). */
    fun list(): List<String> {
        if (!Files.isDirectory(DIR)) return emptyList()
        return runCatching {
            DIR.listDirectoryEntries()
                .filter { it.toString().endsWith(".txt") }
                .filter { it.isRegularFile() }
                .sortedByDescending {
                    runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L)
                }
                .map { it.nameWithoutExtension }
                .toList()
        }.getOrDefault(emptyList())
    }

    /** Whether a save with [name] exists on disk. */
    fun exists(name: String): Boolean = filePath(name).exists()

    /** The most recently modified save name, or null if none. */
    fun mostRecent(): String? = list().firstOrNull()

    /**
     * Trim a user-typed name to a safe, non-empty filename stem.
     *
     * Always lowercased for case-insensitive lookup; capped at 40 characters to keep filenames short.
     * Underscore is the replacement for anything that wouldn't survive every platform's filesystem.
     */
    fun sanitize(raw: String): String =
        raw.trim().map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("").lowercase().trim().take(40).ifEmpty { "save" }

    /**
     * Write the text [content] to a named save file.
     *
     * Creates [DIR] if it does not exist. Returns the sanitized name actually written.
     */
    fun save(name: String, content: String): String {
        val sanitized = sanitize(name)
        Files.createDirectories(DIR)
        filePath(sanitized).writeText(content)
        println("saved to ${filePath(sanitized).toAbsolutePath()}")
        return sanitized
    }

    /**
     * Read the named save file as raw text.
     *
     * Returns null if the save does not exist.
     */
    fun load(name: String): String? {
        val path = filePath(name)
        if (!path.exists()) {
            println("[save] no save '$name'")
            return null
        }
        return path.readBytes().decodeToString()
    }

    /**
     * Delete the named save file (and any sidecars, though there are none).
     */
    fun delete(name: String) {
        runCatching {
            Files.deleteIfExists(filePath(name))
        }
        println("[save] deleted '$name'")
    }

    private fun filePath(name: String): Path = DIR.resolve("${sanitize(name)}.txt")
}
