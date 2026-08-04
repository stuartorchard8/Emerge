package org.emerge.demo.outofspace.world.fluid

/**
 * How much room a tile's gas actually has — a **capacity**, not a count of tiles.
 *
 * Every tile has held the same volume until now, and the sim has been quietly written that way:
 * [org.emerge.demo.outofspace.world.AirField.densityAt] says "since every tile is the same volume" in
 * as many words, and [tilePressure] divides by nothing because dividing by one is invisible. That
 * assumption is correct for a hull full of rooms and wrong for the next thing built on top of it.
 *
 * A pipe is a small volume, and it has to be, or it is not a pipe. Give a pipe cell a room's worth of
 * capacity and it holds a room's worth of gas: a pump would spend minutes filling one, the pressure
 * behind a closed valve would take as long to build as pressurising a deck, and the network would
 * behave like a second set of corridors that happens to be drawn thinner. The narrowness *is* the
 * behaviour. A tank is the same statement in reverse — a cell with more capacity than the tile it
 * occupies — and it comes free from the same field.
 *
 * ### Why per cell rather than per layer
 *
 * The obvious cheaper version is a constant on the pipe layer: rooms are one, pipes are an eighth.
 * That would work today and forecloses on both a tank and a capillary, which are the two things a
 * fluid network is *for*. A field costs one array and makes both of them configuration.
 *
 * ### Volume is not mass
 *
 * Worth stating because the solver's `tileGrams` argument is asked for three different things and
 * only one of them wants this. Advection moves a **fraction of the mass** across a face; velocity is
 * momentum over **mass**; the CFL bound and [applyPressureForce]'s cap are both about **mass**. None
 * of those care how big the box is. What volume changes is **pressure** — the same gas in less room
 * pushes harder — and **density**, which is the comparison [applyBuoyancy] makes against ambient.
 * Two call sites, not everywhere, and the discipline of keeping them apart is why this is a small
 * change rather than a sweep through the whole solver.
 *
 * [FULL] is a power of two for the reasons [ApertureField.OPEN] is one: scaling by a capacity is a
 * shift rather than a division, and an eighth of a tile is exactly an eighth rather than nearly it.
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
