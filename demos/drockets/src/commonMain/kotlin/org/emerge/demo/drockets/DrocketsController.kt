package org.emerge.demo.drockets

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TickStepper
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Local-only controller for the Drockets demo. No networking --
 * just steps the simulation each frame with empty player inputs.
 */
class DrocketsController(
    cfg: PhysicsConfig = DROCKETS_CONFIG,
) {
    private val reducer = DrocketsReducer()
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
            gravityNumerator = Frac(1, 16),
            shipCollisionDamageThreshold = Frac(1, 1024 * 8),
            shipMaxDamage = Frac(1, 256),
            shipRespawnTicks = 0,
        )

        private fun Frac(n: Long, d: Int): org.emerge.sim.core.physics.primitives.Frac =
            org.emerge.sim.core.physics.primitives.Frac(n, d)
    }
}
