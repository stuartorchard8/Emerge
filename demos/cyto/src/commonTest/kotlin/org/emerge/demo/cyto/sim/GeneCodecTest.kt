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
            // Empty operands serialize as `_` and decode back to empty, so this holds for both the
            // operand-carrying (Import/FormBond/Convert) and operand-less (Expand/Contract/Mitosis/Repair)
            // actions — the point is that the action token itself survives.
            val gene = Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(action))
            val back = GeneCodec.parse(GeneCodec.serialize(listOf(gene)))
            assertEquals(listOf(gene), back, "$action")
        }
    }

    /** Every operand kind round-trips on either side of the gate — the exhaustive backstop for the
     *  operand tokens (constant / species / Biomass / Touching) now that both sides are operands. */
    @Test
    fun roundTripsEveryOperandKindOnBothSides() {
        val kinds = listOf(Operand.Constant(7), Operand.Chem("ab"), Operand.Biomass, Operand.Touching)
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
