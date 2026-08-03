package org.emerge.demo.outofspace.world

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
