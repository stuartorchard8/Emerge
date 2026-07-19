package org.emerge.demo.cyto.host

import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/** One banked gene-group: a [name] and the [genes] that make it up (all sharing the group tag [name]). */
class SnippetEntry(val name: String, val genes: List<Gene>)

/**
 * The **gene bank** — named gene-*groups* (a "clock", a "reproduce" subsystem) saved under [DIR]
 * (`cyto-snippets/`) so a group authored on one cell can be pasted into another, across worlds and scenarios
 * (the Factorio-blueprint ask). Distinct from [CytoGenomes], which banks *whole* genomes for the brush; a
 * snippet is a subsystem you drop into an existing cell.
 *
 * A snippet is just a `.gene` file whose body is [GeneCodec] text — the group tag rides along in that text, so
 * pasting a snippet restores its group. A same-name save overwrites (a group is banked under its own name).
 */
object CytoSnippets {
    private val DIR: Path get() = CytoStorage.baseDir.resolve("cyto-snippets")

    /** All banked snippets, alphabetical by name. */
    fun list(): List<SnippetEntry> {
        if (!Files.isDirectory(DIR)) return emptyList()
        return runCatching {
            Files.list(DIR).use { s ->
                s.filter { it.toString().endsWith(".gene") }
                    .map { it.nameWithoutExtension }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList()).mapNotNull { read(it) }
    }

    fun save(rawName: String, genes: List<Gene>): String {
        val name = CytoSaves.sanitize(rawName)
        Files.createDirectories(DIR)
        val text = buildString {
            appendLine("# snippet: $name")
            appendLine(GeneCodec.serialize(genes))
        }
        Files.write(path(name), text.toByteArray())
        println("[cyto] banked group '$name' (${genes.size} gene(s))")
        return name
    }

    fun delete(name: String) {
        runCatching { Files.deleteIfExists(path(name)) }
        println("[cyto] deleted snippet '$name'")
    }

    private fun read(name: String): SnippetEntry? = runCatching {
        val body = Files.readAllBytes(path(name)).decodeToString()
        SnippetEntry(name, GeneCodec.parse(body))
    }.getOrElse { println("[cyto] snippet '$name' failed to parse: ${it.message}"); null }

    private fun path(name: String): Path = DIR.resolve("${CytoSaves.sanitize(name)}.gene")
}
