package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.TickStepper
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

    val tick: Long get() = stepper.tick.value

    fun tick(deltaSeconds: Float): CytoFrame {
        accumulator += deltaSeconds.coerceIn(0f, 0.25f)
        while (accumulator >= STEP) {
            stepper.step(mapOf(PlayerId(0) to drainInput()))
            accumulator -= STEP
        }
        return CytoFrame(stepper.state, stepper.tick.value)
    }

    private fun drainInput(): CytoInput {
        if (pendingSpawns.isEmpty() && pendingTaps.isEmpty()) return CytoInput.EMPTY
        val input = CytoInput(pendingSpawns.toList(), pendingTaps.toList())
        pendingSpawns.clear()
        pendingTaps.clear()
        return input
    }

    // ── Pointer interaction (logical Cyto coordinates) ──────────────────────────

    fun spawn(x: Float, y: Float, type: CellType) {
        pendingSpawns.add(CytoInput.Spawn(x, y, type))
    }

    fun tap(x: Float, y: Float, mode: TouchMode, type: CellType) {
        pendingTaps.add(CytoInput.Tap(x, y, mode, type))
    }

    // ── Persistence ─────────────────────────────────────────────────────────────

    fun snapshotBytes(): ByteArray = CytoSaveCodec.encode(stepper.state)

    fun restoreSnapshot(bytes: ByteArray) {
        stepper.reset(CytoSaveCodec.decode(bytes), Tick(0))
        accumulator = 0f
        pendingSpawns.clear()
        pendingTaps.clear()
    }

    companion object {
        const val STEP = 1f / 64f
    }
}
