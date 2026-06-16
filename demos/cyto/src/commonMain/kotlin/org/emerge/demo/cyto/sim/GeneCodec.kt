package org.emerge.demo.cyto.sim

/**
 * Human-writable text format for a genome (a `List<Gene>`) — for hand-authoring + testing a genome
 * without UI, and the foundation the in-game editor and save file build on.
 *
 * One gene per line, three `:`-separated parts — **energy source : condition : action**:
 *
 *     Light : a < 4 : Import a            # import 'a' while the cytoplasm 'a' count < 4
 *     Light : a > 0 : FormBond a b        # bond an 'a'-ending molecule to a 'b'-starting one
 *     Light : ab > 0 : Convert ab         # lock 'ab' into biomass
 *     Light : Biomass > 8 : Mitosis       # divide once biomass exceeds 8 bonds
 *     Break ab : Biomass < ab : Convert ab  # grow only while biomass is below the stored 'ab' reserve
 *
 * Condition is `<operand> <>|<> <operand>`, each operand one of: an integer (a constant), `Biomass`,
 * `Touching`, or a species token (its cytoplasm count) — and a species token may be any length (`a`, `ab`,
 * `abb`, …), not just a monomer/dimer. Action is `Import <species>`, `FormBond <a> <b>`, `Convert <species>`,
 * `Contract`, `Mitosis`, or `Repair`. Blank lines and `#` comments are ignored. Round-trips every preset
 * genome (see GeneCodecTest).
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

    private fun condition(c: GeneCondition): String =
        "${operand(c.lhs)} ${cmp(c.cmp)} ${operand(c.rhs)}"

    private fun parseCondition(t: List<String>): GeneCondition {
        require(t.size == 3) { "condition needs '<operand> <>|<> <operand>': ${t.joinToString(" ")}" }
        return GeneCondition(parseOperand(t[0]), cmp(t[1]), parseOperand(t[2]))
    }

    private fun operand(op: Operand): String = when (op) {
        is Operand.Constant -> op.value.toString()
        is Operand.Chem -> tok(op.species)
        Operand.Biomass -> "Biomass"
        Operand.Touching -> "Touching"
    }

    // A token is `Biomass`/`Touching` (live readings), an integer (a constant), or a species token (its
    // cytoplasm count). Species are lowercase letters, so they never collide with an integer or a keyword.
    private fun parseOperand(s: String): Operand = when (s) {
        "Biomass" -> Operand.Biomass
        "Touching" -> Operand.Touching
        else -> s.toIntOrNull()?.let { Operand.Constant(it) } ?: Operand.Chem(untok(s))
    }

    private fun action(a: GeneAction): String = when (a.type) {
        ActionType.Import -> "Import ${tok(a.a)}"
        ActionType.FormBond -> "FormBond ${tok(a.a)} ${tok(a.b)}"
        ActionType.Convert -> "Convert ${tok(a.a)}"
        ActionType.Contract -> "Contract"
        ActionType.Mitosis -> "Mitosis"
        ActionType.Repair -> "Repair"
    }

    private fun parseAction(t: List<String>): GeneAction = when (t[0]) {
        "Import" -> { require(t.size == 2) { fmt(t) }; GeneAction(ActionType.Import, untok(t[1])) }
        "FormBond" -> { require(t.size == 3) { fmt(t) }; GeneAction(ActionType.FormBond, untok(t[1]), untok(t[2])) }
        "Convert" -> { require(t.size == 2) { fmt(t) }; GeneAction(ActionType.Convert, untok(t[1])) }
        // Expand was banned (it raised a cell's radius above the biomass soft-cap, coarsening the broadphase
        // grid for the whole world; Contract is kept as the locomotion actuator). Legacy saves decode it to
        // an inert Repair (a no-op while undamaged) rather than crashing on load.
        "Expand" -> GeneAction(ActionType.Repair)
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
