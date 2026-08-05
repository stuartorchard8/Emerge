package org.emerge.demo.outofspace.world

/** One term of an action's sum: a channel and how strongly it counts, in permille. */
data class Trigger(val channel: Channel, val weightPermille: Int) {
    /** Rendered as a percentage, since that is how it reads in the UI. */
    val percent: Int get() = weightPermille / 10
}

/**
 * Machine action drivers: activation = Σ(signal × weight), clamped ±1000.
 * Proportional (not digital): ALWAYS-RED fades out as RED rises (asymptotic approach, not slam). No threshold support (yet).
 */
data class Wiring(val byAction: Map<Action, List<Trigger>> = DEFAULT) {

    fun triggers(action: Action): List<Trigger> = byAction[action] ?: emptyList()

    /** Activation in permille, clamped to ±1000. Zero or fewer means stopped. */
    fun activation(action: Action, signals: Signals): Int {
        var sum = 0L
        for (t in triggers(action)) sum += signals[t.channel].toLong() * t.weightPermille
        return (sum / Signals.FULL).coerceIn(-Signals.FULL.toLong(), Signals.FULL.toLong()).toInt()
    }

    fun with(action: Action, triggers: List<Trigger>): Wiring =
        Wiring(byAction.toMutableMap().also { it[action] = triggers })

    companion object {
        /** Wired to the constant at full strength: a machine you place just works. */
        val DEFAULT: Map<Action, List<Trigger>> = mapOf(
            Action.Run to listOf(Trigger(Channel.Always, Signals.FULL)),
        )

        val RUNNING = Wiring()
    }
}
