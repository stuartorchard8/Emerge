package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CellWork
import org.emerge.demo.cyto.sim.CytoBiologyCore
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.MoleculeStore
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.sim.core.EntityId
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for cell↔cell cytoplasm diffusion: a diffusible species must spread **evenly** across a
 * welded body of identical cells, regardless of how many welds each cell holds. The original rule divided a
 * cell's outflow by its **own** `degree+1`, which drove the steady-state concentration `∝ (degree+1)` — a
 * high-degree interior cell piled up ~2× its low-degree neighbours, stalling a low↔high chemical clock and
 * corrupting positional gradients. The fix (a **fixed** divisor `CYTOPLASM_DIFFUSE_DENOM`) makes diffusion
 * edge-symmetric → uniform steady state. This test runs the REAL [CytoBiologyCore.diffuse] on a varied-degree
 * blob seeded uniformly and asserts it STAYS uniform; it fails loudly if the degree-bias ever returns.
 */
class DiffusionBiasProbe {
    private val gg = SpeciesRegistry.id("gg")
    // a genome that metabolises gg (Break/Convert) so handleable.canDiffuse(gg) == true
    private val genome = GeneCodec.parse("Break gg : Biomass < 4000 : Convert gg")

    private fun mkCell(count: Int) = CellWork(
        cytoplasm = MoleculeStore.of(mapOf("gg" to count)),
        biomass = MoleculeStore.of(mapOf("rr" to 4000)),
        logicalRadius = MIN_RADIUS, type = CellType.Collector, genome = genome,
        quanta = 0, touchCount = 0, wear = 0, gridIndex = -1, connectionDamage = mutableMapOf(),
    )

    // centre = id 0 (degree 6), ring = ids 1..6 in a cycle, each also welded to centre (ring degree 3)
    private fun blob(seed: Int): Pair<MutableMap<EntityId, CellWork>, Map<EntityId, List<EntityId>>> {
        val ids = (0..6).map { EntityId(it) }
        val works = LinkedHashMap<EntityId, CellWork>()
        for (id in ids) works[id] = mkCell(seed)
        val nbrs = HashMap<EntityId, List<EntityId>>()
        nbrs[ids[0]] = ids.drop(1)
        for (k in 1..6) {
            val prev = if (k == 1) 6 else k - 1
            val next = if (k == 6) 1 else k + 1
            nbrs[ids[k]] = listOf(ids[0], ids[prev], ids[next])
        }
        return works to nbrs
    }

    @Test
    fun diffusionSpreadsEvenlyRegardlessOfDegree() {
        val (works, nbrs) = blob(seed = 1200)   // identical cells, identical seed (uniform start)
        val orderedIds = works.keys.toList()
        repeat(400) { CytoBiologyCore.diffuse(orderedIds, works, nbrs) }
        val counts = works.values.map { it.cytoplasm.count(gg) }
        val centre = works.getValue(EntityId(0)).cytoplasm.count(gg)   // degree 6
        val ring = works.getValue(EntityId(1)).cytoplasm.count(gg)     // degree 3
        // integer-floor rounding can leave a ±1 jitter; the degree-bias bug was a ~1.76× gap, so a tight
        // tolerance both passes the fix and fails the bug.
        val spread = counts.max() - counts.min()
        assertTrue(spread <= 1, "diffusion is degree-biased: centre(deg6)=$centre vs ring(deg3)=$ring, spread=$spread")
    }
}
