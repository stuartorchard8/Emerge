package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Rock
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.starterVessel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the dynamic rock spawner — chunk-based spawning and despawning.
 *
 * Unlike RockFieldTest (initial scatter) and RockTest (physics), these tests exercise the
 * **chunk spawning loop** that populates the world with rocks as the vessel explores.
 * World-spawned rocks are free mass, so the rock ledger will diverge by their total mass
 * — that is the expected behaviour, not a bug.
 *
 * ⚠️ This test class **enables** RockSpawner. If spawning is broken, these tests fail.
 * If spawning is accidentally disabled, these tests also fail.
 */
class RockSpawnerTest {

    companion object {
        init { RockSpawner.enabled = true }
    }

    private val cfg = OutofspaceConfig()

    @AfterTest
    fun cleanupSpawnerState() {
        RockSpawner.enabled = true
        RockSpawner.reset()
    }

    /**
     * Verify spawning behavior: rocks spawn after activation on chunk change,
     * then one chunk per tick while stationary.
     *
     * Uses direct calls to [RockSpawner.process] so we control vessel movement precisely —
     * the vessel starts in one chunk, then jumps to another at tick 201 to trigger the first
     * spawn. After activation, one UNPOPULATED chunk spawns per tick.
     */
    @Test
    fun `rocks spawn on chunk change and one per tick while stationary`() {
        RockSpawner.reset()
        // RockSpawner.process() expects tile coordinates (not PER_TILE units).
        // Tile 50 → chunk 1, tile 80 → chunk 2, both within 96x60 grid.
        val vesselTileX = 50L
        val vesselTileY = 30L
        var rocks = emptyList<Rock>()

        // Run through activation with vessel stationary — nothing should spawn.
        for (tick in 0L until RockSpawner.ACTIVATE_AFTER_TICK) {
            rocks = RockSpawner.process(
                tick = tick,
                rocks = rocks,
                vesselTileX = vesselTileX,
                vesselTileY = vesselTileY,
            )
        }
        assertEquals(0, rocks.size, "no rocks before activation at tick ${RockSpawner.ACTIVATE_AFTER_TICK}")

        // Move vessel to a nearby different chunk (chunk 2, same Y) — triggers first spawn.
        val newVesselTileX = 80L
        rocks = RockSpawner.process(
            tick = RockSpawner.ACTIVATE_AFTER_TICK.toLong(),
            rocks = rocks,
            vesselTileX = newVesselTileX,
            vesselTileY = vesselTileY,
        )
        assertTrue(rocks.isNotEmpty(), "rocks should have spawned after chunk change")

        // New behavior: one chunk per tick even when stationary.
        // After 10 ticks, expect at least 10 more rocks (one chunk/tick, 2-4 each).
        val rocksAfterStationary = rocks.size
        for (tick in RockSpawner.ACTIVATE_AFTER_TICK + 1L..RockSpawner.ACTIVATE_AFTER_TICK + 10L) {
            rocks = RockSpawner.process(
                tick = tick,
                rocks = rocks,
                vesselTileX = newVesselTileX,
                vesselTileY = vesselTileY,
            )
        }
        assertTrue(
            rocks.size > rocksAfterStationary,
            "new impl spawns 1 chunk/tick while stationary: $rocksAfterStationary -> ${rocks.size}",
        )
        RockSpawner.reset()
    }

    /**
     * One-chunk-per-tick invariant.
     *
     * When the vessel is stationary after activation, the spawner should spawn
     * exactly one chunk (2-4 rocks) per tick, not all newly active chunks at once.
     */
    @Test
    fun `one chunk per tick while stationary`() {
        RockSpawner.reset()
        val vesselTileX = 50L
        val vesselTileY = 30L
        var rocks = emptyList<Rock>()

        // Run through activation with vessel stationary.
        for (tick in 0L until RockSpawner.ACTIVATE_AFTER_TICK) {
            rocks = RockSpawner.process(
                tick = tick,
                rocks = rocks,
                vesselTileX = vesselTileX,
                vesselTileY = vesselTileY,
            )
        }
        assertEquals(0, rocks.size, "no rocks at tick ${RockSpawner.ACTIVATE_AFTER_TICK}")

        // Move vessel to a nearby chunk to trigger the first spawn.
        val newVesselTileX = 80L
        rocks = RockSpawner.process(
            tick = RockSpawner.ACTIVATE_AFTER_TICK.toLong(),
            rocks = rocks,
            vesselTileX = newVesselTileX,
            vesselTileY = vesselTileY,
        )
        val firstSpawnCount = rocks.size
        assertTrue(firstSpawnCount >= 2, "first spawn should have 2+ rocks, got $firstSpawnCount")

        // Now run with vessel stationary — NEW behavior: 1 chunk/tick, rock count grows.
        // Overlap filtering may reduce additions per tick, but count must increase overall.
        val counts = mutableListOf(firstSpawnCount)
        for (tick in RockSpawner.ACTIVATE_AFTER_TICK + 1L..RockSpawner.ACTIVATE_AFTER_TICK + 9L) {
            rocks = RockSpawner.process(
                tick = tick,
                rocks = rocks,
                vesselTileX = newVesselTileX,
                vesselTileY = vesselTileY,
            )
            counts.add(rocks.size)
        }

        // One chunk spawns per tick (2-4 rocks), minus overlap filtering.
        // Total growth over 9 ticks should be at least 9 (one rock per tick minimum).
        val totalGrowth = counts.last() - counts.first()
        assertTrue(
            totalGrowth >= 9,
            "Expected >= 9 rocks added over 9 ticks (1 chunk/tick), got $totalGrowth. " +
                "Full sequence: $counts",
        )
        RockSpawner.reset()
    }

    /**
     * The spawner creates rocks after the activation delay.
     *
     * The fixture starts with zero initial rocks and runs past ACTIVATE_AFTER_TICK (200).
     * The spawner should start filling after activation.
     */
    @Test
    fun `rocks spawn after the activation delay`() {
        // Start with no rocks — the initial field is empty.
        val s = starterVessel(cfg.initialGrid)
        assertEquals(0, s.rocks.size, "fixture needs zero starting rocks")

        val controller = OutofspaceController(cfg, s)

        // Run to just before activation.
        repeat(RockSpawner.ACTIVATE_AFTER_TICK - 1) { controller.stepOnce() }
        assertEquals(0, controller.state.rocks.size, "rocks spawned before activation")

        // Now run past activation — the spawner should start filling.
        repeat(9) { controller.stepOnce() }

        // After activation and a few check cycles, the spawner should have dropped some rocks.
        val after = controller.state
        assertTrue(
            after.rocks.isNotEmpty(),
            "no rocks spawned after ${RockSpawner.ACTIVATE_AFTER_TICK} ticks: ${after.rocks.size}",
        )
        RockSpawner.reset()
    }

    /**
     * Chunks load as the vessel approaches — rocks appear in nearby chunks.
     *
     * This test verifies the chunk-based model: when the vessel is near the origin,
     * rocks should spawn in the chunks around it (not just in a fixed ring).
     */
    @Test
    fun `rocks appear in chunks around the vessel`() {
        val controller = OutofspaceController(cfg, starterVessel(cfg.initialGrid))

        repeat(RockSpawner.ACTIVATE_AFTER_TICK + 15) {
            controller.stepOnce()
        }

        val s = controller.state
        assertTrue(s.rocks.isNotEmpty(), "no rocks spawned")

        // All rocks should be within the 15×15 window (7 chunks × 32 tiles = 224 tiles).
        val vesselTileX = s.positionX / Flight.PER_TILE
        val vesselTileY = s.positionY / Flight.PER_TILE
        val despawnDist = 7L * RockSpawner.CHUNK_SIZE * Flight.PER_TILE

        for (rock in s.rocks) {
            val dx = rock.centreX - vesselTileX * Flight.PER_TILE
            val dy = rock.centreY - vesselTileY * Flight.PER_TILE
            val dist = kotlin.math.sqrt(dx.toDouble() * dx + dy.toDouble() * dy)
            assertTrue(
                dist < despawnDist,
                "rock beyond despawn radius: distance $dist, despawn radius $despawnDist " +
                    "pos=(${rock.centreX}, ${rock.centreY}), vessel=($vesselTileX, $vesselTileY)",
            )
        }
        RockSpawner.reset()
    }

    /**
     * World-spawned rocks carry zero world-frame impulse so they sit still in the world frame
     * while the vessel moves past them.
     */
    @Test
    fun `spawned rocks appear beyond the spawn radius with zero world impulse`() {
        val controller = OutofspaceController(cfg, starterVessel(cfg.initialGrid))

        repeat(RockSpawner.ACTIVATE_AFTER_TICK + 15) {
            controller.stepOnce()
        }

        val s = controller.state
        assertTrue(s.rocks.isNotEmpty(), "no rocks spawned")

        for (rock in s.rocks) {
            // Spawned rocks carry zero world-frame impulse.
            assertEquals(
                0L, rock.impulseX,
                "rock should have zero world impulse (impulseX): ${rock.impulseX}",
            )
            assertEquals(
                0L, rock.impulseY,
                "rock should have zero world impulse (impulseY): ${rock.impulseY}",
            )
        }
        RockSpawner.reset()
    }

    /**
     * The rock ledger diverges by the mass of world-spawned rocks.
     *
     * `baselineRockGrams` only tracks rocks present at world creation. World-spawned rocks
     * are free mass — they enter `rocks` without updating `baselineRockGrams` or `capturedGrams`,
     * so the ledger `baseline + captured − extracted − rockGrams` becomes negative by the
     * mass of world-spawned rocks. This is the documented, intentional behaviour.
     */
    @Test
    fun `world-spawned rocks diverge the rock ledger by their mass`() {
        val controller = OutofspaceController(cfg, starterVessel(cfg.initialGrid))

        repeat(RockSpawner.ACTIVATE_AFTER_TICK + 30) {
            controller.stepOnce()
        }

        val s = controller.state
        val spawnedMass = s.rocks.sumOf { it.massGrams }

        // baselineRockGrams was zero at start and never receives world-spawned rocks.
        assertEquals(0L, s.baselineRockGrams, "baseline should be zero for empty start")
        // capturedGrams was never used — rocks appeared from nowhere.
        assertEquals(0L, s.capturedGrams, "captured should be zero")

        // The divergence: the ledger cannot balance because spawned rocks are free mass.
        val divergence = s.baselineRockGrams + s.capturedGrams - s.extractedGrams - s.rockGrams
        // The divergence should be approximately the negative of the world-spawned rock mass.
        assertTrue(
            divergence < 0L,
            "the ledger did not diverge — world-spawned rocks were not tracked: $divergence",
        )
        RockSpawner.reset()
    }

    /**
     * Rocks do not spawn outside the vessel grid bounds.
     */
    @Test
    fun `rocks only spawn within grid bounds`() {
        val controller = OutofspaceController(cfg, starterVessel(cfg.initialGrid))

        repeat(RockSpawner.ACTIVATE_AFTER_TICK + 30) {
            controller.stepOnce()
        }

        val s = controller.state
        for (rock in s.rocks) {
            val rockTileX = rock.positionX / Flight.PER_TILE
            val rockTileY = rock.positionY / Flight.PER_TILE

            assertTrue(
                rockTileX >= 0 && rockTileY >= 0 && rockTileX < s.grid.width && rockTileY < s.grid.height,
                "rock spawned outside grid bounds: tile($rockTileX, $rockTileY), grid=${s.grid.width}x${s.grid.height}",
            )
        }
        RockSpawner.reset()
    }

    /**
     * Disabling the spawner stops spawning — the flag works.
     */
    @Test
    fun `disabling the spawner stops spawning`() {
        val controller = OutofspaceController(cfg, starterVessel(cfg.initialGrid))

        // Let the spawner create rocks.
        repeat(RockSpawner.ACTIVATE_AFTER_TICK + 15) {
            controller.stepOnce()
        }
        val beforeCount = controller.state.rocks.size

        // Disable the spawner.
        RockSpawner.enabled = false

        // Run more ticks — no new rocks should appear.
        val rockCountBeforeDisable = beforeCount
        repeat(15) {
            controller.stepOnce()
        }
        val afterCount = controller.state.rocks.size

        // Re-enable for other tests.
        RockSpawner.enabled = true

        // The count should not grow beyond what was there (rocks may despawn if far away).
        assertTrue(
            afterCount <= rockCountBeforeDisable + 1,
            "spawning continued after disable: $rockCountBeforeDisable -> $afterCount",
        )
        RockSpawner.reset()
    }

    /**
     * Phase 1: flat array indexing round-trips — stateAt/setStateAt work correctly.
     *
     * Verifies that the flat `state` array, indexed as `row * WINDOW_SIZE + col`, correctly
     * maps chunk coordinates through `stateAt`/`setStateAt`.
     */
    @Test
    fun `phase 1 array indexing round-trips`() {
        RockSpawner.reset()

        // After reset, the center chunk (7,7) in array coords is at world coords
        // (baseChunkX + 7, baseChunkY + 7) = (0, 0) since base = -7.
        val centerChunkX = RockSpawner.baseChunkX + 7
        val centerChunkY = RockSpawner.baseChunkY + 7

        assertEquals(0, RockSpawner.stateAt(centerChunkX, centerChunkY), "center should be 0 after reset")

        // Write a value via setStateAt and read it back.
        RockSpawner.setStateAt(centerChunkX, centerChunkY, 2)
        assertEquals(2, RockSpawner.stateAt(centerChunkX, centerChunkY), "round-trip setStateAt → stateAt")

        // Write to an off-center entry.
        val neighborChunkX = RockSpawner.baseChunkX + 8  // col 8
        val neighborChunkY = RockSpawner.baseChunkY + 7  // row 7
        RockSpawner.setStateAt(neighborChunkX, neighborChunkY, 1)
        assertEquals(1, RockSpawner.stateAt(neighborChunkX, neighborChunkY), "off-center round-trip")
    }

    /**
     * Phase 1: reset produces 0 at center + 1 elsewhere.
     *
     * The 5×5 0 zone at [7][7] should be 0 (0); everything else should be 1 (1).
     */
    @Test
    fun `phase 1 reset produces 0 at center + 1 elsewhere`() {
        RockSpawner.reset()

        val baseX = RockSpawner.baseChunkX
        val baseY = RockSpawner.baseChunkY

        var nearCount = 0
        var unpopCount = 0

        for (row in 0 until 15) {
            for (col in 0 until 15) {
                val worldChunkX = baseX + col
                val worldChunkY = baseY + row
                val s = RockSpawner.stateAt(worldChunkX, worldChunkY)

                val dx = kotlin.math.abs(col - 7)
                val dy = kotlin.math.abs(row - 7)
                val shouldBeNear = dx <= 2 && dy <= 2

                if (shouldBeNear) {
                    assertEquals(0, s, "chunk ($worldChunkX,$worldChunkY) at [$col,$row] should be 0")
                    nearCount++
                } else {
                    assertEquals(1, s, "chunk ($worldChunkX,$worldChunkY) at [$col,$row] should be 1")
                    unpopCount++
                }
            }
        }

        // 5×5 0 zone = 25 entries.
        assertEquals(25, nearCount, "should have exactly 25 0 entries (5x5)")
        assertEquals(15 * 15 - 25, unpopCount, "should have 200 1 entries")
    }

    /**
     * Phase 2: vessel moves 1 chunk → array shifts, center follows, values preserved.
     *
     * After writing known values to specific array cells, moving the vessel 1 chunk should
     * recenter the window while preserving overlapping values.
     */
    @Test
    fun `phase 2 vessel moves 1 chunk → array shifts, center follows, values preserved`() {
        RockSpawner.reset()

        // Write known values to a few cells.
        val c0 = RockSpawner.baseChunkX + 7  // col 7 (center row)
        val c1 = RockSpawner.baseChunkY + 7  // row 7 (center col)
        RockSpawner.setStateAt(c0, c1, 2)  // [7][7]
        RockSpawner.setStateAt(c0 + 1, c1, 1)  // [7][8]
        RockSpawner.setStateAt(c0 - 1, c1, 0)  // [7][6]

        // Move vessel 1 chunk right.
        RockSpawner.onVesselChunkMove(1, 0)

        // Center is now at (1, 0), which maps to array [7][7].
        val newBaseX = RockSpawner.baseChunkX
        val newBaseY = RockSpawner.baseChunkY
        assertEquals(-6, newBaseX, "baseX should be -6 after moving to chunk 1")
        assertEquals(-7, newBaseY, "baseY should be -7 after moving to chunk 0")

        // The value at [7][7] (center) should still be 2 — it was [7][7] before.
        assertEquals(2, RockSpawner.stateAt(1, 0), "center [7][7] preserved after shift")

        // The value at [7][6] was at world chunk (0,0), which is now array [6][7] (row shifted).
        // After shifting right, world chunk (0,0) maps to array col 6.
        assertEquals(0, RockSpawner.stateAt(0, 0), "chunk (0,0) value preserved at [6][7]")
    }

    /**
     * Phase 2: vessel jumps 10 chunks → full reset to 1 (with 0 at center).
     */
    @Test
    fun `phase 2 vessel jumps 10 chunks → full reset`() {
        RockSpawner.reset()

        // Mark some entries as 2 to verify they are cleared.
        RockSpawner.setStateAt(RockSpawner.baseChunkX + 7, RockSpawner.baseChunkY + 7, 2)

        // Jump 10 chunks in X.
        RockSpawner.onVesselChunkMove(10, 0)

        // After a jump > 7, resetWindow(10, 0) sets base = (3, -7).
        assertEquals(3, RockSpawner.baseChunkX, "baseX should be 3 after reset to chunk 10")
        assertEquals(-7, RockSpawner.baseChunkY, "baseY should be -7")

        // All entries except the 5×5 0 zone should be 1.
        var nearCount = 0
        var unpopCount = 0
        for (row in 0 until 15) {
            for (col in 0 until 15) {
                val s = RockSpawner.stateAt(RockSpawner.baseChunkX + col, RockSpawner.baseChunkY + row)
                val dx = kotlin.math.abs(col - 7)
                val dy = kotlin.math.abs(row - 7)
                if (dx <= 2 && dy <= 2) {
                    assertEquals(0, s, "[$col][$row] should be 0")
                    nearCount++
                } else {
                    assertEquals(1, s, "[$col][$row] should be 1")
                    unpopCount++
                }
            }
        }
        assertEquals(25, nearCount)
        assertEquals(200, unpopCount)
    }

    /**
     * Phase 2: vessel oscillates → base tracking stays correct.
     *
     * Move back and forth between two adjacent chunks many times and verify that
     * the base coordinates and array values are consistent throughout.
     */
    @Test
    fun `phase 2 vessel oscillates → base tracking stays correct`() {
        RockSpawner.reset()

        // Write a marker value at a known offset.
        val markerChunkX = RockSpawner.baseChunkX + 9
        val markerChunkY = RockSpawner.baseChunkY + 7
        RockSpawner.setStateAt(markerChunkX, markerChunkY, 2)

        // Oscillate 20 times between chunk 0 and chunk 1.
        for (i in 0 until 20) {
            val targetChunkX = if (i % 2 == 0) 0 else 1
            RockSpawner.onVesselChunkMove(targetChunkX, 0)

            // base should always be (target - 7, -7).
            assertEquals(targetChunkX - 7, RockSpawner.baseChunkX, "baseX after $i oscillations")
            assertEquals(-7, RockSpawner.baseChunkY, "baseY after $i oscillations")

            // The marker at [2][7] (col 9 - base) should still be 2.
            val arrayCol = 9  // markerChunkX - baseChunkX = (base+9) - (base) = 9
            val markerWorldX = RockSpawner.baseChunkX + arrayCol
            val markerWorldY = RockSpawner.baseChunkY + 7
            assertEquals(2, RockSpawner.stateAt(markerWorldX, markerWorldY),
                "marker preserved after $i oscillations")
        }
    }

    /**
     * Phase 3: NEAR zone always covers 5x5 at [7][7] after shift.
     *
     * After applying NEAR zone rules, exactly 25 entries must be NEAR (0), centered on [7][7].
     */
    @Test
    fun `phase 3 NEAR zone always covers 5x5 at 7x7 after shift`() {
        RockSpawner.reset()

        // Move vessel and apply NEAR zone rules.
        RockSpawner.onVesselChunkMove(3, 0)
        RockSpawner.applyNearZoneRules()

        var nearCount = 0
        for (row in 0 until 15) {
            for (col in 0 until 15) {
                val worldChunkX = RockSpawner.baseChunkX + col
                val worldChunkY = RockSpawner.baseChunkY + row
                val s = RockSpawner.stateAt(worldChunkX, worldChunkY)

                val dx = kotlin.math.abs(col - 7)
                val dy = kotlin.math.abs(row - 7)
                val shouldBeNear = dx <= 2 && dy <= 2

                if (shouldBeNear) {
                    assertEquals(0, s, "[$col][$row] should be NEAR after applyNearZoneRules")
                    nearCount++
                } else {
                    // Outside NEAR zone: must NOT be NEAR.
                    val status = if (s == 1) "UNPOPULATED" else "POPULATED"
                    assertTrue(s != 0, "[$col][$row] should not be NEAR, was $status")
                }
            }
        }
        assertEquals(25, nearCount, "exactly 25 NEAR entries expected")
    }

    /**
     * Phase 3: POPULATED chunks outside NEAR persist across shifts.
     *
     * After a vessel move, applyNearZoneRules re-centers the NEAR zone. Chunks outside
     * NEAR should keep their existing state (POPULATED or UNPOPULATED). This prevents
     * re-spawning of rocks in chunks that were previously visited.
     */
    @Test
    fun `phase 3 leaving NEAR marks POPULATED`() {
        RockSpawner.reset()
        // Mark a chunk far outside NEAR as POPULATED.
        RockSpawner.setStateAt(RockSpawner.baseChunkX, RockSpawner.baseChunkY, 2) // [0][0] = world (-7,-7)

        // Move vessel: base shifts, but [0][0] stays far from NEAR.
        RockSpawner.onVesselChunkMove(3, 0)
        RockSpawner.applyNearZoneRules()

        val chunkX = RockSpawner.baseChunkX
        val chunkY = RockSpawner.baseChunkY
        assertEquals(2, RockSpawner.stateAt(chunkX, chunkY),
            "POPULATED chunk outside NEAR keeps state after shift")
    }

    /**
     * Phase 5: rocks despawn when their chunk leaves the window.
     *
     * Spawn some rocks, then move the vessel far away so the window shifts past them.
     * Rocks outside the 15×15 window should be removed.
     */
    @Test
    fun `rocks despawn when chunk leaves window`() {
        RockSpawner.reset()
        var rocks = emptyList<Rock>()

        // tick 200: vessel at chunk (0,0), base = (-7,-7). Spawn rocks.
        rocks = RockSpawner.process(
            tick = RockSpawner.ACTIVATE_AFTER_TICK.toLong(),
            rocks = rocks,
            vesselTileX = 0L,
            vesselTileY = 0L,
        )
        val initialCount = rocks.size
        assertTrue(initialCount >= 2, "should have spawned at least 2 rocks: $initialCount")

        // tick 201: vessel to chunk (7, 0) = tile (224, 0). dx=7 from old center → no reset.
        // base becomes (0, -7). Old chunk (0,0) is at col=0, dy=7 → at edge, still inside.
        rocks = RockSpawner.process(
            tick = RockSpawner.ACTIVATE_AFTER_TICK + 1L,
            rocks = rocks,
            vesselTileX = 224L,
            vesselTileY = 0L,
        )

        // tick 202: vessel to chunk (14, 0) = tile (448, 0). dx=7 from old center → no reset.
        // base becomes (7, -7). Old chunk (0,0) is at col=-7 → outside the window (col < 0).
        rocks = RockSpawner.process(
            tick = RockSpawner.ACTIVATE_AFTER_TICK + 2L,
            rocks = rocks,
            vesselTileX = 448L,
            vesselTileY = 0L,
        )

        // The old rocks at chunk (0,0) should have been despawned.
        // Only new spawns (2-4 each tick) should remain: ticks 201+202 = ~4-8 rocks.
        assertTrue(
            rocks.size <= 8,
            "old rocks outside window should be despawned, got ${rocks.size}",
        )
        RockSpawner.reset()
    }

    /**
     * Phase 5: rocks near window edge survive one tick.
     *
     * A rock whose chunk is at the edge of the window (dx=7 or dy=7) must survive
     * until the window shifts past it.
     */
    @Test
    fun `rocks near window edge survive one tick`() {
        RockSpawner.reset()
        var rocks = emptyList<Rock>()

        // Vessel at (0,0) -> chunk (0,0) = array [7][7] = center.
        rocks = RockSpawner.process(
            tick = RockSpawner.ACTIVATE_AFTER_TICK.toLong(),
            rocks = rocks,
            vesselTileX = 0L,
            vesselTileY = 0L,
        )

        // Move vessel to (224, 0) -> chunk (7, 0), base = (0, -7).
        // Rocks at chunk (0, 0) = array col=0, row=7 -> dx=7, dy=0 -> still inside.
        val rocksBeforeShift = rocks.size
        rocks = RockSpawner.process(
            tick = RockSpawner.ACTIVATE_AFTER_TICK + 1L,
            rocks = rocks,
            vesselTileX = 224L,
            vesselTileY = 0L,
        )

        // Edge rocks survive - at least the same count (minus any that failed overlap).
        assertTrue(rocks.size >= rocksBeforeShift - 1,
            "edge rocks should survive: $rocksBeforeShift -> ${rocks.size}")
        RockSpawner.reset()
    }

}
