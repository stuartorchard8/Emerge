package org.emerge.demo.drockets

import org.emerge.sim.core.TickStepper
import org.emerge.sim.core.ecs.ParallelExecutor
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
    cfg: DrocketsConfig = DROCKETS_CONFIG,
) {
    private val executor = ParallelExecutor()
    private val reducer = DrocketsReducer(executor)
    private val stepper = TickStepper(
        cfg = cfg,
        initialState = createDrocketsInitialState(),
        reducer = reducer,
    )
    private var lineageState = DrocketLineageState.EMPTY
        .advanceFromPhysics(stepper.state, stepper.tick.value)
    private val cladogramLayoutMemo = CladogramLayoutMemo()

    fun tick(): DrocketsFrame {
        stepPhysics()
        advanceLineageFromPhysics()
        return currentFrame()
    }

    /** Step the physics simulation one tick. Exposed (alongside
     *  [advanceLineageFromPhysics] / [currentFrame]) so benchmarks can time
     *  the phases independently — they're internal-but-public for
     *  cross-module benchmarking, not part of the gameplay API. */
    fun stepPhysics() {
        stepper.step(emptyMap())
    }

    /** Apply current physics state to the lineage. This is where the O(n)
     *  `LinkedHashMap(this.nodes)` copy happens — scales with total nodes
     *  ever born, not living drockets. */
    fun advanceLineageFromPhysics() {
        lineageState = lineageState.advanceFromPhysics(stepper.state, stepper.tick.value)
    }

    /** Build the frame from the current stepper + lineage state. */
    fun currentFrame(): DrocketsFrame = DrocketsFrame(
        state = stepper.state,
        lineage = lineageState,
        cladogramLayout = cladogramLayoutMemo.get(lineageState),
        tick = stepper.tick.value,
    )

    fun snapshotBytes(): ByteArray =
        DrocketsSaveCodec.encode(
            DrocketsSnapshot(
                tick = stepper.tick,
                state = stepper.state,
                lineage = lineageState,
            )
        )

    fun restoreSnapshot(bytes: ByteArray) {
        val snapshot = DrocketsSaveCodec.decode(bytes)
        stepper.reset(snapshot.state, snapshot.tick)
        lineageState = snapshot.lineage
        cladogramLayoutMemo.reset()
    }

    companion object {
        val DROCKETS_CONFIG = DrocketsConfig(
            gravityNumerator = Frac(1, 1 shl 11),
            collisionSpeedDamageThreshold = Frac(1, 1 shl 20),
        )
    }
}
