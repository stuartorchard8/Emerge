package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull

/**
 * Every tile's [Structure], derived rather than authored.
 *
 * The player never paints "floor". They build **hull**, and the inside is whatever the hull encloses:
 * a flood fill inward from the grid's edge marks everything space can reach, and what it cannot reach
 * is interior. That gives the right answer to "what is outside the hull" for free, and it makes a
 * breach mean exactly what it should — knock out one hull tile and the fill pours in, so the room
 * *becomes* outside, with no separate concept of a leak.
 *
 * Derived every tick. A flood fill over a grid this size is a rounding error next to the rest of the
 * tick, and the alternative — caching it and invalidating on edits — is a class of bug for no gain.
 */
class StructureMap(
    private val kinds: ByteArray,
    private val solid: BooleanArray,
    private val open: BooleanArray,
) {

    operator fun get(index: Int): Structure = Structure.entries[kinds[index].toInt()]

    /** Air can neither sit in this tile nor cross it. Walls and airlocks; almost nothing else. */
    fun blocksAir(tile: TileIndex): Boolean =
        kinds[tile.index].toInt() == Structure.Hull.ordinal || kinds[tile.index].toInt() == Structure.Machine.ordinal

    /**
     * A **body** can neither sit in this tile nor cross it — what a rock hits.
     *
     * The other half of the split, and the reason it exists: a smelter is a solid object a rock
     * bounces off, *and* it is open on every face to the air of the room it stands in. One flag
     * could only say both at once, so a machine that had to breathe had to be something an asteroid
     * fell through. See [org.emerge.demo.outofspace.world.machine.DeckMachineKind.preventThoroughfare].
     */
    fun blocksPassage(tile: TileIndex): Boolean = solid[tile.index]

    /**
     * Space reaches this tile — so a surface facing it is radiating at the sky.
     *
     * Filled against [blocksPassage] rather than against the air, which is what gives the ship one
     * perimeter: a tile buried inside a machine's own footprint faces nothing, whether or not the
     * machine it belongs to lets the air through.
     */
    fun openToSpace(tile: TileIndex): Boolean = open[tile.index]

    fun isContained(tile: TileIndex): Boolean = kinds[tile.index].toInt() != Structure.Vacuum.ordinal

    val interiorCount: Int get() = kinds.count { it.toInt() == Structure.Interior.ordinal }

    // [open] is a pure function of [solid], so it is not compared: two maps that agree on what a
    // body cannot pass agree on where space reaches.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is StructureMap && kinds.contentEquals(other.kinds) && solid.contentEquals(other.solid))

    override fun hashCode(): Int = 31 * kinds.contentHashCode() + solid.contentHashCode()

    companion object {
        /**
         * Flood-fills space in from every edge tile, stopping at anything solid. Anything not
         * reached and not solid is interior.
         *
         * [openness] names the tiles that are open *this tick* despite being solid things — today,
         * [org.emerge.demo.outofspace.world.machine.Airlock]s that are being signalled. They are skipped exactly as a permeable plate is, so
         * the fill pours through an open door and the room beyond it correctly becomes outside. Omit
         * it and every door is shut, which is the right answer for a world being loaded or built.
         * See [org.emerge.demo.outofspace.world.airlockOpenness].
         */
        /**
         * Breadth-first from every edge tile. An explicit stack rather than recursion: a 48x28 grid
         * is 1344 deep in the worst case and this also runs on JS.
         *
         * [enter] both marks the tile and says whether it had not been marked already — one job,
         * because the two fills below mark different things and neither can be expressed as a
         * predicate the walk then acts on.
         */
        private fun fillFromEdges(grid: Grid, enter: (TileIndex) -> Boolean) {
            val stack = ArrayDeque<TileIndex>()
            fun seed(tile: TileIndex) { if (enter(tile)) stack.addLast(tile) }
            for (x in 0 until grid.width) {
                seed(grid.tile(x, 0))
                seed(grid.tile(x, grid.height - 1))
            }
            for (y in 0 until grid.height) {
                seed(grid.tile(0, y))
                seed(grid.tile(grid.width - 1, y))
            }
            while (stack.isNotEmpty()) {
                val at = stack.removeLast()
                for (dir in Direction.ALL) {
                    val next = grid.neighbour(at, dir)
                    if (next != TileIndex.NONE) seed(next)
                }
            }
        }

        fun derive(grid: Grid, deck: DeckArray, openness: IntArray? = null): StructureMap {
            val kinds = ByteArray(grid.size) { Structure.Interior.ordinal.toByte() }
            // What a rock hits, which is a different list from what holds the air in — and unlike
            // the fill below it needs no fill: whether a body can be here is a fact about the tile
            // and nothing else.
            val solid = BooleanArray(grid.size)
            for (tile in grid.tiles) {
                val m = deck[tile] ?: continue
                // A frame with no metal in it stops nothing, air or rock alike — see below.
                if (m.kind.preventThoroughfare && !deck.isGhost(tile) && (openness?.get(tile.index) ?: 0) == 0) {
                    for (t in m.tiles(grid)) solid[t.index] = true
                }
                if (!m.kind.preventAirflow) continue
                // ⚠️ A ghost is a frame with no metal in it and it does not hold pressure. Air blows
                // straight through where the machine will be, which means a room under construction
                // is open to space until its *last* hull tile is finished. That is the honest
                // reading of a thing that weighs nothing, and it is not to be softened.
                if (deck.isGhost(tile)) continue
                if ((openness?.get(tile.index) ?: 0) > 0) continue
                val kind = if (m is Hull || m is Airlock) Structure.Hull else Structure.Machine
                for (t in m.tiles(grid)) kinds[t.index] = kind.ordinal.toByte()
            }

            fillFromEdges(grid) { tile ->
                if (kinds[tile.index].toInt() != Structure.Interior.ordinal) false
                else {
                    kinds[tile.index] = Structure.Vacuum.ordinal.toByte()
                    true
                }
            }

            // The **second** fill, over the second obstacle set: where space gets to when the only
            // thing stopping it is matter a body cannot pass through. That is the ship's radiative
            // skin, and it is not the air fill — a machine open to the room is still metal with a
            // surface, and a room sealed by a wall is not somewhere anything radiates into.
            //
            // ⚠️ It has to be a **fill** and not just `!solid`, or a wall would radiate into its own
            // sealed room: "is this tile solid" and "does space reach this tile" are different
            // questions and only the second one is about exposure.
            //
            // ⛔ The obstacle set is [blocksPassage] and not "metal is present", which means the
            // rock-permeable kinds — an extractor above all, at five tiles across — are transparent
            // to it and radiate from every tile rather than from their rim. Chosen deliberately:
            // that equipment is the most exposed on the ship. See the discussion in
            // `PLAN_rigid_debris.md` if this is ever revisited.
            val open = BooleanArray(grid.size)
            fillFromEdges(grid) { tile ->
                if (solid[tile.index] || open[tile.index]) false
                else {
                    open[tile.index] = true
                    true
                }
            }
            return StructureMap(kinds, solid, open)
        }
    }
}
