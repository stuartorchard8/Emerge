package org.emerge.demo.norns.world

import org.emerge.demo.norns.brain.Brain
import org.emerge.demo.norns.brain.Lobe
import org.emerge.demo.norns.brain.Tract
import org.emerge.demo.norns.gene.BrainGene
import org.emerge.demo.norns.gene.Genome

/**
 * The brain wiring for a world creature: how the neural-net [Brain] (subsystem 3) is plugged into
 * embodied behaviour. A perception lobe (drives + senses) feeds a decision lobe (what to pursue),
 * and reward-modulated learning refines the choice — so behaviour flows *through* the brain
 * rather than a hardwired rule (closing DESIGN.md G10's behaviour gap; navigation to the chosen
 * target stays mechanical).
 *
 * The brain decides a *goal verb* (seek food / seek a mate / rest), in the spirit of Creatures'
 * decision lobe choosing a verb toward what it attends to. Each creature is born with an
 * **instinct prior** on its dendrites (hunger→seek-food, urge→seek-mate) so newborns act
 * sensibly; reinforcement (drive reduction) then tunes those weights over its life. Innate
 * instinct should ultimately be gene-encoded (G5); here it's a fixed prior.
 */
object CreatureMind {
    // perception neurons
    const val P_HUNGER = 0   // how hungry (0..1)
    const val P_URGE = 1     // mating urge (0..1)
    const val P_FOOD = 2     // food proximity sense (1 near, 0 far/none)
    const val P_MATE = 3     // mate proximity sense
    const val P_BIAS = 4     // always 1 (lets REST be the default when nothing presses)
    const val PERCEPTION = 5

    // decision neurons (goal verbs)
    const val A_SEEK_FOOD = 0
    const val A_SEEK_MATE = 1
    const val A_REST = 2
    const val ACTIONS = 3

    /** Builds a brain from the fixed default instinct prior (used by tests / un-gened callers). */
    fun build(learnRate: Float): Brain =
        buildFromWeights(learnRate) { action, sense -> instinct(action, sense) }

    /**
     * Builds a creature's starting brain from its **genome's** [BrainGene]s (DESIGN.md G5): each
     * gene sets one instinct dendrite weight; unspecified dendrites start at 0. This is what makes
     * instinct heritable + evolvable. Genes with out-of-range indices are ignored.
     */
    fun build(genome: Genome, learnRate: Float): Brain {
        val w = Array(ACTIONS) { FloatArray(PERCEPTION) }
        for (g in genome.genes) if (g is BrainGene && g.action in 0 until ACTIONS && g.sense in 0 until PERCEPTION) {
            w[g.action][g.sense] = g.weight
        }
        return buildFromWeights(learnRate) { action, sense -> w[action][sense] }
    }

    /** The default instinct as a full set of [BrainGene]s (every action×sense dendrite), so a
     *  seeded genome can carry — and then evolve — the whole instinct. */
    fun defaultInstinctGenes(): List<BrainGene> = buildList {
        for (a in 0 until ACTIONS) for (s in 0 until PERCEPTION) add(BrainGene(a, s, instinct(a, s)))
    }

    private fun buildFromWeights(learnRate: Float, weight: (action: Int, sense: Int) -> Float): Brain {
        val perception = Lobe(PERCEPTION)
        val decision = Lobe(ACTIONS)
        val tract = Tract(perception, decision) { action, sense -> weight(action, sense) }
        return Brain(listOf(perception, decision), listOf(tract), learnRate = learnRate)
    }

    private fun instinct(action: Int, sense: Int): Float = when (action) {
        A_SEEK_FOOD -> when (sense) { P_HUNGER -> 2.5f; P_FOOD -> 1f; else -> 0f }
        A_SEEK_MATE -> when (sense) { P_URGE -> 2.5f; P_MATE -> 1f; else -> 0f }
        A_REST -> if (sense == P_BIAS) 0.3f else 0f
        else -> 0f
    }
}
