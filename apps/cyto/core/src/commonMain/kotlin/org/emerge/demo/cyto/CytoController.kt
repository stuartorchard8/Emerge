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
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.CytoTuning
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Operand
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
    /** The starting-world recipe. **[CytoWorldConfig] must already reflect this** (call
     *  `CytoWorldConfig.applyFrom(scenario)` before constructing) — the reducer sizes per-tile buffers from the
     *  world size at construction, which happens before the initial world is built below. */
    scenario: org.emerge.demo.cyto.sim.CytoScenario = org.emerge.demo.cyto.sim.CytoScenario.DEFAULT,
) {
    // Work-stealing pool for the parallel spring solver (daemon threads on JVM/Android, no
    // shutdown needed; a no-op inline runner on JS).
    private val executor = ParallelExecutor()
    private var reducer = CytoSoaReducer(cfg, executor = executor)
    private var tickCount = 0L
    private var accumulator = 0f

    /** The persistent struct-of-arrays world — the columns mutate in place each step (no per-tick
     *  `SimState` rebuild). */
    private var world: CytoWorld = CytoWorld.fromSimState(createCytoInitialState(scenario))

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

    /** True while the user is actively dragging/grabbing a cell. */
    val isGrabbed: Boolean get() = currentGrab != null

    /** The most recently grabbed cell — persists past [releaseGrab] so the info panel keeps showing it
     *  until another cell is grabbed (or it dies). Null until the first grab. */
    var lastHeldId: EntityId? = null

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
        return CytoFrame(currentState, tickCount, world.getSpringData()).also { publishedFrame = it }
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

    /** Tear down the current world and build a fresh one from [scenario] — the title-screen *New* path.
     *  Applies the scenario geometry to [org.emerge.demo.cyto.sim.CytoWorldConfig] and rebuilds the reducer
     *  (its per-tile buffers are sized from the world size) and the world. Thread-safe vs the sim thread;
     *  pause the driver around it so no half-torn step runs. */
    fun newGame(scenario: org.emerge.demo.cyto.sim.CytoScenario) {
        withLock(stepLock) {
            org.emerge.demo.cyto.sim.CytoWorldConfig.applyFrom(scenario)
            reducer = CytoSoaReducer(cfg, executor = executor)
            world = CytoWorld.fromSimState(createCytoInitialState(scenario))
            tickCount = 0L
            accumulator = 0f
            currentState = world.toSimState()
            publishedFrame = CytoFrame(currentState, tickCount)
            lastHeldId = null
            reducer.noMutateEntityId = -1
            withLock(inputLock) {
                pendingSpawns.clear(); pendingTaps.clear(); pendingDetaches.clear(); currentGrab = null
            }
        }
    }

    // ── Pointer interaction (logical Cyto coordinates) ──────────────────────────

    /** The authoring "brush" genome loaded from a `.gene` file (null until loaded). */
    var brushGenome: List<Gene>? = null

    private fun activeBrush() = brushGenome

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

    /** Focus a cell without grabbing it — sets [lastHeldId] and freezes mutation so the info panel
     *  can display it. Used when the user clicks (not drags) a cell. */
    fun focus(entity: EntityId) {
        lastHeldId = entity
        reducer.noMutateEntityId = entity.value
    }

    /** Clear the selection: the info panel closes and the previously-focused cell resumes mutating.
     *  Unlike [releaseGrab] (which only ends the current drag), this drops [lastHeldId] entirely. */
    fun clearSelection() {
        lastHeldId = null
        reducer.noMutateEntityId = -1   // unfreeze: no cell is exempt from natural mutation
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
        val genes: List<GeneRow>,
    ) {
        /** One row of the metabolism table. [dirEnvCyt] is the env↔cytoplasm flow, [dirCytBio] the
         *  cytoplasm↔biomass flow; each is ">>" (toward bio/into cyt), "<<" (out) or "==" (no net flow). */
        class MetRow(
            val species: String, val env: Int, val cyto: Int, val bio: Int,
            val dirEnvCyt: String, val dirCytBio: String,
        )

        /** One gene as the panel sees it: [desc] plain text, whether it would FIRE this tick ([active]), and
         *  [spans] — the description split into coloured segments with the blocking parts flagged. */
        class GeneRow(val desc: String, val active: Boolean, val spans: List<Span>)

        /** A run of a gene's description text; [blocking] ⇒ this part is currently keeping the gene from
         *  firing (a failed condition clause, the energy source with no energy, or a missing action input). */
        class Span(val text: String, val blocking: Boolean)
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
        var capturedQuanta = 0   // lifted out of [light] so the gene-activity diagnosis can read it
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
            capturedQuanta = quanta
            val pct = (sample.toFloat() * exposure.toFloat() / CytoTuning.LIGHT_STRENGTH.toFloat() * 100f).coerceIn(0f, 100f).toInt()
            "$quanta q ($pct%)"
        }
        // Local reservoir contents (the grid-cell this cell sits in).
        val envMap: Map<String, Int> = run {
            val grid = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid
            if (grid == null || pos == null) emptyMap()
            else grid.contentsAt(CytoUnits.toLogical(pos.x), CytoUnits.toLogical(pos.y))
        }
        // Predicted matter flows for the two boundaries (like the env↔cyt arrow, derived from the cell's
        // genome + state, not measured). Convert genes build their operand cyt→bio; degradation breaks the
        // most-abundant multi-atom biomass molecule (ties → lex-smallest), sending its leading monomer to the
        // reservoir and the remainder to cytoplasm — so that molecule + both fragments flow OUT of biomass (and
        // the monomer out to env). Anything the cell can't use is hidden as ballast.
        val cytoMap = cell.cytoplasm; val bioMap = cell.biomass
        val handleable = handleableOf(cell.genome)
        val convertOperands = cell.genome.filter { it.action.type == ActionType.Convert }.map { it.action.a }.toSet()
        val degradeTarget = bioMap.entries.filter { it.value > 0 && it.key.length >= 2 }
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key
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
            // Capped to the collision radius (what the cell physically is); biomass can drive logicalRadius
            // past this but the footprint doesn't grow — so SIZE matches what's rendered/collides.
            radius = fmt(cell.logicalRadius.coerceAtMost(CytoTuning.MAX_COLLISION_RADIUS).toFloat()),
            totalBiomass = org.emerge.demo.cyto.sim.totalBiomassBonds(cell.biomass),
            light = light,
            metabolism = metabolism,
            genes = cell.genome.map { g ->
                val spans = describeGeneSpans(g, cytoMap, envMap, totalBiomass = org.emerge.demo.cyto.sim.totalBiomassBonds(cell.biomass), quanta = capturedQuanta)
                CellInfo.GeneRow(desc = spans.joinToString("") { it.text }, active = spans.none { it.blocking }, spans = spans)
            },
        )
    }

    /** The gene's panel description as coloured [CellInfo.Span]s — action, condition clauses, energy source —
     *  with each part flagged [CellInfo.Span.blocking] when it's what stops the gene firing this tick (a failed
     *  clause, no energy, or a missing action input). Mirrors [org.emerge.demo.cyto.sim.CytoBiologyCore]'s
     *  gating read from the panel snapshot — approximate: `Touching` is transient (read as 0) and the per-gene
     *  1/N energy split isn't modelled, so it shows *what's* blocking, not the exact op count. */
    private fun describeGeneSpans(g: Gene, cyto: Map<String, Int>, env: Map<String, Int>, totalBiomass: Int, quanta: Int): List<CellInfo.Span> {
        val touch = 0
        fun eval(op: Operand): Int = when (op) {
            is Operand.Constant -> op.value
            is Operand.Chem -> cyto[op.species] ?: 0
            is Operand.Conc -> if (totalBiomass > 0) ((cyto[op.species] ?: 0).toLong() * CytoTuning.CONC_SCALE / totalBiomass).toInt() else 0
            Operand.Biomass -> totalBiomass
            Operand.Touching -> touch
        }
        fun clauseFails(c: org.emerge.demo.cyto.sim.Clause): Boolean {
            val l = eval(c.lhs); val r = eval(c.rhs)
            return if (c.cmp == Comparison.Greater) l <= r else l >= r
        }
        val energyBlocked = when (val s = g.source) {
            EnergySource.Light -> quanta <= 0
            is EnergySource.BreakBond -> cyto.none { (sp, n) -> n > 0 && sp.contains(s.bond) }
        }
        val a = g.action
        val inputBlocked = when (a.type) {
            ActionType.FormBond -> a.a.isNotEmpty() && a.b.isNotEmpty() && run {
                val haveA = if (a.aWild) cyto.any { (sp, n) -> n > 0 && sp.endsWith(a.a) } else (cyto[a.a] ?: 0) > 0
                val haveB = if (a.bWild) cyto.any { (sp, n) -> n > 0 && sp.startsWith(a.b) } else (cyto[a.b] ?: 0) > 0
                when {
                    !a.aWild && !a.bWild && a.a == a.b -> (cyto[a.a] ?: 0) < 2 || Molecules.join(a.a, a.b) == null  // homodimer needs 2 + a legal product
                    !haveA || !haveB -> true
                    !a.aWild && !a.bWild -> Molecules.join(a.a, a.b) == null                                       // both present but the product repeats a bond
                    else -> false
                }
            }
            ActionType.Convert -> (cyto[a.a] ?: 0) <= 0
            ActionType.Import -> (env[a.a] ?: 0) <= 0
            ActionType.Export -> (cyto[a.a] ?: 0) <= 0
            else -> false
        }
        val spans = mutableListOf<CellInfo.Span>()
        spans += CellInfo.Span(actionLabel(a), inputBlocked)
        spans += CellInfo.Span(" IF ", false)
        g.condition.clauses.forEachIndexed { i, c ->
            if (i > 0) spans += CellInfo.Span(" & ", false)
            spans += CellInfo.Span(clauseStr(c), clauseFails(c))
        }
        spans += CellInfo.Span(" (", false)
        spans += CellInfo.Span(srcLabel(g.source), energyBlocked)
        spans += CellInfo.Span(")", false)
        if (g.efficiency != 0) spans += CellInfo.Span(" e${g.efficiency}", false)
        return spans
    }

    // ── Live gene editing (the in-game gene-editor kit) ─────────────────────────
    // Edits target the last-held cell. The world is mutated under [stepLock] (so the sim thread can't be
    // mid-tick) and republished immediately, so the change shows even when paused. The editor holds the
    // in-progress draft itself and commits via [setHeldGene] on close — these are the only live mutations.

    /** The last-held cell's current genome (a fresh immutable list), or null if none / it died. */
    /** World-space position of the focused/held cell, or null if no cell is focused or it has died. */
    fun heldCellPosition(): Pair<Float, Float>? {
        val id = lastHeldId ?: return null
        val transform = currentState.components.getTable<TransformComponent>()[id]
        if (transform == null) {
            lastHeldId = null
            reducer.noMutateEntityId = -1
            return null
        }
        return CytoUnits.toLogical(transform.pos.x) to CytoUnits.toLogical(transform.pos.y)
    }

    fun heldGenome(): List<Gene>? =
        lastHeldId?.let { currentState.components.getTable<CytoCellComponent>().asMap()[it]?.genome }

    /** The held cell's current **BIO** display colour (biomass atom-mix, the same hue the renderer's Bio mode
     *  shows) as packed RGBA, or null if no cell is held. Used to colour a saved-genome swatch by the creature
     *  it was exported from. */
    fun heldBioColorRgba(): Long? {
        val id = lastHeldId ?: return null
        val cell = currentState.components.getTable<CytoCellComponent>().asMap()[id] ?: return null
        return bioSwatchColor(cell.biomass)
    }

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

    // ── Mutation rate — in-game tunable + saved on the world (CytoSimParamsComponent) ───────────────────
    private val mutationLadder = intArrayOf(0, 1_000_000, 100_000, 10_000, 1_000)   // off → rare → … → frequent

    /** The effective mutation rate-denominator: the world's explicit value, or the [cfg] default when unset
     *  (the `-1` sentinel). 0 = mutation off; higher = rarer (per-gene per-tick `1/denom`). */
    fun mutationRateDenom(): Int = world.mutationRateDenom.let { if (it >= 0) it else cfg.mutationRateDenom }

    /** Cycle the mutation rate through [mutationLadder] (wrapping), set it explicitly on the world so it
     *  saves, and republish so the change shows even while paused. */
    fun cycleMutationRate() {
        withLock(stepLock) {
            val i = mutationLadder.indexOf(mutationRateDenom())
            world.mutationRateDenom = mutationLadder[if (i < 0) 0 else (i + 1) % mutationLadder.size]
            currentState = world.toSimState()
            publishedFrame = CytoFrame(currentState, tickCount)
        }
    }

    /** The action part of a gene's description (`BOND a·a`, `CONVERT ab`, `DIVIDE →m`, …). FormBond shows the
     *  `*` wildcard markers (MORPHOGENESIS.md §2026-06-18) — `*a` ends-with, `a*` starts-with — like the codec. */
    private fun actionLabel(a: org.emerge.demo.cyto.sim.GeneAction): String = when (a.type) {
        ActionType.Import -> "IMPORT ${a.a}"
        ActionType.Export -> "EXPORT ${a.a}"
        ActionType.FormBond ->
            "BOND ${if (a.aWild && a.a.isNotEmpty()) "*${a.a}" else a.a}·${if (a.bWild && a.b.isNotEmpty()) "${a.b}*" else a.b}"
        ActionType.Convert -> "CONVERT ${a.a}"
        ActionType.Contract -> "CONTRACT"
        ActionType.Mitosis -> {
            val asym = if (a.a.isEmpty()) "" else " ${if (a.morphogenToMother) "→M:" else "→"}${a.a}"
            val orient = if (a.b.isEmpty()) "" else " ${if (a.divideAcross) "across" else "along"} ${a.b}"
            "DIVIDE$asym$orient"
        }
        ActionType.Repair -> "REPAIR"
        ActionType.Lyse -> "LYSE"
    }

    /** One condition clause as `lhs<cmp>rhs` (e.g. `ab<800`). */
    private fun clauseStr(c: org.emerge.demo.cyto.sim.Clause): String =
        "${operandLabel(c.lhs)}${if (c.cmp == Comparison.Greater) ">" else "<"}${operandLabel(c.rhs)}"

    /** The energy-source part (`LIGHT` / `BRK ab`). */
    private fun srcLabel(s: EnergySource): String = when (s) {
        EnergySource.Light -> "LIGHT"
        is EnergySource.BreakBond -> "BRK ${s.bond}"
    }

    /** Panel label for one condition operand: a constant's number, `BIO`/`TOUCH` for the live readings,
     *  or the species token for a cytoplasm count. */
    private fun operandLabel(op: org.emerge.demo.cyto.sim.Operand): String = when (op) {
        is org.emerge.demo.cyto.sim.Operand.Constant -> op.value.toString()
        is org.emerge.demo.cyto.sim.Operand.Chem -> op.species.ifEmpty { "?" }
        is org.emerge.demo.cyto.sim.Operand.Conc -> "[${op.species.ifEmpty { "?" }}]"
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
            // Rebuild the reducer: a save from a different world size needs its per-tile buffers re-sized to
            // the current [CytoWorldConfig] (the host applies the saved geometry to the holder before this).
            reducer = CytoSoaReducer(cfg, executor = executor)
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

/** Pack a biomass atom-mix into a full-value RGBA swatch colour (r/g/b atom counts → R/G/B, normalised by
 *  the peak channel); neutral grey when there's no biomass. Mirrors the renderer's Bio colour, at value 1. */
internal fun bioSwatchColor(biomass: Map<String, Int>): Long {
    var r = 0L; var g = 0L; var b = 0L
    for ((species, count) in biomass) for (ch in species) when (ch) {
        'r' -> r += count; 'g' -> g += count; 'b' -> b += count
    }
    val peak = maxOf(r, maxOf(g, b))
    if (peak <= 0) return 0x888888FFL
    val rr = r * 255 / peak; val gg = g * 255 / peak; val bb = b * 255 / peak
    return (rr shl 24) or (gg shl 16) or (bb shl 8) or 0xFF
}
