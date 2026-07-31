package org.emerge.demo.outofspace.world

/**
 * Thermal energy, per tile, in joules.
 *
 * **Energy is the stored quantity and temperature is derived** — `T = joules / capacity`. Storing
 * temperature directly and averaging it between neighbours would quietly create and destroy energy
 * whenever two tiles of different heat capacity met, which is precisely the sort of leak that mass
 * conservation already taught this project to design out rather than debug.
 *
 * Integers throughout, for the same reasons as [org.emerge.demo.outofspace.chem.Mixture]: two
 * machines must agree, and "where did the energy go" must be answerable exactly.
 */
class HeatField(private val joules: LongArray) {

    val size: Int get() = joules.size

    fun joulesAt(index: Int): Long = joules[index]

    /** Temperature in kelvin. Space is [SPACE_KELVIN]; a tile with no capacity is space. */
    fun kelvinAt(index: Int, capacity: Long): Int =
        if (capacity <= 0L) SPACE_KELVIN else (joules[index] / capacity).toInt()

    fun copyJoules(): LongArray = joules.copyOf()

    val totalJoules: Long get() {
        var sum = 0L
        for (j in joules) sum += j
        return sum
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is HeatField && joules.contentEquals(other.joules))

    override fun hashCode(): Int = joules.contentHashCode()

    companion object {
        /** Deep space, near enough. Everything radiates toward this and nothing gets colder. */
        const val SPACE_KELVIN = 3

        /** Comfortable room temperature — what a freshly enclosed space starts at. */
        const val AMBIENT_KELVIN = 293

        /** Heat capacity of an empty interior tile (its air and fittings), in joules per kelvin. */
        const val INTERIOR_CAPACITY = 2_000L

        /** A hull tile is metal: it holds much more heat, and is the only thing touching space. */
        const val HULL_CAPACITY = 8_000L

        /** A machine adds its own thermal mass on top of the tile it sits in. */
        const val MACHINE_CAPACITY = 5_000L

        /**
         * How fast heat crosses between two adjacent enclosed tiles, in joules per kelvin of
         * difference per second. Tuned so a smelter warms its neighbourhood over tens of seconds —
         * fast enough to watch, slow enough that where you put things matters.
         */
        const val CONDUCTANCE = 220L

        /**
         * How fast a hull tile sheds heat to the space beside it — deliberately **small**.
         *
         * Vacuum is an excellent insulator: a spacecraft's thermal problem is rejecting heat, not
         * keeping it, and every real one carries radiators for the purpose. Tuned so a single running
         * smelter warms the vessel rather than being swamped by the walls. At the first value tried
         * (90) the hull shed a megajoule a second against the smelter's twenty kilojoules, so heat
         * could never accumulate and the only possible failure was freezing — which is neither true
         * nor an interesting thing to build against.
         */
        const val RADIANCE = 1L

        fun ambient(grid: Grid, structure: StructureMap, occupancy: Occupancy): HeatField {
            val joules = LongArray(grid.size)
            for (i in 0 until grid.size) {
                val capacity = capacityOf(structure, occupancy, i)
                joules[i] = if (capacity <= 0L) 0L else AMBIENT_KELVIN * capacity
            }
            return HeatField(joules)
        }

        fun of(joules: LongArray): HeatField = HeatField(joules.copyOf())

        /**
         * Joules per kelvin for a tile: its structure plus whatever machine covers it.
         *
         * **Covers**, not "is stored at" — a five-tile smelter is twenty-five tiles of thermal mass,
         * because it is twenty-five tiles of furnace. Charging the capacity only to its centre would
         * make a big machine thermally identical to a small one, and the reason a furnace should be
         * hard to cool is precisely that there is a lot of it.
         */
        fun capacityOf(structure: StructureMap, occupancy: Occupancy, index: Int): Long = when {
            structure.isVacuum(index) -> 0L
            structure.isHull(index) -> HULL_CAPACITY
            else -> INTERIOR_CAPACITY + if (!occupancy.isFree(index)) MACHINE_CAPACITY else 0L
        }
    }
}

/**
 * Advances heat one tick: conduction between neighbours, then radiation from the skin to space.
 *
 * Conduction is computed from the **old** temperatures into a delta buffer and applied afterwards
 * (Jacobi rather than Gauss-Seidel), so the result cannot depend on the order tiles are visited.
 * Energy moves as a matched pair — subtracted from one tile, added to the other — so conduction
 * conserves by construction, exactly as mass transfer does.
 *
 * Each flux is also capped at the amount that would *equalise* the two tiles. Without that, a large
 * temperature difference plus a coarse timestep sends more energy than exists in the gap and the
 * field oscillates instead of settling.
 *
 * @return the joules radiated away to space, which is the only place energy legitimately leaves.
 */
fun stepHeat(
    grid: Grid,
    structure: StructureMap,
    occupancy: Occupancy,
    heat: HeatField,
    ticksPerSecond: Int,
): Pair<HeatField, Long> {
    val joules = heat.copyJoules()
    val delta = LongArray(joules.size)
    val capacity = LongArray(joules.size) { HeatField.capacityOf(structure, occupancy, it) }
    val kelvin = IntArray(joules.size) { if (capacity[it] <= 0L) HeatField.SPACE_KELVIN else (joules[it] / capacity[it]).toInt() }

    // ── Conduction, each unordered pair once (right and down covers every edge) ──
    for (index in joules.indices) {
        if (!structure.isEnclosed(index)) continue
        for (dir in CONDUCTION_DIRS) {
            val other = grid.neighbour(index, dir)
            if (other < 0 || !structure.isEnclosed(other)) continue
            val dT = kelvin[index] - kelvin[other]
            if (dT == 0) continue

            val hot = if (dT > 0) index else other
            val cold = if (dT > 0) other else index
            val gap = if (dT > 0) dT else -dT

            val wanted = HeatField.CONDUCTANCE * gap / ticksPerSecond
            // The most that can move before the two are equal: dT * Ca*Cb / (Ca+Cb).
            val ceiling = gap.toLong() * capacity[hot] * capacity[cold] / (capacity[hot] + capacity[cold])
            val flux = minOf(wanted, ceiling)
            if (flux <= 0L) continue

            delta[hot] -= flux
            delta[cold] += flux
        }
    }

    // ── Radiation: hull tiles touching space lose heat to it, permanently ──
    var radiated = 0L
    for (index in joules.indices) {
        if (!structure.isHull(index)) continue
        var exposure = 0
        for (dir in Direction.ALL) {
            val other = grid.neighbour(index, dir)
            if (other < 0 || structure.isVacuum(other)) exposure++
        }
        if (exposure == 0) continue
        val gap = kelvin[index] - HeatField.SPACE_KELVIN
        if (gap <= 0) continue
        val wanted = HeatField.RADIANCE * exposure * gap / ticksPerSecond
        // Never radiate past the temperature of space.
        val ceiling = gap.toLong() * capacity[index]
        val flux = minOf(wanted, ceiling)
        if (flux <= 0L) continue
        delta[index] -= flux
        radiated += flux
    }

    for (i in joules.indices) {
        joules[i] = (joules[i] + delta[i]).coerceAtLeast(0L)
        // A tile that is no longer enclosed holds nothing — breach a hull and the room's heat goes
        // with its air. That energy is *radiated*, not deleted: silently zeroing it would be exactly
        // the kind of leak the mass model was built to make impossible.
        if (!structure.isEnclosed(i)) {
            radiated += joules[i]
            joules[i] = 0L
        }
    }
    return HeatField.of(joules) to radiated
}

/**
 * How much heat a machine dumps into its tile per gram it works on.
 *
 * Tying heat to *work done* rather than to a per-second rate means it needs no clock and no carry of
 * its own: the material flow is already modelled exactly, so the heat that flow implies is exact
 * too. A throttled machine warms its surroundings proportionally less, for free.
 */
fun heatPerGram(machine: Machine?): Long = when (machine) {
    is Smelter -> 40L      // a furnace, and the main reason a vessel needs to shed heat at all
    is Fabricator -> 12L
    is Processor -> 8L     // crushing and grinding
    is Miner -> 4L
    else -> 0L
}

/** Right and down: visiting every tile with these two covers each edge exactly once. */
private val CONDUCTION_DIRS = listOf(Direction.Right, Direction.Down)
