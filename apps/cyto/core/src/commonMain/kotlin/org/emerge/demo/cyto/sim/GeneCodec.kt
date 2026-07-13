package org.emerge.demo.cyto.sim

/**
 * Human-writable text format for a genome (a `List<Gene>`) — for hand-authoring + testing a genome
 * without UI, and the foundation the in-game editor and save file build on.
 *
 * One gene per line, three `:`-separated parts — **energy source : condition : action**:
 *
 *     Light : r < 4 : Import r            # import 'r' while the cytoplasm 'r' count < 4
 *     Light : r > 0 : FormBond r g        # bond an 'r'-ending molecule to a 'g'-starting one
 *     Light : rg > 0 : Convert rg         # lock 'rg' into biomass
 *     Light : Biomass > 8 : Mitosis       # divide once biomass exceeds 8 bonds
 *     Break rg : Biomass < rg : Convert rg  # grow only while biomass is below the stored 'rg' reserve
 *
 * Condition is one or more `<operand> <>|<> <operand>` clauses joined by ` & ` — the gene fires iff **all**
 * hold (e.g. `b > 50 & b < 200` = a concentration band). Each operand is one of: an integer (a constant),
 * `Biomass`, `Touching`, a species token (its cytoplasm count), or `Conc(<species>)` (its size-normalised
 * concentration) — and a species token may be any length (`r`, `rg`, `rgg`, …), not just a monomer/dimer.
 *  Action is `Import <species>`, `FormBond <a> <b>`, `Convert <species>`,
 *  `Contract`, `Mitosis` *(or `Mitosis <morphogen>` for asymmetric division — the morphogen is allocated whole
 *  to one side, MORPHOGENESIS.md §C; append `mother` to keep it in the mother = centred source, vs the default
 *  daughter = edge source; append `sever` so the daughter rejects all mother welds → splits off as a separate
 *  1-celled organism)*, `Repair`, or `Lyse` *(steals all species from touching cells — MORPHOGENESIS.md §B)*.
 *  An optional **4th `:`-part** is a functional-group tag ([Gene.group], e.g. `… : Convert rg : Grow`),
 *  omitted when ungrouped so 3-part genomes are unchanged. Blank lines and `#` comments are ignored.
 *  Round-trips every preset genome (see GeneCodecTest).
 */
object GeneCodec {

    fun serialize(genome: List<Gene>): String = genome.joinToString("\n") { gene ->
        val effSuffix = if (gene.efficiency != 0) " @${gene.efficiency}" else ""   // efficiency gear, omitted when 0
        // The functional-group tag is an optional 4th `:`-part, omitted when ungrouped so untagged genomes
        // (every sandbox preset + pre-tag save/`.gene` file) round-trip byte-identically to the 3-part form.
        val groupSuffix = if (gene.group.isNotEmpty()) " : ${gene.group}" else ""
        "${source(gene.source)} : ${condition(gene.condition)} : ${action(gene.action)}$effSuffix$groupSuffix"
    }

    fun parse(text: String): List<Gene> = text.lines().mapNotNull { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@mapNotNull null
        val parts = line.split(":")
        require(parts.size == 3 || parts.size == 4) { "gene line must have three (or four, with a group tag) ':'-separated parts: \"$raw\"" }
        // The action part may carry a trailing efficiency gear token `@<g>` (e.g. `Convert rg @6`).
        val actionTokens = parts[2].trim().split(WS).toMutableList()
        var efficiency = 0
        if (actionTokens.isNotEmpty() && actionTokens.last().startsWith("@")) {
            efficiency = actionTokens.removeAt(actionTokens.lastIndex).substring(1).toInt()
        }
        Gene(
            source = parseSource(parts[0].trim().split(WS)),
            condition = parseCondition(parts[1]),
            action = parseAction(actionTokens),
            efficiency = efficiency,
            group = if (parts.size == 4) parts[3].trim() else "",
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

    // Whole condition: clauses joined by ` & ` (an AND-conjunction).
    private fun condition(c: GeneCondition): String = c.clauses.joinToString(" & ") { clause ->
        "${operand(clause.lhs)} ${cmp(clause.cmp)} ${operand(clause.rhs)}"
    }

    private fun parseCondition(raw: String): GeneCondition {
        val clauses = raw.split("&").map { part ->
            val t = part.trim().split(WS)
            require(t.size == 3) { "clause needs '<operand> <>|<> <operand>': \"$part\"" }
            Clause(parseOperand(t[0]), cmp(t[1]), parseOperand(t[2]))
        }
        require(clauses.isNotEmpty()) { "condition needs at least one clause: \"$raw\"" }
        return GeneCondition(clauses)
    }

    private fun operand(op: Operand): String = when (op) {
        is Operand.Constant -> op.value.toString()
        is Operand.Chem -> tok(op.species)
        is Operand.Conc -> "Conc(${tok(op.species)})"
        Operand.Biomass -> "Biomass"
        Operand.Touching -> "Touching"
    }

    // A token is `Biomass`/`Touching` (live readings), `Conc(<species>)` (concentration), an integer (a
    // constant), or a species token (its cytoplasm count). Species are lowercase letters, so they never
    // collide with an integer or a keyword.
    private val CONC = Regex("^Conc\\((.*)\\)$")
    private fun parseOperand(s: String): Operand = when {
        s == "Biomass" -> Operand.Biomass
        s == "Touching" -> Operand.Touching
        CONC.matchEntire(s) != null -> Operand.Conc(untok(CONC.matchEntire(s)!!.groupValues[1]))
        else -> s.toIntOrNull()?.let { Operand.Constant(it) } ?: Operand.Chem(untok(s))
    }

    private fun action(a: GeneAction): String = when (a.type) {
        ActionType.Import -> "Import ${tok(a.a)}"
        ActionType.Export -> "Export ${tok(a.a)}"
        // Exact species by default; a wildcard operand is marked with `*` on the outer (non-junction) side:
        // `*a` = any molecule ENDING with a (left), `a*` = any STARTING with a (right). See GeneAction.aWild.
        ActionType.FormBond -> "FormBond ${tok(wildLeft(a.a, a.aWild))} ${tok(wildRight(a.b, a.bWild))}"
        ActionType.Convert -> "Convert ${tok(a.a)}"
        ActionType.Contract -> "Contract"
        // Mitosis: optional morphogen ([GeneAction.a], allocated whole to one side — §C) with a trailing
        // `mother` (centred source) vs default daughter (edge source); optional oriented division
        // `along|across <axis-morphogen>` ([GeneAction.b]/[divideAcross], §Morphogens for shape). Each part
        // is omitted when unset, so a bare `Mitosis` (symmetric, unoriented) round-trips unchanged.
        ActionType.Mitosis -> buildString {
            append("Mitosis")
            if (a.a.isNotEmpty()) { append(" ${tok(a.a)}"); if (a.morphogenToMother) append(" mother") }
            if (a.rejectMother) append(" sever")
            if (a.b.isNotEmpty()) append(if (a.divideAcross) " across ${tok(a.b)}" else " along ${tok(a.b)}")
        }
        ActionType.Repair -> "Repair"
        ActionType.Lyse -> "Lyse"
        ActionType.Retain -> "Retain ${tok(a.a)}"
    }

    private fun parseAction(t: List<String>): GeneAction = when (t[0]) {
        "Import" -> { require(t.size == 2) { fmt(t) }; GeneAction(ActionType.Import, untok(t[1])) }
        "Export" -> { require(t.size == 2) { fmt(t) }; GeneAction(ActionType.Export, untok(t[1])) }
        "FormBond" -> {
            require(t.size == 3) { fmt(t) }
            val (a, aWild) = unwildLeft(untok(t[1]))
            val (b, bWild) = unwildRight(untok(t[2]))
            GeneAction(ActionType.FormBond, a, b, aWild = aWild, bWild = bWild)
        }
        "Convert" -> { require(t.size == 2) { fmt(t) }; GeneAction(ActionType.Convert, untok(t[1])) }
        "Retain" -> { require(t.size == 2) { fmt(t) }; GeneAction(ActionType.Retain, untok(t[1])) }
        // Expand was banned (it raised a cell's radius above the biomass soft-cap, coarsening the broadphase
        // grid for the whole world; Contract is kept as the locomotion actuator). Legacy saves decode it to
        // an inert Repair (a no-op while undamaged) rather than crashing on load.
        "Expand" -> GeneAction(ActionType.Repair)
        "Contract" -> GeneAction(ActionType.Contract)
        // `Mitosis [<morphogen> [mother|daughter]] [along|across <axis-morphogen>]` — optional asymmetric
        // morphogen + retain-side (§C/§Source placement), optional oriented-division axis (§Morphogens for
        // shape). Keyword tokens (mother/daughter/along/across) are recognised positionally; a bare `Mitosis`
        // = symmetric + unoriented.
        "Mitosis" -> {
            val kw = setOf("mother", "daughter", "along", "across", "sever")
            var i = 1; var morph = ""; var toMother = false; var axis = ""; var across = false; var sever = false
            if (i < t.size && t[i] !in kw) { morph = untok(t[i]); i++ }
            if (i < t.size && (t[i] == "mother" || t[i] == "daughter")) { toMother = t[i] == "mother"; i++ }
            if (i < t.size && t[i] == "sever") { sever = true; i++ }
            if (i < t.size && (t[i] == "along" || t[i] == "across")) {
                across = t[i] == "across"; require(i + 1 < t.size) { fmt(t) }; axis = untok(t[i + 1]); i += 2
            }
            require(i == t.size) { fmt(t) }
            GeneAction(ActionType.Mitosis, morph, axis, morphogenToMother = toMother, divideAcross = across, rejectMother = sever)
        }
        "Repair" -> GeneAction(ActionType.Repair)
        "Lyse" -> GeneAction(ActionType.Lyse)
        else -> throw IllegalArgumentException("unknown action: ${t[0]}")
    }

    // A mutation can leave an operand empty (e.g. an action-type change on an operand-less gene); encode
    // empty as `_` so every representable gene round-trips (and decode never crashes on a missing token).
    private fun tok(s: String) = s.ifEmpty { "_" }
    private fun untok(s: String) = if (s == "_") "" else s

    // FormBond wildcard `*` marker, on the outer (non-junction) side: `*a` left / `a*` right. Only emitted
    // for a non-empty wildcard operand (an empty operand is a no-op gene, kept as `_`).
    private fun wildLeft(s: String, wild: Boolean) = if (wild && s.isNotEmpty()) "*$s" else s
    private fun wildRight(s: String, wild: Boolean) = if (wild && s.isNotEmpty()) "$s*" else s
    private fun unwildLeft(s: String): Pair<String, Boolean> = if (s.startsWith("*")) s.substring(1) to true else s to false
    private fun unwildRight(s: String): Pair<String, Boolean> = if (s.endsWith("*")) s.dropLast(1) to true else s to false
    private fun fmt(t: List<String>) = "malformed action: ${t.joinToString(" ")}"

    private fun cmp(c: Comparison): String = if (c == Comparison.Greater) ">" else "<"
    private fun cmp(s: String): Comparison = when (s) {
        ">" -> Comparison.Greater
        "<" -> Comparison.Less
        else -> throw IllegalArgumentException("comparator must be > or <: $s")
    }

    private val WS = Regex("\\s+")
}
