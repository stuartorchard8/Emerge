package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import kotlin.random.Random

/**
 * Dynamic asteroid spawning and despawning using a 15×15 chunk-state array.
 *
 * Rocks spawn one chunk per tick — the nearest UNPOPULATED chunk outside a 5×5 NEAR zone
 * centered on the vessel. Rocks despawn when their chunk leaves the 15×15 window.
 *
 * Spawned rocks are **free mass** — not tracked by [VesselState.baselineRockGrams]. The rock
 * ledger will diverge by the mass of world-spawned rocks the extractor eats (intentional).
 */
object RockSpawner {

    // ── Chunk-state array ──

    /** State constants for the chunk-state array. */
    internal const val NEAR = 0
    internal const val UNPOPULATED = 1
    internal const val POPULATED = 2

    /** Window size of the chunk-state array (15×15). */
    private const val WINDOW_SIZE = 15

    /** Half-window for computing NEAR zone radius (5×5 NEAR zone → radius 2 from center). */
    private const val NEAR_RADIUS = 2

    /** Flat backing store: row-major, indexed as state[row * WINDOW_SIZE + col]. */
    internal val state = IntArray(WINDOW_SIZE * WINDOW_SIZE)

    /** Real-world chunk coordinate at state[0][0]. */
    internal val baseChunkX: Int
        get() = _baseChunkX
    private var _baseChunkX: Int = 0

    /** Real-world chunk coordinate at state[0][0]. */
    internal val baseChunkY: Int
        get() = _baseChunkY
    private var _baseChunkY: Int = 0

    /** Old window base — preserved across shifts so `applyNearZoneRules` can distinguish
     * entries that were previously NEAR (in the old coordinate system) from new entries. */
    private var _oldBaseChunkX: Int = 0
    private var _oldBaseChunkY: Int = 0

    /** Look up the state value for a chunk coordinate (throws if outside window). */
    internal fun stateAt(chunkX: Int, chunkY: Int): Int {
        val col = chunkX - _baseChunkX
        val row = chunkY - _baseChunkY
        require(col in 0..14 && row in 0..14) { "chunk ($chunkX,$chunkY) outside window" }
        return state[row * WINDOW_SIZE + col]
    }

    /** Set the state value for a chunk coordinate (throws if outside window). */
    internal fun setStateAt(chunkX: Int, chunkY: Int, value: Int) {
        val col = chunkX - _baseChunkX
        val row = chunkY - _baseChunkY
        require(col in 0..14 && row in 0..14) { "chunk ($chunkX,$chunkY) outside window" }
        state[row * WINDOW_SIZE + col] = value
    }

    /** Whether dynamic spawning is disabled explicitly. Tests may set this to false. */
    var enabled: Boolean = true

    /** Size of each chunk in tiles. */
    const val CHUNK_SIZE: Int = 32

    /** How many ticks to wait before the spawner activates (preserves initial rock field). */
    const val ACTIVATE_AFTER_TICK: Int = 200

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
     * Rocks whose chunk leaves the 15×15 window are despawned.
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

        // ── Despawn rocks outside the 15×15 window ──
        val result = ArrayList<Rock>(rocks.size)
        for (rock in rocks) {
            val rockTileX = rock.positionX / Flight.PER_TILE
            val rockTileY = rock.positionY / Flight.PER_TILE
            val rockChunkX = chunkIndexOf(rockTileX)
            val rockChunkY = chunkIndexOf(rockTileY)
            val dx = kotlin.math.abs(rockChunkX - _baseChunkX)
            val dy = kotlin.math.abs(rockChunkY - _baseChunkY)
            if (dx <= 7 && dy <= 7) {
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
                val dist = kotlin.math.max(kotlin.math.abs(row - 7), kotlin.math.abs(col - 7))
                if (dist < nearestDist || (dist == nearestDist && (row < nearestRow || (row == nearestRow && col < nearestCol)))) {
                    nearestDist = dist
                    nearestRow = row
                    nearestCol = col
                }
            }
        }

        if (nearestRow >= 0) {
            val worldChunkX = _baseChunkX + nearestCol
            val worldChunkY = _baseChunkY + nearestRow
            state[nearestRow * WINDOW_SIZE + nearestCol] = POPULATED

            val newRocks = spawnPointsForChunk(worldChunkX, worldChunkY)

            for (rock in newRocks) {
                if (!wouldOverlap(rock.positionX / Flight.PER_TILE, rock.positionY / Flight.PER_TILE, (rock.width / 2), result)) {
                    result.add(rock)
                }
            }
        }

        return result
    }

    /**
     * Generate deterministic rocks for a given chunk.
     *
     * Uses the chunk coordinates to seed a deterministic layout of 2–4 rocks within the chunk.
     * Rocks are placed away from the chunk edges to avoid overlapping with adjacent chunks.
     */
    private fun spawnPointsForChunk(chunkX: Int, chunkY: Int): List<Rock> {
        val hash = (chunkX * 73856093L xor chunkY * 19349663L).toInt()
        val rng = Random(hash.toLong() and 0xFFFFFFFFL)

        // Pick 2-4 rocks based on chunk hash.
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
        val composition = ORE_BODIES[compositionIndex.coerceIn(0, ORE_BODIES.size - 1)]

        val rocks = mutableListOf<Rock>()
        for ((gx, gy) in gridPositions) {
            val finalX = (gx + offsetX + CHUNK_SIZE) % usableSize + margin
            val finalY = (gy + offsetY + CHUNK_SIZE) % usableSize + margin
            val radius = rng.nextInt(3)  // 0, 1, 2

            val tileX = chunkX * CHUNK_SIZE + finalX
            val tileY = chunkY * CHUNK_SIZE + finalY

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
     * Also resets the chunk-state array: the 5×5 NEAR zone at [7][7] is marked NEAR,
     * everything else is UNPOPULATED.
     */
    fun reset() {
        lastVesselChunkX = Int.MIN_VALUE
        lastVesselChunkY = Int.MIN_VALUE
        resetWindow(0, 0)
    }

    /**
     * Reset the chunk-state array so that [vesselChunkX][vesselChunkY] is at the center [7][7].
     *
     * The 5×5 NEAR zone around the center is marked NEAR; all other entries are UNPOPULATED.
     */
    private fun resetWindow(vesselChunkX: Int, vesselChunkY: Int) {
        _baseChunkX = vesselChunkX - 7
        _baseChunkY = vesselChunkY - 7
        _oldBaseChunkX = _baseChunkX
        _oldBaseChunkY = _baseChunkY
        for (row in 0 until WINDOW_SIZE) {
            for (col in 0 until WINDOW_SIZE) {
                val worldChunkX = _baseChunkX + col
                val worldChunkY = _baseChunkY + row
                val dx = kotlin.math.abs(col - 7)
                val dy = kotlin.math.abs(row - 7)
                state[row * WINDOW_SIZE + col] = if (dx <= NEAR_RADIUS && dy <= NEAR_RADIUS) NEAR else UNPOPULATED
            }
        }
    }

    /**
     * Recenter the chunk-state array when the vessel moves to a new chunk.
     *
     * If the new vessel chunk is within ±7 of the current base (i.e. still in-window),
     * recompute base so the vessel lands at [7][7]. If the jump exceeds 7 chunks in
     * either axis, perform a full reset — the window cannot track that distance.
     */
    internal fun onVesselChunkMove(newVesselChunkX: Int, newVesselChunkY: Int) {
        // Save old base for applyNearZoneRules (to detect "was NEAR" transitions).
        _oldBaseChunkX = _baseChunkX
        _oldBaseChunkY = _baseChunkY

        val dx = newVesselChunkX - (baseChunkX + 7)
        val dy = newVesselChunkY - (baseChunkY + 7)

        if (kotlin.math.abs(dx) > 7 || kotlin.math.abs(dy) > 7) {
            resetWindow(newVesselChunkX, newVesselChunkY)
            return
        }

        _baseChunkX = newVesselChunkX - 7
        _baseChunkY = newVesselChunkY - 7
    }

    /**
     * Apply NEAR zone rules after a window shift.
     *
     * NEAR is defined by a chunk's world coordinates relative to the vessel, not by
     * array position. After a shift the base changes so different world chunks occupy
     * the same array slots — we must check whether the old world chunk at each slot
     * was near the old vessel position.
     *
     * - Chunks now within NEAR_RADIUS of the vessel → NEAR
     * - Chunks that were NEAR (old world chunk was near old vessel) but now outside → POPULATED
     * - Chunks that shifted in from outside → left as-is (safe default)
     */
    internal fun applyNearZoneRules() {
        val oldVesselChunkX = _oldBaseChunkX + 7
        val oldVesselChunkY = _oldBaseChunkY + 7
        val newVesselChunkX = _baseChunkX + 7
        val newVesselChunkY = _baseChunkY + 7

        // First pass: identify which entries were NEAR (old world chunk was near old vessel).
        val wasNear = BooleanArray(WINDOW_SIZE * WINDOW_SIZE)
        for (row in 0 until WINDOW_SIZE) {
            for (col in 0 until WINDOW_SIZE) {
                val worldChunkX = _oldBaseChunkX + col
                val worldChunkY = _oldBaseChunkY + row
                val dx = kotlin.math.abs(worldChunkX - oldVesselChunkX)
                val dy = kotlin.math.abs(worldChunkY - oldVesselChunkY)
                wasNear[row * WINDOW_SIZE + col] = dx <= NEAR_RADIUS && dy <= NEAR_RADIUS
            }
        }

        // Second pass: apply rules.
        for (row in 0 until WINDOW_SIZE) {
            for (col in 0 until WINDOW_SIZE) {
                val idx = row * WINDOW_SIZE + col
                val worldChunkX = _baseChunkX + col
                val worldChunkY = _baseChunkY + row
                val dx = kotlin.math.abs(worldChunkX - newVesselChunkX)
                val dy = kotlin.math.abs(worldChunkY - newVesselChunkY)

                if (dx <= NEAR_RADIUS && dy <= NEAR_RADIUS) {
                    // Now near the vessel → always NEAR.
                    state[idx] = NEAR
                } else if (wasNear[idx]) {
                    // Was near the old vessel but now outside → POPULATED.
                    state[idx] = POPULATED
                }
                // Else: leave as-is (UNPOPULATED or already POPULATED).
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
