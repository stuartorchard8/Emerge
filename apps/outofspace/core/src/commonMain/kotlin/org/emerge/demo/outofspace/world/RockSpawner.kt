package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.speciesColor
import org.emerge.sim.core.physics.primitives.Frac
import kotlin.math.abs
import kotlin.random.Random

/**
 * Dynamic asteroid spawning and despawning using a WINDOW_SIZE×WINDOW_SIZE chunk-state array.
 *
 * Bodies spawn one chunk per tick — the nearest UNPOPULATED chunk outside a 5×5 NEAR zone
 * centered on the vessel. Bodies despawn when their chunk leaves the WINDOW_SIZE×WINDOW_SIZE window.
 *
 * Spawned bodies are **free mass** — no conservation ledger tracks them, not even drops from the
 * sim editor. Bodies are just `VesselState.bodies`.
 */
object RockSpawner {

    // ── Chunk-state array ──

    /** State constants for the chunk-state array. */
    const val UNPOPULATED = 0
    const val NEAR = 1
    const val POPULATED = 2

    /** Window size of the chunk-state array (WINDOW_SIZE×WINDOW_SIZE). */
    const val WINDOW_SIZE = 11
    const val WINDOW_BUFFER_SIZE = WINDOW_SIZE+WINDOW_SIZE%2 // Note that this must be even for GPUs to render the density map properly
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
    private const val NOISE_SCALE_NUM = 1L
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
    internal val abundanceBytes = ByteArray(WINDOW_BUFFER_SIZE * WINDOW_BUFFER_SIZE * 4)

    /**
     * The composition and rock density rolled for each populated window slot, kept so the nav map can
     * be recoloured for a different [highlight] without re-rolling the noise: the roll is pure (see
     * [mixtureForChunk]) but a hundred and sixty-five species of two-octave simplex per chunk is not
     * something to pay for on a frame that only changed which species the player is reading about.
     *
     * Indexed like [state]; null where the slot is UNPOPULATED. [densityBytes] holds the same 0..255
     * the alpha channel carries by default.
     */
    private val slotMixture = arrayOfNulls<Mixture>(WINDOW_SIZE * WINDOW_SIZE)
    private val densityBytes = IntArray(WINDOW_SIZE * WINDOW_SIZE)

    /**
     * The species the nav map is being read for, or null for the full spectrum.
     *
     * With a species set, a chunk is drawn in that species' own colour at a brightness telling how far
     * above or below its ordinary share of a rock this chunk came out — so a seam is a bright patch on
     * a dark field, and a prospector has somewhere to fly to. Sharing the field's alpha with the rock
     * density means an empty region stays empty however concentrated its (absent) rock would be.
     */
    var highlight: Species? = null
        set(value) {
            if (field == value) return
            field = value
            for (row in 0 until WINDOW_SIZE) {
                for (col in 0 until WINDOW_SIZE) {
                    writeSlot(col, row)
                }
            }
        }

    /** Total of every natural [Species.relativeAbundance] — the denominator of an ordinary share. */
    private val NATURAL_ABUNDANCE_TOTAL: Long = Species.NATURAL.sumOf { it.relativeAbundance.toLong() }

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
     * [bodies] is the current list of active bodies.
     * [vesselTileX] and [vesselTileY] place the spawn centre in tile coordinates.
     * Returns a new bodies list with spawns/despawns applied.
     *
     * One UNPOPULATED chunk per tick (nearest to vessel, outside NEAR zone) is spawned into.
     * Bodies whose chunk leaves the WINDOW_SIZE×WINDOW_SIZE window are despawned.
     */
    /**
     * Where the grid sits in the world, for this call only.
     *
     * The spawner thinks entirely in **tiles on the grid** — chunks, spawn windows and the overlap
     * test are all grid quantities — while a body is stored in the world. Rather than convert a
     * body's position back and forth (which would run it through [rotScale] twice a tick and let it
     * drift), this converts once in each direction: existing bodies are *read* into the grid frame
     * for the two comparisons, and a newly spawned body is *written* out of it at birth.
     *
     * Held rather than threaded because the object already keeps `lastVesselChunk` across calls, and
     * it is rewritten at the top of every [process].
     */
    private var pose: Pose = Pose.IDENTITY

    fun process(
        pose: Pose,
        tick: Long,
        bodies: List<RigidBody>,
        vesselTileX: Long,
        vesselTileY: Long,
    ): List<RigidBody> {
        if (!enabled) return bodies
        if (tick < ACTIVATE_AFTER_TICK) return bodies

        this.pose = pose
        val vesselChunkX = chunkIndexOf(vesselTileX)
        val vesselChunkY = chunkIndexOf(vesselTileY)

        // Detect vessel chunk change → shift window and recompute NEAR zone.
        if (vesselChunkX != lastVesselChunkX || vesselChunkY != lastVesselChunkY) {
            onVesselChunkMove(vesselChunkX, vesselChunkY)
            applyNearZoneRules()
            lastVesselChunkX = vesselChunkX
            lastVesselChunkY = vesselChunkY
        }

        // ── Despawn bodies outside the WINDOW_SIZE×WINDOW_SIZE window ──
        val result = ArrayList<RigidBody>(bodies.size)
        for (body in bodies) {
            val bodyTileX = body.localX(pose) / Flight.PER_TILE
            val bodyTileY = body.localY(pose) / Flight.PER_TILE
            val bodyChunkX = chunkIndexOf(bodyTileX)
            val bodyChunkY = chunkIndexOf(bodyTileY)
            val dx = abs(bodyChunkX)
            val dy = abs(bodyChunkY)
            if (dx <= WINDOW_RADIUS && dy <= WINDOW_RADIUS) {
                result.add(body)
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
            val mixture = mixtureForChunk(worldChunkX, worldChunkY)

            val newBodies = spawnBodiesForChunk(worldChunkX, worldChunkY, density, mixture)

            for (body in newBodies) {
                if (!wouldOverlap(body.localX(pose) / Flight.PER_TILE, body.localY(pose) / Flight.PER_TILE, (body.width / 2), result)) {
                    result.add(body)
                }
            }

            setAbundanceAt(nearestCol, nearestRow, mixture, density)
            state[nearestRow * WINDOW_SIZE + nearestCol] = POPULATED
        }

        return result
    }

    private fun setAbundanceAt(x: Int, y: Int, mixture: Mixture, density: Frac) {
        slotMixture[y * WINDOW_SIZE + x] = mixture
        densityBytes[y * WINDOW_SIZE + x] = density.scaleInt(0xFF)
        writeSlot(x, y)
    }

    /** Paint one window slot into [abundanceBytes] from what was rolled for it, under the current [highlight]. */
    private fun writeSlot(x: Int, y: Int) {
        val mixture = slotMixture[y * WINDOW_SIZE + x]
        if (mixture == null) {
            clearSlot(x, y)
            return
        }
        val density = densityBytes[y * WINDOW_SIZE + x]
        val species = highlight
        val color: Int
        val alpha: Int
        if (species == null) {
            color = mixture.color
            alpha = density
        } else {
            color = speciesColor(species).toInt()
            alpha = density * concentration(mixture, species) / 0xFF
        }
        abundanceBytes[(y * WINDOW_BUFFER_SIZE + x)*4+0] = (color.shr(24) and 0xFF).toByte()
        abundanceBytes[(y * WINDOW_BUFFER_SIZE + x)*4+1] = (color.shr(16) and 0xFF).toByte()
        abundanceBytes[(y * WINDOW_BUFFER_SIZE + x)*4+2] = (color.shr(8) and 0xFF).toByte()
        abundanceBytes[(y * WINDOW_BUFFER_SIZE + x)*4+3] = alpha.toByte()
    }

    /**
     * How rich this chunk is in [species], as 0..255, against the share that species has of an
     * *ordinary* rock.
     *
     * The share itself is useless as a brightness: gold is fourteen parts per hundred million however
     * good the seam, so a linear reading of it is black everywhere. What a prospector wants is the
     * ratio to the ordinary share, which [mixtureForChunk]'s `1 + 15×noise` weighting keeps inside
     * roughly [0.1, 2]; twice ordinary is drawn full brightness.
     *
     * The multiplication is in `Long` on purpose: a share of a billion times a hundred-million-part
     * abundance total is 10¹⁷, and would overflow an `Int` several times over.
     */
    private fun concentration(mixture: Mixture, species: Species): Int {
        val total = mixture.total
        if (total <= 0L || species.relativeAbundance <= 0) return 0
        val ordinary = total * species.relativeAbundance / NATURAL_ABUNDANCE_TOTAL
        if (ordinary <= 0L) return 0
        return (mixture[species] * 0xFF / (2L * ordinary)).coerceAtMost(0xFFL).toInt()
    }

    /** Blank one window slot — nothing was rolled there, so the nav map shows empty space. */
    private fun clearSlot(x: Int, y: Int) {
        abundanceBytes[(y * WINDOW_BUFFER_SIZE + x)*4+0] = 0
        abundanceBytes[(y * WINDOW_BUFFER_SIZE + x)*4+1] = 0
        abundanceBytes[(y * WINDOW_BUFFER_SIZE + x)*4+2] = 0
        abundanceBytes[(y * WINDOW_BUFFER_SIZE + x)*4+3] = 0
    }

    /**
     * Generate deterministic bodies for a given chunk.
     *
     * Uses the chunk coordinates to seed a deterministic layout of up to [MAX_SPAWNS_PER_CHUNK] bodies within the chunk.
     */
    private fun spawnBodiesForChunk(chunkX: Int, chunkY: Int, density: Frac, composition: Mixture): List<RigidBody> {
        val hash = (chunkX * 73856093L xor chunkY * 19349663L).toInt()
        val rng = Random(hash.toLong() and 0xFFFFFFFFL)

        val fractionalGranularity = 100
        val fractionalSpawns = MAX_SPAWNS_PER_CHUNK*fractionalGranularity

        val numFractionalSpawnAttempts = density.scaleInt(fractionalSpawns)
        val numSpawnAttempts = numFractionalSpawnAttempts/fractionalGranularity
        val fractionalSpawnAttempt = numFractionalSpawnAttempts%fractionalGranularity

        val bodies = mutableListOf<RigidBody>()
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

            val (worldTileX, worldTileY) = chunkX.toLong() * CHUNK_SIZE + rx.toLong() to chunkY.toLong() * CHUNK_SIZE + ry.toLong()

            // Born on the grid, stored in the world — the one conversion, done once. See [pose].
            bodies.add(RigidBody.rockBlob(
                radius = radius,
                positionX = pose.toWorldX(tileX.toLong() * Flight.PER_TILE, tileY.toLong() * Flight.PER_TILE),
                positionY = pose.toWorldY(tileX.toLong() * Flight.PER_TILE, tileY.toLong() * Flight.PER_TILE),
                composition = composition,
            ))
        }

        return bodies
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
     * Deterministic 2-octave 2D simplex noise sampling for rock composition per tile.
     *
     * Samples [simplex2D] at increasing frequencies/decreasing amplitudes (standard fBm), then
     * remaps the [-1,1] result to a [0,1] density.
     *
     * ### Why [density] is a billion and not a thousand
     *
     * It is the resolution of the recipe this returns: the share each species gets is
     * `weight × density / total`, floored, so `density` is the smallest fraction the result can
     * express. At 1000 that was one part per thousand — and with a hundred species summing to a
     * total in the thousands, **every trace mineral was pinned to exactly that one value or zero**.
     * Measured over 1600 chunks, all fifty-one species at the old abundances of 1 or 2 came out
     * identical: about 2% of chunks, one part per thousand, never anything else.
     *
     * A billion gives six more digits, which is what osmium at 490 parts per billion needs in order
     * to be *rarer than* gold rather than merely tied with it. Raising the abundances alone would
     * not have done it: this expression is ratio-invariant, so scaling every weight by a million
     * leaves the output bit-for-bit identical. Both halves were required.
     *
     * ⚠️ The product `weight × density` is taken in `Long` deliberately. `weight` is the noise-
     * modulated abundance, up to 16× the table value, so the worst case today is about
     * 4.5×10⁸ × 10⁹ ≈ 4.5×10¹⁷ — comfortable against `Long`'s 9.2×10¹⁸, and *immediately* overflow
     * if either factor were an `Int`. See [Species.relativeAbundance] for the matching ceiling on
     * the other side.
     */
    private fun mixtureForChunk(chunkX: Int, chunkY: Int, density: Long = 1_000_000_000L, species: List<Species> = Species.NATURAL): Mixture {
        val relativeComposition = IntArray(species.size)
        var totalComposition = 0L

        for (i in species.indices) {
            // Large offsets to minimize predictability
            val x = chunkX+Int.MAX_VALUE.toLong()*(i-species.size/2)+Int.MAX_VALUE/2
            val y = chunkY+Int.MAX_VALUE.toLong()*(i-species.size/2)+Int.MAX_VALUE/2

            var amplitude = Frac(1L, 1)
            var frequency = 1
            var sum = FRAC_ZERO
            var maxAmplitude = FRAC_ZERO
            repeat(2) {
                val x = Frac(x * NOISE_SCALE_NUM * frequency, NOISE_SCALE_DEN*10)
                val y = Frac(y * NOISE_SCALE_NUM * frequency, NOISE_SCALE_DEN*10)
                sum += simplex2D(x, y) * amplitude
                maxAmplitude += amplitude
                amplitude /= 2
                frequency *= 2
            }

            val normalized = ((sum / maxAmplitude + Frac(1L, 1)) / 2).coerceIn(FRAC_ZERO, Frac(1L, 1))

            // 1/16 predetermined, 15/16 local density
            relativeComposition[i] = species[i].relativeAbundance + 15*normalized.scaleInt(species[i].relativeAbundance) // TODO come back and clean this up when it's working
            totalComposition += relativeComposition[i]
        }

        return Mixture.of(
            *species.mapIndexed {
                index, species -> species to relativeComposition[index].toLong()*density/totalComposition
            }.toTypedArray(),
            energy = Budget.JOULE, // TODO: add energy equivalent to [SPACE_KELVIN] for this composition
        )
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
                slotMixture[row * WINDOW_SIZE + col] = null
                densityBytes[row * WINDOW_SIZE + col] = 0
                clearSlot(col, row)
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
            baseChunkX = newVesselChunkX-WINDOW_RADIUS
            baseChunkY = newVesselChunkY-WINDOW_RADIUS
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
                        slotMixture[dstY * WINDOW_SIZE + dstX] = null
                        densityBytes[dstY * WINDOW_SIZE + dstX] = 0
                        clearSlot(dstX, dstY)
                    } else {
                        state[dstY * WINDOW_SIZE + dstX] = state[srcY * WINDOW_SIZE + srcX]
                        slotMixture[dstY * WINDOW_SIZE + dstX] = slotMixture[srcY * WINDOW_SIZE + srcX]
                        densityBytes[dstY * WINDOW_SIZE + dstX] = densityBytes[srcY * WINDOW_SIZE + srcX]
                        abundanceBytes[(dstY * WINDOW_BUFFER_SIZE + dstX)*4+0] = abundanceBytes[(srcY * WINDOW_BUFFER_SIZE + srcX)*4+0]
                        abundanceBytes[(dstY * WINDOW_BUFFER_SIZE + dstX)*4+1] = abundanceBytes[(srcY * WINDOW_BUFFER_SIZE + srcX)*4+1]
                        abundanceBytes[(dstY * WINDOW_BUFFER_SIZE + dstX)*4+2] = abundanceBytes[(srcY * WINDOW_BUFFER_SIZE + srcX)*4+2]
                        abundanceBytes[(dstY * WINDOW_BUFFER_SIZE + dstX)*4+3] = abundanceBytes[(srcY * WINDOW_BUFFER_SIZE + srcX)*4+3]
                    }
                }
            }
        }
    }

    /**
     * Apply NEAR zone rules after a window shift.
     *
     * NEAR is the central squares of the array.
     * After a shift the base changes so different world chunks occupy
     * the same array slots — we must check whether the old world chunk at each slot
     * was near the old vessel position.ebuging navigation chunk rendering
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
        return tilePos.toInt().floorDiv(CHUNK_SIZE)
    }

    /**
     * Check if a body at ([tileX], [tileY]) with [radius] cells would overlap any body in [bodies].
     *
     * [radius] is the blob radius in cells; the bounding box is `(radius * 2 + 1)` tiles.
     * Position is in tile coordinates, not billionths.
     */
    private fun wouldOverlap(
        tileX: Long,
        tileY: Long,
        radius: Int,
        bodies: List<RigidBody>,
    ): Boolean {
        val span = radius * 2 + 1
        val halfSpan = span / 2L
        val minX = (tileX - halfSpan) * Flight.PER_TILE
        val maxX = (tileX + halfSpan) * Flight.PER_TILE
        val minY = (tileY - halfSpan) * Flight.PER_TILE
        val maxY = (tileY + halfSpan) * Flight.PER_TILE

        for (body in bodies) {
            val bodyMinX = body.localX(pose)
            val bodyMinY = body.localY(pose)
            val bodyMaxX = bodyMinX + body.width * Flight.PER_TILE
            val bodyMaxY = bodyMinY + body.height * Flight.PER_TILE

            if (minX < bodyMaxX && maxX > bodyMinX && minY < bodyMaxY && maxY > bodyMinY) {
                return true
            }
        }
        return false
    }
}
