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
