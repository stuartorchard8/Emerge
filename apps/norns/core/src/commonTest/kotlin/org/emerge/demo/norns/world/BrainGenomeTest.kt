package org.emerge.demo.norns.world

import org.emerge.demo.norns.brain.Brain
import org.emerge.demo.norns.gene.BrainGene
import org.emerge.demo.norns.gene.GeneRng
import org.emerge.demo.norns.gene.Genome
import org.emerge.demo.norns.evo.Individual
import org.emerge.demo.norns.evo.Population
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for gene-encoded brain instinct (G5): a brain built from a genome
 * obeys its [BrainGene]s, and — the payoff — a population's *behaviour* evolves under selection
 * (random instincts → good decisions), because instinct is now heritable + mutable.
 */
class BrainGenomeTest {

    private fun decide(b: Brain, hunger: Float, urge: Float, food: Float, mate: Float, fatigue: Float = 0f): Int {
        b.lobes[0].set(floatArrayOf(hunger, urge, food, mate, fatigue, 1f))
        b.propagate()
        return b.lobes[1].argmax()
    }

    private fun instinctGenes(weights: (action: Int, sense: Int) -> Float): List<BrainGene> = buildList {
        for (a in 0 until CreatureMind.ACTIONS) for (s in 0 until CreatureMind.PERCEPTION) add(BrainGene(a, s, weights(a, s)))
    }

    @Test
    fun brainObeysItsGenes() {
        // A genome whose only strong dendrite is hunger→seek-food makes a creature that eats when hungry.
        val foodSeeker = Genome(1, 1, instinctGenes { a, s ->
            if (a == CreatureMind.A_SEEK_FOOD && s == CreatureMind.P_HUNGER) 3f
            else if (a == CreatureMind.A_REST && s == CreatureMind.P_BIAS) 0.3f else 0f
        })
        assertEquals(CreatureMind.A_SEEK_FOOD, decide(CreatureMind.build(foodSeeker, 0f), hunger = 1f, urge = 0f, food = 1f, mate = 0f))

        // Zero out that instinct → a hungry creature no longer seeks food (rests by default).
        val noInstinct = Genome(1, 1, instinctGenes { a, s -> if (a == CreatureMind.A_REST && s == CreatureMind.P_BIAS) 0.3f else 0f })
        assertEquals(CreatureMind.A_REST, decide(CreatureMind.build(noInstinct, 0f), hunger = 1f, urge = 0f, food = 1f, mate = 0f))
    }

    /** Behavioural fitness: does the expressed brain make the two life-critical decisions? */
    private fun fitness(g: Genome): Float {
        val b = CreatureMind.build(g, 0f)
        var f = 0f
        if (decide(b, hunger = 1f, urge = 0f, food = 1f, mate = 0f) == CreatureMind.A_SEEK_FOOD) f += 1f
        if (decide(b, hunger = 0f, urge = 1f, food = 0f, mate = 1f) == CreatureMind.A_SEEK_MATE) f += 1f
        return f
    }

    @Test
    fun instinctEvolvesUnderSelection() {
        // Start with RANDOM instincts (most decide poorly); select for good decisions; watch
        // behaviour evolve — only possible because instinct is now gene-encoded + heritable.
        val rng = GeneRng(2024)
        fun randomGenome() = Genome(1, 1, instinctGenes { _, _ -> rng.nextFloat() * 2f })
        var pop = Population((0 until 40).map { Individual(randomGenome()) })

        val initialFitness = pop.members.map { fitness(it.genome) }.average()

        repeat(40) { pop = pop.evolve(mutationRate = 0.5f, rng = rng) { fitness(it) } }

        val finalFitness = pop.members.map { fitness(it.genome) }.average()

        assertEquals(40, pop.size)
        assertTrue(finalFitness > initialFitness,
            "behaviour should adapt under selection (mean fitness $initialFitness → $finalFitness)")
        assertTrue(finalFitness > 1.5,
            "an evolved population should mostly make both life-critical decisions: $finalFitness")
        // (We assert the *behaviour* improves, not which dendrites encode it — the network finds
        // its own solution; an early version wrongly assumed the hunger→food weight must rise.)
    }
}
