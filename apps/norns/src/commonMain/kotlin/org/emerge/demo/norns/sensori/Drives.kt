package org.emerge.demo.norns.sensori

/**
 * A creature's drives — its discomforts (hunger, fatigue, …), each in `[0, 1]` where 0 is
 * satisfied. Drives rise over time and fall when the creature performs a drive-satisfying
 * action; the creature is rewarded for *reducing* its total drive. This drive-reduction reward
 * is the reinforcement signal that teaches the brain which actions help in which states — the
 * core Creatures learning loop.
 */
class Drives(val count: Int) {
    val level = FloatArray(count)

    /** Total discomfort across all drives (the quantity the creature is driven to minimise). */
    fun discomfort(): Float {
        var s = 0f
        for (v in level) s += v
        return s
    }
}

/**
 * The sensorimotor model: how drives drift up over time ([riseRate]) and how each action changes
 * them ([actionEffect]`[action][drive]`). Together with [reward] this turns "did that action make
 * me feel better?" into the scalar the brain learns from.
 *
 * Faithful in shape, simplified in detail: a fixed action→drive effect table (C1 derives effects
 * from world objects + biochemistry) and a flat discomfort sum (C1 weights drives). Both are
 * tuning surfaces — DESIGN.md G7.
 */
class DriveModel(
    val driveCount: Int,
    val actionCount: Int,
    val riseRate: FloatArray,
    val actionEffect: Array<FloatArray>,
) {
    init {
        require(riseRate.size == driveCount) { "riseRate must have driveCount entries" }
        require(actionEffect.size == actionCount) { "actionEffect must have actionCount rows" }
        actionEffect.forEach { require(it.size == driveCount) { "each actionEffect row needs driveCount cols" } }
    }

    /** Natural per-tick increase of every drive (clamped to [0,1]). */
    fun rise(d: Drives) {
        for (i in 0 until driveCount) d.level[i] = (d.level[i] + riseRate[i]).coerceIn(0f, 1f)
    }

    /** Applies [action]'s drive effects (clamped to [0,1]). */
    fun perform(action: Int, d: Drives) {
        val e = actionEffect[action]
        for (i in 0 until driveCount) d.level[i] = (d.level[i] + e[i]).coerceIn(0f, 1f)
    }

    /** Reward = reduction in total discomfort since [prevDiscomfort] (positive when drives fell). */
    fun reward(prevDiscomfort: Float, d: Drives): Float = prevDiscomfort - d.discomfort()
}
