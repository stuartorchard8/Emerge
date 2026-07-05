package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import kotlin.test.Test
import kotlin.test.assertEquals

class GeneCodecTest {

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

    /** Genes a mutation can produce — an empty [Operand.Chem] species / empty action operands (after a
     *  kind- or action-type flip) — must round-trip, not crash decode. Guards the save path. */
    @Test
    fun roundTripsEmptyOperandsAndSpecies() {
        val genome = listOf(
            Gene(EnergySource.Light, GeneCondition(Operand.Chem(""), Comparison.Less, Operand.Constant(9)), GeneAction(ActionType.Import, "")),
            Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(3)), GeneAction(ActionType.FormBond, "", "")),
        )
        assertEquals(genome, GeneCodec.parse(GeneCodec.serialize(genome)), "empty operand/species genome")
    }

    /** Every action type round-trips — so a newly-added action can't silently fail to serialize (the
     *  KDoc promises every representable gene round-trips; this is the enum-exhaustive backstop). */
    @Test
    fun roundTripsEveryActionType() {
        for (action in ActionType.entries) {
            // Empty operands serialize as `_` and decode back to empty, so this holds for the
            // operand-carrying (Import/FormBond/Convert) and the bare-token (Contract/Mitosis/Repair)
            // actions alike — the point is that the action token itself survives. (Mitosis with a
            // morphogen operand is covered by roundTripsAsymmetricMitosisMorphogen below.)
            val gene = Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(action))
            val back = GeneCodec.parse(GeneCodec.serialize(listOf(gene)))
            assertEquals(listOf(gene), back, "$action")
        }
    }

    /** Every operand kind round-trips on either side of the gate — the exhaustive backstop for the
     *  operand tokens (constant / species / concentration / Biomass / Touching) now that both sides are
     *  operands. */
    @Test
    fun roundTripsEveryOperandKindOnBothSides() {
        val kinds = listOf(Operand.Constant(7), Operand.Chem("ab"), Operand.Conc("abb"), Operand.Biomass, Operand.Touching)
        for (op in kinds) {
            val asLhs = Gene(EnergySource.Light, GeneCondition(op, Comparison.Greater, Operand.Constant(2)), GeneAction(ActionType.Mitosis))
            assertEquals(listOf(asLhs), GeneCodec.parse(GeneCodec.serialize(listOf(asLhs))), "$op as lhs")
            val asRhs = Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Less, op), GeneAction(ActionType.Mitosis))
            assertEquals(listOf(asRhs), GeneCodec.parse(GeneCodec.serialize(listOf(asRhs))), "$op as rhs")
        }
    }

    /** A condition can compare two live variables (no constant at all) — the headline of the operand
     *  generalisation — and that round-trips too. */
    @Test
    fun roundTripsAVariableVsVariableCondition() {
        val gene = Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Biomass, Comparison.Less, Operand.Chem("ab")), GeneAction(ActionType.Convert, "ab"))
        assertEquals(listOf(gene), GeneCodec.parse(GeneCodec.serialize(listOf(gene))), "biomass < stored ab reserve")
    }

    /** Asymmetric mitosis (MORPHOGENESIS.md §C) names a morphogen that must round-trip — this is the
     *  capability that lets §C genomes be hand-authored / saved as text. A bare `Mitosis` (symmetric) still
     *  parses to an empty operand, so existing genomes are unaffected. */
    @Test
    fun roundTripsAsymmetricMitosisMorphogen() {
        val asymmetric = Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(8)), GeneAction(ActionType.Mitosis, "m"))
        assertEquals("Break ab : Biomass > 8 : Mitosis m", GeneCodec.serialize(listOf(asymmetric)), "serialized form")
        assertEquals(listOf(asymmetric), GeneCodec.parse(GeneCodec.serialize(listOf(asymmetric))), "asymmetric mitosis morphogen")

        // Retain-side (MORPHOGENESIS.md §Source placement): `mother` keeps the morphogen in the mother
        // (centred source); default/`daughter` hands it out (edge source).
        val toMother = Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(8)), GeneAction(ActionType.Mitosis, "m", morphogenToMother = true))
        assertEquals("Break ab : Biomass > 8 : Mitosis m mother", GeneCodec.serialize(listOf(toMother)), "serialized mother-retention")
        assertEquals(listOf(toMother), GeneCodec.parse(GeneCodec.serialize(listOf(toMother))), "mother-retention round-trip")
        assertEquals(listOf(asymmetric), GeneCodec.parse("Break ab : Biomass > 8 : Mitosis m daughter"), "explicit 'daughter' = default")

        // A bare `Mitosis` token (no operand) still decodes to a symmetric, daughter-side split — backward compatibility.
        val symmetric = Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(8)), GeneAction(ActionType.Mitosis))
        assertEquals(listOf(symmetric), GeneCodec.parse("Light : Biomass > 8 : Mitosis"), "bare Mitosis stays symmetric")
    }

    /** A `Conc` (concentration) operand and a multi-clause AND condition round-trip + parse — the
     *  positional-band readout the morphogen-for-shape work relies on (MORPHOGENESIS.md §Morphogens for
     *  shape). A bare single-clause condition still parses (backward compatible). */
    @Test
    fun roundTripsConcBandAndMultiClause() {
        val band = Gene(
            EnergySource.Light,
            GeneCondition(listOf(
                Clause(Operand.Conc("ac"), Comparison.Greater, Operand.Constant(50)),
                Clause(Operand.Conc("ac"), Comparison.Less, Operand.Constant(200)),
            )),
            GeneAction(ActionType.Convert, "ab"),
        )
        assertEquals("Light : Conc(ac) > 50 & Conc(ac) < 200 : Convert ab", GeneCodec.serialize(listOf(band)), "serialized band")
        assertEquals(listOf(band), GeneCodec.parse(GeneCodec.serialize(listOf(band))), "Conc band round-trip")

        // A hand-written multi-clause gate parses into ordered clauses; a single-clause one still works.
        assertEquals(
            GeneCondition(listOf(
                Clause(Operand.Biomass, Comparison.Greater, Operand.Constant(8)),
                Clause(Operand.Chem("ab"), Comparison.Less, Operand.Constant(4)),
            )),
            GeneCodec.parse("Light : Biomass > 8 & ab < 4 : Mitosis").single().condition,
            "multi-clause parse",
        )
    }

    /** Oriented division (MORPHOGENESIS.md §Morphogens for shape): the axis-morphogen + along/across mode
     *  round-trip, composing with the asymmetric morphogen + retain-side. */
    @Test
    fun roundTripsOrientedMitosis() {
        val gate = GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(8))
        // axis only (no asymmetric morphogen): `along`/`across <axis>`.
        val along = Gene(EnergySource.Light, gate, GeneAction(ActionType.Mitosis, b = "bc"))
        assertEquals("Light : Biomass > 8 : Mitosis along bc", GeneCodec.serialize(listOf(along)), "along, axis only")
        assertEquals(listOf(along), GeneCodec.parse(GeneCodec.serialize(listOf(along))))
        // all four Mitosis params at once: asym morphogen → mother, oriented across an axis.
        val full = Gene(EnergySource.BreakBond("ab"), gate, GeneAction(ActionType.Mitosis, a = "ac", b = "bc", morphogenToMother = true, divideAcross = true))
        assertEquals("Break ab : Biomass > 8 : Mitosis ac mother across bc", GeneCodec.serialize(listOf(full)), "asym+mother+across")
        assertEquals(listOf(full), GeneCodec.parse(GeneCodec.serialize(listOf(full))))
    }

    /** Mitosis with `sever` — the daughter rejects all mother welds, splitting off as a separate 1-celled
     *  organism. Round-trips with and without asymmetric morphogen. */
    @Test
    fun roundTripsMitosisSever() {
        val gate = GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(8))
        // bare sever: `sever` alone
        val sever = Gene(EnergySource.Light, gate, GeneAction(ActionType.Mitosis, rejectMother = true))
        assertEquals("Light : Biomass > 8 : Mitosis sever", GeneCodec.serialize(listOf(sever)))
        assertEquals(listOf(sever), GeneCodec.parse(GeneCodec.serialize(listOf(sever))), "sever round-trip")

        // sever + asymmetric morphogen to mother
        val fullSever = Gene(EnergySource.BreakBond("ab"), gate, GeneAction(ActionType.Mitosis, a = "x", morphogenToMother = true, rejectMother = true))
        assertEquals("Break ab : Biomass > 8 : Mitosis x mother sever", GeneCodec.serialize(listOf(fullSever)))
        assertEquals(listOf(fullSever), GeneCodec.parse(GeneCodec.serialize(listOf(fullSever))), "sever+mother round-trip")

        // sever + oriented division
        val orientedSever = Gene(EnergySource.Light, gate, GeneAction(ActionType.Mitosis, b = "bc", divideAcross = true, rejectMother = true))
        assertEquals(orientedSever, GeneCodec.parse("Light : Biomass > 8 : Mitosis sever across bc").single())
    }

    /** FormBond reactant matching (MORPHOGENESIS.md §2026-06-18): exact by default, `*` opts into a
     *  wildcard (`*a` left = ends-with, `a*` right = starts-with). All four exact/wildcard combinations
     *  round-trip, and the serialized text is the documented form. */
    @Test
    fun roundTripsFormBondExactAndWildcard() {
        val gate = GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0))
        val exact = Gene(EnergySource.Light, gate, GeneAction(ActionType.FormBond, "a", "b"))
        assertEquals("Light : Biomass > 0 : FormBond a b", GeneCodec.serialize(listOf(exact)), "exact form")

        val leftWild = Gene(EnergySource.Light, gate, GeneAction(ActionType.FormBond, "a", "b", aWild = true))
        assertEquals("Light : Biomass > 0 : FormBond *a b", GeneCodec.serialize(listOf(leftWild)), "left wildcard")

        val rightWild = Gene(EnergySource.Light, gate, GeneAction(ActionType.FormBond, "a", "b", bWild = true))
        assertEquals("Light : Biomass > 0 : FormBond a b*", GeneCodec.serialize(listOf(rightWild)), "right wildcard")

        val bothWild = Gene(EnergySource.Light, gate, GeneAction(ActionType.FormBond, "ab", "c", aWild = true, bWild = true))
        assertEquals("Light : Biomass > 0 : FormBond *ab c*", GeneCodec.serialize(listOf(bothWild)), "both wildcard, multi-atom")

        for (g in listOf(exact, leftWild, rightWild, bothWild)) {
            assertEquals(listOf(g), GeneCodec.parse(GeneCodec.serialize(listOf(g))), "round-trip $g")
        }
    }

    /** Lysis (MORPHOGENESIS.md §B): `Lyse` steals all species from touching cells.
     *  Undigestible species are forced into the attacker's cytoplasm — the basis of prey toxicity. */
    @Test
    fun roundTripsLyseAction() {
        val gate = GeneCondition(Operand.Touching, Comparison.Greater, Operand.Constant(0))
        // Bare lyse (steal all species)
        val lyse = Gene(EnergySource.BreakBond("ab"), gate, GeneAction(ActionType.Lyse))
        assertEquals("Break ab : Touching > 0 : Lyse", GeneCodec.serialize(listOf(lyse)), "lyse")
        assertEquals(listOf(lyse), GeneCodec.parse(GeneCodec.serialize(listOf(lyse))), "lyse round-trip")

        // With efficiency gear
        val lyseGear = Gene(EnergySource.BreakBond("ab"), gate, GeneAction(ActionType.Lyse), efficiency = 3)
        assertEquals("Break ab : Touching > 0 : Lyse @3", GeneCodec.serialize(listOf(lyseGear)), "lyse with gear")
        assertEquals(listOf(lyseGear), GeneCodec.parse(GeneCodec.serialize(listOf(lyseGear))), "lyse+gear round-trip")

        // Light-powered lyse
        val lyseLight = Gene(EnergySource.Light, gate, GeneAction(ActionType.Lyse), efficiency = 0)
        assertEquals("Light : Touching > 0 : Lyse", GeneCodec.serialize(listOf(lyseLight)), "lyse with light")
        assertEquals(listOf(lyseLight), GeneCodec.parse(GeneCodec.serialize(listOf(lyseLight))), "lyse light round-trip")
    }

    /** A hand-authored genome parses to exactly the genes intended (the author-by-text workflow). */
    @Test
    fun parsesAHandWrittenGenome() {
        val text = """
            # a little autotroph: import a/b, bond them, grow, divide
            Light : a < 4 : Import a
            Light : ab > 0 : Convert ab
            Light : Biomass > 8 : Mitosis
            Break ab : Biomass < ab : Convert ab
        """.trimIndent()
        val genome = GeneCodec.parse(text)
        assertEquals(4, genome.size)
        assertEquals(Gene(EnergySource.Light, GeneCondition(Operand.Chem("a"), Comparison.Less, Operand.Constant(4)), GeneAction(ActionType.Import, "a")), genome[0])
        assertEquals(Gene(EnergySource.Light, GeneCondition(Operand.Chem("ab"), Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Convert, "ab")), genome[1])
        assertEquals(Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(8)), GeneAction(ActionType.Mitosis)), genome[2])
        assertEquals(Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Biomass, Comparison.Less, Operand.Chem("ab")), GeneAction(ActionType.Convert, "ab")), genome[3])
    }
}
