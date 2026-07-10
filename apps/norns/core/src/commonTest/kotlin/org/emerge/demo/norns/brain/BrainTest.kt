package org.emerge.demo.norns.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for the brain (subsystem 3). Mechanism tests (weighted propagation,
 * reward-gated Hebbian strengthen/weaken, determinism) plus the capstone behavioural proxy:
 * the brain **learns a context→action association** from reward — the signature Creatures
 * property. Constants are placeholders (DESIGN.md G1); this proves the learning MECHANISM.
 */
class BrainTest {

    private fun eqf(a: Float, b: Float, tol: Float = 1e-5f, msg: String = "") =
        assertTrue(kotlin.math.abs(a - b) <= tol, "$msg expected≈$b got=$a")

    @Test
    fun propagationSumsWeightedInputsThenActivates() {
        val perception = Lobe(2)
        val decision = Lobe(1)
        val tract = Tract(perception, decision) { _, s -> if (s == 0) 1f else 0.4f }
        val brain = Brain(listOf(perception, decision), listOf(tract), learnRate = 0f)

        perception.set(floatArrayOf(0.5f, 0.5f))
        brain.propagate()
        eqf(decision.output[0], 0.7f, msg = "0.5*1.0 + 0.5*0.4:") // 0.7, below the [0,1] clamp
    }

    @Test
    fun rewardStrengthensAndPunishmentWeakensCoactiveDendrites() {
        val pre = Lobe(1); val post = Lobe(1)
        val tract = Tract(pre, post) { _, _ -> 0.2f }
        val brain = Brain(listOf(pre, post), listOf(tract), learnRate = 0.1f)

        pre.set(floatArrayOf(1f)); post.set(floatArrayOf(1f))
        brain.learn(reward = 1f)
        eqf(tract.weight[0][0], 0.3f, msg = "reward strengthens co-active:") // 0.2 + 0.1*1*1*1

        brain.learn(reward = -1f)
        eqf(tract.weight[0][0], 0.2f, msg = "punishment weakens:") // 0.3 - 0.1

        // Inactive source: no change regardless of reward.
        pre.set(floatArrayOf(0f)); post.set(floatArrayOf(1f))
        brain.learn(reward = 1f)
        eqf(tract.weight[0][0], 0.2f, msg = "inactive dendrite unchanged:")
    }

    @Test
    fun learningIsDeterministic() {
        fun run(): Brain = makeAssociationBrain().also { trainAssociations(it) }
        val a = run(); val b = run()
        val ta = a.tracts[0]; val tb = b.tracts[0]
        for (d in ta.weight.indices) for (s in ta.weight[d].indices) {
            assertEquals(ta.weight[d][s].toRawBits(), tb.weight[d][s].toRawBits(), "weight[$d][$s]")
        }
    }

    @Test
    fun brainLearnsContextActionAssociation() {
        // RED context should come to mean EAT, BLUE should come to mean REST — learned purely
        // from reward co-occurring with (context, action) activity.
        val brain = makeAssociationBrain()
        val perception = brain.lobes[0]; val decision = brain.lobes[1]

        // Before training: untrained brain has no preference (uniform weights -> tie -> index 0).
        trainAssociations(brain)

        // After training, free propagation chooses the rewarded action per context.
        perception.set(floatArrayOf(1f, 0f)) // RED
        brain.propagate()
        assertEquals(EAT, decision.argmax(), "RED should choose EAT after training")
        assertTrue(decision.output[EAT] > decision.output[REST], "RED: EAT must out-activate REST")

        perception.set(floatArrayOf(0f, 1f)) // BLUE
        brain.propagate()
        assertEquals(REST, decision.argmax(), "BLUE should choose REST after training")
        assertTrue(decision.output[REST] > decision.output[EAT], "BLUE: REST must out-activate EAT")
    }

    // ── association-learning fixture ─────────────────────────────────────────────
    private val RED = 0; private val BLUE = 1
    private val EAT = 0; private val REST = 1

    private fun makeAssociationBrain(): Brain {
        val perception = Lobe(2) // RED, BLUE
        val decision = Lobe(2)   // EAT, REST
        val tract = Tract(perception, decision) { _, _ -> 0.05f } // small uniform start
        return Brain(listOf(perception, decision), listOf(tract), learnRate = 0.05f)
    }

    /** Reward EAT-in-RED and REST-in-BLUE (post activity teacher-forced, reward present). */
    private fun trainAssociations(brain: Brain) {
        val perception = brain.lobes[0]; val decision = brain.lobes[1]
        repeat(30) {
            perception.set(floatArrayOf(1f, 0f)); decision.set(floatArrayOf(1f, 0f)); brain.learn(1f) // RED→EAT
            perception.set(floatArrayOf(0f, 1f)); decision.set(floatArrayOf(0f, 1f)); brain.learn(1f) // BLUE→REST
        }
    }
}
