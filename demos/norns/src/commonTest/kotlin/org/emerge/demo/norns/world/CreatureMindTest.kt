package org.emerge.demo.norns.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for wiring the brain into behaviour (G10): the instinct-primed brain
 * makes sensible goal decisions from its drives/senses, and reinforcement adjusts those decisions.
 * (Colony viability under brain-driven behaviour is gated by NornsWorldTest.)
 */
class CreatureMindTest {

    private fun brain(learnRate: Float = 0f) = CreatureMind.build(learnRate)

    private fun decide(b: org.emerge.demo.norns.brain.Brain, hunger: Float, urge: Float, food: Float, mate: Float, fatigue: Float = 0f): Int {
        b.lobes[0].set(floatArrayOf(hunger, urge, food, mate, fatigue, 1f))
        b.propagate()
        return b.lobes[1].argmax()
    }

    @Test
    fun instinctBrainChoosesSensibleGoals() {
        val b = brain()
        assertEquals(CreatureMind.A_SEEK_FOOD, decide(b, hunger = 0.9f, urge = 0f, food = 1f, mate = 0f),
            "hungry → seek food")
        assertEquals(CreatureMind.A_SEEK_MATE, decide(b, hunger = 0f, urge = 0.9f, food = 0f, mate = 1f),
            "fed + urgent → seek mate")
        assertEquals(CreatureMind.A_REST, decide(b, hunger = 0f, urge = 0f, food = 0f, mate = 0f),
            "satisfied → rest")
        assertEquals(CreatureMind.A_REST, decide(b, hunger = 0f, urge = 0f, food = 0f, mate = 0f, fatigue = 0.9f),
            "tired → rest")
        // Survival takes priority when both press (tie → lower index = seek food).
        assertEquals(CreatureMind.A_SEEK_FOOD, decide(b, hunger = 0.9f, urge = 0.9f, food = 1f, mate = 1f),
            "hungry AND urgent → eat first")
    }

    @Test
    fun reinforcementStrengthensTheRewardedGoal() {
        val b = brain(learnRate = 0.1f)
        val w = b.tracts[0].weight
        val before = w[CreatureMind.A_SEEK_FOOD][CreatureMind.P_HUNGER]
        // Repeatedly: hungry context, chose SEEK_FOOD, got rewarded (drive fell from eating).
        repeat(20) {
            b.lobes[0].set(floatArrayOf(1f, 0f, 1f, 0f, 0f, 1f)) // hunger, urge, food, mate, fatigue, bias
            b.propagate()
            b.lobes[1].set(floatArrayOf(1f, 0f, 0f)) // post = SEEK_FOOD
            b.learn(reward = 1f)
        }
        assertTrue(w[CreatureMind.A_SEEK_FOOD][CreatureMind.P_HUNGER] > before,
            "reward should strengthen seek-food-when-hungry (was $before, now ${w[CreatureMind.A_SEEK_FOOD][CreatureMind.P_HUNGER]})")
    }
}
