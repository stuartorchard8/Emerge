package org.emerge.demo.fluidlab.world.fluid

/**
 * Tile gas capacity (not tile count). Rooms = FULL, pipes = FULL/8, tanks > FULL.
 * Per-cell (not per-layer): enables tanks and capillaries as configuration.
 * Affects pressure (same gas, less room = harder push) and density (buoyancy comparison), not mass.
 * FULL = power of two (shift, not divide).
 */
class VolumeField(private val v: IntArray) {

    fun at(tile: Int): Int = v[tile]

    fun copy(): IntArray = v.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is VolumeField && v.contentEquals(other.v))

    override fun hashCode(): Int = v.contentHashCode()

    companion object {
        /** One tile's worth of room — what every cell held before this field existed. */
        const val FULL = 1024

        /**
         * Every cell a whole tile. The field with no pipes in it, and the one that reproduces the
         * behaviour of every tick simulated before volume was a quantity at all.
         */
        fun uniform(tileCount: Int): VolumeField = VolumeField(IntArray(tileCount) { FULL })

        /**
         * A field somebody has an opinion about.
         *
         * Zero is refused rather than clamped. A cell with no room is not a physical object — it is a
         * division by zero in [tilePressure] and an infinite pressure everywhere downstream of it —
         * and the honest way to say "nothing flows here" is an aperture of
         * [ApertureField.CLOSED], which the solver already understands. Letting a zero through would
         * turn a build-time mistake into a silent field of infinities.
         */
        fun of(volumes: IntArray): VolumeField {
            for (v in volumes) require(v > 0) { "a cell must have room in it; got $v" }
            return VolumeField(volumes.copyOf())
        }
    }
}
