package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import kotlin.random.Random

/**
 * The rocks a world starts with: a scattering of them out in open space, for the player to go and
 * find.
 *
 * This is increment H4, and it is deliberately the whole of it. What the extractor needed was not a
 * capture mechanism but *something to capture* — `F6` was a debug key standing in for a world with
 * ore in it, and a key is a poor substitute for a place. The vessel flies to a rock and lines its
 * plate up under it; the rock does nothing but be there.
 *
 * So: no drift, no despawn, no replenishment. Rocks are placed once, when the world is made, and
 * they keep their positions until something in the game moves them. Rates and lifetimes are a later
 * question and are better asked of a world that already has rocks in it.
 *
 * **They are baseline mass, not captured mass.** A rock that was here when the world started did not
 * arrive from outside it, so it belongs in [VesselState.baselineRockGrams] — which is what happens
 * for free by handing them to the constructor rather than dropping them in through an edit. Both
 * ledgers read zero on tick one. See [VesselState.capturedGrams].
 */
object RockField {

    /**
     * How many rocks a starting world gets.
     *
     * Enough that one is always somewhere near, few enough that the map is not gravel. Fewer will
     * appear on a grid with less open space than this asks for — see [scatter], which gives up
     * rather than packing them in.
     */
    const val DEFAULT_COUNT: Int = 12

    /**
     * The seed the starting world uses.
     *
     * Fixed, and a value rather than a clock reading, because every determinism check in the suite
     * compares two independently built starter vessels and a world that differed between them would
     * fail the check for the one reason that is not a bug. A varying world is something a host asks
     * for by passing a seed, which is the right way round.
     */
    const val DEFAULT_SEED: Int = 0x0A5E

    /** Rocks come in a few sizes: 3, 5 and 7 tiles across. A field of identical discs reads as tiling. */
    private val RADII = intArrayOf(1, 2, 3)

    /**
     * Clear space to leave around the vessel and around each rock, in tiles.
     *
     * Around the vessel because a rock overlapping the hull at tick zero starts inside the thing it
     * is meant to be flown at, and the collision solver's job is to stop that happening rather than
     * to dig one out. Around each other for the same reason, one step down.
     */
    private const val MARGIN: Int = 2

    /**
     * A field of rocks in the open space around [machines], laid out by [seed].
     *
     * Rejection sampling with a bounded number of attempts: it proposes a rock, and drops it if it
     * would overlap the vessel or one already placed. A grid with no room left therefore returns
     * **fewer rocks than asked for** rather than looping forever or wedging one into the hull — the
     * fixtures build small grids, and a world generator that hangs on one is worse than a sparse map.
     */
    fun scatter(
        grid: Grid,
        machines: List<Machine?>,
        count: Int = DEFAULT_COUNT,
        seed: Int = DEFAULT_SEED,
        composition: Mixture,
    ): List<Rock> {
        if (count <= 0) return emptyList()
        val vessel = boundsOf(grid, machines)
        val rng = Random(seed)
        val placed = ArrayList<Rock>(count)
        val taken = ArrayList<IntArray>(count + 1)
        if (vessel != null) taken.add(grown(vessel, MARGIN))

        // Ten tries a rock: enough that a roomy grid fills, cheap enough that a full one gives up
        // quickly. Attempts are spent, not per-rock, so a crowded map ends early rather than
        // grinding through the whole budget for the last one.
        var attempts = count * 10
        while (placed.size < count && attempts-- > 0) {
            val radius = RADII[rng.nextInt(RADII.size)]
            val span = radius * 2 + 1
            if (span > grid.width || span > grid.height) continue
            val x = rng.nextInt(grid.width - span + 1)
            val y = rng.nextInt(grid.height - span + 1)
            val box = intArrayOf(x, y, x + span - 1, y + span - 1)
            if (taken.any { overlaps(it, box) }) continue
            taken.add(grown(box, MARGIN))
            placed.add(
                Rock.blob(
                    radius = radius,
                    positionX = x.toLong() * Flight.PER_TILE,
                    positionY = y.toLong() * Flight.PER_TILE,
                    composition = composition,
                ),
            )
        }
        return placed
    }

    /** The bounding box of everything built, or null when nothing is. `[minX, minY, maxX, maxY]`. */
    private fun boundsOf(grid: Grid, machines: List<Machine?>): IntArray? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (i in machines.indices) {
            val m = machines[i] ?: continue
            // The footprint, not the anchor: a smelter is stored at its centre and reaches two tiles
            // past it, and a box drawn round the anchors would leave a rock sitting on the furnace.
            val reach = m.kind.size / 2
            val x = grid.xOf(i)
            val y = grid.yOf(i)
            if (x - reach < minX) minX = x - reach
            if (y - reach < minY) minY = y - reach
            if (x + reach > maxX) maxX = x + reach
            if (y + reach > maxY) maxY = y + reach
        }
        return if (minX > maxX) null else intArrayOf(minX, minY, maxX, maxY)
    }

    private fun grown(box: IntArray, by: Int): IntArray =
        intArrayOf(box[0] - by, box[1] - by, box[2] + by, box[3] + by)

    private fun overlaps(a: IntArray, b: IntArray): Boolean =
        a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3]
}
