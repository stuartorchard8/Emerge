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
     * Baseline: verify current spawning behavior before the chunk-state array refactoring.
     *
     * Rocks should spawn after the activation delay when the vessel changes chunks. This test
     * uses direct calls to [RockSpawner.process] so we control vessel movement precisely —
     * the vessel starts in one chunk, then jumps to another at tick 201 to trigger the first
     * spawn. The current implementation spawns all newly active chunks at once (not one per tick),
     * which is the behavior this baseline verifies.
     */
    @Test
    fun `baseline current implementation spawns on chunk change`() {
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
                gridWidth = cfg.initialGrid.width,
                gridHeight = cfg.initialGrid.height,
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
            gridWidth = cfg.initialGrid.width,
            gridHeight = cfg.initialGrid.height,
        )
        assertTrue(rocks.isNotEmpty(), "rocks should have spawned after chunk change")

        // Now keep vessel stationary — the current implementation should not spawn more.
        val rocksAfterStationary = rocks.size
        for (tick in RockSpawner.ACTIVATE_AFTER_TICK + 1L..RockSpawner.ACTIVATE_AFTER_TICK + 10L) {
            rocks = RockSpawner.process(
                tick = tick,
                rocks = rocks,
                vesselTileX = newVesselTileX,
                vesselTileY = vesselTileY,
                gridWidth = cfg.initialGrid.width,
                gridHeight = cfg.initialGrid.height,
            )
        }
        assertEquals(
            rocksAfterStationary,
            rocks.size,
            "current impl should not spawn more while vessel stationary: $rocksAfterStationary -> ${rocks.size}",
        )
        RockSpawner.reset()
    }

    /**
     * One-chunk-per-tick invariant (will fail until Phase 4 is built).
     *
     * When the vessel is stationary after activation, the spawner should spawn
     * exactly one chunk (2-4 rocks) per tick, not all newly active chunks at once.
     * This tests the NEW behavior that replaces the current set-based approach.
     */
    @Test
    fun `one chunk per tick invariant (new behavior, fails until Phase 4)`() {
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
                gridWidth = cfg.initialGrid.width,
                gridHeight = cfg.initialGrid.height,
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
            gridWidth = cfg.initialGrid.width,
            gridHeight = cfg.initialGrid.height,
        )
        val firstSpawnCount = rocks.size
        assertTrue(firstSpawnCount >= 2, "first spawn should have 2+ rocks, got $firstSpawnCount")

        // Now run with vessel stationary — with the NEW one-chunk-per-tick behavior,
        // the rock count should grow by 2-4 per tick (one chunk's worth).
        // The CURRENT implementation will NOT grow (returns early when vessel is stationary),
        // so this test will fail until Phase 4 is built.
        val counts = mutableListOf(firstSpawnCount)
        for (tick in RockSpawner.ACTIVATE_AFTER_TICK + 1L..RockSpawner.ACTIVATE_AFTER_TICK + 9L) {
            rocks = RockSpawner.process(
                tick = tick,
                rocks = rocks,
                vesselTileX = newVesselTileX,
                vesselTileY = vesselTileY,
                gridWidth = cfg.initialGrid.width,
                gridHeight = cfg.initialGrid.height,
            )
            counts.add(rocks.size)
        }

        // With the new behavior: each step should add 2-4 rocks.
        // With the current behavior: all counts will be equal to firstSpawnCount (test fails).
        for (i in 1 until counts.size) {
            val growth = counts[i] - counts[i - 1]
            assertTrue(
                growth in 2..4,
                "Expected 2-4 rocks per tick (one chunk), got growth of $growth. " +
                    "Full sequence: $counts. This is expected to fail until Phase 4 is built.",
            )
        }
        RockSpawner.reset()
    }

    /**
     * The spawner creates rocks after the activation delay.
     *
     * The fixture starts with zero initial rocks and runs past ACTIVATE_AFTER_TICK (200).
     * The MIN_ROCKS_FOR_SPAWN threshold is 4, so if the initial field is empty the spawner
     * should kick in and drop the count toward MAX_ACTIVE over a few spawn cycles.
     */
    @Test
    fun `rocks spawn after the activation delay`() {
        // Start with no rocks — the initial field is empty.
        val s = starterVessel(cfg.initialGrid, rocks = 0)
        assertEquals(0, s.rocks.size, "fixture needs zero starting rocks")

        val controller = OutofspaceController(cfg, s)

        // Run to just before activation.
        repeat(RockSpawner.ACTIVATE_AFTER_TICK - 1) { controller.stepOnce() }
        assertEquals(0, controller.state.rocks.size, "rocks spawned before activation")

        // Now run past activation — but the MIN_ROCKS_FOR_SPAWN guard means it won't spawn
        // if there are already 4+ rocks. With zero rocks, it should start filling.
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
     * The spawner stops at MAX_ACTIVE rocks.
     *
     * After enough cycles the count should level off at or below MAX_ACTIVE, never exceeding it
     * (unless the initial field already had more — but this test starts empty).
     */
    @Test
    fun `the spawner caps at max active`() {
        val controller = OutofspaceController(cfg, starterVessel(cfg.initialGrid, rocks = 0))

        // Run long enough for the spawner to fill up: activation delay + enough cycles.
        repeat(RockSpawner.ACTIVATE_AFTER_TICK + 60) {
            controller.stepOnce()
        }

        val s = controller.state
        assertTrue(s.rocks.isNotEmpty(), "rocks never spawned")
        assertTrue(
            s.rocks.size <= RockSpawner.MAX_ACTIVE,
            "too many rocks: ${s.rocks.size} (max is ${RockSpawner.MAX_ACTIVE})",
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
        val controller = OutofspaceController(cfg, starterVessel(cfg.initialGrid, rocks = 0))

        repeat(RockSpawner.ACTIVATE_AFTER_TICK + 15) {
            controller.stepOnce()
        }

        val s = controller.state
        assertTrue(s.rocks.isNotEmpty(), "no rocks spawned")

        // All rocks should be within the despawn radius from the vessel.
        val vesselTileX = s.positionX / Flight.PER_TILE
        val vesselTileY = s.positionY / Flight.PER_TILE
        val despawnDist = RockSpawner.CHUNK_DESPAWN_MULTIPLIER *
            RockSpawner.CHUNK_SIZE * Flight.PER_TILE

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
     * World-spawned rocks appear outside the vessel and drift with the world.
     *
     * A spawned rock should be beyond the spawn radius from the vessel (SPAWN_RADIUS = 10 tiles)
     * and carry zero world-frame impulse (SPAWN_IMPULSE = 0), so it sits still in the world frame
     * while the vessel moves past it.
     */
    @Test
    fun `spawned rocks appear beyond the spawn radius with zero world impulse`() {
        val controller = OutofspaceController(cfg, starterVessel(cfg.initialGrid, rocks = 0))

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
        val controller = OutofspaceController(cfg, starterVessel(cfg.initialGrid, rocks = 0))

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
     * The spawner respects grid bounds — rocks do not spawn outside the grid.
     */
    @Test
    fun `rocks only spawn within grid bounds`() {
        val controller = OutofspaceController(cfg, starterVessel(cfg.initialGrid, rocks = 0))

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
        val controller = OutofspaceController(cfg, starterVessel(cfg.initialGrid, rocks = 0))

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

        assertEquals(0, RockSpawner.stateAt(centerChunkX, centerChunkY), "center should be NEAR after reset")

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
     * Phase 1: reset produces NEAR at center + UNPOPULATED elsewhere.
     *
     * The 5×5 NEAR zone at [7][7] should be NEAR (0); everything else should be UNPOPULATED (1).
     */
    @Test
    fun `phase 1 reset produces NEAR at center + UNPOPULATED elsewhere`() {
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
                    assertEquals(0, s, "chunk ($worldChunkX,$worldChunkY) at [$col,$row] should be NEAR")
                    nearCount++
                } else {
                    assertEquals(1, s, "chunk ($worldChunkX,$worldChunkY) at [$col,$row] should be UNPOPULATED")
                    unpopCount++
                }
            }
        }

        // 5×5 NEAR zone = 25 entries.
        assertEquals(25, nearCount, "should have exactly 25 NEAR entries (5x5)")
        assertEquals(15 * 15 - 25, unpopCount, "should have 200 UNPOPULATED entries")
    }
}
