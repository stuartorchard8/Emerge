package org.emerge.demo.cyto.sim

/**
 * Human-writable text format for a genome (a `List<Gene>`) — the foundation the in-game editor and
 * the save file both build on, and a way to hand-author + test a cell genome without any UI.
 *
 * One gene per line: the inputs (`|`-separated), then `>`, then the single output. Each input is
 * `TYPE chem weight`; the output is `TYPE chem1 chem2 bias`. `_` stands for an empty chemical name,
 * and `-` for "no inputs". Blank lines and `#` comments are ignored. Example (the Collector preset):
 *
 *     # collector: light -> energy
 *     Light _ 1.0 > Secrete energy _ 0.0
 *
 * Round-trips every preset genome byte-stably (see GeneCodecTest).
 */
object GeneCodec {

    fun serialize(genome: List<Gene>): String = genome.joinToString("\n") { gene ->
        val ins =
            if (gene.inputs.isEmpty()) "-"
            else gene.inputs.joinToString(" | ") { "${it.type} ${tok(it.chem)} ${it.weight}" }
        val o = gene.output
        "$ins > ${o.type} ${tok(o.chem1)} ${tok(o.chem2)} ${o.bias}"
    }

    fun parse(text: String): List<Gene> = text.lines().mapNotNull { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@mapNotNull null
        val sides = line.split(">")
        require(sides.size == 2) { "gene line must have exactly one '>': \"$raw\"" }
        val inputs = sides[0].trim().let { ins ->
            if (ins == "-" || ins.isEmpty()) emptyList()
            else ins.split("|").map { field ->
                val t = field.trim().split(WS)
                require(t.size == 3) { "input must be 'TYPE chem weight': \"$field\"" }
                GeneInput(GeneInputType.valueOf(t[0]), untok(t[1]), t[2].toFloat())
            }
        }
        val o = sides[1].trim().split(WS)
        require(o.size == 4) { "output must be 'TYPE chem1 chem2 bias': \"${sides[1]}\"" }
        Gene(inputs, GeneOutput(GeneOutputType.valueOf(o[0]), untok(o[1]), untok(o[2]), o[3].toFloat()))
    }

    private val WS = Regex("\\s+")
    private fun tok(s: String) = s.ifEmpty { "_" }
    private fun untok(s: String) = if (s == "_") "" else s
}
