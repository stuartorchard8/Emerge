package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.physics.primitives.Frac
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneCodecTest {

    /** Every preset genome survives serialize → parse with identical structure (GeneInput/GeneOutput
     *  are data classes, so == is structural). */
    @Test
    fun roundTripsEveryPreset() {
        for (type in CellType.entries) {
            val genome = genomeForType(type)
            val back = GeneCodec.parse(GeneCodec.serialize(genome))
            assertEquals(genome.size, back.size, "$type gene count")
            for (i in genome.indices) {
                assertEquals(genome[i].inputs, back[i].inputs, "$type gene $i inputs")
                assertEquals(genome[i].output, back[i].output, "$type gene $i output")
            }
        }
    }

    /** A hand-authored genome parses to exactly the genes intended (the author-by-text workflow). */
    @Test
    fun parsesAHandWrittenGenome() {
        val text = """
            # a little organism: collect light, divide on surplus, stick together
            Light _ 1.0 > Secrete energy _ 0.0
            Chem energy 1.0 > Mitosis _ _ -5.0
            - > Sticky _ _ 0.0
        """.trimIndent()
        val genome = GeneCodec.parse(text)
        assertEquals(3, genome.size)
        assertEquals(GeneInput(GeneInputType.Light, "", Frac.fromFloat(1.0f)), genome[0].inputs.single())
        assertEquals(GeneOutput(GeneOutputType.Secrete, "energy", "", Frac.fromFloat(0.0f)), genome[0].output)
        assertEquals(GeneOutput(GeneOutputType.Mitosis, "", "", Frac.fromFloat(-5.0f)), genome[1].output)
        assertTrue(genome[2].inputs.isEmpty(), "the Sticky gene has no inputs")
        assertEquals(GeneOutputType.Sticky, genome[2].output.type)
    }
}
