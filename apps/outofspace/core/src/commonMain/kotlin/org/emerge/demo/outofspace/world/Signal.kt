package org.emerge.demo.outofspace.world

/**
 * A signal channel — the vessel's wiring colours.
 *
 * A fixed palette rather than player-named strings, because naming things needs a keyboard and this
 * game has to work on a phone. Six colours is plenty for a vessel and they read at a glance, which
 * is the same reason ONI and Factorio use coloured wires and icons.
 *
 * [Always] is the constant: it reads 1000 permille forever and is what every machine is wired to by
 * default, so a freshly placed machine simply works and wiring is something you *add*.
 */
enum class Channel(val label: String, val color: Long) {
    Always("ALWAYS", 0x9AA4B4FFL),
    Red("RED", 0xD05A4AFFL),
    Green("GREEN", 0x5AC07AFFL),
    Blue("BLUE", 0x4A8AD0FFL),
    Amber("AMBER", 0xE0A93AFFL),
    Cyan("CYAN", 0x4AC0C8FFL),
    Violet("VIOLET", 0xA06AD0FFL),
    ;

    companion object {
        val ALL: List<Channel> = entries.toList()

        /** The channels a sensor may emit on — everything except the constant. */
        val EMITTABLE: List<Channel> = ALL.filter { it != Always }
    }
}

/**
 * The value on every channel this tick, in permille.
 *
 * Permille integers rather than floats: this feeds machine throughput, and a float here would put
 * platform-dependent rounding directly into the simulation's output rates.
 *
 * When several sensors emit on one channel the **highest** wins. "Any of these is full" is the
 * useful reading, and max is associative and order-independent — which matters, because the
 * alternative (last writer wins) would make the result depend on grid iteration order.
 */
class Signals(private val values: IntArray) {
    operator fun get(channel: Channel): Int = values[channel.ordinal]

    override fun toString(): String =
        Channel.ALL.filter { values[it.ordinal] != 0 }.joinToString { "${it.label}=${values[it.ordinal]}" }

    companion object {
        const val FULL = 1000

        fun build(emit: (raise: (Channel, Int) -> Unit) -> Unit): Signals {
            val values = IntArray(Channel.ALL.size)
            values[Channel.Always.ordinal] = FULL
            emit { channel, value ->
                if (channel != Channel.Always && value > values[channel.ordinal]) {
                    values[channel.ordinal] = value.coerceIn(0, FULL)
                }
            }
            return Signals(values)
        }
    }
}

/**
 * What a machine can be told to do. One entry today, deliberately: [Run] is a *throttle*, not a
 * switch — its magnitude scales the machine's rate, so a half-strength signal is a half-speed
 * machine and weights mean something beyond on and off.
 *
 * The map-of-actions shape is kept from the Godot `action_triggers` grammar so that adding a second
 * action later is adding an enum entry rather than reworking the wiring.
 */
enum class Action(val label: String) {
    Run("RUN"),
}

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

/** The weights the UI cycles through. A short ladder beats a slider on a touchscreen. */
val WEIGHT_LADDER: List<Int> = listOf(1000, 750, 500, 250, -250, -500, -750, -1000)
