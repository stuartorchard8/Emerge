package org.emerge.demo.drockets.soa

import org.emerge.demo.drockets.DrocketsConfig
import org.emerge.demo.drockets.DrocketsInput
import org.emerge.demo.drockets.DrocketsReducer
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.systems.IntegrationSystem
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import org.emerge.demo.drockets.createDrocketsInitialState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-phase bit-identity gate for the incremental drockets SoA reducer: each ported phase must
 * produce byte-identical output to its array-of-structs system when run in isolation on the same
 * real mid-simulation state. This is the unit of progress — a phase is only "ported" once it
 * passes here. As phases accrue, the full reducer is their composition.
 */
class DrocketsSoaPhaseEquivalenceTest {

    private val cfg = DrocketsConfig()

    /** A real, busy state: past maturity so phases/landings/impulses are all populated. */
    private fun busyState(): SimState {
        val reducer = DrocketsReducer()
        val inputs = mapOf(PlayerId(0) to DrocketsInput)
        var s = createDrocketsInitialState()
        repeat(700) { s = reducer.reduce(cfg, s, inputs) }
        return s
    }

    @Test
    fun integrateMatchesEngineIntegrationSystem() {
        val state = busyState()

        // Array-of-structs: run IntegrationSystem alone.
        val builder = SimBuilder(state)
        IntegrationSystem.update(cfg, builder, mapOf(PlayerId(0) to DrocketsInput))
        val aos = builder.build()

        // SoA: run the ported integrate in place on a DrocketsWorld loaded from the same state.
        val world = DrocketsWorld.fromSimState(state)
        DrocketsSoaReducer().integrate(world)
        val soa = world.toSimState()

        // Integration only rewrites Transform + Motion; both must match bit-for-bit.
        assertEquals(
            aos.components.getTable<TransformComponent>().asMap(),
            soa.components.getTable<TransformComponent>().asMap(),
            "TransformComponent diverged after integrate",
        )
        assertEquals(
            aos.components.getTable<MotionComponent>().asMap(),
            soa.components.getTable<MotionComponent>().asMap(),
            "MotionComponent diverged after integrate",
        )
        // Sanity: integration actually moved bodies (impulses + velocities were present).
        assertTrue(
            aos.components.getTable<TransformComponent>().asMap() != state.components.getTable<TransformComponent>().asMap(),
            "expected integration to change transforms",
        )
    }
}
