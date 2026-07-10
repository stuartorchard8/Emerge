package org.emerge.desktop

import org.emerge.demo.cyto.sim.AUTOTROPH_GENES
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.HETEROTROPH_GENES
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/** One named genome the brush palette can paint with: a [name], a swatch [color] (RGBA), and its [genome]. */
class GenomeEntry(val name: String, val color: Long, val genome: List<Gene>)

/**
 * The **genome library**: named genomes saved under [DIR] (`cyto-genomes/`), each a `.gene` file whose header
 * carries the swatch colour and whose body is [GeneCodec] text. Cross-world (like [CytoSaves]) so a creature's
 * genome authored in one world can be painted into another. Seeds a couple of defaults on first use; the
 * in-game "Save Genome" flow adds/overwrites entries (a same-name save replaces one).
 *
 * (Named "genomes", not "blueprints" — a blueprint here is reserved for a future whole-organism save.)
 */
object CytoGenomes {
    private val DIR: Path = Path.of("cyto-genomes")

    /** All saved genomes, alphabetical by name. Seeds the defaults if the library is empty. */
    fun list(): List<GenomeEntry> {
        seedDefaultsIfEmpty()
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

    fun exists(name: String): Boolean = Files.exists(path(name))

    fun save(rawName: String, color: Long, genome: List<Gene>): String {
        val name = CytoSaves.sanitize(rawName)
        Files.createDirectories(DIR)
        val text = buildString {
            appendLine("# genome: $name")
            appendLine("# color: ${color.toString(16).padStart(8, '0')}")
            appendLine(GeneCodec.serialize(genome))
        }
        Files.writeString(path(name), text)
        println("[cyto] saved genome '$name' (${genome.size} gene(s))")
        return name
    }

    fun delete(name: String) {
        runCatching { Files.deleteIfExists(path(name)) }
        println("[cyto] deleted genome '$name'")
    }

    private fun read(name: String): GenomeEntry? = runCatching {
        val body = Files.readString(path(name))
        var color = 0x888888FFL
        for (line in body.lineSequence()) {
            val m = Regex("^#\\s*color:\\s*([0-9a-fA-F]{6,8})").find(line.trim())
            if (m != null) { color = m.groupValues[1].padEnd(8, 'f').toLong(16); break }
        }
        GenomeEntry(name, color, GeneCodec.parse(body))
    }.getOrElse { println("[cyto] genome '$name' failed to parse: ${it.message}"); null }

    private fun seedDefaultsIfEmpty() {
        if (Files.isDirectory(DIR) && runCatching { Files.list(DIR).use { it.findAny().isPresent } }.getOrDefault(false)) return
        save("Autotroph", 0x44CC55FFL, AUTOTROPH_GENES)
        save("Heterotroph", 0xDD3333FFL, HETEROTROPH_GENES)
    }

    private fun path(name: String): Path = DIR.resolve("${CytoSaves.sanitize(name)}.gene")
}
