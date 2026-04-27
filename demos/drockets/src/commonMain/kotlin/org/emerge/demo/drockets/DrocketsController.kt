package org.emerge.demo.drockets

import org.emerge.sim.core.TickStepper
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Local-only controller for the Drockets demo. No networking --
 * just steps the simulation each frame with empty player inputs.
 *
 * Isolated pipeline phases are dispatched across [executor]. On JVM/Android this is
 * a work-stealing [ParallelExecutor] (daemon threads, no shutdown required); on JS
 * it's a no-op that runs tasks inline.
 */
class DrocketsController(
    cfg: PhysicsConfig = DROCKETS_CONFIG,
) {
    private val executor = ParallelExecutor()
    private val reducer = DrocketsReducer(executor)
    private val stepper = TickStepper(
        cfg = cfg,
        initialState = createDrocketsInitialState(),
        reducer = reducer,
    )

    fun tick(): DrocketsFrame {
        stepper.step(emptyMap())
        return DrocketsFrame(
            state = stepper.state,
            tick = stepper.tick.value,
        )
    }

    companion object {
        val DROCKETS_CONFIG = PhysicsConfig(
            thrustFactorInv = Int.MAX_VALUE / (1024 * 16),
            turnFactorInv = Int.MAX_VALUE / (1024 * 512),
            gravityNumerator = Frac(1, 2 shl 10),
            collisionSpeedDamageThreshold = Frac(1, 1 shl 12),
            maxDamage = Frac(1),
            respawnTicks = -1, // Respawn is disabled
        )
    }
}
