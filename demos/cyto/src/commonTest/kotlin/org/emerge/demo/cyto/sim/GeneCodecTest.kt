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

    /** Genes a mutation can produce — empty condition species / action operands (after a condition-type
     *  or action-type flip) — must round-trip, not crash decode. Guards the save path. */
    @Test
    fun roundTripsEmptyOperandsAndSpecies() {
        val genome = listOf(
            Gene(EnergySource.Light, GeneCondition(ConditionType.ChemQty, "", Comparison.Less, 9), GeneAction(ActionType.Import, "")),
            Gene(EnergySource.BreakBond("ab"), GeneCondition(ConditionType.Biomass, "", Comparison.Greater, 3), GeneAction(ActionType.FormBond, "", "")),
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
            val gene = Gene(EnergySource.Light, GeneCondition(ConditionType.Biomass, "", Comparison.Greater, 0), GeneAction(action))
            val back = GeneCodec.parse(GeneCodec.serialize(listOf(gene)))
            assertEquals(listOf(gene), back, "$action")
        }
    }

    /** Every condition type round-trips — the enum-exhaustive backstop for the gate token. */
    @Test
    fun roundTripsEveryConditionType() {
        for (type in ConditionType.entries) {
            val gene = Gene(EnergySource.Light, GeneCondition(type, "ab", Comparison.Greater, 2), GeneAction(ActionType.Mitosis))
            val back = GeneCodec.parse(GeneCodec.serialize(listOf(gene)))
            // Biomass/Touching ignore the species operand (not serialized), so normalise it for the compare.
            val expected = if (type == ConditionType.ChemQty) gene else gene.copy(condition = gene.condition.copy(species = ""))
            assertEquals(listOf(expected), back, "$type")
        }
    }

    /** A hand-authored genome parses to exactly the genes intended (the author-by-text workflow). */
    @Test
    fun parsesAHandWrittenGenome() {
        val text = """
            # a little autotroph: import a/b, bond them, grow, divide
            Light : ChemQty a < 4 : Import a
            Light : ChemQty ab > 0 : Convert ab
            Light : Biomass > 8 : Mitosis
        """.trimIndent()
        val genome = GeneCodec.parse(text)
        assertEquals(3, genome.size)
        assertEquals(Gene(EnergySource.Light, GeneCondition(ConditionType.ChemQty, "a", Comparison.Less, 4), GeneAction(ActionType.Import, "a")), genome[0])
        assertEquals(Gene(EnergySource.Light, GeneCondition(ConditionType.ChemQty, "ab", Comparison.Greater, 0), GeneAction(ActionType.Convert, "ab")), genome[1])
        assertEquals(Gene(EnergySource.Light, GeneCondition(ConditionType.Biomass, "", Comparison.Greater, 8), GeneAction(ActionType.Mitosis)), genome[2])
    }
}
