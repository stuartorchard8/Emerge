package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.apportion

/**
 * Air: flat LongArray (tiles × species), integers (exact conservation).
 * pressureAt = millimoles (not mass — lets heavy gas sink). densityAt = mass/volume.
 */
class Atmosphere(private val masses: MassArray, private val energies: EnergyArray) {

    fun massOf(tile: TileIndex, species: Species): Long = masses[MassIndex(tile, species)]

    /** Pressure in millimoles (particle count, not mass — heavy gases sink). */
    fun pressureAt(tile: TileIndex): Long = millimolesOf(masses, tile)

    /**
     * Joules per kelvin held by the air in a tile — what it costs to warm this much gas by a degree.
     *
     * Here rather than at every call site because a tile's temperature depends on it, and computing
     * it from [copyMass] would allocate the whole field once per tile queried.
     */
    fun heatCapacityAt(tile: TileIndex): Long = heatCapacityAt(masses, tile)

    /**
     * How hot the air in a tile is, in kelvin. A tile with no air reads as ambient — see [gasKelvin]
     * for why that is the right placeholder for an absent quantity rather than a dodge.
     */
    fun kelvinAt(tile: TileIndex): Int {
        val capacity = heatCapacityAt(masses, tile)
        return if (capacity <= 0L) Temperature.AMBIENT_KELVIN else (energies[tile] / capacity).toInt()
    }

    /** Total gas mass in a tile — its density, since every tile is the same volume. */
    fun densityAt(tile: TileIndex): Long {
        var sum = 0L
        for (s in Species.ALL) sum += masses[MassIndex(tile, s)]
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
    fun mixtureAt(tile: TileIndex): Mixture {
        val out = LongArray(Species.COUNT)
        for (s in Species.ALL) out[s.ordinal] = masses[MassIndex(tile, s)]
        return Mixture.of(out, energies[tile])
    }

    val totalMass: Long get() {
        var sum = 0L
        masses.forEach { sum += it }
        return sum
    }

    /** Total thermal energy of the atmosphere — the ledger quantity, the twin of [totalMass]. */
    val totalEnergy: Long get() {
        var sum = 0L
        energies.forEach { sum += it }
        return sum
    }

    fun copyMass(): MassArray = masses.copyOf()

    fun copyEnergy(): EnergyArray = energies.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is Atmosphere && masses.contentEquals(other.masses) && energies.contentEquals(other.energies))

    override fun hashCode(): Int = 31 * masses.contentHashCode() + energies.contentHashCode()

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
         * ⚠️ Stated in [Budget.GRAM]s and not in bare integers: this is a **mass**, a real kilogram
         * of air in a tile, and not the composition it also gets used as. It reads like parts per
         * thousand because at one gram per unit the two coincide — which is exactly why it survived
         * the step 2 audit unnoticed and turned a tile of air into a milligram at step 8.
         *
         * ⚠️ **One gram is below the diffusion stranding floor**, which is five (`SLOTS/FACE_SHARE`),
         * so ambient CO₂ **cannot move** at the current mass scale — it sits in whatever tile it
         * starts in. That is not a defect introduced here; it is the existing quantisation, finally
         * visible because the value is finally honest. It is the clearest single argument for the
         * mass-unit rebaseline: a trace gas is not representable as a *moving* thing until a unit is
         * smaller than a gram. See `NUMERIC_LIMITS.md` §6.2.
         *
         * TODO: use a recipie, not an actual mixture
         */
        val AMBIENT_AIR: Mixture = Mixture.of(
            Species.Nitrogen to 755L * Budget.GRAM,
            Species.Oxygen to 231L * Budget.GRAM,
            Species.Argon to 13L * Budget.GRAM,
            Species.CarbonDioxide to 1L * Budget.GRAM,
            energy = 0,
        )

        /**
         * Air at room temperature.
         *
         * The energy is **derived from the mass** rather than defaulted to zero, and that default is
         * what makes this whole design safe. Heat lives inside [Atmosphere] precisely because it must
         * not be possible to replace a world's air and leave its temperature behind: on a
         * `data class`, `copy(air = …)` does not re-evaluate other properties' defaults, so a
         * parallel `airEnergy` array would silently keep describing gas that is no longer there. Ten
         * kilograms of oxygen inheriting one kilogram's worth of energy reads as 57K and stops
         * behaving like a gas at all — which is exactly what happened when it was tried that way.
         *
         * One value, so the two cannot disagree.
         */
        fun of(mass: MassArray): Atmosphere =
            Atmosphere(mass.copyOf(), ambientGasEnergy(mass.size / Species.COUNT, mass))

        /** Air at a temperature somebody has an opinion about. Both arrays are copied. */
        fun of(mass: MassArray, energy: EnergyArray): Atmosphere =
            Atmosphere(mass.copyOf(), energy.copyOf())

        /** Every enclosed tile filled with [AMBIENT_AIR]; vacuum left empty. */
        fun ambient(grid: Grid, structure: StructureMap): Atmosphere {
            val mass = MassArray(grid.size)
            for (tile in grid.tiles) {
                if (!structure.isContained(tile) || structure.isImpermeable(tile)) continue
                for (s in Species.ALL) mass[MassIndex(tile, s)] = AMBIENT_AIR[s]
            }
            return of(mass)
        }
    }
}

/**
 * Displace air from [area] to permeable exits. All-or-nothing (refuses if any air can't reach space).
 * Air splits by inverse-distance through area (far tiles exit near door). Runs during edit pass (permeable param, not StructureMap).
 */
fun tryDisplaceAir(
    grid: Grid,
    masses: MassArray,
    energies: EnergyArray,
    area: Collection<TileIndex>,
    permeable: (TileIndex) -> Boolean,
): Boolean {
    val order = area.toList()
    val slotOf = HashMap<TileIndex, Int>(order.size * 2)
    for (i in order.indices) slotOf[order[i]] = i

    // ── The ways out: permeable tiles touching the area, in a fixed order ──
    val exits = ArrayList<TileIndex>()
    val exitSlot = HashMap<TileIndex, Int>()
    for (tile in order) {
        for (dir in Direction.ALL) {
            val other = grid.neighbour(tile, dir)
            if (other == TileIndex.NONE || other in slotOf || other in exitSlot || !permeable(other)) continue
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
    val movedMass = MassArray(exits.size)
    val movedEnergy = EnergyArray(exits.size)
    val weights = LongArray(exits.size)
    for (slot in order.indices) {
        val tile = order[slot]
        var totalMass = 0L
        for (s in Species.ALL) totalMass += masses[MassIndex(tile, s)]
        if (totalMass <= 0L) continue

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
            val share = apportion(weights, masses[MassIndex(tile, s)])
            for (e in exits.indices) movedMass[MassIndex(TileIndex(e), s)] += share[e]
        }
        val share = apportion(weights, energies[tile])
        for (e in exits.indices) movedEnergy[TileIndex(e)] += share[e]
    }

    for (slot in order) {
        energies[slot] = 0L
        for (s in Species.ALL) masses[MassIndex(slot, s)] = 0L
    }
    for (i in exits.indices) {
        val exitTile = exits[i]
        val source = TileIndex(i)
        energies[exitTile] += movedEnergy[source]
        for (s in Species.ALL) masses[MassIndex(exitTile, s)] += movedMass[MassIndex(source, s)]
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

