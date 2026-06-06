package org.emerge.demo.drockets.soa

import org.emerge.demo.drockets.AtmosphereDragSystem
import org.emerge.demo.drockets.DrocketsConfig
import org.emerge.demo.drockets.DrocketsInput
import org.emerge.demo.drockets.DrocketsReducer
import org.emerge.demo.drockets.createDrocketsInitialState
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.Phase
import org.emerge.sim.core.ecs.isolated
import org.emerge.sim.core.ecs.runSequential
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.systems.GravitySystem
import org.emerge.sim.core.physics.systems.ImpulseResetSystem
import org.emerge.sim.core.physics.systems.IntegrationSystem
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-phase bit-identity gate for the incremental drockets SoA reducer: each ported phase must
 * produce byte-identical output to its array-of-structs system when run in isolation on the same
 * real mid-simulation state. A phase is only "ported" once it passes here.
 */
class DrocketsSoaPhaseEquivalenceTest {

    private val cfg = DrocketsConfig()
    private val inputs = mapOf(PlayerId(0) to DrocketsInput)

    /** A real, busy state: past maturity so phases/landings/impulses are all populated. */
    private fun busyState(): SimState {
        val reducer = DrocketsReducer()
        var s = createDrocketsInitialState()
        repeat(700) { s = reducer.reduce(cfg, s, inputs) }
        return s
    }

    /** Non-zero impulses keyed by entity (absent ≡ zero); the dense accumulator and the AoS
     *  sparse table agree only after dropping zero entries. */
    private fun nonZeroImpulses(state: SimState): Map<EntityId, ImpulseComponent> =
        state.components.getTable<ImpulseComponent>().asMap().filterValues { it != ImpulseComponent() }

    @Test
    fun forceGatherMatchesEngineGravityAndAtmosphere() {
        val state = busyState()

        // Array-of-structs: reset (empty Impulse) then the isolated force phase, exactly as the
        // real reducer composes them.
        val pipeline = listOf(
            Phase("reset", ImpulseResetSystem),
            Phase("forceGather", GravitySystem(), AtmosphereDragSystem).isolated(),
        )
        val builder = SimBuilder(state)
        runSequential(cfg, builder, inputs, pipeline)
        val aos = builder.build()

        // SoA: reset then forceGather in place.
        val world = DrocketsWorld.fromSimState(state)
        val reducer = DrocketsSoaReducer(cfg)
        reducer.reset(world)
        reducer.forceGather(world)
        val soa = world.toSimState()

        assertEquals(nonZeroImpulses(aos), nonZeroImpulses(soa), "impulses diverged after reset+forceGather")
        assertTrue(nonZeroImpulses(aos).isNotEmpty(), "expected gravity/drag to produce impulses")
    }

    @Test
    fun integrateMatchesEngineIntegrationSystem() {
        val state = busyState()

        val builder = SimBuilder(state)
        IntegrationSystem.update(cfg, builder, inputs)
        val aos = builder.build()

        val world = DrocketsWorld.fromSimState(state)
        DrocketsSoaReducer(cfg).integrate(world)
        val soa = world.toSimState()

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
        assertTrue(
            aos.components.getTable<TransformComponent>().asMap() != state.components.getTable<TransformComponent>().asMap(),
            "expected integration to change transforms",
        )
    }
}
