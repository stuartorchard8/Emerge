package org.emerge.demo.outofspace.world

/**
 * Persistent cursor state — maps fork tile to last-used branch index.
 *
 * This is the type stored in [VesselState]. For the mutable per-tick version, see [FlowCursors].
 */
data class Diverters private constructor(private val map: Map<Int, Int> = emptyMap()) {
    val cursor: Map<Int, Int> get() = map

    val isEmpty: Boolean get() = map.isEmpty()

    override fun equals(other: Any?): Boolean =
        this === other || (other is Diverters && this.map == other.map)

    override fun hashCode(): Int = map.hashCode()

    override fun toString(): String = "Diverters($map)"

    companion object {
        val EMPTY: Diverters = Diverters()
        fun of(cursor: Map<Int, Int>): Diverters = if (cursor.isEmpty()) EMPTY else Diverters(cursor.toMap())
    }
}
