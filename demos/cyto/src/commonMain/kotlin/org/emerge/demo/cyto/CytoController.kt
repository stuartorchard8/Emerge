package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState

/**
 * Local-only controller for the native (Box2D-free) Cyto demo. Drives the **struct-of-arrays**
 * [CytoSoaReducer] over a persistent [CytoWorld] — the columns mutate in place each step with no
 * per-tick `SimState` rebuild — at a fixed 1/64 rate from the host's real frame delta. Pointer
 * interactions buffer into the next step's [CytoInput] so taps/spawns are never dropped on a
 * frame that runs zero steps.
 *
 * The renderer, hit-testing, readouts, and save codec all consume an engine [SimState]; the world
 * is materialized into one via [CytoWorld.toSimState] **once per frame** (only when a step ran),
 * not per step — so multiple steps in a heavy frame share a single materialize and the per-step
 * SoA win is preserved. The SoA tick is byte-identical to the former AoS `CytoReducer` (gated by
 * `CytoSoaEquivalenceTest`), so behaviour — including save/load — is unchanged.
 */
class CytoController(
    private val cfg: CytoConfig = CytoConfig(),
) {
    // Work-stealing pool for the parallel spring solver (daemon threads on JVM/Android, no
    // shutdown needed; a no-op inline runner on JS).
    private val executor = ParallelExecutor()
    private val reducer = CytoReducer(executor = executor)
    private var tickCount = 0L
    private var accumulator = 0f

    /** The live snapshot — what the renderer / hit-test / save read. The AoS [CytoReducer] returns a
     *  fresh [SimState] each tick (the SoA structural-win path was shelved for the matter rework). */
    private var currentState: SimState = createCytoInitialState()

    private val pendingSpawns = ArrayList<CytoInput.Spawn>()
    private val pendingTaps = ArrayList<CytoInput.Tap>()
    private val pendingDetaches = ArrayList<EntityId>()
    private var currentGrab: CytoInput.Grab? = null

    val tick: Long get() = tickCount

    fun tick(deltaSeconds: Float): CytoFrame {
        accumulator += deltaSeconds.coerceIn(0f, 0.25f)
        var firstStep = true
        while (accumulator >= STEP) {
            // Spawns/taps are one-shot (consumed on the first step); the grab is continuous.
            val input = CytoInput(
                spawns = if (firstStep) pendingSpawns.toList() else emptyList(),
                taps = if (firstStep) pendingTaps.toList() else emptyList(),
                grab = currentGrab,
                detaches = if (firstStep) pendingDetaches.toList() else emptyList(),
            )
            if (firstStep) {
                pendingSpawns.clear()
                pendingTaps.clear()
                pendingDetaches.clear()
            }
            currentState = reducer.reduce(cfg, currentState, mapOf(PlayerId(0) to input))
            tickCount++
            accumulator -= STEP
            firstStep = false
        }
        return CytoFrame(currentState, tickCount)
    }

    // ── Pointer interaction (logical Cyto coordinates) ──────────────────────────

    /** The authoring "brush" genome loaded from a `.gene` file (null until loaded). */
    var brushGenome: List<org.emerge.demo.cyto.sim.Gene>? = null

    /** Whether painting uses the brush ([brushGenome]) rather than the selected type's preset — driven
     *  by the "Brush" selection in the cell-type controls. Off = type presets (the default). */
    var brushActive: Boolean = false

    private fun activeBrush() = if (brushActive) brushGenome else null

    fun spawn(x: Float, y: Float, type: CellType) {
        pendingSpawns.add(CytoInput.Spawn(x, y, type, activeBrush()))
    }

    fun tap(x: Float, y: Float, mode: TouchMode, type: CellType) {
        pendingTaps.add(CytoInput.Tap(x, y, mode, type, activeBrush()))
    }

    /** The cell whose disc contains the logical point ([x], [y]), or null. */
    fun cellAt(x: Float, y: Float): EntityId? {
        val state = currentState
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

    /** Start/continue pulling [entity] toward the logical point each tick. [sticky] makes it
     *  weld to whatever it touches while held (Sticky hold mode). */
    fun grab(entity: EntityId, x: Float, y: Float, sticky: Boolean = false) {
        currentGrab = CytoInput.Grab(entity, x, y, sticky)
    }

    fun releaseGrab() {
        currentGrab = null
    }

    /** Cut all of [entity]'s connections (Detach hold mode, on grab-start). */
    fun detach(entity: EntityId) {
        pendingDetaches.add(entity)
    }

    /** A cell's chemical readout: logical position + a multi-line "name:value" label. */
    class Readout(val x: Float, val y: Float, val text: String)

    /** Readouts for the [grabbed] cell, or for every cell when [all] (the Debug toggle). */
    fun readouts(grabbed: EntityId?, all: Boolean): List<Readout> {
        if (grabbed == null && !all) return emptyList()
        val state = currentState
        val cells = state.components.getTable<CytoCellComponent>().asMap()
        val transforms = state.components.getTable<TransformComponent>()
        val out = ArrayList<Readout>()
        for ((id, cell) in cells) {
            if (!all && id != grabbed) continue
            val transform = transforms[id] ?: continue
            // Matter readout: cytoplasm species (mobile) then biomass species (locked, prefixed *).
            val cyto = cell.cytoplasm.entries.joinToString("\n") { "${it.key}:${it.value}" }
            val bio = cell.biomass.entries.joinToString("\n") { "*${it.key}:${it.value}" }
            val text = listOf(cyto, bio).filter { it.isNotEmpty() }.joinToString("\n")
            if (text.isEmpty()) continue
            out.add(Readout(CytoUnits.toLogical(transform.pos.x), CytoUnits.toLogical(transform.pos.y), text))
        }
        return out
    }

    // ── Persistence ─────────────────────────────────────────────────────────────

    fun snapshotBytes(): ByteArray = CytoSaveCodec.encode(currentState)

    fun restoreSnapshot(bytes: ByteArray) {
        currentState = CytoSaveCodec.decode(bytes)
        tickCount = 0
        accumulator = 0f
        pendingSpawns.clear()
        pendingTaps.clear()
        pendingDetaches.clear()
        currentGrab = null
    }

    companion object {
        const val STEP = 1f / 64f
    }
}
