package org.emerge.demo.norns.evo

import org.emerge.demo.norns.gene.GeneRng
import org.emerge.demo.norns.gene.Genome
import org.emerge.demo.norns.gene.HalfLifeGene
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for reproduction/evolution (subsystem 7): a population whose
 * heritable trait adapts under selection (evolution), persists across generations, and breeds
 * deterministically. Trait = a `HalfLifeGene` value (a stand-in for any genome-encoded biochemical
 * parameter). Explicit truncation selection here; implicit embodied selection is deferred
 * (DESIGN.md G9).
 */
class EvolutionTest {

    private fun seed(halfLife: Float) = Genome(chemicalCount = 1, locusCount = 0, genes = listOf(HalfLifeGene(0, halfLife)))
    private fun trait(g: Genome) = (g.genes[0] as HalfLifeGene).halfLife
    private fun meanError(p: Population, target: Float) =
        p.members.map { abs(trait(it.genome) - target) }.average().toFloat()

    @Test
    fun populationAdaptsAHeritableTraitTowardSelection() {
        val rng = GeneRng(2024)
        val target = 8f
        val initial = Population((0 until 30).map { Individual(seed(rng.nextFloat() * 20f)) })
        val initialError = meanError(initial, target)

        var pop = initial
        repeat(40) {
            pop = pop.evolve(mutationRate = 1f, rng = rng) { g -> -abs(trait(g) - target) }
        }
        val finalError = meanError(pop, target)

        assertEquals(30, pop.size, "population size is preserved")
        assertTrue(finalError < initialError,
            "the population should adapt toward the optimum (initial err=$initialError final err=$finalError)")
        assertTrue(finalError < 2.5f, "an adapted population should cluster near the optimum: $finalError")
    }

    @Test
    fun populationPersistsAcrossManyGenerations() {
        val rng = GeneRng(5)
        var pop = Population((0 until 20).map { Individual(seed(rng.nextFloat() * 10f)) })
        repeat(100) {
            pop = pop.evolve(mutationRate = 0.5f, rng = rng) { 1f } // neutral fitness
            assertEquals(20, pop.size, "population neither dies out nor explodes")
        }
    }

    @Test
    fun evolutionIsDeterministic() {
        fun run(): Population {
            val rng = GeneRng(77)
            var pop = Population((0 until 16).map { Individual(seed(rng.nextFloat() * 10f)) })
            repeat(20) { pop = pop.evolve(mutationRate = 1f, rng = rng) { g -> trait(g) } }
            return pop
        }
        val a = run(); val b = run()
        for (i in a.members.indices) {
            assertEquals(trait(a.members[i].genome).toRawBits(), trait(b.members[i].genome).toRawBits(), "individual $i diverged")
        }
    }
}
