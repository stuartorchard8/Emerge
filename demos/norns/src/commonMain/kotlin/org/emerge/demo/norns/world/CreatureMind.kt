package org.emerge.demo.norns.world

import org.emerge.demo.norns.brain.Brain
import org.emerge.demo.norns.brain.Lobe
import org.emerge.demo.norns.brain.Tract

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

    /** Builds a fresh brain with the instinct prior. */
    fun build(learnRate: Float): Brain {
        val perception = Lobe(PERCEPTION)
        val decision = Lobe(ACTIONS)
        val tract = Tract(perception, decision) { action, sense -> instinct(action, sense) }
        return Brain(listOf(perception, decision), listOf(tract), learnRate = learnRate)
    }

    private fun instinct(action: Int, sense: Int): Float = when (action) {
        A_SEEK_FOOD -> when (sense) { P_HUNGER -> 2.5f; P_FOOD -> 1f; else -> 0f }
        A_SEEK_MATE -> when (sense) { P_URGE -> 2.5f; P_MATE -> 1f; else -> 0f }
        A_REST -> if (sense == P_BIAS) 0.3f else 0f
        else -> 0f
    }
}
