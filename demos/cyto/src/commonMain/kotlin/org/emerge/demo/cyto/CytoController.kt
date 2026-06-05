package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.TickStepper
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState

/**
 * Local-only controller for the native (Box2D-free) Cyto demo. Owns a [TickStepper] over
 * the [CytoReducer], steps it at a fixed 1/64 rate from the host's real frame delta, and
 * buffers pointer interactions into the next step's [CytoInput] so taps/spawns are never
 * dropped on a frame that runs zero steps.
 */
class CytoController(
    private val cfg: CytoConfig = CytoConfig(),
) {
    private val reducer = CytoReducer()
    private val stepper = TickStepper(cfg, createCytoInitialState(), reducer)
    private var accumulator = 0f

    private val pendingSpawns = ArrayList<CytoInput.Spawn>()
    private val pendingTaps = ArrayList<CytoInput.Tap>()
    private var currentGrab: CytoInput.Grab? = null

    val tick: Long get() = stepper.tick.value

    fun tick(deltaSeconds: Float): CytoFrame {
        accumulator += deltaSeconds.coerceIn(0f, 0.25f)
        var firstStep = true
        while (accumulator >= STEP) {
            // Spawns/taps are one-shot (consumed on the first step); the grab is continuous.
            val input = CytoInput(
                spawns = if (firstStep) pendingSpawns.toList() else emptyList(),
                taps = if (firstStep) pendingTaps.toList() else emptyList(),
                grab = currentGrab,
            )
            if (firstStep) {
                pendingSpawns.clear()
                pendingTaps.clear()
            }
            stepper.step(mapOf(PlayerId(0) to input))
            accumulator -= STEP
            firstStep = false
        }
        return CytoFrame(stepper.state, stepper.tick.value)
    }

    // ── Pointer interaction (logical Cyto coordinates) ──────────────────────────

    fun spawn(x: Float, y: Float, type: CellType) {
        pendingSpawns.add(CytoInput.Spawn(x, y, type))
    }

    fun tap(x: Float, y: Float, mode: TouchMode, type: CellType) {
        pendingTaps.add(CytoInput.Tap(x, y, mode, type))
    }

    /** The cell whose disc contains the logical point ([x], [y]), or null. */
    fun cellAt(x: Float, y: Float): EntityId? {
        val state = stepper.state
        val transforms = state.components.getTable<TransformComponent>()
        val colliders = state.components.getTable<ColliderComponent>()
        for (id in state.components.getTable<CytoCellComponent>().keys()) {
            val transform = transforms[id] ?: continue
            val radius = colliders[id]?.radius ?: continue
            val dx = CytoUnits.toLogical(transform.pos.x) - x
            val dy = CytoUnits.toLogical(transform.pos.y) - y
            val r = CytoUnits.toLogical(radius)
            if (dx * dx + dy * dy < r * r) return id
        }
        return null
    }

    /** Start/continue pulling [entity] toward the logical point each tick. */
    fun grab(entity: EntityId, x: Float, y: Float) {
        currentGrab = CytoInput.Grab(entity, x, y)
    }

    fun releaseGrab() {
        currentGrab = null
    }

    // ── Persistence ─────────────────────────────────────────────────────────────

    fun snapshotBytes(): ByteArray = CytoSaveCodec.encode(stepper.state)

    fun restoreSnapshot(bytes: ByteArray) {
        stepper.reset(CytoSaveCodec.decode(bytes), Tick(0))
        accumulator = 0f
        pendingSpawns.clear()
        pendingTaps.clear()
        currentGrab = null
    }

    companion object {
        const val STEP = 1f / 64f
    }
}
