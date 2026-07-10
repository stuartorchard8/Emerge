package org.emerge.demo.norns.world

import org.emerge.demo.norns.biology.Biology
import org.emerge.demo.norns.brain.Brain
import org.emerge.demo.norns.gene.GeneRng
import org.emerge.demo.norns.sensori.DriveModel
import org.emerge.demo.norns.sensori.Drives

/**
 * An embodied creature: the integration point where brain, drives, and biology run together over
 * one tick, coupled to a [World]. This is where the separately-verified subsystems become a
 * living thing.
 *
 * Each tick: drives rise → the brain perceives (drive levels + a food sensor) and chooses an
 * action → the action acts on the world and drives (EAT only helps if there's food) → the
 * drive-reduction reward trains the brain → sustained hunger injures the organs → biology ages
 * the creature and applies death. A creature that keeps its hunger down stays healthy and lives;
 * one that doesn't starves and dies.
 *
 * Biochemistry isn't wired in here yet — drives are still a separate system rather than chemicals
 * read via receptors (DESIGN.md G8). This subsystem integrates the behavioural survival loop;
 * folding drives into the biochemistry is the remaining integration step.
 */
class Creature(
    val brain: Brain,
    val biology: Biology,
    val driveModel: DriveModel,
    private val cfg: CreatureConfig,
    private val rng: GeneRng,
) {
    val drives = Drives(driveModel.driveCount)
    private val biologyLoci = FloatArray(cfg.biologyLocusCount)

    var ticksLived: Int = 0
        private set

    val alive: Boolean get() = biology.alive

    fun tick(world: World) {
        if (!biology.alive) return
        world.step()
        driveModel.rise(drives)

        // Perceive: drive levels (by index) + a food-present sensor.
        val percept = FloatArray(cfg.perceptionSize)
        for (i in 0 until driveModel.driveCount) percept[i] = drives.level[i]
        percept[cfg.foodSensorIndex] = if (world.foodAvailable) 1f else 0f
        brain.lobes[cfg.perceptionLobe].set(percept)

        val prevDiscomfort = drives.discomfort()
        brain.propagate()
        val decision = brain.lobes[cfg.decisionLobe]
        val greedy = decision.argmax()
        val action =
            if (cfg.explore > 0f && rng.nextFloat() < cfg.explore) (rng.nextInt().mod(driveModel.actionCount))
            else greedy

        // Act. EAT only satisfies hunger when there is food to eat; other actions always apply.
        if (action == cfg.eatAction) {
            if (world.consumeFood()) driveModel.perform(cfg.eatAction, drives)
        } else {
            driveModel.perform(action, drives)
        }

        // Learn: reinforce the action just taken by the discomfort it relieved.
        if (cfg.learn) {
            val reward = prevDiscomfort - drives.discomfort()
            decision.set(oneHot(action, driveModel.actionCount))
            brain.learn(reward)
        }

        // Sustained hunger injures the organs (starvation); otherwise they slowly repair.
        val hunger = drives.level[cfg.hungerDrive]
        val injury = if (hunger > cfg.starvationThreshold) (hunger - cfg.starvationThreshold) * cfg.starvationDamage else 0f
        biologyLoci[biology.cfg.injuryLocus] = injury
        biologyLoci[biology.cfg.repairLocus] = cfg.baseRepair
        biology.tick(biologyLoci)

        ticksLived++
    }

    private fun oneHot(k: Int, n: Int) = FloatArray(n) { if (it == k) 1f else 0f }
}

/**
 * Wiring for a [Creature]: which lobes/indices mean what, the action set, and the
 * learning/starvation tuning. All placeholders (DESIGN.md G1).
 */
class CreatureConfig(
    val perceptionLobe: Int = 0,
    val decisionLobe: Int = 1,
    val perceptionSize: Int,
    val foodSensorIndex: Int,
    val hungerDrive: Int,
    val eatAction: Int,
    val biologyLocusCount: Int,
    val learn: Boolean = true,
    val explore: Float = 0f,
    val starvationThreshold: Float = 0.8f,
    val starvationDamage: Float = 2.5f,
    val baseRepair: Float = 0.02f,
)
