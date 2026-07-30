package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.sim.AUTOTROPH_REPAIR_GENE
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.Operand
import org.emerge.demo.cyto.sim.SpeciesRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rehoming a curated subsystem onto the player's chemistry. The failure this guards against is **partial**
 * translation: a gene whose action was rebound but whose condition or energy source still names the
 * autotroph's atoms inserts as something that looks right and cannot fire.
 */
class ChemBindingTest {

    private fun lineage(bond: String) = lineageOf(
        GeneCodec.parse("Bond ${bond[0]} ${bond[1]} : Biomass > 2000 : Divide sever")
    )

    /** Every token-bearing field moves together: energy source, condition operand, and action operands. */
    @Test
    fun everyTokenInAGeneIsRebound() {
        // The Repair subsystem, transcribed into placeholders: fuel pair (x,y), gated on holding its bond.
        val template = GeneCodec.parse("Bond x y : xy > 0 : Repair").single()
        val bound = ChemBinding.of(lineage("bg")).gene(template)

        val src = bound.source as EnergySource.FormBond
        assertEquals("b", src.a, "energy source reactant")
        assertEquals("g", src.b, "energy source reactant")
        assertEquals("bg", (bound.condition.clauses.single().lhs as Operand.Chem).species, "condition operand")

        // ...and the whole gene is now expressible in the real alphabet.
        assertTrue(SpeciesRegistry.id("bg") >= 0)
        assertEquals(GeneCodec.parse("Bond b g : bg > 0 : Repair").single(), bound)
    }

    /** Action operands (both of them) and multi-atom tokens rebind per character. */
    @Test
    fun actionOperandsAndMoleculesRebindPerCharacter() {
        val b = ChemBinding.of(lineage("bg"))
        assertEquals("b", b.species(ChemBinding.X))
        assertEquals("g", b.species(ChemBinding.Y))
        assertEquals("r", b.species(ChemBinding.Z), "the spare atom is the one the fuel pair does not use")
        assertEquals("bg", b.species("xy"))
        assertEquals("gb", b.species("yx"), "order is significant - gb is not bg")
        assertEquals("rr", b.species("zz"))

        // BreakBond names two fragments in a/b; Divide names morphogens there. Both must move.
        val broken = b.gene(GeneCodec.parse("Light : : Break x y").single())
        assertEquals("b", broken.action.a)
        assertEquals("g", broken.action.b)
        assertEquals("bg", broken.action.breakTarget, "the derived substrate follows the rebound fragments")
    }

    /** The derived ids are recomputed, not carried over from the template (where they were -1). */
    @Test
    fun derivedIdsAreRecomputedAfterRebinding() {
        val template = GeneCodec.parse("Light : : Convert xy").single()
        assertEquals(-1, template.action.aId, "placeholders are deliberately outside the real alphabet")

        val bound = ChemBinding.of(lineage("bg")).gene(template)
        assertEquals(SpeciesRegistry.id("bg"), bound.action.aId)
        assertTrue(bound.action.aId >= 0, "a rebound gene must name a real species or it can never fire")
    }

    /**
     * With nothing to read, the binding reproduces the authored autotroph exactly — which is what leaves the
     * existing Ch1-10 chapters untouched by any of this.
     */
    @Test
    fun anUnreadableLineageFallsBackToTheAutotrophsOwnChemistry() {
        val template = GeneCodec.parse("Bond x y : xy > 0 : Repair").single()
        for (l in listOf(null, lineageOf(GeneCodec.parse("Light : : Convert rg")))) {
            assertEquals(
                AUTOTROPH_REPAIR_GENE, ChemBinding.of(l).gene(template),
                "an unbound template must still insert the working r/g subsystem",
            )
        }
    }

    /** A player whose fuel pair is the autotroph's own gets the identity mapping by the normal path. */
    @Test
    fun theAutotrophsOwnFuelPairIsJustAnotherBinding() {
        assertEquals(
            AUTOTROPH_REPAIR_GENE,
            ChemBinding.of(lineage("rg")).gene(GeneCodec.parse("Bond x y : xy > 0 : Repair").single()),
        )
    }
}
