package org.emerge.demo.fluidlab.world

/**
 * Where one term of a machine's activation gets its number from.
 *
 * Two sources, and deliberately only two. [Always] is the constant — it reads full forever, needs no
 * wire, and is what a freshly placed machine is wired to, so placing a machine still just works and
 * wiring remains something you *add*. [Wire] reads the signal network under the machine's own tile.
 *
 * This pair is what replaced the six colours, and it is what made that replacement safe: an unwired
 * [Wire] term reads 0, which is exactly what an unemitted channel read, so every vessel that was
 * wired `ALWAYS − RED` went on behaving identically as `ALWAYS − WIRE` the day it changed.
 */
enum class SignalSource(val label: String) {
    Always("ALWAYS"),
    Wire("WIRE"),
    ;

    companion object {
        val ALL: List<SignalSource> = entries.toList()
    }
}

/** One term of an action's sum: where the value comes from, and how strongly it counts, in permille. */
data class Trigger(val source: SignalSource, val weightPermille: Int) {
    /** Rendered as a percentage, since that is how it reads in the UI. */
    val percent: Int get() = weightPermille / 10
}

/**
 * Machine action drivers: activation = Σ(signal × weight), clamped ±1000.
 * Proportional (not digital): ALWAYS−WIRE fades out as the wire rises (asymptotic approach, not slam). No threshold support (yet).
 */
data class Wiring(val byAction: Map<Action, List<Trigger>> = DEFAULT) {

    fun triggers(action: Action): List<Trigger> = byAction[action] ?: emptyList()

    /**
     * Activation in permille, clamped to ±1000. Zero or fewer means stopped.
     *
     * [wire] is what the network under this machine is carrying — see [SignalField.at]. It is passed
     * in rather than looked up because [Wiring] does not know where its machine is, and giving it a
     * tile index would make a machine's wiring depend on where it happens to be stored.
     */
    fun activation(action: Action, wire: Int): Int {
        var sum = 0L
        for (t in triggers(action)) {
            val value = when (t.source) {
                SignalSource.Always -> SignalField.FULL
                SignalSource.Wire -> wire
            }
            sum += value.toLong() * t.weightPermille
        }
        return (sum / SignalField.FULL).coerceIn(-SignalField.FULL.toLong(), SignalField.FULL.toLong()).toInt()
    }

    fun with(action: Action, triggers: List<Trigger>): Wiring =
        Wiring(byAction.toMutableMap().also { it[action] = triggers })

    companion object {
        /** Wired to the constant at full strength: a machine you place just works. */
        val DEFAULT: Map<Action, List<Trigger>> = mapOf(
            Action.Run to listOf(Trigger(SignalSource.Always, SignalField.FULL)),
        )

        val RUNNING = Wiring()
    }
}
