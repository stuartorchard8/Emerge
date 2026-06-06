package org.emerge.demo.norns.sensori

import org.emerge.demo.norns.brain.Brain
import org.emerge.demo.norns.brain.Lobe
import org.emerge.demo.norns.brain.Tract
import org.emerge.demo.norns.gene.GeneRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for drives + sensorimotor (subsystem 5). Mechanism tests for drive
 * rise / action effects / drive-reduction reward, then the capstone integration: a creature that
 * wires drives → brain → action → reward and **learns to satisfy its own drives** (the core
 * Creatures loop). Constants are placeholders (DESIGN.md G1/G7).
 */
class DrivesTest {

    private val HUNGER = 0; private val FATIGUE = 1
    private val EAT = 0; private val REST = 1

    private fun model() = DriveModel(
        driveCount = 2,
        actionCount = 2,
        riseRate = floatArrayOf(0.03f, 0.03f),
        actionEffect = arrayOf(
            floatArrayOf(-0.5f, 0f), // EAT reduces hunger
            floatArrayOf(0f, -0.5f), // REST reduces fatigue
        ),
    )

    @Test
    fun drivesRiseAndClamp() {
        val m = model(); val d = Drives(2)
        repeat(10) { m.rise(d) }
        assertTrue(kotlin.math.abs(d.level[HUNGER] - 0.3f) < 1e-4f, "hunger rose: ${d.level[HUNGER]}")
        repeat(1000) { m.rise(d) }
        assertEquals(1f, d.level[HUNGER], "drive clamps at 1")
    }

    @Test
    fun actionReducesTargetDriveAndClampsAtZero() {
        val m = model(); val d = Drives(2)
        d.level[HUNGER] = 0.6f
        m.perform(EAT, d)
        assertTrue(kotlin.math.abs(d.level[HUNGER] - 0.1f) < 1e-4f, "EAT reduced hunger: ${d.level[HUNGER]}")
        m.perform(EAT, d)
        assertEquals(0f, d.level[HUNGER], "drive clamps at 0")
    }

    @Test
    fun rewardIsTotalDriveReduction() {
        val m = model(); val d = Drives(2)
        d.level[HUNGER] = 0.8f; d.level[FATIGUE] = 0.2f
        val prev = d.discomfort() // 1.0
        m.perform(EAT, d)         // hunger 0.8 -> 0.3
        assertTrue(kotlin.math.abs(m.reward(prev, d) - 0.5f) < 1e-4f, "reward = discomfort drop")
    }

    @Test
    fun creatureLearnsToSatisfyItsDrives() {
        // Wire drives -> brain (perception = drive levels) -> action -> drive-reduction reward ->
        // learning. The creature should learn to EAT when hungry and REST when tired, and thereby
        // keep itself more comfortable than an untrained creature that can't adapt.
        val m = model()
        val trained = makeBrain()
        train(trained, m, GeneRng(20240607))

        val perception = trained.lobes[0]; val decision = trained.lobes[1]

        // Learned policy: the dominant drive selects the matching action.
        perception.set(floatArrayOf(0.9f, 0.1f)); trained.propagate()
        assertEquals(EAT, decision.argmax(), "hungry creature should choose EAT")
        perception.set(floatArrayOf(0.1f, 0.9f)); trained.propagate()
        assertEquals(REST, decision.argmax(), "tired creature should choose REST")

        // Homeostasis: the trained creature keeps lower average discomfort than an untrained one.
        val trainedDiscomfort = avgDiscomfort(trained, m)
        val untrainedDiscomfort = avgDiscomfort(makeBrain(), m)
        assertTrue(trainedDiscomfort < untrainedDiscomfort,
            "learning should improve homeostasis (trained=$trainedDiscomfort untrained=$untrainedDiscomfort)")
    }

    // ── fixture ──────────────────────────────────────────────────────────────────
    private fun makeBrain(): Brain {
        val perception = Lobe(2) // drive levels as context
        val decision = Lobe(2)   // EAT, REST
        val tract = Tract(perception, decision) { _, _ -> 0.05f }
        return Brain(listOf(perception, decision), listOf(tract), learnRate = 0.1f)
    }

    /** ε-greedy training: act (mostly greedily, sometimes exploring), then reinforce the chosen
     *  action by the drive reduction it produced. */
    private fun train(brain: Brain, m: DriveModel, rng: GeneRng) {
        val perception = brain.lobes[0]; val decision = brain.lobes[1]
        val d = Drives(2)
        val eps = 0.25f
        repeat(5000) {
            m.rise(d)
            perception.set(d.level)
            val prev = d.discomfort()
            brain.propagate()
            val action = if (rng.nextFloat() < eps) (if (rng.nextFloat() < 0.5f) EAT else REST) else decision.argmax()
            m.perform(action, d)
            val reward = m.reward(prev, d)
            decision.set(oneHot(action))   // post = the action actually taken
            brain.learn(reward)
        }
    }

    /** Average discomfort over a greedy (no-learning) evaluation window from a fixed start. */
    private fun avgDiscomfort(brain: Brain, m: DriveModel): Float {
        val perception = brain.lobes[0]; val decision = brain.lobes[1]
        val d = Drives(2); d.level[HUNGER] = 0.5f; d.level[FATIGUE] = 0.5f
        var total = 0f
        val window = 500
        repeat(window) {
            m.rise(d)
            perception.set(d.level)
            brain.propagate()
            m.perform(decision.argmax(), d)
            total += d.discomfort()
        }
        return total / window
    }

    private fun oneHot(k: Int) = FloatArray(2) { if (it == k) 1f else 0f }
}
