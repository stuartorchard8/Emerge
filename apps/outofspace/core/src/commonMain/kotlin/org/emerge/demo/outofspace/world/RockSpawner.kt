package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import kotlin.math.abs
import kotlin.random.Random

/**
 * Dynamic asteroid spawning and despawning using a WINDOW_SIZE×WINDOW_SIZE chunk-state array.
 *
 * Rocks spawn one chunk per tick — the nearest UNPOPULATED chunk outside a 5×5 NEAR zone
 * centered on the vessel. Rocks despawn when their chunk leaves the WINDOW_SIZE×WINDOW_SIZE window.
 *
 * Spawned rocks are **free mass** — not tracked by [VesselState.baselineRockGrams]. The rock
 * ledger will diverge by the mass of world-spawned rocks the extractor eats (intentional).
 */
object RockSpawner {

    // ── Chunk-state array ──

    /** State constants for the chunk-state array. */
    const val UNPOPULATED = 0
    const val NEAR = 1
    const val POPULATED = 2

    /** Window size of the chunk-state array (WINDOW_SIZE×WINDOW_SIZE). */
    private const val WINDOW_SIZE = 11
    private const val WINDOW_RADIUS = (WINDOW_SIZE-1)/2

    /** Half-window for computing NEAR zone radius (5×5 NEAR zone → radius 2 from center). */
    private const val NEAR_RADIUS = 1

    /** Maximum rock spawn attempts per chunk at peak local density */
    private const val MAX_SPAWNS_PER_CHUNK = 4

    /** Flat backing store: row-major, indexed as state[row * WINDOW_SIZE + col]. */
    internal val state = IntArray(WINDOW_SIZE * WINDOW_SIZE)

    private var baseChunkX: Int = 0

    private var baseChunkY: Int = 0

    /** Look up the state value for a chunk coordinate (throws if outside window). */
    internal fun stateAt(chunkX: Int, chunkY: Int): Int {
        val col = chunkX - baseChunkX
        val row = chunkY - baseChunkY
        require(col in 0..14 && row in 0..14) { "chunk ($chunkX,$chunkY) outside window" }
        return state[row * WINDOW_SIZE + col]
    }

    /** Set the state value for a chunk coordinate (throws if outside window). */
    internal fun setStateAt(chunkX: Int, chunkY: Int, value: Int) {
        val col = chunkX - baseChunkX
        val row = chunkY - baseChunkY
        require(col in 0..14 && row in 0..14) { "chunk ($chunkX,$chunkY) outside window" }
        state[row * WINDOW_SIZE + col] = value
    }

    /** Whether dynamic spawning is disabled explicitly. Tests may set this to false. */
    var enabled: Boolean = true

    /** Size of each chunk in tiles. */
    const val CHUNK_SIZE: Int = 64

    /** How many ticks to wait before the spawner activates (preserves initial rock field). */
    const val ACTIVATE_AFTER_TICK: Int = 4

    /** Rocks spawned with zero world-frame impulse so they do not carry momentum into the ship. */
    const val SPAWN_IMPULSE: Long = 0L

    /** Four ore bodies the spawner rotates through, selected by spawn position hash. */
    private val ORE_BODIES: List<Mixture> = listOf(
        Mixture.of(
            Species.Iron to 410L,
            Species.Silica to 300L,
            Species.Copper to 180L,
            Species.Titanium to 110L,
        ),
        Mixture.of(
            Species.Copper to 400L,
            Species.Iron to 250L,
            Species.Silica to 200L,
            Species.Carbon to 150L,
        ),
        Mixture.of(
            Species.Titanium to 350L,
            Species.Iron to 300L,
            Species.Aluminum to 200L,
            Species.Silica to 150L,
        ),
        Mixture.of(
            Species.Silica to 450L,
            Species.Carbon to 300L,
            Species.Iron to 150L,
            Species.RareEarth to 100L,
        ),
    )

    /**
     * The vessel's last known chunk coordinates.
     *
     * Used to detect when the vessel has moved between chunks so the window can shift.
     * Starts at a sentinel so the first call always shifts the window regardless of vessel position.
     */
    private var lastVesselChunkX: Int = Int.MIN_VALUE
    private var lastVesselChunkY: Int = Int.MIN_VALUE

    /**
     * Process spawning and despawning for one tick.
     *
     * [tick] is the current simulation tick, used to schedule periodic checks.
     * [rocks] is the current list of active rocks.
     * [vesselTileX] and [vesselTileY] place the spawn centre in tile coordinates.
     * Returns a new rocks list with spawns/despawns applied.
     *
     * One UNPOPULATED chunk per tick (nearest to vessel, outside NEAR zone) is spawned into.
     * Rocks whose chunk leaves the WINDOW_SIZE×WINDOW_SIZE window are despawned.
     */
    fun process(
        tick: Long,
        rocks: List<Rock>,
        vesselTileX: Long,
        vesselTileY: Long,
    ): List<Rock> {
        if (!enabled) return rocks
        if (tick < ACTIVATE_AFTER_TICK) return rocks

        val vesselChunkX = chunkIndexOf(vesselTileX)
        val vesselChunkY = chunkIndexOf(vesselTileY)

        // Detect vessel chunk change → shift window and recompute NEAR zone.
        if (vesselChunkX != lastVesselChunkX || vesselChunkY != lastVesselChunkY) {
            onVesselChunkMove(vesselChunkX, vesselChunkY)
            applyNearZoneRules()
            lastVesselChunkX = vesselChunkX
            lastVesselChunkY = vesselChunkY
        }

        // ── Despawn rocks outside the WINDOW_SIZE×WINDOW_SIZE window ──
        val result = ArrayList<Rock>(rocks.size)
        for (rock in rocks) {
            val rockTileX = rock.positionX / Flight.PER_TILE
            val rockTileY = rock.positionY / Flight.PER_TILE
            val rockChunkX = chunkIndexOf(rockTileX)
            val rockChunkY = chunkIndexOf(rockTileY)
            val dx = abs(rockChunkX)
            val dy = abs(rockChunkY)
            if (dx <= WINDOW_RADIUS && dy <= WINDOW_RADIUS) {
                result.add(rock)
            }
        }

        // ── Spawn one UNPOPULATED chunk per tick (nearest to vessel, outside NEAR) ──
        var nearestRow = -1
        var nearestCol = -1
        var nearestDist = Int.MAX_VALUE

        for (row in 0 until WINDOW_SIZE) {
            for (col in 0 until WINDOW_SIZE) {
                val idx = row * WINDOW_SIZE + col
                if (state[idx] != UNPOPULATED) continue
                val distSq = (row - WINDOW_RADIUS)*(row - WINDOW_RADIUS) + (col - WINDOW_RADIUS)*(col - WINDOW_RADIUS)
                if (distSq < nearestDist || (distSq == nearestDist && (row < nearestRow || (row == nearestRow && col < nearestCol)))) {
                    nearestDist = distSq
                    nearestRow = row
                    nearestCol = col
                }
            }
        }

        if (nearestRow >= 0) {
            val worldChunkX = baseChunkX + nearestCol
            val worldChunkY = baseChunkY + nearestRow
            state[nearestRow * WINDOW_SIZE + nearestCol] = POPULATED

            val newRocks = spawnRocksForChunk(worldChunkX, worldChunkY)

            for (rock in newRocks) {
                if (!wouldOverlap(rock.positionX / Flight.PER_TILE, rock.positionY / Flight.PER_TILE, (rock.width / 2), result)) {
                    result.add(rock)
                }
            }
        }

        return result
    }

    /**
     * Generate deterministic rocks for a given chunk, blocking spawns that overlap with existing rocks.
     *
     * Uses the chunk coordinates to seed a deterministic layout of up to [MAX_SPAWNS_PER_CHUNK] rocks within the chunk.
     * Chunk coordinates are also used to index into a deterministic smooth noise field that scales back max_spawns for less dense regions.
     */
    private fun spawnRocksForChunk(chunkX: Int, chunkY: Int): List<Rock> {
        val hash = (chunkX * 73856093L xor chunkY * 19349663L).toInt()
        val rng = Random(hash.toLong() and 0xFFFFFFFFL)

        // TODO: Make numSpawnAttempts sample normalized smooth noise to scale back when density is low.
        val numSpawnAttempts = MAX_SPAWNS_PER_CHUNK

        val compositionIndex = ((hash.ushr(16) xor hash) and 3)
        val composition = ORE_BODIES[compositionIndex.coerceIn(0, ORE_BODIES.size - 1)]

        val rocks = mutableListOf<Rock>()
        (0 until numSpawnAttempts).forEach { _ ->
            val rx = rng.nextInt(CHUNK_SIZE)
            val ry = rng.nextInt(CHUNK_SIZE)
            val radius = rng.nextInt(3)+1  // 1, 2, 3

            val tileX = (chunkX-lastVesselChunkX) * CHUNK_SIZE + rx
            val tileY = (chunkY-lastVesselChunkY) * CHUNK_SIZE + ry

            rocks.add(Rock.blob(
                radius = radius,
                positionX = tileX.toLong() * Flight.PER_TILE,
                positionY = tileY.toLong() * Flight.PER_TILE,
                composition = composition,
            ))
        }

        return rocks
    }

    /**
     * Reset internal state. Intended for test isolation — clears chunk tracking and
     * forces the next [process] call to activate chunks from scratch.
     *
     * Also resets the chunk-state array: the 5×5 NEAR zone at [WINDOW_RADIUS][WINDOW_RADIUS] is marked NEAR,
     * everything else is UNPOPULATED.
     */
    fun reset() {
        lastVesselChunkX = Int.MIN_VALUE
        lastVesselChunkY = Int.MIN_VALUE
        resetWindow(0, 0)
    }

    /**
     * Reset the chunk-state array so that [vesselChunkX][vesselChunkY] is at the center [WINDOW_RADIUS][WINDOW_RADIUS].
     *
     * All entries are UNPOPULATED.
     */
    private fun resetWindow(vesselChunkX: Int, vesselChunkY: Int) {
        baseChunkX = vesselChunkX - WINDOW_RADIUS
        baseChunkY = vesselChunkY - WINDOW_RADIUS
        for (row in 0 until WINDOW_SIZE) {
            for (col in 0 until WINDOW_SIZE) {
                state[row * WINDOW_SIZE + col] = UNPOPULATED
            }
        }
    }

    /**
     * Recenter the chunk-state array when the vessel moves to a new chunk.
     *
     * If the new vessel chunk is within ±WINDOW_RADIUS of the current base (i.e. still in-window),
     * recompute base so the vessel lands at [WINDOW_RADIUS][WINDOW_RADIUS]. If the jump exceeds WINDOW_RADIUS chunks in
     * either axis, perform a full reset — the window cannot track that distance.
     */
    internal fun onVesselChunkMove(newVesselChunkX: Int, newVesselChunkY: Int) {
        val dx = newVesselChunkX - lastVesselChunkX
        val dy = newVesselChunkY - lastVesselChunkY

        if (abs(dx) > WINDOW_RADIUS || abs(dy) > WINDOW_RADIUS) {
            resetWindow(newVesselChunkX, newVesselChunkY)
        } else {
            for (row in 0 until WINDOW_SIZE) {
                val dstY = if (dy > 0) row else WINDOW_SIZE-row-1
                val srcY = dstY+dy
                for (col in 0 until WINDOW_SIZE) {
                    val dstX = if (dx > 0) col else WINDOW_SIZE-col-1
                    val srcX = dstX+dx
                    if (srcX !in 0..<WINDOW_SIZE ||
                        srcY !in 0..<WINDOW_SIZE) {
                        // Source is outside of bounds of previous representation
                        state[dstY * WINDOW_SIZE + dstX] = UNPOPULATED
                    } else {
                        state[dstY * WINDOW_SIZE + dstX] = state[srcY * WINDOW_SIZE + srcX]
                    }
                }
            }
            baseChunkX = newVesselChunkX-WINDOW_RADIUS
            baseChunkY = newVesselChunkY-WINDOW_RADIUS
        }
    }

    /**
     * Apply NEAR zone rules after a window shift.
     *
     * NEAR is the central squares of the array.
     * After a shift the base changes so different world chunks occupy
     * the same array slots — we must check whether the old world chunk at each slot
     * was near the old vessel position.
     *
     * - Chunks now within NEAR_RADIUS of the vessel → NEAR
     * - Chunks that were NEAR (old world chunk was near old vessel) but now outside → POPULATED
     * - Chunks that shifted in from outside → left as-is (safe default)
     */
    internal fun applyNearZoneRules() {
        for (row in 0 until WINDOW_SIZE) {
            for (col in 0 until WINDOW_SIZE) {
                val idx = row * WINDOW_SIZE + col
                val dx = abs(col - WINDOW_RADIUS)
                val dy = abs(row - WINDOW_RADIUS)

                if (dx <= NEAR_RADIUS && dy <= NEAR_RADIUS) {
                    state[idx] = NEAR
                } else if (state[idx] == NEAR) {
                    state[idx] = POPULATED
                }
            }
        }
    }

    private fun chunkIndexOf(tilePos: Long): Int {
        return tilePos.toInt() / CHUNK_SIZE
    }

    /**
     * Check if a rock at ([tileX], [tileY]) with [radius] cells would overlap any rock in [rocks].
     *
     * [radius] is the blob radius in cells; the bounding box is `(radius * 2 + 1)` tiles.
     * Position is in tile coordinates, not billionths.
     */
    private fun wouldOverlap(
        tileX: Long,
        tileY: Long,
        radius: Int,
        rocks: List<Rock>,
    ): Boolean {
        val span = radius * 2 + 1
        val halfSpan = span / 2L
        val minX = (tileX - halfSpan) * Flight.PER_TILE
        val maxX = (tileX + halfSpan) * Flight.PER_TILE
        val minY = (tileY - halfSpan) * Flight.PER_TILE
        val maxY = (tileY + halfSpan) * Flight.PER_TILE

        for (rock in rocks) {
            val rockMinX = rock.positionX
            val rockMinY = rock.positionY
            val rockMaxX = rockMinX + rock.width * Flight.PER_TILE
            val rockMaxY = rockMinY + rock.height * Flight.PER_TILE

            if (minX < rockMaxX && maxX > rockMinX && minY < rockMaxY && maxY > rockMinY) {
                return true
            }
        }
        return false
    }
}
