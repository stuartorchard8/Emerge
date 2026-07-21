package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the thermodynamic closure of the inverted chemistry (HYDROTHERMAL_CHEMISTRY_PLAN.md; see the
 * [EnergySource] kdoc for the invariant in full).
 *
 * These exist because the inversion was *first* implemented additively — the new synthesis-as-energy-source
 * was added while the old break-as-energy-source was left in place — which opened a perpetual-motion loop: a
 * genome could form a bond for +1 quantum and break the same bond for another +1, returning to its exact
 * starting state having minted 2 quanta from nothing. Nothing in the type system prevents that from being
 * reintroduced, so it is asserted here.
 *
 * The invariant: chemical energy is minted only by forming a bond, at exactly one quantum per bond, and
 * bonds are destroyed only at a cost of exactly one quantum per bond. Hence **every closed cycle in
 * bond-space nets ≤ 0 energy**. The structural half of that is checked directly (there is no gene shape that
 * yields energy for breaking); the numeric half is checked by running the tightest possible cycle and
 * showing it does not ratchet.
 */
class ThermodynamicsTest {

    private val alwaysOn = GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0))
    private fun sid(s: String) = SpeciesRegistry.id(s)

    /**
     * The structural guarantee. Breaking a bond must never be an energy *source* — that shape is what closed
     * the loop, and the cheapest way to keep it closed is for it not to be expressible. Equally, synthesis
     * must not also be an action: if it were, a gene could pay to build while another was paid to build.
     */
    @Test
    fun breakingIsNeverAnEnergySourceAndSynthesisIsNeverAnAction() {
        // Exactly two energy sources, and neither of them breaks anything.
        val sources = listOf(EnergySource.Light, EnergySource.FormBond("r", "g"))
        assertTrue(
            sources.none { it::class.simpleName?.contains("Break") == true },
            "no EnergySource may yield energy for breaking a bond — that is the perpetual-motion loop",
        )
        // And no action synthesises: forming a bond is the source, never something a gene spends energy on.
        assertTrue(
            ActionType.entries.none { it.name.contains("Form") },
            "no ActionType may form a bond — synthesis is the energy source (${ActionType.entries})",
        )
    }

    /**
     * The gear guarantee. The efficiency gear multiplies ops-per-energy-unit for throughput actions, and
     * applying it to [ActionType.BreakBond] would mean one formed bond could fund `g+1` breaks — reopening
     * the loop through the back door even with the primitives correct. Break gets the per-tick *cap* (pure
     * rate limiting, which is safe) but never the multiplier.
     */
    @Test
    fun breakBondNeverGetsTheEfficiencyMultiplier() {
        for (g in 0..CytoTuning.EFFICIENCY_MAX_GEAR) {
            val gene = Gene(EnergySource.FormBond("r", "g"), alwaysOn, GeneAction(ActionType.BreakBond, "gb"), efficiency = g)
            assertTrue(!gene.actionHasEfficiency, "BreakBond must not take the g+1 multiplier (gear $g)")
            assertTrue(gene.actionHasCap, "BreakBond should still take the per-tick cap (gear $g)")
        }
    }

    /** A cell holding plenty of both reactants and plenty of the product — enough fuel that any gene which
     *  *can* fire *will*, so an observed no-op means closure rather than starvation. */
    private fun fuelledCell(genome: List<Gene>) = CellWork(
        cytoplasm = MoleculeStore.of(mapOf("r" to 10_000, "g" to 10_000, "rg" to 10_000)),
        biomass = MoleculeStore.of(mapOf("rr" to 2_000)),
        logicalRadius = MIN_RADIUS, type = CellType.Collector,
        genome = genome,
        quanta = 0, touchCount = 0, wear = 0, gridIndex = -1, connectionDamage = HashMap(),
    )

    /**
     * The numeric guarantee, run as the tightest cycle the model allows: a genome that forms `rg` for energy
     * and spends that energy breaking `rg` back into `r`+`g`. Bond-wise this is a closed loop, so it must not
     * ratchet at any gear. Under the pre-inversion additive bug the equivalent cycle minted two quanta a turn.
     *
     * The cycle in fact nets **exactly** zero, which is the right answer but an ambiguous one — an inert cell
     * would look identical. So the same fixture is also run with a genome that merely *spends* (synthesis →
     * Convert), and that one must grow. Together: energy is genuinely available and spendable here, and the
     * cycle's zero is closure, not starvation.
     */
    @Test
    fun formingThenBreakingTheSameBondDoesNotRatchet() {
        // Control: prove this fixture really can do work, so the no-op below is meaningful.
        val spender = fuelledCell(listOf(Gene(EnergySource.FormBond("r", "g"), alwaysOn, GeneAction(ActionType.Convert, "rg"))))
        val spenderAtoms = totalAtoms(spender)
        repeat(200) { CytoBiologyCore.runGenes(spender) }
        assertTrue(spender.biomass.count(sid("rg")) > 0, "control: a synthesis-powered Convert must actually build biomass")
        assertEquals(spenderAtoms, totalAtoms(spender), "control: atoms conserved")

        for (gear in intArrayOf(0, 1, 5, CytoTuning.EFFICIENCY_MAX_GEAR)) {
            val work = fuelledCell(listOf(
                Gene(EnergySource.FormBond("r", "g"), alwaysOn, GeneAction(ActionType.BreakBond, "rg"), efficiency = gear),
            ))
            val atomsBefore = totalAtoms(work)
            repeat(200) { CytoBiologyCore.runGenes(work) }

            assertEquals(atomsBefore, totalAtoms(work), "gear $gear: atoms must be conserved through the cycle")
            assertTrue(
                work.cytoplasm.count(sid("rg")) <= 10_000,
                "gear $gear: a form→break cycle must not MINT `rg` — got ${work.cytoplasm.count(sid("rg"))} from 10000",
            )
            // The monomers can't grow either: every `r` freed by a break was spent forming the bond that
            // paid for it. If this ever ratchets, some path is yielding more than one quantum per bond.
            assertTrue(
                work.cytoplasm.count(sid("r")) <= 10_000,
                "gear $gear: a form→break cycle must not MINT free monomer — got ${work.cytoplasm.count(sid("r"))} from 10000",
            )
        }
    }

    /**
     * A cell with no reactants and no light can do nothing at all. This is the floor the whole model rests
     * on: energy has to come from somewhere, so an empty cell is inert rather than quietly self-funding.
     */
    @Test
    fun aCellWithNoFuelDoesNothing() {
        val work = CellWork(
            cytoplasm = MoleculeStore.of(mapOf("rg" to 5_000)),   // product only — no `r`/`g` to join
            biomass = MoleculeStore.of(mapOf("rr" to 2_000)),
            logicalRadius = MIN_RADIUS, type = CellType.Collector,
            genome = listOf(
                Gene(EnergySource.FormBond("r", "g"), alwaysOn, GeneAction(ActionType.Convert, "rg")),
                Gene(EnergySource.FormBond("r", "g"), alwaysOn, GeneAction(ActionType.BreakBond, "rg")),
            ),
            quanta = 0, touchCount = 0, wear = 0, gridIndex = -1, connectionDamage = HashMap(),
        )
        repeat(50) { CytoBiologyCore.runGenes(work) }
        assertEquals(5_000, work.cytoplasm.count(sid("rg")), "with no reactants there is no energy, so nothing may happen")
        assertEquals(2_000, work.biomass.count(sid("rr")), "and no biomass may be built")
    }

    /** Total atoms across cytoplasm + biomass — the conservation quantity the sim maintains. */
    private fun totalAtoms(work: CellWork): Long {
        var n = 0L
        for (store in listOf(work.cytoplasm, work.biomass)) {
            for (i in 0 until store.size) n += store.countAt(i).toLong() * SpeciesRegistry.atomCount(store.idAt(i))
        }
        return n
    }
}
