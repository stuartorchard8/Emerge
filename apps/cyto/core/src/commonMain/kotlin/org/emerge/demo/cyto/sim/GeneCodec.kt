package org.emerge.demo.cyto.sim

/**
 * Human-writable text format for a genome (a `List<Gene>`) — for hand-authoring + testing a genome
 * without UI, and the foundation the in-game editor and save file build on.
 *
 * One gene per line, three `:`-separated parts — **energy source : condition : action**:
 *
 *     Light : r < 4 : Import r            # import 'r' while the cytoplasm 'r' count < 4
 *     Light : rg > 0 : Convert rg         # lock 'rg' into biomass
 *     Light : Biomass > 8 : Divide       # divide once biomass exceeds 8 bonds
 *     Bond r g : Biomass < 8 : Convert rg # join r+g for energy (and 'rg'), spend it locking 'rg' into biomass
 *     Bond r g : rg > 0 : Break g b        # ...and spend it splitting 'gb' back into 'g' + 'b' (digestion)
 *
 * ## Genome versioning
 *
 * The first line may be `# genome <n>`, declaring which **gene model** the text was written against
 * ([GENOME_VERSION] is current). Text without the header is treated as [GENOME_VERSION_PRE_INVERSION] — the
 * pre-inversion model, where breaking a bond *released* energy and building one cost it — and is upgraded on
 * parse by [GenomeMigration]. That covers every `.gene` file and save written before the chemistry was
 * inverted, so old genomes stay loadable. [serialize] always emits the header, so a genome upgrades once and
 * then round-trips.
 *
 * Condition is one or more `<operand> <>|<> <operand>` clauses joined by ` & ` — the gene fires iff **all**
 * hold (e.g. `b > 50 & b < 200` = a concentration band). Each operand is one of: an integer (a constant),
 * `Biomass`, `Touching`, `Neighbours`, a species token (its cytoplasm count), or `Conc(<species>)` (its size-normalised
 * concentration) — and a species token may be any length (`r`, `rg`, `rgg`, …), not just a monomer/dimer.
 *  The energy source is `Light` or `Bond <a> <b>` (synthesis — join the molecule `<a>` to the molecule
 *  `<b>`, releasing a quantum. Both operands are exact whole species and the pair is ordered, so
 *  `Bond rg b` and `Bond r gb` are different reactions that both build `rgb`).
 *  Action is `Import <species>`, `Break <a> <b>` *(energy-costed digestion — the mirror of the `Bond`
 *  source: names the two fragments, splits the molecule they would join into)*, `Convert <species>`,
 *  `Contract`, `Divide` *(or `Divide <morphogen>` for asymmetric division — the morphogen is allocated whole
 *  to one side, MORPHOGENESIS.md §C; append `mother` to keep it in the mother = centred source, vs the default
 *  daughter = edge source; append `sever` so the daughter rejects all mother welds → splits off as a separate
 *  1-celled organism)*, `Repair`, or `Lyse` *(steals all species from touching cells — MORPHOGENESIS.md §B)*.
 *  An optional **4th `:`-part** is a functional-group tag ([Gene.group], e.g. `… : Convert rg : Grow`),
 *  omitted when ungrouped so 3-part genomes are unchanged. Blank lines and `#` comments are ignored.
 *  Round-trips every preset genome (see GeneCodecTest).
 */
object GeneCodec {

    /** The gene model this build speaks. Bump whenever a change makes previously-written genomes mean
     *  something different (or nothing), and add the upgrade step to [GenomeMigration].
     *  - 1: the pre-inversion model — `Break <bond>` in source position was an energy *yield*, `FormBond
     *       <a> <b>` in action position was an energy *cost*.
     *  - 2: the inverted chemistry (HYDROTHERMAL_CHEMISTRY_PLAN.md) — synthesis is the energy source
     *       (`Bond <a> <b>`), breaking is a costed action (`Break <bond>`, splitting the richest molecule
     *       holding that bond).
     *  - 3: `Break` became the exact mirror of `Bond` — `Break <a> <b>` names the two FRAGMENTS and splits
     *       the molecule they would join into, so a digestion gene names its own substrate instead of
     *       depending on what the cell happens to hold. */
    const val GENOME_VERSION = 4

    /** The version assumed for text with no `# genome` header — i.e. everything written before versioning
     *  existed, which is by definition the pre-inversion model. */
    const val GENOME_VERSION_PRE_INVERSION = 1

    /** Header line declaring the gene model: `# genome <n>`. A special comment, so a *newer* build reading
     *  it is version-aware while any older build just ignores it as a comment. */
    private val GENOME_VER = Regex("^#\\s*genome\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE)

    /** The gene-model version [text] declares, or [GENOME_VERSION_PRE_INVERSION] when it declares none. */
    fun parseVersion(text: String): Int =
        text.lines().firstNotNullOfOrNull { GENOME_VER.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            ?: GENOME_VERSION_PRE_INVERSION

    /** Header line marking a chemical alias: `# alias <species> <name>` (a special comment, so files without
     *  it — every legacy `.gene` — are unaffected). Display-only; see [CytoScenario.aliases]. */
    private val ALIAS = Regex("^#\\s*alias\\s+(\\S+)\\s+(\\S+)\\s*$", RegexOption.IGNORE_CASE)

    /** The chemical aliases declared by `# alias <species> <name>` header lines (species token → name).
     *  Empty when the text declares none. Independent of [parse], which ignores these as comments. */
    fun parseAliases(text: String): Map<String, String> =
        text.lines().mapNotNull { line -> ALIAS.matchEntire(line.trim())?.let { it.groupValues[1] to it.groupValues[2] } }
            .toMap()

    /** [genome] plus optional [aliases] emitted as `# alias` header lines (so a curated genome round-trips
     *  its names). Aliases come first, then the genes. */
    fun serialize(genome: List<Gene>, aliases: Map<String, String>): String {
        val header = aliases.entries.joinToString("") { "# alias ${it.key} ${it.value}\n" }
        return header + serialize(genome)
    }

    fun serialize(genome: List<Gene>): String {
        val body = genome.joinToString("\n") { gene ->
            val effSuffix = if (gene.efficiency != 0) " @${gene.efficiency}" else ""   // efficiency gear, omitted when 0
            // The functional-group tag is an optional 4th `:`-part, omitted when ungrouped so untagged genomes
            // (every sandbox preset + pre-tag save/`.gene` file) round-trip byte-identically to the 3-part form.
            val groupSuffix = if (gene.group.isNotEmpty()) " : ${gene.group}" else ""
            "${source(gene.source)} : ${condition(gene.condition)} : ${action(gene.action)}$effSuffix$groupSuffix"
        }
        return "# genome $GENOME_VERSION\n$body"
    }

    /** Parse [text], upgrading it from whatever gene model it declares (see [parseVersion]) to the current
     *  one. Genomes older than [GENOME_VERSION] go through [GenomeMigration], so a pre-inversion `.gene` file
     *  or save loads as a coherent modern genome rather than failing or silently meaning something else. */
    fun parse(text: String): List<Gene> {
        val version = parseVersion(text)
        return text.lines().mapNotNull { raw -> parseGene(raw, version) }
    }

    private fun parseGene(raw: String, version: Int): Gene? {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return null
        val parts = line.split(":")
        require(parts.size == 3 || parts.size == 4) { "gene line must have three (or four, with a group tag) ':'-separated parts: \"$raw\"" }
        // The action part may carry a trailing efficiency gear token `@<g>` (e.g. `Convert rg @6`).
        val actionTokens = parts[2].trim().split(WS).toMutableList()
        var efficiency = 0
        if (actionTokens.isNotEmpty() && actionTokens.last().startsWith("@")) {
            efficiency = actionTokens.removeAt(actionTokens.lastIndex).substring(1).toInt()
        }
        val sourceTokens = parts[0].trim().split(WS)
        val gene = Gene(
            source = parseSource(sourceTokens),
            condition = parseCondition(parts[1]),
            action = parseAction(actionTokens),
            efficiency = efficiency,
            group = if (parts.size == 4) parts[3].trim() else "",
        )
        if (version >= GENOME_VERSION) return gene
        // Pre-inversion text. `parseSource`/`parseAction` have already mapped each legacy keyword to its
        // modern counterpart *in isolation*; only the both-at-once case still needs reconciling.
        return GenomeMigration.reconcilePreInversion(
            gene,
            hadBreakSource = sourceTokens[0] == "Break",
            legacySynthesis = if (actionTokens.firstOrNull() == "FormBond") parseSynthesis(actionTokens) else null,
        )
    }

    private fun source(s: EnergySource): String = when (s) {
        is EnergySource.Light -> "Light"
        is EnergySource.FormBond -> "Bond ${tok(s.a)} ${tok(s.b)}"
    }

    private fun parseSource(t: List<String>): EnergySource = when (t[0]) {
        "Light" -> EnergySource.Light
        "Bond" -> when (t.size) {
            3 -> parseSynthesis(t)
            // Legacy single-operand form `Bond <bond>` — the short-lived interim shape where the synthesis
            // source could only spark a literal monomer pair. Widen it to the equivalent explicit pair.
            2 -> EnergySource.FormBond(t[1].take(1), t[1].drop(1))
            else -> throw IllegalArgumentException("Bond needs 'Bond <a> <b>': ${t.joinToString(" ")}")
        }
        // PRE-INVERSION `Break <bond>` in SOURCE position: breaking used to be the energy yield. Its modern
        // counterpart is joining the same bond's two halves (migration rule 2) — the organism keeps the bond
        // it was built around, just on the other side of the reaction. If the gene ALSO had a synthesis
        // action, `reconcilePreInversion` overrides this with rule 3.
        "Break" -> {
            require(t.size == 2) { "Break needs 'Break <bond>': ${t.joinToString(" ")}" }
            EnergySource.FormBond(t[1].take(1), t[1].drop(1))
        }
        else -> throw IllegalArgumentException("unknown energy source: ${t[0]}")
    }

    /** `<kw> <a> <b>` → the synthesis reaction it names. Shared by the modern `Bond a b` source and the
     *  pre-inversion `FormBond a b` action, which describe the identical reaction.
     *
     *  Legacy `*a` / `b*` wildcard markers are **parsed and dropped**: wildcards no longer exist (see
     *  [EnergySource.FormBond]), so an old genome's wildcard operand is read as the exact species it names.
     *  That is a behaviour change for those genes by necessity — a wildcard has no exact equivalent — but it
     *  keeps them loadable and coherent, which is the migration contract ([GenomeMigration]). */
    private fun parseSynthesis(t: List<String>): EnergySource.FormBond {
        require(t.size == 3) { fmt(t) }
        return EnergySource.FormBond(stripWild(untok(t[1])), stripWild(untok(t[2])))
    }

    // Whole condition: clauses joined by ` & ` (an AND-conjunction). An empty (unconditional) gate — which
    // fires ALWAYS — has no clauses to print, so it round-trips as the keyword `Always`.
    private fun condition(c: GeneCondition): String =
        if (c.clauses.isEmpty()) "Always"
        else c.clauses.joinToString(" & ") { clause ->
            "${operand(clause.lhs)} ${cmp(clause.cmp)} ${operand(clause.rhs)}"
        }

    private fun parseCondition(raw: String): GeneCondition {
        // `Always` (or a bare empty part) = the unconditional gate — a gene with no clauses, vacuously true.
        if (raw.trim().equals("Always", ignoreCase = true) || raw.isBlank()) return GeneCondition(emptyList())
        val clauses = raw.split("&").map { part ->
            val t = part.trim().split(WS)
            require(t.size == 3) { "clause needs '<operand> <>|<> <operand>': \"$part\"" }
            Clause(parseOperand(t[0]), cmp(t[1]), parseOperand(t[2]))
        }
        return GeneCondition(clauses)
    }

    private fun operand(op: Operand): String = when (op) {
        is Operand.Constant -> op.value.toString()
        is Operand.Chem -> tok(op.species)
        Operand.Biomass -> "Biomass"
        Operand.Touching -> "Touching"
        Operand.Neighbours -> "Neighbours"
    }

    // A token is `Biomass`/`Touching`/`Neighbours` (live readings), an integer (a constant), or a species
    // token (its cytoplasm count). Species are lowercase letters, so they never collide with an integer or
    // a keyword.
    //
    // `Conc(<species>)` is the retired v3 concentration operand, kept here as a MIGRATION branch only (see
    // GENOME_VERSION 4 and [GenomeMigration]). It must stay ahead of the species fallback: without it,
    // `Conc(gb)` does not fail to parse, it silently becomes a species literally NAMED "Conc(gb)", giving a
    // genome that loads looking correct and never fires. Mapping it to the raw count is exact for the only
    // form that survived in practice (`Conc(x) > 0`, since a positive count over positive biomass floors
    // above zero) and coherent-but-different otherwise, which is [GenomeMigration]'s stated contract.
    private val CONC = Regex("^Conc\\((.*)\\)$")
    private fun parseOperand(s: String): Operand = when {
        s == "Biomass" -> Operand.Biomass
        s == "Touching" -> Operand.Touching
        s == "Neighbours" -> Operand.Neighbours
        CONC.matchEntire(s) != null -> Operand.Chem(untok(CONC.matchEntire(s)!!.groupValues[1]))
        else -> s.toIntOrNull()?.let { Operand.Constant(it) } ?: Operand.Chem(untok(s))
    }

    private fun action(a: GeneAction): String = when (a.type) {
        ActionType.Import -> "Import ${tok(a.a)}"
        ActionType.Export -> "Export ${tok(a.a)}"
        // The mirror of `Bond <a> <b>`: [a]/[b] are the two fragments, and the molecule split is the one
        // they would join into (an energy-costed digestion step).
        ActionType.BreakBond -> "Break ${tok(a.a)} ${tok(a.b)}"
        ActionType.Convert -> "Convert ${tok(a.a)}"
        ActionType.Contract -> "Contract"
        // Divide: optional morphogen ([GeneAction.a], allocated whole to one side — §C) with a trailing
        // `mother` (centred source) vs default daughter (edge source); optional oriented division
        // `along|across <axis-morphogen>` ([GeneAction.b]/[divideAcross], §Morphogens for shape). Each part
        // is omitted when unset, so a bare `Divide` (symmetric, unoriented) round-trips unchanged.
        ActionType.Divide -> buildString {
            append("Divide")
            if (a.a.isNotEmpty()) { append(" ${tok(a.a)}"); if (a.morphogenToMother) append(" mother") }
            if (a.rejectMother) append(" sever")
            if (a.b.isNotEmpty()) append(if (a.divideAcross) " across ${tok(a.b)}" else " along ${tok(a.b)}")
        }
        ActionType.Repair -> "Repair"
        ActionType.Lyse -> "Lyse"
        ActionType.Retain -> "Retain ${tok(a.a)}"
        // The authoring blank — an inert gene with no action chosen yet. Round-trips as the keyword `None`.
        ActionType.None -> "None"
    }

    private fun parseAction(t: List<String>): GeneAction = when (t[0]) {
        "Import" -> { require(t.size == 2) { fmt(t) }; GeneAction(ActionType.Import, untok(t[1])) }
        "Export" -> { require(t.size == 2) { fmt(t) }; GeneAction(ActionType.Export, untok(t[1])) }
        // PRE-INVERSION `FormBond <a> <b>` in ACTION position: building used to be what you spent energy on.
        // Its modern counterpart is splitting what it would have built (migration rule 1) — and because
        // `Break` is now the exact mirror of `Bond`, the two operands carry over VERBATIM: a gene that joined
        // `a`+`b` becomes one that splits them back apart. When the gene's SOURCE was also legacy,
        // `reconcilePreInversion` applies rule 3 instead.
        "FormBond" -> parseSynthesis(t).let { GeneAction(ActionType.BreakBond, it.a, it.b) }
        // `Break <a> <b>` (current). The 2-token form is the v2 shape, where the operand was the 2-atom
        // BOND to break rather than the fragments: read it as splitting that bond's own two halves, which is
        // what it meant whenever the cell's richest match was the bare dimer.
        "Break" -> when (t.size) {
            3 -> GeneAction(ActionType.BreakBond, untok(t[1]), untok(t[2]))
            2 -> untok(t[1]).let { GeneAction(ActionType.BreakBond, it.take(1), it.drop(1)) }
            else -> throw IllegalArgumentException(fmt(t))
        }
        "Convert" -> { require(t.size == 2) { fmt(t) }; GeneAction(ActionType.Convert, untok(t[1])) }
        "Retain" -> { require(t.size == 2) { fmt(t) }; GeneAction(ActionType.Retain, untok(t[1])) }
        // Expand was banned (it raised a cell's radius above the biomass soft-cap, coarsening the broadphase
        // grid for the whole world; Contract is kept as the locomotion actuator). Legacy saves decode it to
        // an inert Repair (a no-op while undamaged) rather than crashing on load.
        "Expand" -> GeneAction(ActionType.Repair)
        "Contract" -> GeneAction(ActionType.Contract)
        // `Divide [<morphogen> [mother|daughter]] [along|across <axis-morphogen>]` — optional asymmetric
        // morphogen + retain-side (§C/§Source placement), optional oriented-division axis (§Morphogens for
        // shape). Keyword tokens (mother/daughter/along/across) are recognised positionally; a bare `Divide`
        // = symmetric + unoriented.
        "Divide" -> {
            val kw = setOf("mother", "daughter", "along", "across", "sever")
            var i = 1; var morph = ""; var toMother = false; var axis = ""; var across = false; var sever = false
            if (i < t.size && t[i] !in kw) { morph = untok(t[i]); i++ }
            if (i < t.size && (t[i] == "mother" || t[i] == "daughter")) { toMother = t[i] == "mother"; i++ }
            if (i < t.size && t[i] == "sever") { sever = true; i++ }
            if (i < t.size && (t[i] == "along" || t[i] == "across")) {
                across = t[i] == "across"; require(i + 1 < t.size) { fmt(t) }; axis = untok(t[i + 1]); i += 2
            }
            require(i == t.size) { fmt(t) }
            GeneAction(ActionType.Divide, morph, axis, morphogenToMother = toMother, divideAcross = across, rejectMother = sever)
        }
        "Repair" -> GeneAction(ActionType.Repair)
        "Lyse" -> GeneAction(ActionType.Lyse)
        // The authoring blank (an inert, action-less gene). `_` is the empty-token spelling a mutation can
        // also leave behind when it clears an operand-less action's tokens, so decode it here too.
        "None", "_" -> GeneAction(ActionType.None)
        else -> throw IllegalArgumentException("unknown action: ${t[0]}")
    }

    // A mutation can leave an operand empty (e.g. an action-type change on an operand-less gene); encode
    // empty as `_` so every representable gene round-trips (and decode never crashes on a missing token).
    private fun tok(s: String) = s.ifEmpty { "_" }
    private fun untok(s: String) = if (s == "_") "" else s

    // Legacy wildcard markers (`*a` left / `a*` right) are accepted on read and discarded — see
    // [parseSynthesis]. Nothing emits them any more.
    private fun stripWild(s: String) = s.removePrefix("*").removeSuffix("*")
    private fun fmt(t: List<String>) = "malformed action: ${t.joinToString(" ")}"

    private fun cmp(c: Comparison): String = if (c == Comparison.Greater) ">" else "<"
    private fun cmp(s: String): Comparison = when (s) {
        ">" -> Comparison.Greater
        "<" -> Comparison.Less
        else -> throw IllegalArgumentException("comparator must be > or <: $s")
    }

    private val WS = Regex("\\s+")
}
