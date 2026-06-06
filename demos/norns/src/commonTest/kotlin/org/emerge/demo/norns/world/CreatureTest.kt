package org.emerge.demo.norns.world

import org.emerge.demo.norns.biology.Biology
import org.emerge.demo.norns.biology.BiologyConfig
import org.emerge.demo.norns.brain.Brain
import org.emerge.demo.norns.brain.Lobe
import org.emerge.demo.norns.brain.Tract
import org.emerge.demo.norns.gene.GeneRng
import org.emerge.demo.norns.sensori.DriveModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Self-verification harness for the embodied creature (subsystem 6): brain + drives + biology
 * running as one tick in a world. Proves the integrated loop sustains life when the policy is
 * good, kills via starvation when it isn't, and that a *learned* policy outlives an untrained one.
 *
 * Single-drive (hunger) survival problem: eating when food is present is unambiguously the
 * life-preserving behaviour. The richer multi-drive trade-off (hunger vs fatigue), where the flat
 * discomfort sum no longer aligns with survival, is the tuning surface DESIGN.md G7 flags.
 * Constants are placeholders (G1).
 */
class CreatureTest {

    private val HUNGER = 0
    private val REST = 0; private val EAT = 1   // REST=0 so an untrained tie defaults to "don't eat"
    private val FOOD = 1                          // perception index of the food sensor
    private val INJURY = 0; private val REPAIR = 1; private val AGE = 2; private val STAGE = 3
    private val window = 300

    private fun driveModel() = DriveModel(
        driveCount = 1,
        actionCount = 2,
        riseRate = floatArrayOf(0.05f),
        actionEffect = arrayOf(
            floatArrayOf(0f),     // REST -> no effect (a wasted action when hungry)
            floatArrayOf(-0.5f),  // EAT  -> relieves hunger
        ),
    )

    private fun biology(maxAge: Int = 100_000) = Biology(
        BiologyConfig(
            stageStartAge = intArrayOf(0, 10, 20, 30, 40, 50, 60, 70),
            maxAge = maxAge, organCount = 1, vital = booleanArrayOf(true),
            injuryLocus = INJURY, repairLocus = REPAIR, ageLocus = AGE, lifeStageLocus = STAGE,
        ),
    )

    private fun freshBrain(): Brain {
        val perception = Lobe(2) // hunger, food
        val decision = Lobe(2)   // REST, EAT
        val tract = Tract(perception, decision) { _, _ -> 0.05f }
        return Brain(listOf(perception, decision), listOf(tract), learnRate = 0.1f)
    }

    private fun config(learn: Boolean, explore: Float, starvationDamage: Float = 2.5f) = CreatureConfig(
        perceptionSize = 2, foodSensorIndex = FOOD, hungerDrive = HUNGER, eatAction = EAT,
        biologyLocusCount = 4, learn = learn, explore = explore, starvationDamage = starvationDamage,
    )

    @Test
    fun competentCreatureSurvivesByEating() {
        val brain = freshBrain()
        brain.tracts[0].weight[EAT][FOOD] = 3f // eat whenever food is present
        val creature = Creature(brain, biology(), driveModel(), config(learn = false, explore = 0f), GeneRng(1))
        val world = World()

        repeat(window) { creature.tick(world) }
        assertTrue(creature.alive, "a creature that eats when food is available should survive")
        assertTrue(creature.drives.level[HUNGER] < 0.8f, "hunger stays out of the starvation zone: ${creature.drives.level[HUNGER]}")
    }

    @Test
    fun creatureThatNeverEatsStarvesToDeath() {
        val creature = Creature(freshBrain(), biology(), driveModel(), config(learn = false, explore = 0f), GeneRng(2))
        val world = World()

        repeat(window) { creature.tick(world) }
        assertFalse(creature.alive, "a creature that never eats should starve")
        assertTrue(creature.ticksLived < window, "it should die before the window ends (lived ${creature.ticksLived})")
    }

    @Test
    fun learnedPolicyOutlivesUntrained() {
        // Train a brain in a death-free world (drive-reduction reward), then drop it into a normal
        // world: the learned creature should survive where an untrained one starves.
        val trainedBrain = freshBrain()
        val trainer = Creature(
            trainedBrain, biology(), driveModel(),
            config(learn = true, explore = 0.25f, starvationDamage = 0f), // no death during training
            GeneRng(12345),
        )
        val trainWorld = World()
        repeat(6000) { trainer.tick(trainWorld) }

        val learned = Creature(trainedBrain, biology(), driveModel(), config(learn = false, explore = 0f), GeneRng(7))
        val control = Creature(freshBrain(), biology(), driveModel(), config(learn = false, explore = 0f), GeneRng(8))
        val w1 = World(); val w2 = World()
        repeat(window) { learned.tick(w1) }
        repeat(window) { control.tick(w2) }

        assertTrue(learned.alive, "the learned creature should survive the window")
        assertFalse(control.alive, "the untrained creature should starve")
        assertTrue(learned.ticksLived > control.ticksLived,
            "learning should extend life (learned=${learned.ticksLived} control=${control.ticksLived})")
    }

    @Test
    fun tickIsDeterministic() {
        fun run(): Creature {
            val c = Creature(freshBrain(), biology(), driveModel(), config(learn = true, explore = 0.25f), GeneRng(99))
            val w = World()
            repeat(120) { c.tick(w) }
            return c
        }
        val a = run(); val b = run()
        assertTrue(a.ticksLived == b.ticksLived && a.alive == b.alive, "creature tick must be deterministic")
        for (i in a.drives.level.indices) {
            assertTrue(a.drives.level[i].toRawBits() == b.drives.level[i].toRawBits(), "drive[$i] diverged")
        }
    }
}
