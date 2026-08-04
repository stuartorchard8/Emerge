package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.sim.core.physics.primitives.Frac2

/**
 * Loose material lying on the floor: what falls out of a machine when you take it apart.
 *
 * Before this existed, dismantling a full storage destroyed its contents and the mass balance said
 * `LEAK` — correctly, because it *was* one. The fix is not to exempt the player's own edits from the
 * accounting; it is to give the material somewhere to go. A vent is still the only place matter
 * legitimately leaves the world.
 *
 * **Sparse on purpose.** Debris is rare and clustered, so this is a map from tile to pile rather than
 * a dense field like [AirField] or [Temperature]. Those are touched everywhere every tick and a dense
 * array is the cheap shape for them; this is touched in a handful of tiles and only when someone
 * takes something apart. A dense `tiles × species` array here would be almost entirely zeroes.
 *
 * A pile keeps its [Resource] forms separate — a heap of ingots beside a heap of ore is two entries,
 * not one blended mixture. Rubble loses its arrangement, not its refinement: it would be much easier
 * to flatten everything to species, and it would quietly destroy the work a smelter did.
 */
class Debris private constructor(private val piles: Map<Int, List<Resource>>) {

    val isEmpty: Boolean get() = piles.isEmpty()

    /** The pile on a tile, in the order it accumulated. Empty if the floor is clear. */
    operator fun get(tile: Int): List<Resource> = piles[tile] ?: emptyList()

    fun massAt(tile: Int): Long {
        var sum = 0L
        for (r in get(tile)) sum += r.mass
        return sum
    }

    fun mixtureAt(tile: Int): Mixture {
        var m = Mixture.EMPTY
        for (r in get(tile)) m += r.mixture
        return m
    }

    /** Tiles holding something, in ascending index order — a fixed order, which is what matters. */
    fun tiles(): List<Int> = piles.keys.sorted()

    val totalGrams: Long get() {
        var sum = 0L
        for ((_, pile) in piles) for (r in pile) sum += r.mass
        return sum
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Debris && piles == other.piles)

    override fun hashCode(): Int = piles.hashCode()

    override fun toString(): String = "Debris(${totalGrams}g on ${piles.size} tiles)"

    companion object {
        val EMPTY: Debris = Debris(emptyMap())

        /**
         * How much one tile's floor holds before a pile stops falling into it and rests on top.
         *
         * Twenty tanks' worth. High enough that ordinary dismantling never stacks, low enough that
         * emptying a warehouse spreads along the deck instead of vanishing into one tile.
         */
        const val TILE_CAP = 400_000L

        fun of(piles: Map<Int, List<Resource>>): Debris =
            Debris(piles.filterValues { pile -> pile.any { it.mass > 0L } })
    }
}

/**
 * Allows loose material to fall, and throws overboard whatever is lying outside the hull.
 *
 * A pile moves one tile per tick in the direction gravity points, passing straight *through*
 * machinery — rubble on the deck under a conveyor is rubble on the deck, and making belts block it
 * would only produce piles floating in midair where a machine used to be. What stops it is the
 * structure: it will not fall into hull or into space, and it will not fall into a tile already
 * holding [Debris.TILE_CAP].
 *
 * Tiles are visited **furthest-down first**, so a column collapses in one pass instead of shuffling
 * one tile per tick from the top. That ordering is derived from [gravity] rather than from array
 * order, so this keeps working when down stops being +y.
 *
 * @return the grams thrown overboard, which join the same vent ledger as everything else that leaves.
 */
fun settleDebris(
    grid: Grid,
    structure: StructureMap,
    work: DebrisWork,
    gravity: Frac2,
): Long {
    var vented = 0L
    val down = downDirection(gravity) ?: return vented

    // Furthest along the fall direction first: the pile at the bottom settles before the one above
    // it tries to land on it, so a stack resolves in a single pass.
    val order = work.tiles().sortedByDescending { fallDepth(grid, it, down) }
    for (tile in order) {
        val below = grid.neighbour(tile, down)
        if (below < 0) {
            // Anything that falls off at the edge of the grid is in space and goes.
            for (r in work.clear(tile)) vented += r.mass
            continue
        }
        // The wall stops a fall; a machine does not. Rubble on the deck under a machine is rubble on
        // the deck — see the test that says so. Machines being solid is a fact about *air*, and
        // reusing it here would leave heaps hanging wherever one happened to be.
        if (structure[below] == Structure.Hull) continue
        if (work.massAt(below) >= Debris.TILE_CAP) continue
        work.spill(below, work.clear(tile))
    }
    return vented
}

/**
 * Which grid direction a pile falls, or null when there is no answer to give.
 *
 * A heap has to choose one of four directions — that is what a tile grid is — so a gravity that does
 * not lie on an axis has to be resolved to one, and the rule is the **dominant component**. A pile
 * under a gravity leaning 99 parts down and 1 part right falls down; nothing else is a defensible
 * reading of that vector, and answering `null` to it would be worse than a rounding, because it
 * switches settling off altogether.
 *
 * Null is kept for the two cases where there genuinely is no answer: **no gravity at all**, and a
 * **tie** — an exactly diagonal pull, where down and right are equally right and picking one is a
 * guess rather than a rounding. That distinction is the whole of what changed here: the rule used to
 * be "exactly on an axis or nothing", which is the same thing as long as gravity is a constant
 * somebody typed, and is not the same thing at all once an engine is writing it. A vessel with its
 * plating on and a lateral thruster firing has a permanently diagonal gravity, and under the old rule
 * every pile aboard it froze the moment the engine lit — not as a decision, but because nobody had
 * had to decide yet.
 *
 * ⚠️ What a pile does under a *genuinely* diagonal gravity — a staircase, presumably — is still
 * undecided, and this does not decide it. It rounds to an axis. Real diagonal settling arrives with
 * diagonal thrust, and both are scheduled.
 */
fun downDirection(gravity: Frac2): Direction? {
    val gx = gravity.x.raw
    val gy = gravity.y.raw
    val ax = if (gx < 0L) -gx else gx
    val ay = if (gy < 0L) -gy else gy
    return when {
        ax == ay -> null                        // both zero, or an exact diagonal: no axis to round to
        ay > ax -> if (gy > 0L) Direction.Down else Direction.Up
        else -> if (gx > 0L) Direction.Right else Direction.Left
    }
}

/** How far along the fall direction a tile sits — the sort key that makes a column collapse at once. */
private fun fallDepth(grid: Grid, tile: Int, down: Direction): Int = when (down) {
    Direction.Down -> grid.yOf(tile)
    Direction.Up -> -grid.yOf(tile)
    Direction.Right -> grid.xOf(tile)
    Direction.Left -> -grid.xOf(tile)
}
