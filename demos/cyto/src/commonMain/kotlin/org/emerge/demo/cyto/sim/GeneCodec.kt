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
 * `FormBond <a> <b>`, `Convert <species>`, `Expand`, `Contract`, `Mitosis`, or `Repair`. Blank lines and
 * `#` comments are ignored.
 * Round-trips every preset genome (see GeneCodecTest).
 */
object GeneCodec {

    fun serialize(genome: List<Gene>): String = genome.joinToString("\n") { gene ->
        "${source(gene.source)} : ${condition(gene.condition)} : ${action(gene.action)}"
    }

    fun parse(text: String): List<Gene> = text.lines().mapNotNull { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@mapNotNull null
        val parts = line.split(":")
        require(parts.size == 3) { "gene line must have three ':'-separated parts: \"$raw\"" }
        Gene(
            source = parseSource(parts[0].trim().split(WS)),
            condition = parseCondition(parts[1].trim().split(WS)),
            action = parseAction(parts[2].trim().split(WS)),
        )
    }

    private fun source(s: EnergySource): String = when (s) {
        is EnergySource.Light -> "Light"
        is EnergySource.BreakBond -> "Break ${s.bond}"
    }

    private fun parseSource(t: List<String>): EnergySource = when (t[0]) {
        "Light" -> EnergySource.Light
        "Break" -> {
            require(t.size == 2) { "Break needs 'Break <bond>': ${t.joinToString(" ")}" }
            EnergySource.BreakBond(t[1])
        }
        else -> throw IllegalArgumentException("unknown energy source: ${t[0]}")
    }

    private fun condition(c: GeneCondition): String = when (c.type) {
        ConditionType.ChemQty -> "ChemQty ${tok(c.species)} ${cmp(c.cmp)} ${c.threshold}"
        ConditionType.Biomass -> "Biomass ${cmp(c.cmp)} ${c.threshold}"
        ConditionType.Touching -> "Touching ${cmp(c.cmp)} ${c.threshold}"
    }

    private fun parseCondition(t: List<String>): GeneCondition = when (t[0]) {
        "ChemQty" -> {
            require(t.size == 4) { "ChemQty needs 'ChemQty <species> <>|<> <n>': ${t.joinToString(" ")}" }
            GeneCondition(ConditionType.ChemQty, untok(t[1]), cmp(t[2]), t[3].toInt())
        }
        "Biomass" -> {
            require(t.size == 3) { "Biomass needs 'Biomass <>|<> <n>': ${t.joinToString(" ")}" }
            GeneCondition(ConditionType.Biomass, "", cmp(t[1]), t[2].toInt())
        }
        "Touching" -> {
            require(t.size == 3) { "Touching needs 'Touching <>|<> <n>': ${t.joinToString(" ")}" }
            GeneCondition(ConditionType.Touching, "", cmp(t[1]), t[2].toInt())
        }
        else -> throw IllegalArgumentException("unknown condition: ${t[0]}")
    }

    private fun action(a: GeneAction): String = when (a.type) {
        ActionType.Import -> "Import ${tok(a.a)}"
        ActionType.FormBond -> "FormBond ${tok(a.a)} ${tok(a.b)}"
        ActionType.Convert -> "Convert ${tok(a.a)}"
        ActionType.Expand -> "Expand"
        ActionType.Contract -> "Contract"
        ActionType.Mitosis -> "Mitosis"
        ActionType.Repair -> "Repair"
    }

    private fun parseAction(t: List<String>): GeneAction = when (t[0]) {
        "Import" -> { require(t.size == 2) { fmt(t) }; GeneAction(ActionType.Import, untok(t[1])) }
        "FormBond" -> { require(t.size == 3) { fmt(t) }; GeneAction(ActionType.FormBond, untok(t[1]), untok(t[2])) }
        "Convert" -> { require(t.size == 2) { fmt(t) }; GeneAction(ActionType.Convert, untok(t[1])) }
        "Expand" -> GeneAction(ActionType.Expand)
        "Contract" -> GeneAction(ActionType.Contract)
        "Mitosis" -> GeneAction(ActionType.Mitosis)
        "Repair" -> GeneAction(ActionType.Repair)
        else -> throw IllegalArgumentException("unknown action: ${t[0]}")
    }

    // A mutation can leave an operand empty (e.g. an action-type change on an operand-less gene); encode
    // empty as `_` so every representable gene round-trips (and decode never crashes on a missing token).
    private fun tok(s: String) = s.ifEmpty { "_" }
    private fun untok(s: String) = if (s == "_") "" else s
    private fun fmt(t: List<String>) = "malformed action: ${t.joinToString(" ")}"

    private fun cmp(c: Comparison): String = if (c == Comparison.Greater) ">" else "<"
    private fun cmp(s: String): Comparison = when (s) {
        ">" -> Comparison.Greater
        "<" -> Comparison.Less
        else -> throw IllegalArgumentException("comparator must be > or <: $s")
    }

    private val WS = Regex("\\s+")
}
