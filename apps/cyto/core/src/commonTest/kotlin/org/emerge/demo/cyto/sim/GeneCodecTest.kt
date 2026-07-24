package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneCodecTest {

    /** The gene lines of a serialized genome, without the leading `# genome <n>` header — so an assertion
     *  can name the DSL text it cares about without restating the version on every line. */
    private fun body(genome: List<Gene>): String = GeneCodec.serialize(genome).substringAfter("\n")

    /** Every preset genome survives serialize → parse with identical structure (Gene is a data class,
     *  so == is structural). */
    @Test
    fun roundTripsEveryPreset() {
        for (type in CellType.entries) {
            val genome = genomeForType(type)
            val back = GeneCodec.parse(GeneCodec.serialize(genome))
            assertEquals(genome, back, "$type genome")
        }
    }

    /** Chemical aliases round-trip through `# alias` header lines, and [parse] treats them as comments
     *  (ignores them) so a genome parses to the same genes whether or not the aliases are present. */
    @Test
    fun roundTripsAliases() {
        val genome = genomeForType(CellType.Collector)
        val aliases = mapOf("rg" to "fuel", "bb" to "marker")
        val text = GeneCodec.serialize(genome, aliases)
        assertEquals(aliases, GeneCodec.parseAliases(text), "aliases survive serialize → parseAliases")
        assertEquals(genome, GeneCodec.parse(text), "the `# alias` headers don't disturb gene parsing")
        // A genome with no aliases declares none.
        assertEquals(emptyMap(), GeneCodec.parseAliases(GeneCodec.serialize(genome)))
    }

    /** The functional-group tag ([Gene.group]) round-trips as the optional 4th `:`-part, so grouping survives
     *  save/load (the world save codec serializes through GeneCodec). Untagged genes stay 3-part; a tag with a
     *  space is preserved verbatim. */
    @Test
    fun roundTripsGroupTag() {
        val genome = listOf(
            Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(3000)), GeneAction(ActionType.Convert, "rg"), group = "Grow"),
            Gene(EnergySource.FormBond("r", "g"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(2000)), GeneAction(ActionType.Divide, rejectMother = true), group = "Hold Together"),
            Gene(EnergySource.FormBond("r", "g"), GeneCondition(Operand.Chem("rg"), Comparison.Less, Operand.Constant(3000)), GeneAction(ActionType.Convert, "rg")),  // untagged
        )
        val text = GeneCodec.serialize(genome)
        assertEquals(genome, GeneCodec.parse(text), "tagged genome round-trip")
        assertEquals(3, text.lines().last().split(":").size, "untagged gene stays 3-part")
    }

    /** Genes a mutation can produce — an empty [Operand.Chem] species / empty action operands (after a
     *  kind- or action-type flip) — must round-trip, not crash decode. Guards the save path. */
    @Test
    fun roundTripsEmptyOperandsAndSpecies() {
        val genome = listOf(
            Gene(EnergySource.Light, GeneCondition(Operand.Chem(""), Comparison.Less, Operand.Constant(9)), GeneAction(ActionType.Import, "")),
            Gene(EnergySource.FormBond("", ""), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(3)), GeneAction(ActionType.Import, "")),
        )
        assertEquals(genome, GeneCodec.parse(GeneCodec.serialize(genome)), "empty operand/species genome")
    }

    /** An **unconditional** gate (no clauses — reads ALWAYS in the editor) and a fully-**blank** gene (no
     *  condition, [ActionType.None] action) both round-trip: the empty gate serializes as `Always` and the
     *  blank action as `None`, and each decodes back to the same structure. Guards the authoring blank the
     *  in-game "+ NEW GENE" now creates through the save path. */
    @Test
    fun roundTripsUnconditionalAndBlankGene() {
        val always = Gene(EnergySource.Light, GeneCondition(emptyList()), GeneAction(ActionType.Convert, "rg"))
        assertEquals(listOf(always), GeneCodec.parse(body(listOf(always))), "unconditional (ALWAYS) gate")
        assertTrue(body(listOf(always)).contains("Always"), "empty gate serializes as `Always`")

        val blank = Gene(EnergySource.Light, GeneCondition(emptyList()), GeneAction(ActionType.None))
        assertEquals(listOf(blank), GeneCodec.parse(body(listOf(blank))), "fully-blank authoring gene")
    }

    /** Every action type round-trips — so a newly-added action can't silently fail to serialize (the
     *  KDoc promises every representable gene round-trips; this is the enum-exhaustive backstop). */
    @Test
    fun roundTripsEveryActionType() {
        for (action in ActionType.entries) {
            // Empty operands serialize as `_` and decode back to empty, so this holds for the
            // operand-carrying (Import/FormBond/Convert) and the bare-token (Contract/Divide/Repair)
            // actions alike — the point is that the action token itself survives. (Divide with a
            // morphogen operand is covered by roundTripsAsymmetricDivideMorphogen below.)
            val gene = Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(action))
            val back = GeneCodec.parse(body(listOf(gene)))
            assertEquals(listOf(gene), back, "$action")
        }
    }

    /** Every operand kind round-trips on either side of the gate — the exhaustive backstop for the
     *  operand tokens (constant / species / Biomass / Touching / Neighbours) now that both
     *  sides are operands. */
    @Test
    fun roundTripsEveryOperandKindOnBothSides() {
        val kinds = listOf(Operand.Constant(7), Operand.Chem("rg"), Operand.Biomass, Operand.Touching, Operand.Neighbours)
        for (op in kinds) {
            val asLhs = Gene(EnergySource.Light, GeneCondition(op, Comparison.Greater, Operand.Constant(2)), GeneAction(ActionType.Divide))
            assertEquals(listOf(asLhs), GeneCodec.parse(body(listOf(asLhs))), "$op as lhs")
            val asRhs = Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Less, op), GeneAction(ActionType.Divide))
            assertEquals(listOf(asRhs), GeneCodec.parse(body(listOf(asRhs))), "$op as rhs")
        }
    }

    /** A condition can compare two live variables (no constant at all) — the headline of the operand
     *  generalisation — and that round-trips too. */
    @Test
    fun roundTripsAVariableVsVariableCondition() {
        val gene = Gene(EnergySource.FormBond("r", "g"), GeneCondition(Operand.Biomass, Comparison.Less, Operand.Chem("rg")), GeneAction(ActionType.Convert, "rg"))
        assertEquals(listOf(gene), GeneCodec.parse(body(listOf(gene))), "biomass < stored rg reserve")
    }

    /** Asymmetric divide (MORPHOGENESIS.md §C) names a morphogen that must round-trip — this is the
     *  capability that lets §C genomes be hand-authored / saved as text. A bare `Divide` (symmetric) still
     *  parses to an empty operand, so existing genomes are unaffected. */
    @Test
    fun roundTripsAsymmetricDivideMorphogen() {
        val asymmetric = Gene(EnergySource.FormBond("r", "g"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(8)), GeneAction(ActionType.Divide, "m"))
        assertEquals("Bond r g : Biomass > 8 : Divide m", body(listOf(asymmetric)), "serialized form")
        assertEquals(listOf(asymmetric), GeneCodec.parse(body(listOf(asymmetric))), "asymmetric divide morphogen")

        // Retain-side (MORPHOGENESIS.md §Source placement): `mother` keeps the morphogen in the mother
        // (centred source); default/`daughter` hands it out (edge source).
        val toMother = Gene(EnergySource.FormBond("r", "g"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(8)), GeneAction(ActionType.Divide, "m", morphogenToMother = true))
        assertEquals("Bond r g : Biomass > 8 : Divide m mother", body(listOf(toMother)), "serialized mother-retention")
        assertEquals(listOf(toMother), GeneCodec.parse(body(listOf(toMother))), "mother-retention round-trip")
        assertEquals(listOf(asymmetric), GeneCodec.parse("Break rg : Biomass > 8 : Divide m daughter"), "explicit 'daughter' = default")

        // A bare `Divide` token (no operand) still decodes to a symmetric, daughter-side split — backward compatibility.
        val symmetric = Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(8)), GeneAction(ActionType.Divide))
        assertEquals(listOf(symmetric), GeneCodec.parse("Light : Biomass > 8 : Divide"), "bare Divide stays symmetric")
    }

    /**
     * A retired-v3 `Conc(x)` operand migrates to the raw count `x` — it must NOT fall through to the
     * species fallback.
     *
     * This is the one hazard that made dropping `Conc` more than a deletion (GENE_OPERANDS_PLAN §1.4).
     * `parseOperand` ends in `Operand.Chem(untok(s))` for anything unrecognised, so with no explicit branch
     * `Conc(gb)` does not fail to parse — it silently becomes a species literally NAMED "Conc(gb)", which no
     * cell ever holds. The genome loads looking correct and the gene simply never fires. Assert on the
     * parsed species, not just that parsing succeeded, because the corrupt form parses "fine".
     */
    @Test
    fun migratesRetiredConcOperandToRawCount() {
        val parsed = GeneCodec.parse("Light : Conc(gb) > 30 : Convert rg")
        val lhs = parsed.single().condition.clauses.single().lhs
        assertEquals(Operand.Chem("gb"), lhs, "Conc(gb) must migrate to the gb count, not a species named \"Conc(gb)\"")

        // `Conc(x) > 0` is the form that actually survived in the genome library, and it migrates EXACTLY:
        // a positive count over positive biomass always floors above zero, so the gate is unchanged.
        val zero = GeneCodec.parse("Light : Conc(r) > 0 : Repair").single().condition.clauses.single()
        assertEquals(Operand.Chem("r"), zero.lhs, "Conc(r) > 0 migrates to r > 0")

        // And it is gone from the output: nothing re-serialises as Conc(...).
        assertEquals("Light : gb > 30 : Convert rg", body(parsed), "migrated genome serialises without Conc")
    }

    /** A multi-clause AND condition round-trips — the positional-band readout the morphogen-for-shape work
     *  relies on (MORPHOGENESIS.md §Morphogens for shape). A bare single-clause condition still parses
     *  (backward compatible). */
    @Test
    fun roundTripsBandAndMultiClause() {
        val band = Gene(
            EnergySource.Light,
            GeneCondition(listOf(
                Clause(Operand.Chem("rb"), Comparison.Greater, Operand.Constant(50)),
                Clause(Operand.Chem("rb"), Comparison.Less, Operand.Constant(200)),
            )),
            GeneAction(ActionType.Convert, "rg"),
        )
        assertEquals("Light : rb > 50 & rb < 200 : Convert rg", body(listOf(band)), "serialized band")
        assertEquals(listOf(band), GeneCodec.parse(body(listOf(band))), "band round-trip")

        // A hand-written multi-clause gate parses into ordered clauses; a single-clause one still works.
        assertEquals(
            GeneCondition(listOf(
                Clause(Operand.Biomass, Comparison.Greater, Operand.Constant(8)),
                Clause(Operand.Chem("rg"), Comparison.Less, Operand.Constant(4)),
            )),
            GeneCodec.parse("Light : Biomass > 8 & rg < 4 : Divide").single().condition,
            "multi-clause parse",
        )
    }

    /** Oriented division (MORPHOGENESIS.md §Morphogens for shape): the axis-morphogen + along/across mode
     *  round-trip, composing with the asymmetric morphogen + retain-side. */
    @Test
    fun roundTripsOrientedDivide() {
        val gate = GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(8))
        // axis only (no asymmetric morphogen): `along`/`across <axis>`.
        val along = Gene(EnergySource.Light, gate, GeneAction(ActionType.Divide, b = "gb"))
        assertEquals("Light : Biomass > 8 : Divide along gb", body(listOf(along)), "along, axis only")
        assertEquals(listOf(along), GeneCodec.parse(body(listOf(along))))
        // all four Divide params at once: asym morphogen → mother, oriented across an axis.
        val full = Gene(EnergySource.FormBond("r", "g"), gate, GeneAction(ActionType.Divide, a = "rb", b = "gb", morphogenToMother = true, divideAcross = true))
        assertEquals("Bond r g : Biomass > 8 : Divide rb mother across gb", body(listOf(full)), "asym+mother+across")
        assertEquals(listOf(full), GeneCodec.parse(body(listOf(full))))
    }

    /** Divide with `sever` — the daughter rejects all mother welds, splitting off as a separate 1-celled
     *  organism. Round-trips with and without asymmetric morphogen. */
    @Test
    fun roundTripsDivideSever() {
        val gate = GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(8))
        // bare sever: `sever` alone
        val sever = Gene(EnergySource.Light, gate, GeneAction(ActionType.Divide, rejectMother = true))
        assertEquals("Light : Biomass > 8 : Divide sever", body(listOf(sever)))
        assertEquals(listOf(sever), GeneCodec.parse(body(listOf(sever))), "sever round-trip")

        // sever + asymmetric morphogen to mother
        val fullSever = Gene(EnergySource.FormBond("r", "g"), gate, GeneAction(ActionType.Divide, a = "x", morphogenToMother = true, rejectMother = true))
        assertEquals("Bond r g : Biomass > 8 : Divide x mother sever", body(listOf(fullSever)))
        assertEquals(listOf(fullSever), GeneCodec.parse(body(listOf(fullSever))), "sever+mother round-trip")

        // sever + oriented division
        val orientedSever = Gene(EnergySource.Light, gate, GeneAction(ActionType.Divide, b = "gb", divideAcross = true, rejectMother = true))
        assertEquals(orientedSever, GeneCodec.parse("Light : Biomass > 8 : Divide sever across gb").single())
    }

    /** Synthesis operands are exact whole species and the pair is ordered, so `Bond rg b` and `Bond r gb`
     *  are different genes that both build `rgb`. The `*` wildcard markers (MORPHOGENESIS.md §2026-06-18)
     *  are gone: legacy text carrying them still parses — dropping the marker and keeping the species it
     *  named — but nothing ever emits one again. */
    @Test
    fun roundTripsSynthesisAndDropsLegacyWildcards() {
        val gate = GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0))
        val act = GeneAction(ActionType.Convert, "rg")

        val exact = Gene(EnergySource.FormBond("r", "g"), gate, act)
        assertEquals("Bond r g : Biomass > 0 : Convert rg", body(listOf(exact)), "exact form")

        // Ordered pair: same product, different reaction, and both round-trip distinctly.
        val leftHeavy = Gene(EnergySource.FormBond("rg", "b"), gate, act)
        val rightHeavy = Gene(EnergySource.FormBond("r", "gb"), gate, act)
        assertEquals("Bond rg b : Biomass > 0 : Convert rg", body(listOf(leftHeavy)), "multi-atom left operand")
        assertEquals("Bond r gb : Biomass > 0 : Convert rg", body(listOf(rightHeavy)), "multi-atom right operand")
        assertTrue(leftHeavy != rightHeavy, "rg+b and r+gb build the same product but are different genes")

        for (g in listOf(exact, leftHeavy, rightHeavy)) {
            assertEquals(listOf(g), GeneCodec.parse(GeneCodec.serialize(listOf(g))), "round-trip $g")
        }

        // Legacy wildcard markers: parsed, stripped, never re-emitted.
        val fromWild = GeneCodec.parse("Bond *rg b* : Biomass > 0 : Convert rg").single()
        assertEquals(EnergySource.FormBond("rg", "b"), fromWild.source, "`*` markers are dropped, the species kept")
        assertTrue(!body(listOf(fromWild)).contains("*"), "nothing re-emits a wildcard marker")
    }

    /** The inverted chemistry (HYDROTHERMAL_CHEMISTRY_PLAN.md): synthesis is the energy source
     *  (`Bond <a> <b>`) and breaking is a costed action (`Break <bond>`). Their pre-inversion mirrors —
     *  `Break` in source position, `FormBond` in action position — no longer exist as gene shapes, so each
     *  keyword now belongs to exactly one `:`-part. */
    @Test
    fun roundTripsSynthesisSourceAndBreakAction() {
        val preset = AUTOTROPH_GENES
        assertEquals(preset, GeneCodec.parse(GeneCodec.serialize(preset)), "autotroph preset round-trip")
        assertTrue(body(preset).lines().all { it.startsWith("Bond r g") }, "every autotroph gene sources from Bond r g")

        val gate = GeneCondition(Operand.Chem("rg"), Comparison.Greater, Operand.Constant(0))
        // `Break <a> <b>` mirrors `Bond <a> <b>`: it names the two FRAGMENTS and splits what they join into.
        val digest = Gene(EnergySource.Light, gate, GeneAction(ActionType.BreakBond, "r", "gb"))
        assertEquals("Light : rg > 0 : Break r gb", body(listOf(digest)), "Break in action position")
        assertEquals(listOf(digest), GeneCodec.parse(GeneCodec.serialize(listOf(digest))), "Break-action round-trip")
        assertEquals("rgb", digest.action.breakTarget, "the substrate is derived from the two fragments")

        // v2 wrote `Break <bond>`, meaning "split the richest molecule holding this bond". It reads as
        // splitting that bond's own two halves — what it meant whenever the richest match was the bare dimer.
        val fromV2 = GeneCodec.parse("# genome 2\nLight : rg > 0 : Break gb").single()
        assertEquals(GeneAction(ActionType.BreakBond, "g", "b"), fromV2.action, "v2 single-operand Break widens to its two halves")

        val vent = Gene(EnergySource.FormBond("r", "g"), gate, GeneAction(ActionType.Repair))
        assertEquals("Bond r g : rg > 0 : Repair", body(listOf(vent)), "Bond in source position")
        assertEquals(listOf(vent), GeneCodec.parse(body(listOf(vent))), "Bond-source round-trip")
    }

    /** Serialized genomes declare their gene model, and text without the header is read as pre-inversion
     *  (which is what every genome written before versioning existed actually is). */
    @Test
    fun declaresAndReadsTheGenomeVersion() {
        assertEquals(GeneCodec.GENOME_VERSION, GeneCodec.parseVersion(GeneCodec.serialize(AUTOTROPH_GENES)), "serialize declares the current model")
        assertEquals(GeneCodec.GENOME_VERSION_PRE_INVERSION, GeneCodec.parseVersion("Light : Biomass > 0 : Divide"), "headerless text is pre-inversion")
        assertEquals(7, GeneCodec.parseVersion("# genome 7\nLight : Biomass > 0 : Divide"), "explicit header wins")
    }

    /**
     * The v1 → v2 migration ([GenomeMigration]), one case per rule. A pre-inversion genome must load and be
     * coherent — every gene still names a real reaction and the organism keeps the bonds it ran on.
     */
    @Test
    fun migratesPreInversionGenomes() {
        fun one(legacy: String): Gene = GeneCodec.parse(legacy).single()

        // Rule 1: Light → FormBond X Y  ⇒  Light → Break X Y. Light still funds it, and because Break mirrors
        // Bond operand-for-operand the two molecules carry over verbatim: it now splits what it used to join.
        val r1 = one("Light : Biomass > 0 : FormBond r g")
        assertEquals(EnergySource.Light, r1.source, "rule 1 keeps Light as the source")
        assertEquals(GeneAction(ActionType.BreakBond, "r", "g"), r1.action, "rule 1 splits what the gene used to build")

        // Rule 2: Break XY → <action>  ⇒  Bond X Y → <action>. The action is untouched; the organism keeps
        // running on the same bond, now by forming it rather than breaking it.
        val r2 = one("Break rg : Biomass > 0 : Convert rg")
        assertEquals(EnergySource.FormBond("r", "g"), r2.source, "rule 2 powers the gene by forming the bond it used to break")
        assertEquals(GeneAction(ActionType.Convert, "rg"), r2.action, "rule 2 leaves the action alone")

        // Rule 3: Break XY → FormBond ZW  ⇒  Bond Z W → Break XY. NOT a per-slot inversion — the gene still
        // BUILDS zw and still BREAKS rg, only which side pays has flipped. Getting this backwards (Bond r g →
        // Break bg) would invert the organism's chemistry a second time.
        val r3 = one("Break rg : Biomass > 0 : FormBond b g")
        assertEquals(EnergySource.FormBond("b", "g"), r3.source, "rule 3 synthesises what the gene used to build")
        assertEquals(GeneAction(ActionType.BreakBond, "r", "g"), r3.action, "rule 3 breaks what the gene used to break")

        // Rule 3 carries the reaction across; a legacy wildcard operand keeps the species it named, minus
        // the marker (wildcards no longer exist — see EnergySource.FormBond).
        val wild = one("Break rg : Biomass > 0 : FormBond *rg b*")
        assertEquals(EnergySource.FormBond("rg", "b"), wild.source, "rule 3 preserves the reaction, dropping the wildcard markers")
        assertEquals(GeneAction(ActionType.BreakBond, "r", "g"), wild.action, "and still breaks what it broke")

        // Migrated text re-serializes as v2 and is then stable (the migration runs once).
        val migrated = GeneCodec.parse("Break rg : Biomass > 0 : FormBond b g")
        assertEquals(migrated, GeneCodec.parse(GeneCodec.serialize(migrated)), "migration is idempotent once re-serialized")

        // A whole pre-inversion genome loads, and nothing is dropped.
        val legacyGenome = GeneCodec.parse(
            """
            Light : r < 4 : Import r
            Break rg : Biomass > 8 : Divide
            Break rg : bb < 5 : FormBond b r
            """.trimIndent()
        )
        assertEquals(3, legacyGenome.size, "every legacy gene survives the upgrade")
        assertTrue(legacyGenome.all { it.source is EnergySource.Light || it.source is EnergySource.FormBond }, "no legacy source shape survives")
    }

    /** Lysis (MORPHOGENESIS.md §B): `Lyse` steals all species from touching cells.
     *  Undigestible species are forced into the attacker's cytoplasm — the basis of prey toxicity. */
    @Test
    fun roundTripsLyseAction() {
        val gate = GeneCondition(Operand.Touching, Comparison.Greater, Operand.Constant(0))
        // Bare lyse (steal all species)
        val lyse = Gene(EnergySource.FormBond("r", "g"), gate, GeneAction(ActionType.Lyse))
        assertEquals("Bond r g : Touching > 0 : Lyse", body(listOf(lyse)), "lyse")
        assertEquals(listOf(lyse), GeneCodec.parse(body(listOf(lyse))), "lyse round-trip")

        // With efficiency gear
        val lyseGear = Gene(EnergySource.FormBond("r", "g"), gate, GeneAction(ActionType.Lyse), efficiency = 3)
        assertEquals("Bond r g : Touching > 0 : Lyse @3", body(listOf(lyseGear)), "lyse with gear")
        assertEquals(listOf(lyseGear), GeneCodec.parse(body(listOf(lyseGear))), "lyse+gear round-trip")

        // Light-powered lyse
        val lyseLight = Gene(EnergySource.Light, gate, GeneAction(ActionType.Lyse), efficiency = 0)
        assertEquals("Light : Touching > 0 : Lyse", body(listOf(lyseLight)), "lyse with light")
        assertEquals(listOf(lyseLight), GeneCodec.parse(body(listOf(lyseLight))), "lyse light round-trip")
    }

    /** A hand-authored genome parses to exactly the genes intended (the author-by-text workflow). */
    @Test
    fun parsesAHandWrittenGenome() {
        val text = """
            # a little autotroph: import r/g, bond them, grow, divide
            Light : r < 4 : Import r
            Light : rg > 0 : Convert rg
            Light : Biomass > 8 : Divide
            Break rg : Biomass < rg : Convert rg
        """.trimIndent()
        val genome = GeneCodec.parse(text)
        assertEquals(4, genome.size)
        assertEquals(Gene(EnergySource.Light, GeneCondition(Operand.Chem("r"), Comparison.Less, Operand.Constant(4)), GeneAction(ActionType.Import, "r")), genome[0])
        assertEquals(Gene(EnergySource.Light, GeneCondition(Operand.Chem("rg"), Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Convert, "rg")), genome[1])
        assertEquals(Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(8)), GeneAction(ActionType.Divide)), genome[2])
        assertEquals(Gene(EnergySource.FormBond("r", "g"), GeneCondition(Operand.Biomass, Comparison.Less, Operand.Chem("rg")), GeneAction(ActionType.Convert, "rg")), genome[3])
    }
}
