package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.sim.core.physics.primitives.Frac
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
    const val WINDOW_SIZE = 11
    private const val WINDOW_RADIUS = (WINDOW_SIZE-1)/2

    /** Half-window for computing NEAR zone radius (5×5 NEAR zone → radius 2 from center). */
    private const val NEAR_RADIUS = 1

    /** Maximum rock spawn attempts per chunk at peak local density */
    private const val MAX_SPAWNS_PER_CHUNK = 2

    /**
     * Chunk-to-noise-space scale (0.15) as an exact rational: controls how many chunks wide a
     * density "belt" spans. Kept as numerator/denominator so the base-octave coordinate
     * `chunkX * NOISE_SCALE_NUM * frequency / NOISE_SCALE_DEN` is an exact fixed-point value.
     */
    private const val NOISE_SCALE_NUM = 3L
    private const val NOISE_SCALE_DEN = 20

    /** One in [Frac]'s fixed-point scale — `Frac.raw / FRAC_ONE` is the represented value. */
    private const val FRAC_ONE: Long = Int.MAX_VALUE.toLong()

    private val FRAC_ZERO = Frac(0L)
    private val FRAC_HALF = Frac(1L, 2)

    /** √3 in fixed point, from [Frac.sqrt] so the skew constants below stay deterministic. */
    private val SQRT3 = Frac(3L, 1).sqrt()

    /** Simplex skew factor F2 = (√3 − 1)/2. */
    private val F2 = (SQRT3 - Frac(1L, 1)) / 2

    /** Simplex unskew factor G2 = (3 − √3)/6. */
    private val G2 = (Frac(3L, 1) - SQRT3) / 6

    /** 8-direction gradient set for simplex noise. */
    private val GRAD2X = longArrayOf(FRAC_ONE, -FRAC_ONE, FRAC_ONE, -FRAC_ONE, FRAC_ONE, -FRAC_ONE, 0L, 0L)
    private val GRAD2Y = longArrayOf(FRAC_ONE, FRAC_ONE, -FRAC_ONE, -FRAC_ONE, 0L, 0L, FRAC_ONE, -FRAC_ONE)

    /** Flat backing store: row-major, indexed as state[row * WINDOW_SIZE + col]. */
    internal val state = IntArray(WINDOW_SIZE * WINDOW_SIZE)
    internal val densityBytes = ByteArray(WINDOW_SIZE * WINDOW_SIZE)

    private var baseChunkX: Int = 0

    private var baseChunkY: Int = 0

    /** World chunk at window slot (0,0) — pair with [WINDOW_SIZE] to enumerate the tracked window. */
    val windowBaseChunkX: Int get() = baseChunkX
    val windowBaseChunkY: Int get() = baseChunkY

    /** Look up the state value for a chunk coordinate (throws if outside window). */
    internal fun stateAt(chunkX: Int, chunkY: Int): Int {
        val col = chunkX - baseChunkX
        val row = chunkY - baseChunkY
        require(col in 0 until WINDOW_SIZE && row in 0 until WINDOW_SIZE) { "chunk ($chunkX,$chunkY) outside window" }
        return state[row * WINDOW_SIZE + col]
    }

    /** Set the state value for a chunk coordinate (throws if outside window). */
    internal fun setStateAt(chunkX: Int, chunkY: Int, value: Int) {
        val col = chunkX - baseChunkX
        val row = chunkY - baseChunkY
        require(col in 0 until WINDOW_SIZE && row in 0 until WINDOW_SIZE) { "chunk ($chunkX,$chunkY) outside window" }
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
            val density = densityForChunk(worldChunkX, worldChunkY)
            val newRocks = spawnRocksForChunk(worldChunkX, worldChunkY, density)

            for (rock in newRocks) {
                if (!wouldOverlap(rock.positionX / Flight.PER_TILE, rock.positionY / Flight.PER_TILE, (rock.width / 2), result)) {
                    result.add(rock)
                }
            }

            densityBytes[nearestRow * WINDOW_SIZE + nearestCol] = density.scaleInt(255).toByte()
            state[nearestRow * WINDOW_SIZE + nearestCol] = POPULATED
        }

        return result
    }

    /**
     * Generate deterministic rocks for a given chunk.
     *
     * Uses the chunk coordinates to seed a deterministic layout of up to [MAX_SPAWNS_PER_CHUNK] rocks within the chunk.
     */
    private fun spawnRocksForChunk(chunkX: Int, chunkY: Int, density: Frac): List<Rock> {
        val hash = (chunkX * 73856093L xor chunkY * 19349663L).toInt()
        val rng = Random(hash.toLong() and 0xFFFFFFFFL)

        val fractionalGranularity = 100
        val fractionalSpawns = MAX_SPAWNS_PER_CHUNK*fractionalGranularity

        val numFractionalSpawnAttempts = density.scaleInt(fractionalSpawns)
        val numSpawnAttempts = numFractionalSpawnAttempts/fractionalGranularity
        val fractionalSpawnAttempt = numFractionalSpawnAttempts%fractionalGranularity

        val compositionIndex = ((hash.ushr(16) xor hash) and 3)
        val composition = ORE_BODIES[compositionIndex.coerceIn(0, ORE_BODIES.size - 1)]

        val rocks = mutableListOf<Rock>()
        for (i in 0 .. numSpawnAttempts) {
            if (i == numSpawnAttempts) {
                // Fractional attempt - skip if rng rolls higher than the fraction
                if (rng.nextInt(fractionalGranularity)+1 > fractionalSpawnAttempt) continue
            }
            val rx = rng.nextInt(CHUNK_SIZE)
            val ry = rng.nextInt(CHUNK_SIZE)
            val radius = rng.nextInt(3)+1  // 1, 2, 3

            val (originTileX, originTileY) = chunkOriginTile(chunkX, chunkY)
            val tileX = originTileX + rx
            val tileY = originTileY + ry

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
     * Tile coordinates of a chunk's top-left corner, in the same vessel-relative tile frame used
     * for spawned rock positions (see [spawnRocksForChunk]) — the frame the renderer must match to
     * place chunk-density tiles at the same screen position their rocks would occupy.
     */
    internal fun chunkOriginTile(chunkX: Int, chunkY: Int): Pair<Int, Int> =
        (chunkX - lastVesselChunkX) * CHUNK_SIZE to (chunkY - lastVesselChunkY) * CHUNK_SIZE

    /**
     * Deterministic 4-octave 2D simplex noise sampling for rock density per chunk.
     *
     * Samples [simplex2D] at increasing frequencies/decreasing amplitudes (standard fBm), then
     * remaps the [-1,1] result to a [0,1] density.
     */
    private fun densityForChunk(chunkX: Int, chunkY: Int): Frac {
        var amplitude = Frac(1L, 1)
        var frequency = 1
        var sum = FRAC_ZERO
        var maxAmplitude = FRAC_ZERO
        repeat(4) {
            val x = Frac(chunkX.toLong() * NOISE_SCALE_NUM * frequency, NOISE_SCALE_DEN)
            val y = Frac(chunkY.toLong() * NOISE_SCALE_NUM * frequency, NOISE_SCALE_DEN)
            sum += simplex2D(x, y) * amplitude
            maxAmplitude += amplitude
            amplitude /= 2
            frequency *= 2
        }
        val normalized = ((sum / maxAmplitude + Frac(1L, 1)) / 2).coerceIn(FRAC_ZERO, Frac(1L, 1))
        return normalized*normalized*normalized*normalized
    }

    /**
     * Deterministic hash of an integer lattice point into one of the 8 directions in
     * [GRAD2X]/[GRAD2Y]; returns the gradient index, not the vector, so no boxing occurs.
     */
    private fun latticeGradient(x: Int, y: Int): Int {
        var h = (x * 73856093) xor (y * 19349663)
        h = h xor (h ushr 13)
        h *= -0x61c88647
        h = h xor (h ushr 16)
        return h and 7
    }

    /** Floor of a [Frac] to the nearest integer at or below it (Long division truncates toward zero). */
    private fun floorFrac(v: Frac): Int {
        val q = v.raw / FRAC_ONE
        return (if (v.raw < 0 && q * FRAC_ONE != v.raw) q - 1 else q).toInt()
    }

    /**
     * `wide * small` where `wide` may lie well outside [Frac]'s safe multiplication range but
     * `small` is within roughly [-1, 1]. Splitting `wide` into its integer and fractional parts
     * keeps both products inside the `raw * raw` ceiling (see [Frac.times]).
     */
    private fun mulWide(wide: Frac, small: Frac): Frac {
        val whole = (wide.raw / FRAC_ONE).toInt()
        val fraction = Frac(wide.raw % FRAC_ONE)
        return small * whole + small * fraction
    }

    /**
     * Classic 2D simplex noise (Gustavson) in fixed point, returning a value in roughly [-1, 1].
     *
     * [x] and [y] may be large; every value derived from them past the lattice floor is a
     * cell-local offset in roughly [-1, 1], which is what keeps the products below Frac's ceiling.
     */
    private fun simplex2D(x: Frac, y: Frac): Frac {
        val one = Frac(1L, 1)

        val s = mulWide(x + y, F2)
        val i = floorFrac(x + s)
        val j = floorFrac(y + s)

        val t = G2 * (i + j)
        val x0 = x - Frac(i.toLong(), 1) + t
        val y0 = y - Frac(j.toLong(), 1) + t

        val i1: Int
        val j1: Int
        if (x0 > y0) {
            i1 = 1; j1 = 0
        } else {
            i1 = 0; j1 = 1
        }

        val x1 = x0 - Frac(i1.toLong(), 1) + G2
        val y1 = y0 - Frac(j1.toLong(), 1) + G2
        val x2 = x0 - one + G2 * 2
        val y2 = y0 - one + G2 * 2

        var n0 = FRAC_ZERO
        var t0 = FRAC_HALF - x0 * x0 - y0 * y0
        if (t0 > FRAC_ZERO) {
            t0 *= t0
            val g = latticeGradient(i, j)
            n0 = t0 * t0 * (Frac(GRAD2X[g]) * x0 + Frac(GRAD2Y[g]) * y0)
        }

        var n1 = FRAC_ZERO
        var t1 = FRAC_HALF - x1 * x1 - y1 * y1
        if (t1 > FRAC_ZERO) {
            t1 *= t1
            val g = latticeGradient(i + i1, j + j1)
            n1 = t1 * t1 * (Frac(GRAD2X[g]) * x1 + Frac(GRAD2Y[g]) * y1)
        }

        var n2 = FRAC_ZERO
        var t2 = FRAC_HALF - x2 * x2 - y2 * y2
        if (t2 > FRAC_ZERO) {
            t2 *= t2
            val g = latticeGradient(i + 1, j + 1)
            n2 = t2 * t2 * (Frac(GRAD2X[g]) * x2 + Frac(GRAD2Y[g]) * y2)
        }

        return (n0 + n1 + n2) * 70
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
                densityBytes[row * WINDOW_SIZE + col] = 0
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
                        densityBytes[dstY * WINDOW_SIZE + dstX] = 0
                    } else {
                        state[dstY * WINDOW_SIZE + dstX] = state[srcY * WINDOW_SIZE + srcX]
                        densityBytes[dstY * WINDOW_SIZE + dstX] = densityBytes[srcY * WINDOW_SIZE + srcX]
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
