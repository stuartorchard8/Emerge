package org.emerge.demo.outofspace.world

/**
 * What every signal network is carrying this tick, in permille.
 *
 * The replacement for the six global channels. A value belongs to a *network* — a connected run of
 * wire — so a transmitter and a receiver share a reading only if the player has actually joined them
 * with something visible on screen.
 *
 * Permille integers rather than floats: this feeds machine throughput, and a float here would put
 * platform-dependent rounding directly into the simulation's output rates.
 *
 * When several transmitters drive one network the **highest** wins. "Any of these is asking for it"
 * is the useful reading, and max is associative and order-independent — which matters, because the
 * alternative (last writer wins) would make the result depend on grid iteration order.
 */
class SignalField(
    private val networks: SignalNetworks,
    private val values: IntArray,
) {
    /** What the network under [tile] is carrying — 0 where no wire is laid, which is not an error. */
    fun at(tile: Int): Int {
        val id = networks[tile]
        return if (id < 0) 0 else values[id]
    }

    /** By network id, for tests and for the readout that lists circuits rather than tiles. */
    fun ofNetwork(id: Int): Int = if (id in values.indices) values[id] else 0

    val networkCount: Int get() = values.size

    override fun toString(): String =
        (0 until values.size).filter { values[it] != 0 }.joinToString { "#$it=${values[it]}" }

    companion object {
        /** A full signal, and the denominator every weight is out of. */
        const val FULL = 1000

        /** No wire anywhere, so nothing carries anything. */
        fun none(tileCount: Int): SignalField =
            SignalField(SignalNetworks.none(tileCount), IntArray(0))

        /**
         * Collects what every transmitter is putting on the wire under it.
         *
         * [emit] is handed a `raise(tile, value)`; a tile with no wire under it is silently ignored,
         * because a sensor that has not been wired up yet is a normal state of a half-built vessel
         * and not something to complain about.
         */
        fun build(networks: SignalNetworks, emit: (raise: (Int, Int) -> Unit) -> Unit): SignalField {
            val values = IntArray(networks.count)
            emit { tile, value ->
                val id = networks[tile]
                if (id >= 0) {
                    val clamped = value.coerceIn(0, FULL)
                    if (clamped > values[id]) values[id] = clamped
                }
            }
            return SignalField(networks, values)
        }
    }
}
