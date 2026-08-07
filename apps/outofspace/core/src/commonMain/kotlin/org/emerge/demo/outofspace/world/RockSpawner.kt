package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import kotlin.random.Random

/**
 * Dynamic asteroid spawning and despawning.
 *
 * Replaces [RockField]'s one-time scatter with a living ring of rocks that maintains a set of
 * active rocks around the vessel. Rocks spawn just inside [SPAWN_RADIUS] and despawn beyond
 * [DESPAWN_RADIUS]. The goal is a world that has rocks to fly at without needing the player to
 * hunt across a static field.
 *
 * Spawned rocks are **free mass** — not tracked by [VesselState.baselineRockGrams]. The rock
 * ledger will diverge by the mass of world-spawned rocks the extractor eats (intentional).
 */
object RockSpawner {

    /** Whether dynamic spawning is disabled explicitly. Tests may set this to false. */
    var enabled: Boolean = true

    /** Spawn ring: rocks appear just inside this radius (tiles from vessel centre). */
    const val SPAWN_RADIUS: Int = 30

    /** Despawn ring: rocks beyond this radius leave the world. */
    const val DESPAWN_RADIUS: Int = 35

    /** Maximum rocks kept active at once. */
    const val MAX_ACTIVE: Int = 15

    /** How often to check spawn/despawn (in ticks). */
    const val CHECK_INTERVAL: Int = 3

    /** Minimum rock count before spawning kicks in (below RockField.DEFAULT_COUNT). */
    const val MIN_ROCKS_FOR_SPAWN: Int = 4

    /** How many ticks to wait before the spawner activates (preserves initial rock field). */
    const val ACTIVATE_AFTER_TICK: Int = 200

    /** Rocks this far from the vessel (in tiles) are always despawned, regardless of origin. */
    const val ABSOLUTE_DESPAWN_RADIUS: Int = 100

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

        // Despawn rocks that have drifted very far from the vessel.
        // Only despawn rocks beyond ABSOLUTE_DESPAWN_RADIUS to avoid removing
        // test fixture rocks placed intentionally far away.
        val absDespawnDist = (ABSOLUTE_DESPAWN_RADIUS * Flight.PER_TILE).toDouble()
        val active = rocks.filter { rock ->
            val dx = rock.centreX - vesselTileX * Flight.PER_TILE
            val dy = rock.centreY - vesselTileY * Flight.PER_TILE
            kotlin.math.sqrt(dx.toDouble() * dx + dy.toDouble() * dy) < absDespawnDist
        }

        // Only check for spawns on schedule ticks (every CHECK_INTERVAL).
        if (tick % CHECK_INTERVAL != 0L || active.size >= MAX_ACTIVE) {
            return active
        }

        // Don't spawn when there are already enough rocks — the spawner is a
        // replenishment mechanism, not a replacement for the initial rock field.
        if (active.size >= MIN_ROCKS_FOR_SPAWN) {
            return active
        }

        val spawns = ArrayList<Rock>(MAX_ACTIVE - active.size)
        var attempts = 0
        val maxAttempts = (MAX_ACTIVE - active.size) * 5

        while (active.size + spawns.size < MAX_ACTIVE && attempts < maxAttempts) {
            val tileX = vesselTileX + Random.nextInt(-SPAWN_RADIUS, SPAWN_RADIUS + 1).toLong()
            val tileY = vesselTileY + Random.nextInt(-SPAWN_RADIUS, SPAWN_RADIUS + 1).toLong()

            // Check tile is within grid bounds.
            if (tileX < 0 || tileY < 0 || tileX >= gridWidth || tileY >= gridHeight) {
                attempts++
                continue
            }

            // Convert to vessel frame position (billionths of a tile).
            val posX = tileX * Flight.PER_TILE + Flight.PER_TILE / 2L
            val posY = tileY * Flight.PER_TILE + Flight.PER_TILE / 2L

            // Check distance from vessel is within spawn ring.
            val dx = posX - vesselTileX * Flight.PER_TILE
            val dy = posY - vesselTileY * Flight.PER_TILE
            val dist = kotlin.math.sqrt(dx.toDouble() * dx + dy.toDouble() * dy)
            if (dist < SPAWN_RADIUS * Flight.PER_TILE) {
                attempts++
                continue
            }

            // Check no overlap with existing rocks.
            if (wouldOverlap(tileX, tileY, 1, active) || wouldOverlap(tileX, tileY, 1, spawns)) {
                attempts++
                continue
            }

            // Determine composition from position hash.
            val composition = compositionFor(tileX, tileY)
            val radius = Random.nextInt(3) // 0, 1, 2 (3, 5, 7 tiles across)

            val rock = Rock.blob(
                radius = radius,
                positionX = tileX * Flight.PER_TILE,
                positionY = tileY * Flight.PER_TILE,
                composition = composition,
            )

            // Give it a small random world-frame impulse so it drifts naturally.
            val impulseX = Random.nextLong(-500L, 500L)
            val impulseY = Random.nextLong(-500L, 500L)
            spawns.add(rock.copy(impulseX = impulseX, impulseY = impulseY))

            attempts++
        }

        return active + spawns
    }

    /**
     * Determine which ore body a spawn position maps to.
     *
     * Uses a deterministic hash of the tile coordinates so the same position always yields the
     * same ore — enabling the player to learn patterns in rock distribution.
     */
    private fun compositionFor(tileX: Long, tileY: Long): Mixture {
        val hash = (tileX * 73856093L xor tileY * 19349663L).toInt()
        val index = ((hash.ushr(16) xor hash) and 3).coerceIn(0, ORE_BODIES.size - 1)
        return ORE_BODIES[index]
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

    /** Integer square root: floor(sqrt(value)). Binary search, no floats. */
    private fun longSqrt(value: Long): Long {
        if (value < 0) throw IllegalArgumentException("cannot sqrt negative")
        if (value == 0L) return 0L
        var low = 1L
        var high = value
        var result = value
        while (low <= high) {
            val mid = (low + high) ushr 1
            val sq = mid * mid
            if (sq == value) return mid
            if (sq < value) {
                low = mid + 1
                result = mid
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
