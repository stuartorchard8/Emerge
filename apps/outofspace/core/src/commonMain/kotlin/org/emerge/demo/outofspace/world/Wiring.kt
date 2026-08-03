package org.emerge.demo.outofspace.world

/** One term of an action's sum: a channel and how strongly it counts, in permille. */
data class Trigger(val channel: Channel, val weightPermille: Int) {
    /** Rendered as a percentage, since that is how it reads in the UI. */
    val percent: Int get() = weightPermille / 10
}

/**
 * How a machine's actions are driven: `activation = Σ(signal × weight)`, clamped to ±1000.
 *
 * This is the Godot vessel's `action_triggers` grammar, unchanged. It is a small, complete dataflow
 * language — enough to express "run unless the buffer is full", "run only while the smelter is
 * hungry", and inverted control by a negative weight — without needing a scripting language or a
 * graph editor.
 *
 * ### It is proportional, not digital
 * `ALWAYS − RED` does not stop a machine when RED arrives; it *fades* it out as RED rises, because
 * activation throttles the rate. A miner filling a tank therefore slows as the tank fills and
 * approaches full asymptotically rather than slamming shut. That is the more interesting behaviour
 * and it is deliberate.
 *
 * What the grammar cannot say is a **threshold** — "stop when the tank is past 90%" needs a
 * comparison, and there is no comparison here. That gap is known and deliberate for now; adding
 * `WHEN RED > 900` later is a new kind of term, not a change to this arithmetic.
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
