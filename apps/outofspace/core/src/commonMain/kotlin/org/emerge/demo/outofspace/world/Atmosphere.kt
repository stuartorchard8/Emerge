package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.apportion
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * The air, tile by tile: grams of each gas species in every enclosed tile.
 *
 * Stored as one flat `LongArray` of `tiles × species` rather than a `Mixture` per tile. A mixture per
 * tile would allocate a thousand small objects every tick, and this is the field that will be touched
 * most often once life support and combustion exist.
 *
 * Grams again, and integers again, for the reasons [Mixture] already gives. Pressure here is simply
 * the total mass in a tile: every tile is the same volume, so mass *is* density, and gas flows from
 * dense to sparse. Temperature is deliberately not in it yet — coupling `P ∝ mT` is what gives
 * convection, and it deserves its own pass rather than being smuggled in with the plumbing.
 */
class AirField(private val grams: LongArray) {

    fun gramsOf(tile: Int, species: Species): Long = grams[tile * Species.COUNT + species.ordinal]

    /** Total gas mass in a tile. With uniform tile volume this is the pressure. */
    fun pressureAt(tile: Int): Long {
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

        /**
         * How much of a pressure difference moves in a second. Gas equalises quickly — a door opening
         * is not a slow event — but the flux is capped at half the gap so it can never overshoot and
         * oscillate, the same guard conduction needs.
         */
        const val FLOW_PER_SECOND = 6L

        /** How fast heavy gas trades places with light gas below it, per second. */
        const val STRATIFY_PER_SECOND = 3L

        fun of(grams: LongArray): AirField = AirField(grams.copyOf())

        /** Every enclosed tile filled with [AMBIENT_AIR]; vacuum left empty. */
        fun ambient(grid: Grid, structure: StructureMap): AirField {
            val grams = LongArray(grid.size * Species.COUNT)
            for (tile in 0 until grid.size) {
                if (!structure.isInterior(tile)) continue
                val base = tile * Species.COUNT
                for (s in Species.GASES) grams[base + s.ordinal] = AMBIENT_AIR[s]
            }
            return AirField(grams)
        }
    }
}

/**
 * Advances the atmosphere one tick: flow, then stratification, then venting.
 *
 * Flow is computed from the old pressures into a delta buffer and applied afterwards, so — as with
 * heat — the result cannot depend on the order tiles are visited. Gas moved between tiles is a
 * *proportional sample* of the source ([Mixture.take] over the same [apportion]), so a draught
 * carries the room's actual mix rather than skimming one gas off the top.
 *
 * A residual gradient of one gram between neighbours is the resting state, not a bug: a difference
 * of one cannot be split in half without overshooting, so integer pressure settles into a ±1
 * staircase rather than a perfectly flat field. At a kilogram per tile that is a tenth of a percent.
 *
 * @return the new field and the grams vented to space, which is the only place air legitimately goes.
 */
fun stepAir(
    grid: Grid,
    structure: StructureMap,
    air: AirField,
    gravity: Frac2,
    ticksPerSecond: Int,
): Pair<AirField, Long> {
    val grams = air.copyGrams()
    val pressure = LongArray(grid.size) { tile ->
        var sum = 0L
        val base = tile * Species.COUNT
        for (s in Species.GASES) sum += grams[base + s.ordinal]
        sum
    }

    // ── Flow: dense to sparse, each edge once ──
    val moves = ArrayList<LongArray>()   // (from, to, amount) collected, then applied
    for (tile in 0 until grid.size) {
        if (!structure.isInterior(tile)) continue
        for (dir in FLOW_DIRS) {
            val other = grid.neighbour(tile, dir)
            if (other < 0 || !structure.isInterior(other)) continue
            val gap = pressure[tile] - pressure[other]
            if (gap == 0L) continue
            val from = if (gap > 0) tile else other
            val magnitude = if (gap > 0) gap else -gap
            var flux = AirField.FLOW_PER_SECOND * magnitude / ticksPerSecond
            // Integer division floors, and this rate is a fraction per tick, so without a minimum of
            // one gram every gradient below ten grams would freeze exactly where it was — a room
            // would visibly stop equalising with a permanent staircase across it. Heat has no such
            // problem only because its coefficient happens to exceed the tick rate.
            if (flux == 0L && magnitude >= 2L) flux = 1L
            flux = minOf(flux, magnitude / 2)          // never past equal
            flux = minOf(flux, pressure[from])          // never more than is there
            if (flux <= 0L) continue
            moves.add(longArrayOf(from.toLong(), (if (gap > 0) other else tile).toLong(), flux))
        }
    }
    for (move in moves) {
        val from = move[0].toInt()
        val to = move[1].toInt()
        transferGas(grams, from, to, move[2])
    }

    stratifyColumns(grid, structure, grams, gravity, ticksPerSecond)

    // ── Venting: anything not enclosed has no air, and what it had is gone ──
    var vented = 0L
    for (tile in 0 until grid.size) {
        if (structure.isInterior(tile)) continue
        val base = tile * Species.COUNT
        for (s in Species.GASES) {
            vented += grams[base + s.ordinal]
            grams[base + s.ordinal] = 0L
        }
    }
    return AirField.of(grams) to vented
}

/**
 * Lets heavy gas sink and light gas rise, one vertical pair at a time.
 *
 * **This is the one function permitted to assume gravity is axis-aligned** (see the plan's §3). It
 * walks vertical neighbours directly, which is only meaningful when "down" is a grid axis. When
 * acceleration-derived gravity arrives, this is the single thing that gets replaced by a general
 * flux along an arbitrary vector — everything else already takes gravity as a parameter and will not
 * notice.
 *
 * Stratification is a **swap**: an equal mass of the heavier gas goes down as the lighter goes up. It
 * therefore moves composition around without moving pressure, which is what stops it fighting the
 * flow pass, and it conserves each species exactly.
 */
fun stratifyColumns(
    grid: Grid,
    structure: StructureMap,
    grams: LongArray,
    gravity: Frac2,
    ticksPerSecond: Int,
) {
    // Which way is down, and is it even a grid direction? A diagonal or zero gravity means no
    // stratification rather than a wrong one.
    val gx = gravity.x.raw
    val gy = gravity.y.raw
    val down: Direction = when {
        gx == 0L && gy > 0L -> Direction.Down
        gx == 0L && gy < 0L -> Direction.Up
        gy == 0L && gx > 0L -> Direction.Right
        gy == 0L && gx < 0L -> Direction.Left
        else -> return
    }

    // Heaviest first, so each pair is considered once in the order (heavy, lighter).
    val byWeight = Species.GASES.sortedByDescending { it.molarMass }

    for (tile in 0 until grid.size) {
        if (!structure.isInterior(tile)) continue
        val below = grid.neighbour(tile, down)
        if (below < 0 || !structure.isInterior(below)) continue

        val upper = tile * Species.COUNT
        val lower = below * Species.COUNT
        for (h in byWeight.indices) {
            for (l in h + 1 until byWeight.size) {
                val heavy = byWeight[h]
                val light = byWeight[l]
                // Swap what is "the wrong way up": heavy gas above, light gas below.
                val available = minOf(grams[upper + heavy.ordinal], grams[lower + light.ordinal])
                val swap = AirField.STRATIFY_PER_SECOND * available / ticksPerSecond
                if (swap <= 0L) continue
                grams[upper + heavy.ordinal] -= swap
                grams[lower + heavy.ordinal] += swap
                grams[lower + light.ordinal] -= swap
                grams[upper + light.ordinal] += swap
            }
        }
    }
}

/** Moves [amount] grams from one tile to another as a proportional sample of the source's mix. */
private fun transferGas(grams: LongArray, from: Int, to: Int, amount: Long) {
    val fromBase = from * Species.COUNT
    val toBase = to * Species.COUNT
    val weights = LongArray(Species.COUNT)
    for (s in Species.GASES) weights[s.ordinal] = grams[fromBase + s.ordinal]
    val share = apportion(weights, amount)
    for (s in Species.GASES) {
        val moved = minOf(share[s.ordinal], grams[fromBase + s.ordinal])
        grams[fromBase + s.ordinal] -= moved
        grams[toBase + s.ordinal] += moved
    }
}

/** Right and down: visiting every tile with these two covers each edge exactly once. */
private val FLOW_DIRS = listOf(Direction.Right, Direction.Down)
