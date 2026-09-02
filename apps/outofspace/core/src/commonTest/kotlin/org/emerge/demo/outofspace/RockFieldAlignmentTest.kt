package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.BodyKind
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.RockSpawner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The asteroid field is in the world, and the nav map draws the world — so a rock is where the map
 * says a rock is.
 *
 * ⚠️ **The map was never the thing that was wrong.** Its UVs put the vessel at texel `frac + 5` of
 * an eleven-chunk window based at `vesselChunk − 5`, which is right, and [RockSpawner.writeSlot]
 * pairs the eleven-wide backing arrays with the twelve-wide texture correctly. What disagreed was
 * the spawner, which indexed density by world chunk and then placed the rock through the vessel's
 * pose — so the two claims about "where chunk `c` is" were made in different frames.
 *
 * ⚠️ **There is no test here that the field does not *turn* with the ship**, and there was one for
 * about ten minutes. It could not fail: `process` has no pose to turn by any more, so both halves of
 * the comparison were the same call. The symptom was real — a quarter turn moved a rock from world
 * chunk (0, 2) to (−3, 0) — and it is recorded in [RockSpawner]'s own header, where the thing that
 * would have to come back for it to return is written down.
 */
class RockFieldAlignmentTest {

    /**
     * A chunk's rocks land in that chunk, wherever the ship was standing when it rolled them.
     *
     * The displacement was `shipTile mod CHUNK_SIZE` — nothing at a chunk boundary and most of a
     * chunk just before the next one — and since chunks spawn at different moments, every chunk in
     * the field carried a different one. That is why it read as noise rather than as an offset.
     */
    @Test
    fun `a rock lands in the chunk it was rolled for, wherever the ship is`() {
        for (shipTile in listOf(0L, 17L, 32L, 63L, 640L, 673L)) {
            for (rock in fieldSpawnedWithShipAt(shipTile)) {
                val chunkX = floorDiv(rock.comX / Flight.PER_TILE, RockSpawner.CHUNK_SIZE)
                val chunkY = floorDiv(rock.comY / Flight.PER_TILE, RockSpawner.CHUNK_SIZE)
                assertTrue(
                    chunkX in windowChunksX() && chunkY in windowChunksY(),
                    "ship at $shipTile put a rock in chunk ($chunkX, $chunkY), outside the window " +
                        "${windowChunksX()} x ${windowChunksY()} the density was rolled for",
                )
            }
        }
    }

    /**
     * The same chunk puts its rocks in the same place however far the ship has flown.
     *
     * Measured before the fix: ship at world tile 0 put one at 6.2, ship at 32 put it at 38.2.
     */
    @Test
    fun `the field does not slide with the ship`() {
        val atOrigin = fieldSpawnedWithShipAt(0L).map { it.comX to it.comY }
        val alongABit = fieldSpawnedWithShipAt(32L).map { it.comX to it.comY }

        assertTrue(atOrigin.isNotEmpty(), "nothing spawned, so this proved nothing")
        assertEquals(atOrigin, alongABit, "the asteroid field slid with the ship")
    }

    /** Every rock a fresh window spawns with the vessel parked at [shipTile] tiles along x. */
    private fun fieldSpawnedWithShipAt(shipTile: Long): List<RigidBody> {
        RockSpawner.reset()
        RockSpawner.enabled = true
        var bodies = emptyList<RigidBody>()
        for (step in 1..40) {
            bodies = RockSpawner.process(RockSpawner.ACTIVATE_AFTER_TICK + step.toLong(), bodies, shipTile, 0L)
        }
        return bodies.filter { it.kind != BodyKind.STATION }
    }

    private fun windowChunksX() =
        RockSpawner.windowBaseChunkX until RockSpawner.windowBaseChunkX + RockSpawner.WINDOW_SIZE

    private fun windowChunksY() =
        RockSpawner.windowBaseChunkY until RockSpawner.windowBaseChunkY + RockSpawner.WINDOW_SIZE

    private fun floorDiv(a: Long, b: Int): Int = a.toInt().floorDiv(b)
}
