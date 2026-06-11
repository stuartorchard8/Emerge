package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CellWork
import org.emerge.demo.cyto.sim.CytoBiologyCore
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.MAX_CHEM
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.RADIUS_ELASTICITY
import org.emerge.demo.cyto.sim.runGenes
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/** Emitted by [CytoBiologySystem]; consumed by [CytoLifecycleSystem] in a later phase. */
data class CellDivisionIntent(val id: EntityId)
data class CellDestroyIntent(val id: EntityId)

/**
 * Cyto's chemistry + genes + growth, ported from `Cell.chemistry` / `Cell.act` and
 * `CellWorld.fixedUpdate`'s two-pass structure (all cells run chemistry, then all run
 * act). Cell biology stays in Float/logical units; the engine [ColliderComponent] radius
 * is derived from the grown logical radius. Division/death are emitted as intents for the
 * structural [CytoLifecycleSystem] to apply.
 */
object CytoBiologySystem : EcsSystem<CytoConfig, SimState, org.emerge.demo.cyto.sim.CytoInput> {
    private val ZERO = Frac(0, 1)

    override fun update(
        cfg: CytoConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, org.emerge.demo.cyto.sim.CytoInput>,
    ) {
        val cells = builder.entries<CytoCellComponent>()
        if (cells.isEmpty()) return
        val springs = builder.entries<SpringConstraintComponent>()
        val transforms = builder.entries<org.emerge.sim.core.physics.components.TransformComponent>()
        val lightField = org.emerge.demo.cyto.sim.CytoLightField.default()
        val dt = TIME_STEP

        // Build per-cell working state, applying last tick's pending transfers.
        val works = LinkedHashMap<EntityId, CellWork>(cells.size)
        val neighbourCounts = HashMap<EntityId, Int>(cells.size)
        for ((id, cell) in cells) {
            val chem = HashMap(cell.chemicals)
            for ((k, v) in cell.pendingTransfers) {
                chem[k] = ((chem[k] ?: ZERO) + v).coerceIn(ZERO, MAX_CHEM)
            }
            // Harvest = field × exposure: only surface cells reach the resource (income ∝ surface,
            // not volume), so a growing colony's per-capita income falls → density dependence.
            val pos = transforms[id]?.pos
            var harvest = ZERO
            if (pos != null) {
                var k = 0
                springs[id]?.springs?.let { sp ->
                    for (s in sp) {
                        if (k >= org.emerge.demo.cyto.sim.CytoExposure.MAX_NEIGHBOURS) break
                        val np = transforms[s.other]?.pos ?: continue
                        val d = np - pos
                        expoScratch[k++] = org.emerge.demo.cyto.sim.CytoExposure.diamondAngle(d.x, d.y).raw
                    }
                }
                harvest = lightField.sampleAt(CytoUnits.toLogical(pos.x), CytoUnits.toLogical(pos.y)) *
                    org.emerge.demo.cyto.sim.CytoExposure.weight(expoScratch, k)
            }
            works[id] = CellWork(
                chemicals = chem,
                transfers = HashMap(),
                initialSuppression = cell.suppression,
                touch = cell.touch,
                logicalRadius = cell.logicalRadius,
                divideCharge = cell.divideCharge,
                type = cell.type,
                genome = cell.genome,
                light = harvest,
            )
            neighbourCounts[id] = springs[id]?.springs?.size ?: 0
        }

        // Pass 1 — chemistry: genes then enzyme reactions, for every cell.
        for ((_, work) in works) {
            runGenes(work, dt)
            work.touch = ZERO
            CytoBiologyCore.runReactions(work)
        }

        // Pass 2 — act: diffusion, energy, growth, type behaviour, division decision.
        val divide = ArrayList<EntityId>()
        val destroy = ArrayList<EntityId>()
        for ((id, work) in works) {
            CytoBiologyCore.act(id, work, springs[id]?.springs?.map { it.other }, works, neighbourCounts, dt, divide, destroy)
        }

        // Write back component + collider radius.
        for ((id, work) in works) {
            builder.update<CytoCellComponent>(id) { current ->
                (current ?: CytoCellComponent(work.type, work.chemicals, work.logicalRadius)).copy(
                    chemicals = work.chemicals,
                    suppression = work.suppression,
                    logicalRadius = work.logicalRadius,
                    divideCharge = work.divideCharge,
                    pendingTransfers = work.transfers,
                    touch = ZERO,
                    stickyTemp = work.isStickyTemp,
                )
            }
            // The collider radius is a pure function of logicalRadius; only rewrite it when
            // the radius actually moved (a settled cell holds its radius, so this skips an
            // allocation + write for the steady bulk). len() is deterministic, so equal
            // logicalRadius ⇒ identical ColliderComponent.
            val original = cells[id]
            if (original == null || work.logicalRadius != original.logicalRadius) {
                builder.update<ColliderComponent>(id) { ColliderComponent(CytoUnits.len(work.logicalRadius.toFloat())) }
            }
        }

        for (id in destroy) builder.emit(CellDestroyIntent(id))
        for (id in divide) builder.emit(CellDivisionIntent(id))
    }

    val TIME_STEP = Frac(1, 64)

    // Reused per-cell scratch for the exposure (neighbour diamond-angle raws). Single-threaded reducer.
    private val expoScratch = LongArray(org.emerge.demo.cyto.sim.CytoExposure.MAX_NEIGHBOURS)
}
