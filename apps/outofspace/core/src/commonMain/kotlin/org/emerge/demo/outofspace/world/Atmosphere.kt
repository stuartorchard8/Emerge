package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.apportion
import org.emerge.demo.outofspace.world.fluid.millimolesOf

/**
 * The air, tile by tile: grams of each gas species in every enclosed tile.
 *
 * Stored as one flat `LongArray` of `tiles × species` rather than a `Mixture` per tile. A mixture per
 * tile would allocate a thousand small objects every tick, and this is the field that will be touched
 * most often once life support and combustion exist.
 *
 * Grams again, and integers again, for the reasons [Mixture] already gives. Mass is what is stored;
 * [pressureAt] and [densityAt] are the two different things derived from it, and keeping them
 * distinct is what lets a heavy gas settle without a rule telling it to.
 */
class AirField(private val grams: LongArray) {

    fun gramsOf(tile: Int, species: Species): Long = grams[tile * Species.COUNT + species.ordinal]

    /**
     * Pressure in a tile, in the millimoles [tilePressure] counts.
     *
     * **Not mass.** Pressure goes as the number of particles and density as their weight, and this
     * used to return the mass on the grounds that every tile is the same volume — right about
     * density, wrong about pressure, and the difference is exactly what lets a heavy gas sink. See
     * [tilePressure] for why conflating the two is what forced the old `stratifyColumns` to exist.
     */
    fun pressureAt(tile: Int): Long = millimolesOf(grams, tile)

    /** Total gas mass in a tile — its density, since every tile is the same volume. */
    fun densityAt(tile: Int): Long {
        var sum = 0L
        val base = tile * Species.COUNT
        for (s in Species.GASES) sum += grams[base + s.ordinal]
        return sum
    }

    /** The tile's air as a [Mixture], for the inspector. Allocates — not for the hot path. */
    fun mixtureAt(tile: Int): Mixture {
        val out = LongArray(Species.COUNT)
        val base = tile * Species.COUNT
        for (s in Species.GASES) out[s.ordinal] = grams[base + s.ordinal]
        return Mixture.ofGrams(out)
    }

    val totalGrams: Long get() {
        var sum = 0L
        for (g in grams) sum += g
        return sum
    }

    fun copyGrams(): LongArray = grams.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is AirField && grams.contentEquals(other.grams))

    override fun hashCode(): Int = grams.contentHashCode()

    companion object {
        /**
         * What a sealed tile holds at one atmosphere: a kilogram of ordinary air, roughly Earth's
         * mix by mass. The numbers matter less than their ratios, which are what the inspector shows
         * and what life support will have to hold steady.
         */
        val AMBIENT_AIR: Mixture = Mixture.of(
            Species.Nitrogen to 755L,
            Species.Oxygen to 232L,
            Species.CarbonDioxide to 13L,
        )

        fun of(grams: LongArray): AirField = AirField(grams.copyOf())

        /** Every enclosed tile filled with [AMBIENT_AIR]; vacuum left empty. */
        fun ambient(grid: Grid, structure: StructureMap): AirField {
            val grams = LongArray(grid.size * Species.COUNT)
            for (tile in 0 until grid.size) {
                if (!structure.isContained(tile) || structure.isImpermeable(tile)) continue
                val base = tile * Species.COUNT
                for (s in Species.GASES) grams[base + s.ordinal] = AMBIENT_AIR[s]
            }
            return AirField(grams)
        }
    }
}

/**
 * Tries to shove the air out of an area that is about to stop being air, and reports whether it
 * could.
 *
 * A solid tile is not part of the atmosphere: the fluid pass shuts every face of one, so air
 * left inside one is neither flowing nor vented — it is simply frozen, invisible, and waiting to
 * reappear the moment the thing on top of it is removed. Building over a room has to *move* that air
 * rather than swallow it, and it has to move it without deleting a gram, because the vessel's air
 * ledger is a conservation invariant and the player's own edits are not exempt from it.
 *
 * **All or nothing.** If any of [area] holds air that cannot reach open space, nothing is moved and
 * this returns `false` — the caller's job is then to refuse the build. That is the honest rule: the
 * alternative is either destroying the air or leaving it stranded under the new machine, and both of
 * those are the bug this exists to prevent. An area holding no air at all succeeds trivially, so
 * building in vacuum or in an evacuated room is never blocked.
 *
 * Where the air goes is decided by **distance through the area to each way out**. The exits are the
 * permeable tiles touching [area]; a breadth-first walk inward from them gives every tile of the
 * area its distance to each one, and a tile's air is split between the exits in inverse proportion
 * to those distances. So air at the far end of a long machine leaves by the near door rather than
 * being teleported evenly to both ends, and a tile with only one way out sends everything there.
 *
 * The walk is confined to [area] on purpose: distance is how far the air has to travel *through the
 * space being taken away*, which is what decides which way it gets pushed. Once it is out it is the
 * flow pass's problem, and the flow pass runs on the same array immediately afterwards.
 *
 * [permeable] is asked about tiles rather than a [StructureMap] being passed, because this runs
 * *during* the edit pass, before the structure for the tick has been derived.
 */
fun tryDisplaceAir(
    grid: Grid,
    grams: LongArray,
    area: Collection<Int>,
    permeable: (Int) -> Boolean,
): Boolean {
    val order = area.toList()
    val slotOf = HashMap<Int, Int>(order.size * 2)
    for (i in order.indices) slotOf[order[i]] = i

    // ── The ways out: permeable tiles touching the area, in a fixed order ──
    val exits = ArrayList<Int>()
    val exitSlot = HashMap<Int, Int>()
    for (tile in order) {
        for (dir in Direction.ALL) {
            val other = grid.neighbour(tile, dir)
            if (other < 0 || other in slotOf || other in exitSlot || !permeable(other)) continue
            exitSlot[other] = exits.size
            exits.add(other)
        }
    }
    if (exits.isEmpty()) return false

    // ── Distance from every exit to every tile of the area, walking only through the area ──
    val distance = Array(exits.size) { IntArray(order.size) { UNREACHABLE } }
    val queue = ArrayDeque<Int>()
    for (e in exits.indices) {
        val d = distance[e]
        queue.clear()
        for (dir in Direction.ALL) {
            val first = grid.neighbour(exits[e], dir)
            val slot = slotOf[first] ?: continue
            if (d[slot] > 1) { d[slot] = 1; queue.addLast(slot) }
        }
        while (queue.isNotEmpty()) {
            val slot = queue.removeFirst()
            for (dir in Direction.ALL) {
                val next = grid.neighbour(order[slot], dir)
                val nextSlot = slotOf[next] ?: continue
                if (d[nextSlot] > d[slot] + 1) { d[nextSlot] = d[slot] + 1; queue.addLast(nextSlot) }
            }
        }
    }

    // ── Work out every move before making any, so a refusal leaves the field untouched ──
    val moved = LongArray(exits.size * Species.COUNT)
    val weights = LongArray(exits.size)
    for (slot in order.indices) {
        val base = order[slot] * Species.COUNT
        var total = 0L
        for (s in Species.GASES) total += grams[base + s.ordinal]
        if (total <= 0L) continue

        var reachable = false
        for (e in exits.indices) {
            val d = distance[e][slot]
            // Inverse distance, scaled so the near exit outweighs the far one without a fraction.
            weights[e] = if (d == UNREACHABLE) 0L else DISPLACE_WEIGHT / d
            if (weights[e] > 0L) reachable = true
        }
        // Air with nowhere to go. Refuse, rather than delete it or bury it.
        if (!reachable) return false

        for (s in Species.GASES) {
            val share = apportion(weights, grams[base + s.ordinal])
            for (e in exits.indices) moved[e * Species.COUNT + s.ordinal] += share[e]
        }
    }

    for (slot in order.indices) {
        val base = order[slot] * Species.COUNT
        for (s in Species.GASES) grams[base + s.ordinal] = 0L
    }
    for (e in exits.indices) {
        val base = exits[e] * Species.COUNT
        for (s in Species.GASES) grams[base + s.ordinal] += moved[e * Species.COUNT + s.ordinal]
    }
    return true
}

/** Stands in for "no path from this exit to this tile" — larger than any real distance. */
private const val UNREACHABLE = Int.MAX_VALUE

/**
 * The numerator of the inverse-distance weighting. Big enough that the *ratios* between distances
 * survive the integer division — at a distance of a hundred the weight is still four figures — and
 * small enough that summing one per exit cannot overflow.
 */
private const val DISPLACE_WEIGHT = 1L shl 20

