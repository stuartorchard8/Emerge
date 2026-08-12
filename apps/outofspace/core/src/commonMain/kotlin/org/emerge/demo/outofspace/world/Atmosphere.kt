package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.apportion

/**
 * Air: flat LongArray (tiles × species), integers (exact conservation).
 * pressureAt = millimoles (not mass — lets heavy gas sink). densityAt = mass/volume.
 */
class AirField(private val grams: LongArray, private val joules: LongArray) {

    fun gramsOf(tile: Int, species: Species): Long = grams[tile * Species.COUNT + species.ordinal]

    /** Pressure in millimoles (particle count, not mass — heavy gases sink). */
    fun pressureAt(tile: Int): Long = millimolesOf(grams, tile)

    /**
     * Joules per kelvin held by the air in a tile — what it costs to warm this much gas by a degree.
     *
     * Here rather than at every call site because a tile's temperature depends on it, and computing
     * it from [copyGrams] would allocate the whole field once per tile queried.
     */
    fun heatCapacityAt(tile: Int): Long = gasCapacityAt(grams, tile)

    /**
     * How hot the air in a tile is, in kelvin. A tile with no air reads as ambient — see [gasKelvin]
     * for why that is the right placeholder for an absent quantity rather than a dodge.
     */
    fun kelvinAt(tile: Int): Int {
        val capacity = gasCapacityAt(grams, tile)
        return if (capacity <= 0L) Temperature.AMBIENT_KELVIN else (joules[tile] / capacity).toInt()
    }

    /** Total gas mass in a tile — its density, since every tile is the same volume. */
    fun densityAt(tile: Int): Long {
        var sum = 0L
        val base = tile * Species.COUNT
        for (s in Species.ALL) sum += grams[base + s.ordinal]
        return sum
    }

    /** The tile's air as a [Mixture], for the inspector. Allocates — not for the hot path. */
    /**
     * Everything this field holds at [tile] — **every** species, not a chosen subset.
     *
     * It used to walk [Species.GASES], which was invisibly wrong the moment water became a fluid the
     * solver moves. A field should report what is in it and let the caller decide what that means:
     * this same function serves the atmosphere, and those two do not agree on
     * which species are interesting, so any filter here is wrong for one of them.
     *
     * The consequence was not cosmetic. [org.emerge.demo.outofspace.world.Save] serialises the
     * atmosphere through this, so a world with water in it saved fine and **came back without any**,
     * taking the mass ledger with it. The HUD and the agent probe were quietly blind to it too, so
     * the injector would have looked like it was doing nothing.
     */
    fun mixtureAt(tile: Int): Mixture {
        val out = LongArray(Species.COUNT)
        val base = tile * Species.COUNT
        for (s in Species.ALL) out[s.ordinal] = grams[base + s.ordinal]
        return Mixture.ofGrams(out)
    }

    val totalGrams: Long get() {
        var sum = 0L
        for (g in grams) sum += g
        return sum
    }

    /** Total thermal energy of the atmosphere — the ledger quantity, the twin of [totalGrams]. */
    val totalJoules: Long get() {
        var sum = 0L
        for (j in joules) sum += j
        return sum
    }

    fun copyGrams(): LongArray = grams.copyOf()

    fun copyJoules(): LongArray = joules.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is AirField && grams.contentEquals(other.grams) && joules.contentEquals(other.joules))

    override fun hashCode(): Int = 31 * grams.contentHashCode() + joules.contentHashCode()

    companion object {
        /**
         * A tile at 1 atm: one kilogram of **real dry air**, stated to the gram.
         *
         * Measured composition by mass, which is what this field holds — the volume percentages
         * everyone quotes are a different set of numbers, and converting them is what the molar
         * masses are for: N₂ 75.5%, O₂ 23.1%, argon 1.29%, CO₂ 0.064%.
         *
         * ⚠️ **CO₂ is 1 g here and that is not a typo.** It used to be 13 g, which is argon's share
         * wearing the wrong name because there was no noble gas species to give it to — an
         * atmosphere twenty times too rich in carbon dioxide. Now that [Species.Argon] exists, both
         * are stated at their real values.
         *
         * ⚠️ **One gram is below the diffusion stranding floor**, which is five (`SLOTS/FACE_SHARE`),
         * so ambient CO₂ **cannot move** at the current mass scale — it sits in whatever tile it
         * starts in. That is not a defect introduced here; it is the existing quantisation, finally
         * visible because the value is finally honest. It is the clearest single argument for the
         * mass-unit rebaseline: a trace gas is not representable as a *moving* thing until a unit is
         * smaller than a gram. See `NUMERIC_LIMITS.md` §6.2.
         */
        val AMBIENT_AIR: Mixture = Mixture.of(
            Species.Nitrogen to 755L,
            Species.Oxygen to 231L,
            Species.Argon to 13L,
            Species.CarbonDioxide to 1L,
        )

        /**
         * Air at room temperature.
         *
         * The energy is **derived from the grams** rather than defaulted to zero, and that default is
         * what makes this whole design safe. Heat lives inside [AirField] precisely because it must
         * not be possible to replace a world's air and leave its temperature behind: on a
         * `data class`, `copy(air = …)` does not re-evaluate other properties' defaults, so a
         * parallel `airJoules` array would silently keep describing gas that is no longer there. Ten
         * kilograms of oxygen inheriting one kilogram's worth of energy reads as 57K and stops
         * behaving like a gas at all — which is exactly what happened when it was tried that way.
         *
         * One value, so the two cannot disagree.
         */
        fun of(grams: LongArray): AirField =
            AirField(grams.copyOf(), ambientGasJoules(grams.size / Species.COUNT, grams))

        /** Air at a temperature somebody has an opinion about. Both arrays are copied. */
        fun of(grams: LongArray, joules: LongArray): AirField =
            AirField(grams.copyOf(), joules.copyOf())

        /** Every enclosed tile filled with [AMBIENT_AIR]; vacuum left empty. */
        fun ambient(grid: Grid, structure: StructureMap): AirField {
            val grams = LongArray(grid.size * Species.COUNT)
            for (tile in 0 until grid.size) {
                if (!structure.isContained(tile) || structure.isImpermeable(tile)) continue
                val base = tile * Species.COUNT
                for (s in Species.ALL) grams[base + s.ordinal] = AMBIENT_AIR[s]
            }
            return of(grams)
        }
    }
}

/**
 * Displace air from [area] to permeable exits. All-or-nothing (refuses if any air can't reach space).
 * Air splits by inverse-distance through area (far tiles exit near door). Runs during edit pass (permeable param, not StructureMap).
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
        for (s in Species.ALL) total += grams[base + s.ordinal]
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

        for (s in Species.ALL) {
            val share = apportion(weights, grams[base + s.ordinal])
            for (e in exits.indices) moved[e * Species.COUNT + s.ordinal] += share[e]
        }
    }

    for (slot in order.indices) {
        val base = order[slot] * Species.COUNT
        for (s in Species.ALL) grams[base + s.ordinal] = 0L
    }
    for (e in exits.indices) {
        val base = exits[e] * Species.COUNT
        for (s in Species.ALL) grams[base + s.ordinal] += moved[e * Species.COUNT + s.ordinal]
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

