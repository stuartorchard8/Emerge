package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState
import kotlin.concurrent.Volatile

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
 * SoA win is preserved. The reducer's behaviour is frozen as committed golden trajectories
 * (`CytoGoldenTest`) and its invariants checked by `CytoSoaSpecTest`.
 */
class CytoController(
    private val cfg: CytoConfig = CytoConfig(),
) {
    // Work-stealing pool for the parallel spring solver (daemon threads on JVM/Android, no
    // shutdown needed; a no-op inline runner on JS).
    private val executor = ParallelExecutor()
    private val reducer = CytoSoaReducer(cfg, executor = executor)
    private var tickCount = 0L
    private var accumulator = 0f

    /** The persistent struct-of-arrays world — the columns mutate in place each step (no per-tick
     *  `SimState` rebuild). */
    private var world: CytoWorld = CytoWorld.fromSimState(createCytoInitialState())

    /** The live snapshot the renderer / hit-test / readouts / save read — materialized from [world]
     *  via [CytoWorld.toSimState] **once per frame** (single-threaded hosts) or at display cadence
     *  ([publish], threaded hosts). `@Volatile` so a separate draw thread sees a consistent immutable
     *  snapshot (it's only ever reassigned to a freshly-built SimState, never mutated in place). */
    @Volatile
    private var currentState: SimState = world.toSimState()

    /** The latest frame the draw thread reads via [latestFrame] (threaded desktop host). */
    @Volatile
    private var publishedFrame: CytoFrame = CytoFrame(currentState, 0)

    /** Guards world advancement ([stepOnce]/[tick]) against [publish]/[restoreSnapshot] so a draw-thread
     *  save/load can't swap [world] mid-step. Coarse (held across a whole tick) but only contended on the
     *  rare load. */
    private val stepLock = Any()
    /** Guards the pointer-input buffers — appended from the UI/draw thread, drained on the sim thread.
     *  Separate from [stepLock] so input is never blocked behind a heavy tick. */
    private val inputLock = Any()

    private val pendingSpawns = ArrayList<CytoInput.Spawn>()
    private val pendingTaps = ArrayList<CytoInput.Tap>()
    private val pendingDetaches = ArrayList<EntityId>()
    private var currentGrab: CytoInput.Grab? = null

    /** The most recently grabbed cell — persists past [releaseGrab] so the info panel keeps showing it
     *  until another cell is grabbed (or it dies). Null until the first grab. */
    var lastHeldId: EntityId? = null
        private set

    val tick: Long get() = tickCount

    /** Single-threaded host loop (web / android): advance the world from the real frame delta at a fixed
     *  [STEP] rate, materializing one SimState per frame. Desktop instead drives [stepOnce]/[publish] on
     *  a dedicated sim thread (see CytoSimDriver) and reads [latestFrame]. */
    fun tick(deltaSeconds: Float): CytoFrame {
        accumulator += deltaSeconds.coerceIn(0f, 0.25f)
        var firstStep = true
        var stepped = false
        while (accumulator >= STEP) {
            world = reducer.tick(world, drainInput(firstStep))
            tickCount++
            accumulator -= STEP
            firstStep = false
            stepped = true
        }
        // Materialize once per frame (only when a step ran) — multiple steps in a heavy frame share one
        // materialize, so the per-step SoA win is preserved.
        if (stepped) currentState = world.toSimState()
        return CytoFrame(currentState, tickCount).also { publishedFrame = it }
    }

    /** Drain the buffered pointer input into a [CytoInput]. Spawns/taps/detaches are one-shot (consumed
     *  when [firstStep]); the grab is continuous. Thread-safe — the buffers are written from the UI
     *  thread. */
    private fun drainInput(firstStep: Boolean): CytoInput = withLock(inputLock) {
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
        input
    }

    /** Advance the world by exactly one tick, consuming buffered input — the unit the desktop sim thread
     *  loops on at its own cadence. Does NOT materialize a SimState (call [publish] at display cadence so
     *  a fast sim isn't taxed by per-tick materialize). Thread-safe vs [publish]/[restoreSnapshot]. */
    fun stepOnce() {
        withLock(stepLock) {
            world = reducer.tick(world, drainInput(firstStep = true))
            tickCount++
        }
    }

    /** Materialize the current world into a fresh immutable [CytoFrame] for the draw thread ([latestFrame]).
     *  Call at display cadence, not every [stepOnce]. */
    fun publish() {
        withLock(stepLock) {
            currentState = world.toSimState()
            publishedFrame = CytoFrame(currentState, tickCount)
        }
    }

    /** The latest published frame for the draw thread (threaded host). */
    fun latestFrame(): CytoFrame = publishedFrame

    // ── Pointer interaction (logical Cyto coordinates) ──────────────────────────

    /** The authoring "brush" genome loaded from a `.gene` file (null until loaded). */
    var brushGenome: List<org.emerge.demo.cyto.sim.Gene>? = null

    /** Whether painting uses the brush ([brushGenome]) rather than the selected type's preset — driven
     *  by the "Brush" selection in the cell-type controls. Off = type presets (the default). */
    var brushActive: Boolean = false

    private fun activeBrush() = if (brushActive) brushGenome else null

    fun spawn(x: Float, y: Float, type: CellType) {
        withLock(inputLock) { pendingSpawns.add(CytoInput.Spawn(x, y, type, activeBrush())) }
    }

    fun tap(x: Float, y: Float, mode: TouchMode, type: CellType) {
        withLock(inputLock) { pendingTaps.add(CytoInput.Tap(x, y, mode, type, activeBrush())) }
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
        withLock(inputLock) { currentGrab = CytoInput.Grab(entity, x, y, sticky) }
        lastHeldId = entity
    }

    fun releaseGrab() {
        withLock(inputLock) { currentGrab = null }
    }

    /** Cut all of [entity]'s connections (Detach hold mode, on grab-start). */
    fun detach(entity: EntityId) {
        withLock(inputLock) { pendingDetaches.add(entity) }
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

    /** A structured snapshot of one cell, for the in-game info panel. */
    class CellInfo(
        val id: Int,
        val type: String,
        val radius: String,
        val totalBiomass: Int,
        val cytoplasm: List<Pair<String, Int>>,
        val biomass: List<Pair<String, Int>>,
        val genes: List<String>,
    )

    /** Info for the **last-held** cell (persists past release), or null if none has been held or it has
     *  since died. Read each frame by the info panel. */
    fun heldCellInfo(): CellInfo? {
        val id = lastHeldId ?: return null
        val cell = currentState.components.getTable<CytoCellComponent>().asMap()[id] ?: return null
        return CellInfo(
            id = id.value,
            type = cell.type.name,
            radius = fmt(cell.logicalRadius.toFloat()),
            totalBiomass = org.emerge.demo.cyto.sim.totalBiomassBonds(cell.biomass),
            cytoplasm = cell.cytoplasm.entries.map { it.key to it.value },
            biomass = cell.biomass.entries.map { it.key to it.value },
            genes = cell.genome.map { describeGene(it) },
        )
    }

    /** A compact, panel-friendly one-line description of a gene: `ACTION IF CONDITION [src]`. */
    private fun describeGene(gene: org.emerge.demo.cyto.sim.Gene): String {
        val a = gene.action
        val action = when (a.type) {
            org.emerge.demo.cyto.sim.ActionType.Import -> "IMPORT ${a.a}"
            org.emerge.demo.cyto.sim.ActionType.FormBond -> "BOND ${a.a}${a.b}"
            org.emerge.demo.cyto.sim.ActionType.Convert -> "CONVERT ${a.a}"
            org.emerge.demo.cyto.sim.ActionType.Expand -> "EXPAND"
            org.emerge.demo.cyto.sim.ActionType.Contract -> "CONTRACT"
            org.emerge.demo.cyto.sim.ActionType.Mitosis -> "DIVIDE"
            org.emerge.demo.cyto.sim.ActionType.Repair -> "REPAIR"
        }
        val c = gene.condition
        val cmp = if (c.cmp == org.emerge.demo.cyto.sim.Comparison.Greater) ">" else "<"
        val cond = when (c.type) {
            org.emerge.demo.cyto.sim.ConditionType.ChemQty -> "${c.species}$cmp${c.threshold}"
            org.emerge.demo.cyto.sim.ConditionType.Biomass -> "BIO$cmp${c.threshold}"
            org.emerge.demo.cyto.sim.ConditionType.Touching -> "TOUCH$cmp${c.threshold}"
        }
        val src = when (val s = gene.source) {
            org.emerge.demo.cyto.sim.EnergySource.Light -> "LIGHT"
            is org.emerge.demo.cyto.sim.EnergySource.BreakBond -> "BRK ${s.bond}"
        }
        return "$action IF $cond ($src)"
    }

    /** Fixed-point-ish 2dp formatter (multiplatform-safe — no String.format). */
    private fun fmt(v: Float): String {
        val h = (v * 100f).toInt()
        return "${h / 100}.${(kotlin.math.abs(h) % 100).toString().padStart(2, '0')}"
    }

    // ── Persistence ─────────────────────────────────────────────────────────────

    fun snapshotBytes(): ByteArray = CytoSaveCodec.encode(currentState)

    /** Replace the world from a save. Thread-safe vs the sim thread ([stepLock]) — a draw-thread F9 load
     *  can't swap [world] mid-step. */
    fun restoreSnapshot(bytes: ByteArray) {
        withLock(stepLock) {
            world = CytoWorld.fromSimState(CytoSaveCodec.decode(bytes))
            tickCount = 0
            accumulator = 0f
            currentState = world.toSimState()
            publishedFrame = CytoFrame(currentState, 0)
            withLock(inputLock) {
                pendingSpawns.clear()
                pendingTaps.clear()
                pendingDetaches.clear()
                currentGrab = null
            }
        }
    }

    companion object {
        const val STEP = 1f / 64f
    }
}
