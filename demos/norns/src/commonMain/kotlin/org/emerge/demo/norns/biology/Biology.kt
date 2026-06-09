package org.emerge.demo.norns.biology

/**
 * The life cycle and physiology of one creature: an aging clock that advances [lifeStage], a set
 * of organs with [organHealth], and the death conditions that end the creature. Like the brain,
 * biology couples to the rest of the creature only through the locus bus — it reads an injury and
 * a repair locus, and publishes its age and life stage back — so biochemistry and the world can
 * gate on life stage without biology knowing about them.
 *
 * Faithful in mechanism, simplified in detail (DESIGN.md gap G6): injury/repair apply uniformly
 * across organs from single loci (C1 damages organs independently and runs their own
 * chemistry), and life-stage transitions are driven by an age clock rather than the genome's
 * life-stage chemicals. Death follows the two main C1 routes: a **vital organ failing**, or
 * **old age**.
 */
class Biology(val cfg: BiologyConfig) {
    var age: Int = 0
        private set
    var lifeStage: LifeStage = LifeStage.EMBRYO
        private set
    var alive: Boolean = true
        private set
    val organHealth = FloatArray(cfg.organCount) { 1f }

    /**
     * One tick of physiology. Reads `loci[injuryLocus]` / `loci[repairLocus]`, ages, updates
     * organ health, advances life stage, applies death conditions, then publishes
     * `loci[ageLocus]` and `loci[lifeStageLocus]`. A no-op once dead (age and state freeze).
     */
    fun tick(loci: FloatArray) {
        if (!alive) return
        age++

        val injury = loci[cfg.injuryLocus].coerceAtLeast(0f) * cfg.damageGain
        val repair = loci[cfg.repairLocus].coerceAtLeast(0f) * cfg.repairGain
        val delta = repair - injury
        for (i in 0 until cfg.organCount) {
            organHealth[i] = (organHealth[i] + delta).coerceIn(0f, 1f)
        }

        lifeStage = stageForAge(age)

        if (age >= cfg.maxAge) {
            die()
        } else {
            for (i in 0 until cfg.organCount) {
                if (cfg.vital[i] && organHealth[i] <= 0f) { die(); break }
            }
        }

        loci[cfg.ageLocus] = age.toFloat()
        loci[cfg.lifeStageLocus] = lifeStage.ordinal.toFloat()
    }

    private fun stageForAge(a: Int): LifeStage {
        var stage = LifeStage.EMBRYO
        for (s in LifeStage.entries) if (a >= cfg.stageStartAge[s.ordinal]) stage = s
        return stage
    }

    private fun die() { alive = false }
}

/** The Creatures life stages (death is the orthogonal [Biology.alive] flag, not a stage). */
enum class LifeStage { EMBRYO, BABY, CHILD, ADOLESCENT, YOUTH, ADULT, OLD, SENILE }

/** Walk-speed multiplier by life stage: babies are slowest, ramping **linearly** up to full speed
 *  at adult. [babyFactor] is the baby's fraction of adult speed; child + adolescent interpolate
 *  between it and 1.0 (youth and older walk at full speed). Used by both the sim (movement) and the
 *  renderer (so the walk cadence still matches the scaled speed). */
fun walkSpeedFactor(stage: LifeStage, babyFactor: Float): Float {
    val age = when (stage) { LifeStage.BABY -> 0; LifeStage.CHILD -> 1; LifeStage.ADOLESCENT -> 2; else -> 3 }
    return babyFactor + (1f - babyFactor) * (age / 3f)
}

/**
 * @param stageStartAge age (ticks) at which each [LifeStage] begins; ascending, `[0] == 0`,
 *   length == number of life stages.
 * @param maxAge creature dies of old age at this age.
 * @param organCount number of organs; [vital] (same length) marks which kill the creature at 0.
 * @param injuryLocus / repairLocus loci read each tick to damage / repair organs.
 * @param ageLocus / lifeStageLocus loci published each tick for other subsystems to gate on.
 */
class BiologyConfig(
    val stageStartAge: IntArray,
    val maxAge: Int,
    val organCount: Int,
    val vital: BooleanArray,
    val injuryLocus: Int,
    val repairLocus: Int,
    val ageLocus: Int,
    val lifeStageLocus: Int,
    val damageGain: Float = 1f,
    val repairGain: Float = 1f,
) {
    init {
        require(stageStartAge.size == LifeStage.entries.size) {
            "stageStartAge must have ${LifeStage.entries.size} entries"
        }
        require(vital.size == organCount) { "vital must have organCount entries" }
    }
}
