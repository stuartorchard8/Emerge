package org.emerge.demo.cyto.sim

/**
 * Human-writable text format for a genome (a `List<Gene>`) — for hand-authoring + testing a genome
 * without UI, and the foundation the in-game editor and save file build on.
 *
 * One gene per line, three `:`-separated parts — **energy source : condition : action**:
 *
 *     Light : ChemQty a < 4 : Import a       # import 'a' while the cytoplasm has < 4
 *     Light : ChemQty a > 0 : FormBond a b   # bond an 'a'-ending molecule to a 'b'-starting one
 *     Light : ChemQty ab > 0 : Convert ab    # lock 'ab' into biomass
 *     Light : Biomass > 8 : Mitosis          # divide once biomass exceeds 8 bonds
 *
 * Condition is `ChemQty <species> <>|<> <n>` or `Biomass <>|<> <n>`; action is `Import <species>`,
 * `FormBond <a> <b>`, `Convert <species>`, or `Mitosis`. Blank lines and `#` comments are ignored.
 * Round-trips every preset genome (see GeneCodecTest).
 */
object GeneCodec {

    fun serialize(genome: List<Gene>): String = genome.joinToString("\n") { gene ->
        "${gene.source} : ${condition(gene.condition)} : ${action(gene.action)}"
    }

    fun parse(text: String): List<Gene> = text.lines().mapNotNull { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@mapNotNull null
        val parts = line.split(":")
        require(parts.size == 3) { "gene line must have three ':'-separated parts: \"$raw\"" }
        Gene(
            source = EnergySource.valueOf(parts[0].trim()),
            condition = parseCondition(parts[1].trim().split(WS)),
            action = parseAction(parts[2].trim().split(WS)),
        )
    }

    private fun condition(c: GeneCondition): String = when (c.type) {
        ConditionType.ChemQty -> "ChemQty ${c.species} ${cmp(c.cmp)} ${c.threshold}"
        ConditionType.Biomass -> "Biomass ${cmp(c.cmp)} ${c.threshold}"
    }

    private fun parseCondition(t: List<String>): GeneCondition = when (t[0]) {
        "ChemQty" -> {
            require(t.size == 4) { "ChemQty needs 'ChemQty <species> <>|<> <n>': ${t.joinToString(" ")}" }
            GeneCondition(ConditionType.ChemQty, t[1], cmp(t[2]), t[3].toInt())
        }
        "Biomass" -> {
            require(t.size == 3) { "Biomass needs 'Biomass <>|<> <n>': ${t.joinToString(" ")}" }
            GeneCondition(ConditionType.Biomass, "", cmp(t[1]), t[2].toInt())
        }
        else -> throw IllegalArgumentException("unknown condition: ${t[0]}")
    }

    private fun action(a: GeneAction): String = when (a.type) {
        ActionType.Import -> "Import ${a.a}"
        ActionType.FormBond -> "FormBond ${a.a} ${a.b}"
        ActionType.Convert -> "Convert ${a.a}"
        ActionType.Mitosis -> "Mitosis"
    }

    private fun parseAction(t: List<String>): GeneAction = when (t[0]) {
        "Import" -> GeneAction(ActionType.Import, t[1])
        "FormBond" -> GeneAction(ActionType.FormBond, t[1], t[2])
        "Convert" -> GeneAction(ActionType.Convert, t[1])
        "Mitosis" -> GeneAction(ActionType.Mitosis)
        else -> throw IllegalArgumentException("unknown action: ${t[0]}")
    }

    private fun cmp(c: Comparison): String = if (c == Comparison.Greater) ">" else "<"
    private fun cmp(s: String): Comparison = when (s) {
        ">" -> Comparison.Greater
        "<" -> Comparison.Less
        else -> throw IllegalArgumentException("comparator must be > or <: $s")
    }

    private val WS = Regex("\\s+")
}
