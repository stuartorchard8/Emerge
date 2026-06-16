package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoExposure
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.Molecules
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.demo.cyto.sim.handleableOf
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
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
        reducer.noMutateEntityId = entity.value   // freeze the focused (inspected) cell against mutation
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
        /** Light the cell actually captures this tick, as the energy **quanta** it powers actions with
         *  (ambient field × surface exposure × LIGHT_QUANTA_SCALE — the same value the sim spends), with the
         *  % of peak in parens. Quanta is the honest energy budget; the % alone hid how large it was. */
        val light: String,
        /** Per-species metabolism table: environment (local reservoir) | cytoplasm | biomass, with a flow
         *  arrow on each boundary. Only metabolically-relevant species (handleable, or stored in biomass). */
        val metabolism: List<MetRow>,
        val genes: List<String>,
    ) {
        /** One row of the metabolism table. [dirEnvCyt] is the env↔cytoplasm flow, [dirCytBio] the
         *  cytoplasm↔biomass flow; each is ">>" (toward bio/into cyt), "<<" (out) or "==" (no net flow). */
        class MetRow(
            val species: String, val env: Int, val cyto: Int, val bio: Int,
            val dirEnvCyt: String, val dirCytBio: String,
        )
    }

    /** Info for the **last-held** cell (persists past release), or null if none has been held or it has
     *  since died. Read each frame by the info panel. */
    fun heldCellInfo(): CellInfo? {
        val id = lastHeldId ?: return null
        val state = currentState
        val cell = state.components.getTable<CytoCellComponent>().asMap()[id] ?: return null
        val transforms = state.components.getTable<TransformComponent>()
        val pos = transforms[id]?.pos
        // The light the cell actually CAPTURES = ambient field × surface exposure (how much of its surface
        // isn't buried by connected neighbours), as % of peak — what the cell's energy depends on, not the
        // raw field. Exposure replicates CytoExposure (diamond-angle of each neighbour delta); lone = full.
        val light: String = if (pos == null) "?" else {
            val sample = CytoLightField.default().sampleAt(CytoUnits.toLogical(pos.x), CytoUnits.toLogical(pos.y), state.tick)
            val angles = LongArray(CytoExposure.MAX_NEIGHBOURS)
            var ek = 0
            for (sp in state.components.getTable<SpringConstraintComponent>()[id]?.springs.orEmpty()) {
                if (ek >= CytoExposure.MAX_NEIGHBOURS) break
                val np = transforms[sp.other]?.pos ?: continue
                val d = np - pos
                angles[ek++] = CytoExposure.diamondAngle(d.x, d.y).raw
            }
            val exposure = CytoExposure.weight(angles, ek)
            // Quanta = the actual per-tick energy the cell powers actions with (matches CytoSoaReducer's
            // light calc: (field × exposure × SCALE).raw / Int.MAX). Pre-shading (a co-located crowd splits
            // it); it's the cell's gross capture. The % of peak follows it for context.
            val quanta = (((sample * exposure) * CytoTuning.LIGHT_QUANTA_SCALE).raw / Int.MAX_VALUE.toLong()).toInt()
            val pct = (sample.toFloat() * exposure.toFloat() / CytoTuning.LIGHT_STRENGTH.toFloat() * 100f).coerceIn(0f, 100f).toInt()
            "$quanta q ($pct%)"
        }
        // Local reservoir contents (the grid-cell this cell sits in).
        val envMap: Map<String, Int> = run {
            val grid = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid
            if (grid == null || pos == null) emptyMap()
            else grid.cellAt(grid.indexOf(CytoUnits.toLogical(pos.x), CytoUnits.toLogical(pos.y)))
        }
        // Predicted matter flows for the two boundaries (like the env↔cyt arrow, derived from the cell's
        // genome + state, not measured). Convert genes build their operand cyt→bio; degradation breaks the
        // lex-smallest multi-atom biomass molecule, sending its leading monomer to the reservoir and the
        // remainder to cytoplasm — so that molecule + both fragments flow OUT of biomass (and the monomer
        // out to env). Anything the cell can't use is hidden as ballast.
        val cytoMap = cell.cytoplasm; val bioMap = cell.biomass
        val handleable = handleableOf(cell.genome)
        val convertOperands = cell.genome.filter { it.action.type == ActionType.Convert }.map { it.action.a }.toSet()
        val degradeTarget = bioMap.entries.filter { it.value > 0 && it.key.length >= 2 }.minByOrNull { it.key }?.key
        val degradeSplit = degradeTarget?.let { Molecules.splitLeftmost(it) }
        val degradeMono = degradeSplit?.first       // ejected to the reservoir
        val degradeRest = degradeSplit?.second       // returned to cytoplasm
        // Approx degradation rate (broken bonds / tick) — wear gains total-biomass-bonds each tick and breaks
        // one per DEGRADE_PERIOD, so steady-state ≈ bonds / period; ≥1 while there's anything to degrade.
        val degRate = if (degradeTarget == null) 0
            else maxOf(1, org.emerge.demo.cyto.sim.totalBiomassBonds(cell.biomass) / CytoTuning.DEGRADE_PERIOD)
        val metabolism = (cytoMap.keys + bioMap.keys + envMap.keys).sorted().mapNotNull { s ->
            val env = envMap[s] ?: 0; val cyto = cytoMap[s] ?: 0; val bio = bioMap[s] ?: 0
            val canHold = handleable.canHold(SpeciesRegistry.id(s))
            if (!canHold && bio == 0) return@mapNotNull null
            val dirCytBio = when {
                s in convertOperands -> ">>"                                  // Convert builds it into biomass
                s == degradeTarget || s == degradeMono || s == degradeRest -> "<<"  // degradation output (or the consumed molecule)
                else -> "=="
            }
            // env↔cyt is a SIGNED SUM: passive exchange (absorb usable when the reservoir's richer, leak
            // un-usable surplus) plus the degradation monomer ejected to the reservoir. So a monomer that's
            // abundant outside still reads ">>" (net drawn in) even while degradation trickles some out.
            val passive = when {
                canHold && env > cyto -> (env - cyto) / 2
                !canHold && cyto > env -> -(cyto - env) / 2
                else -> 0
            }
            val envCytNet = passive - (if (s == degradeMono) degRate else 0)
            val dirEnvCyt = when {
                envCytNet > 0 -> ">>"
                envCytNet < 0 -> "<<"
                else -> "=="
            }
            CellInfo.MetRow(s, env, cyto, bio, dirEnvCyt, dirCytBio)
        }
        return CellInfo(
            id = id.value,
            type = cell.type.name,
            radius = fmt(cell.logicalRadius.toFloat()),
            totalBiomass = org.emerge.demo.cyto.sim.totalBiomassBonds(cell.biomass),
            light = light,
            metabolism = metabolism,
            genes = cell.genome.map { describeGene(it) },
        )
    }

    // ── Live gene editing (the in-game gene-editor kit) ─────────────────────────
    // Edits target the last-held cell. The world is mutated under [stepLock] (so the sim thread can't be
    // mid-tick) and republished immediately, so the change shows even when paused. The editor holds the
    // in-progress draft itself and commits via [setHeldGene] on close — these are the only live mutations.

    /** The last-held cell's current genome (a fresh immutable list), or null if none / it died. */
    fun heldGenome(): List<Gene>? =
        lastHeldId?.let { currentState.components.getTable<CytoCellComponent>().asMap()[it]?.genome }

    /** Apply [transform] to the held cell's genome (returning null = no change), then republish. */
    private fun editHeldGenome(transform: (List<Gene>) -> List<Gene>?) {
        val id = lastHeldId ?: return
        withLock(stepLock) {
            val slot = world.slotOf(id.value)
            val next = if (slot < 0) null else transform(world.cell.genome[slot] ?: emptyList())
            if (next != null) {
                world.cell.genome[slot] = next
                currentState = world.toSimState()
                publishedFrame = CytoFrame(currentState, tickCount)
            }
        }
    }

    /** Replace the gene at [index] (commit a finished edit). */
    fun setHeldGene(index: Int, gene: Gene) =
        editHeldGenome { g -> if (index in g.indices) g.toMutableList().also { it[index] = gene } else null }

    /** Delete the gene at [index]. */
    fun deleteHeldGene(index: Int) =
        editHeldGenome { g -> if (index in g.indices) g.toMutableList().also { it.removeAt(index) } else null }

    /** Insert a copy of the gene at [index] immediately after it. */
    fun duplicateHeldGene(index: Int) =
        editHeldGenome { g -> if (index in g.indices) g.toMutableList().also { it.add(index + 1, g[index]) } else null }

    /** A compact, panel-friendly one-line description of a gene: `ACTION IF CONDITION [src]`. */
    private fun describeGene(gene: org.emerge.demo.cyto.sim.Gene): String {
        val a = gene.action
        val action = when (a.type) {
            org.emerge.demo.cyto.sim.ActionType.Import -> "IMPORT ${a.a}"
            // FormBond uses only the FIRST atom of each operand (the rest is ignored — a mutation can drop a
            // multi-atom species in, but only a[0]/b[0] matter), so show just the two operative atoms.
            org.emerge.demo.cyto.sim.ActionType.FormBond -> "BOND ${a.a.take(1)}${a.b.take(1)}"
            org.emerge.demo.cyto.sim.ActionType.Convert -> "CONVERT ${a.a}"
            org.emerge.demo.cyto.sim.ActionType.Contract -> "CONTRACT"
            org.emerge.demo.cyto.sim.ActionType.Mitosis -> "DIVIDE"
            org.emerge.demo.cyto.sim.ActionType.Repair -> "REPAIR"
        }
        val c = gene.condition
        val cmp = if (c.cmp == org.emerge.demo.cyto.sim.Comparison.Greater) ">" else "<"
        val cond = "${operandLabel(c.lhs)}$cmp${operandLabel(c.rhs)}"
        val src = when (val s = gene.source) {
            org.emerge.demo.cyto.sim.EnergySource.Light -> "LIGHT"
            is org.emerge.demo.cyto.sim.EnergySource.BreakBond -> "BRK ${s.bond}"
        }
        val eff = if (gene.efficiency != 0) " e${gene.efficiency}" else ""   // efficiency gear (throughput actions)
        return "$action IF $cond ($src)$eff"
    }

    /** Panel label for one condition operand: a constant's number, `BIO`/`TOUCH` for the live readings,
     *  or the species token for a cytoplasm count. */
    private fun operandLabel(op: org.emerge.demo.cyto.sim.Operand): String = when (op) {
        is org.emerge.demo.cyto.sim.Operand.Constant -> op.value.toString()
        is org.emerge.demo.cyto.sim.Operand.Chem -> op.species.ifEmpty { "?" }
        org.emerge.demo.cyto.sim.Operand.Biomass -> "BIO"
        org.emerge.demo.cyto.sim.Operand.Touching -> "TOUCH"
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
            // Resume the sim clock from the save (the codec persists it) rather than zeroing it — the clock
            // drives the moving day/night band, both in the sim (reducer samples world.tick) and on screen
            // (renderer bakes the band at frame.tick), so a reset would snap the cycle back AND desync the
            // displayed band from the actual light.
            tickCount = world.world.tick
            accumulator = 0f
            currentState = world.toSimState()
            publishedFrame = CytoFrame(currentState, tickCount)
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
