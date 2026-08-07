package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import kotlin.random.Random

/** A deterministic spawn point for a rock within a chunk. */
private data class RockSpawnPoint(
    val offsetX: Int,
    val offsetY: Int,
    val composition: Mixture,
    val radius: Int,
)

/**
 * Dynamic asteroid spawning and despawning using a chunk-based world model.
 *
 * Rocks spawn in chunks of [CHUNK_SIZE] tiles around the vessel. A chunk becomes **active** when
 * the vessel's chunk position is within [CHUNK_ACTIVE_RADIUS] chunks (inclusive, in either X or
 * Y). Rocks **despawn** when their chunk goes inactive and the rock lies beyond [CHUNK_DESPAWN_MULTIPLIER]
 * × [CHUNK_SIZE] tiles from the vessel (despawn radius).
 *
 * Spawned rocks are **free mass** — not tracked by [VesselState.baselineRockGrams]. The rock
 * ledger will diverge by the mass of world-spawned rocks the extractor eats (intentional).
 *
 * This replaces the previous ring-based spawner. Instead of spawning rocks in a fixed ring
 * around the vessel, rocks are scattered across the world in chunks that load as the vessel
 * explores. The result is a persistent field of asteroids that the player encounters over time.
 */
object RockSpawner {

    /** Whether dynamic spawning is disabled explicitly. Tests may set this to false. */
    var enabled: Boolean = true

    /** Size of each chunk in tiles. */
    const val CHUNK_SIZE: Int = 32

    /** Number of chunks the vessel must be from a chunk's center for it to become active. */
    const val CHUNK_ACTIVE_RADIUS: Int = 4

    /** Multiplier for despawn radius: DESPAWN = CHUNK_DESPAWN_MULTIPLIER × CHUNK_SIZE tiles. */
    const val CHUNK_DESPAWN_MULTIPLIER: Int = 5

    /** Maximum rocks kept active at once. */
    const val MAX_ACTIVE: Int = 20

    /** Minimum rock count before spawning kicks in (below RockField.DEFAULT_COUNT). */
    const val MIN_ROCKS_FOR_SPAWN: Int = 4

    /** How many ticks to wait before the spawner activates (preserves initial rock field). */
    const val ACTIVATE_AFTER_TICK: Int = 200

    /** Rocks closer than this radius (in tiles) from the vessel never spawn. */
    const val SPAWN_RADIUS: Int = 10

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
     * The set of currently active chunk coordinates.
     *
     * Updated each time [process] runs. Tracks which chunks have been activated so we can
     * despawn rocks when chunks go inactive.
     */
    private var activeChunks: Set<Pair<Int, Int>> = emptySet()

    /**
     * The vessel's last known chunk coordinates.
     *
     * Used to detect when the vessel has moved between chunks and recompute the active set.
     * Starts at a sentinel so the first call always activates chunks regardless of vessel position.
     */
    private var lastVesselChunkX: Int = Int.MIN_VALUE
    private var lastVesselChunkY: Int = Int.MIN_VALUE

    /**
     * Process spawning and despawning for one tick.
     *
     * [tick] is the current simulation tick, used to schedule periodic checks.
     * [rocks] is the current list of active rocks.
     * [vesselTileX] and [vesselTileY] place the spawn centre in tile coordinates.
     * [gridWidth] and [gridHeight] bound where rocks can appear (vessel frame).
     * Returns a new rocks list with spawns/despawns applied.
     */
    fun process(
        tick: Long,
        rocks: List<Rock>,
        vesselTileX: Long,
        vesselTileY: Long,
        gridWidth: Int,
        gridHeight: Int,
    ): List<Rock> {
        if (!enabled) return rocks

        // Don't activate the spawner until enough ticks have passed.
        // This lets the initial rock field serve its purpose before dynamic spawning kicks in.
        if (tick < ACTIVATE_AFTER_TICK) {
            return rocks
        }

        // Compute the vessel's chunk coordinates.
        val vesselChunkX = chunkIndexOf(vesselTileX)
        val vesselChunkY = chunkIndexOf(vesselTileY)

        // Skip if vessel hasn't moved between chunks since last check.
        if (vesselChunkX == lastVesselChunkX && vesselChunkY == lastVesselChunkY) {
            return rocks
        }

        lastVesselChunkX = vesselChunkX
        lastVesselChunkY = vesselChunkY

        // Compute the set of active chunks.
        val newActive = computeActiveChunks(vesselChunkX, vesselChunkY)

        // Separate rocks by chunk so we can despawn inactive-chunk rocks.
        val rocksByChunk = mutableMapOf<Pair<Int, Int>, MutableList<Rock>>()
        for (rock in rocks) {
            val rockTileX = rock.positionX / Flight.PER_TILE
            val rockTileY = rock.positionY / Flight.PER_TILE
            val rockChunkX = chunkIndexOf(rockTileX)
            val rockChunkY = chunkIndexOf(rockTileY)
            val key = rockChunkX to rockChunkY
            rocksByChunk.getOrPut(key) { mutableListOf() }.add(rock)
        }

        // Find inactive chunks (were active last time, now not).
        val inactiveChunks = activeChunks - newActive

        // Find newly active chunks (spawn rocks into them).
        val newlyActive = newActive - activeChunks

        // Build the result.
        val result = ArrayList<Rock>(rocks.size + newlyActive.size * 3)
        val despawnDist = (CHUNK_DESPAWN_MULTIPLIER * CHUNK_SIZE * Flight.PER_TILE).toDouble()

        // Keep rocks that are in active chunks.
        for ((chunkKey, chunkRocks) in rocksByChunk) {
            if (newActive.contains(chunkKey)) {
                result.addAll(chunkRocks)
            } else if (inactiveChunks.contains(chunkKey)) {
                // Chunk is inactive — only keep rocks within the despawn distance.
                for (rock in chunkRocks) {
                    val dx = rock.centreX - vesselTileX * Flight.PER_TILE
                    val dy = rock.centreY - vesselTileY * Flight.PER_TILE
                    val dist = kotlin.math.sqrt(dx.toDouble() * dx + dy.toDouble() * dy)
                    if (dist < despawnDist) {
                        result.add(rock)
                    }
                }
            }
        }

        // Spawn rocks into newly active chunks.
        var spawned = 0
        if (newlyActive.isNotEmpty()) {
            for ((cx, cy) in newlyActive) {
                val spawnPoints = spawnPointsForChunk(cx, cy)
                for (point in spawnPoints) {
                    // Check grid bounds.
                    val spawnTileX = cx * CHUNK_SIZE + point.offsetX
                    val spawnTileY = cy * CHUNK_SIZE + point.offsetY
                    if (spawnTileX < 0 || spawnTileY < 0 ||
                        spawnTileX.toLong() >= gridWidth || spawnTileY.toLong() >= gridHeight
                    ) {
                        continue
                    }

                    // Check distance from vessel — skip spawns too close to the ship.
                    val rockCenterX = spawnTileX.toLong() * Flight.PER_TILE + Flight.PER_TILE / 2L
                    val rockCenterY = spawnTileY.toLong() * Flight.PER_TILE + Flight.PER_TILE / 2L
                    val dx = rockCenterX - vesselTileX * Flight.PER_TILE
                    val dy = rockCenterY - vesselTileY * Flight.PER_TILE
                    val dist = kotlin.math.sqrt(dx.toDouble() * dx + dy.toDouble() * dy)
                    if (dist < SPAWN_RADIUS * Flight.PER_TILE) {
                        continue
                    }

                    // Check no overlap with existing rocks.
                    val rock = Rock.blob(
                        radius = point.radius,
                        positionX = spawnTileX.toLong() * Flight.PER_TILE,
                        positionY = spawnTileY.toLong() * Flight.PER_TILE,
                        composition = point.composition,
                    )
                    if (!wouldOverlap(spawnTileX.toLong(), spawnTileY.toLong(), point.radius, result)) {
                        result.add(rock)
                        spawned++
                    }
                }
            }
        }
        // Update the active chunks set.
        activeChunks = newActive

        return result
    }

    /**
     * Compute which chunks should be active given the vessel's chunk coordinates.
     *
     * A chunk is active if the vessel's chunk position is within [CHUNK_ACTIVE_RADIUS] chunks
     * in either X or Y (Chebyshev distance). This gives a square active area around the vessel.
     */
    private fun computeActiveChunks(vesselChunkX: Int, vesselChunkY: Int): Set<Pair<Int, Int>> {
        val range = CHUNK_ACTIVE_RADIUS
        val result = HashSet<Pair<Int, Int>>((2 * range + 1) * (2 * range + 1))
        for (dx in -range..range) {
            for (dy in -range..range) {
                result.add(vesselChunkX + dx to vesselChunkY + dy)
            }
        }
        return result
    }

    /**
     * Generate deterministic spawn points for a given chunk.
     *
     * Uses the chunk coordinates to seed a deterministic layout of 2-4 rocks within the chunk.
     * Spawn points are placed away from the chunk edges to avoid overlapping with adjacent chunks.
     */
    private fun spawnPointsForChunk(chunkX: Int, chunkY: Int): List<RockSpawnPoint> {
        val hash = (chunkX * 73856093L xor chunkY * 19349663L).toInt()
        val rng = Random(hash.toLong() and 0xFFFFFFFFL)

        // Pick 2-4 spawn points based on chunk hash.
        val numSpawns = (hash and 3) + 2  // 2, 3, or 4

        // Generate spawn offsets that are well-spaced within the chunk.
        // Avoid the chunk edges by keeping spawns at least 4 tiles from each edge.
        val margin = 4
        val usableSize = CHUNK_SIZE - margin * 2  // 24 tiles usable

        // Use deterministic grid positions for spawns.
        val gridPositions = when (numSpawns) {
            2 -> listOf(
                Pair(margin + usableSize / 3, margin + usableSize / 2),
                Pair(margin + usableSize * 2 / 3, margin + usableSize / 2),
            )
            3 -> listOf(
                Pair(margin + usableSize / 2, margin + usableSize / 3),
                Pair(margin + usableSize / 3, margin + usableSize * 2 / 3),
                Pair(margin + usableSize * 2 / 3, margin + usableSize * 2 / 3),
            )
            else -> listOf(
                Pair(margin + usableSize / 4, margin + usableSize / 4),
                Pair(margin + usableSize * 3 / 4, margin + usableSize / 4),
                Pair(margin + usableSize / 4, margin + usableSize * 3 / 4),
                Pair(margin + usableSize * 3 / 4, margin + usableSize * 3 / 4),
            )
        }

        // Shift positions slightly based on chunk hash for variety.
        val offsetX = (rng.nextInt(4) - 2)
        val offsetY = (rng.nextInt(4) - 2)

        val compositionIndex = ((hash.ushr(16) xor hash) and 3).toInt()

        val points = mutableListOf<RockSpawnPoint>()
        for ((gx, gy) in gridPositions) {
            val finalX = (gx + offsetX + CHUNK_SIZE) % usableSize + margin
            val finalY = (gy + offsetY + CHUNK_SIZE) % usableSize + margin
            val radius = rng.nextInt(3)  // 0, 1, 2
            val composition = ORE_BODIES[compositionIndex.coerceIn(0, ORE_BODIES.size - 1)]

            points.add(RockSpawnPoint(
                offsetX = finalX,
                offsetY = finalY,
                composition = composition,
                radius = radius,
            ))
        }

        return points
    }

    /**
     * Convert a tile coordinate to a chunk index.
     *
     * Handles negative tile coordinates correctly: tile -1 is in chunk -1, not chunk 0.
     */
    /**
     * Reset internal state. Intended for test isolation — clears active-chunk tracking and
     * forces the next [process] call to activate chunks from scratch.
     */
    fun reset() {
        activeChunks = emptySet()
        lastVesselChunkX = Int.MIN_VALUE
        lastVesselChunkY = Int.MIN_VALUE
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
