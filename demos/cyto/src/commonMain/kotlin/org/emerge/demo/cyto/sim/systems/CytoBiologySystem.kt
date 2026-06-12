package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.sim.CellWork
import org.emerge.demo.cyto.sim.CytoBiologyCore
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoExposure
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/** Emitted by [CytoBiologySystem]; consumed by [CytoLifecycleSystem] in a later phase. */
data class CellDivisionIntent(val id: EntityId)
data class CellDestroyIntent(val id: EntityId)

/**
 * The matter-model biology (MORPHOGENESIS.md): per cell, light → energy quanta (scaled by surface
 * exposure), then the genome's gated actions (Import / FormBond / Convert / Mitosis), then cytoplasm
 * diffusion to neighbours, degradation, growth (radius from biomass), and the death/division decision.
 * Cells exchange matter with the finite [CytoMatterGrid] reservoir (the [CytoMatterGridComponent]
 * singleton); division/death are emitted as intents for [CytoLifecycleSystem] to apply.
 *
 * Genes run in ascending-EntityId order because Import draws from the shared reservoir (a sequential,
 * order-sensitive step); diffusion is snapshot-based and order-independent.
 */
object CytoBiologySystem : EcsSystem<CytoConfig, SimState, org.emerge.demo.cyto.sim.CytoInput> {

    override fun update(
        cfg: CytoConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, org.emerge.demo.cyto.sim.CytoInput>,
    ) {
        val cells = builder.entries<CytoCellComponent>()
        if (cells.isEmpty()) return
        val springs = builder.entries<SpringConstraintComponent>()
        val transforms = builder.entries<TransformComponent>()
        val lightField = CytoLightField.default()
        // The reservoir, cloned so this tick's draws/deposits don't mutate the input snapshot in place.
        val grid = builder.getComponent<CytoMatterGridComponent>(GRID_SINGLETON)?.grid?.copy()
            ?: CytoMatterGrid.seeded()

        // Process cells in ascending-EntityId order (Import draws from the shared reservoir sequentially).
        val orderedIds = cells.keys.sortedBy { it.value }
        val works = LinkedHashMap<EntityId, CellWork>(cells.size)
        val neighbourIds = HashMap<EntityId, List<EntityId>>(cells.size)
        for (id in orderedIds) {
            val cell = cells.getValue(id)
            val nbrs = springs[id]?.springs?.map { it.other } ?: emptyList()
            neighbourIds[id] = nbrs

            val pos = transforms[id]?.pos
            var gridIndex = -1
            var quanta = 0
            if (pos != null) {
                var k = 0
                for (s in nbrs) {
                    if (k >= CytoExposure.MAX_NEIGHBOURS) break
                    val np = transforms[s]?.pos ?: continue
                    val d = np - pos
                    expoScratch[k++] = CytoExposure.diamondAngle(d.x, d.y).raw
                }
                val lx = CytoUnits.toLogical(pos.x); val ly = CytoUnits.toLogical(pos.y)
                gridIndex = grid.indexOf(lx, ly)
                val sample = lightField.sampleAt(lx, ly)               // Frac, environmental light
                val exposure = CytoExposure.weight(expoScratch, k)     // Frac [0,1]
                // quanta = floor(sample × exposure × SCALE), in integer Frac raws (no float).
                quanta = (((sample * exposure) * CytoBiologyCore.LIGHT_QUANTA_SCALE).raw / Int.MAX_VALUE.toLong()).toInt()
            }
            works[id] = CellWork(
                cytoplasm = HashMap(cell.cytoplasm),
                biomass = HashMap(cell.biomass),
                logicalRadius = cell.logicalRadius,
                type = cell.type,
                genome = cell.genome,
                quanta = quanta,
                wear = cell.wear,
                gridIndex = gridIndex,
            )
        }

        // Phase 1 — genes (sequential, drawing from the reservoir).
        for (id in orderedIds) CytoBiologyCore.runGenes(works.getValue(id), grid)
        // Phase 2 — cytoplasm diffusion (snapshot-based, order-independent).
        CytoBiologyCore.diffuse(works, neighbourIds)
        // Phase 3 — degradation / growth / death+division decision.
        val divide = ArrayList<EntityId>()
        val destroy = ArrayList<EntityId>()
        for (id in orderedIds) CytoBiologyCore.finish(id, works.getValue(id), divide, destroy)

        // Write cells back (+ collider radius only when the radius moved).
        for (id in orderedIds) {
            val work = works.getValue(id)
            val cell = cells.getValue(id)
            builder.update<CytoCellComponent>(id) {
                cell.copy(
                    cytoplasm = work.cytoplasm,
                    biomass = work.biomass,
                    logicalRadius = work.logicalRadius,
                    wear = work.wear,
                    stickyTemp = false,
                )
            }
            if (work.logicalRadius != cell.logicalRadius) {
                builder.update<ColliderComponent>(id) { ColliderComponent(CytoUnits.len(work.logicalRadius.toFloat())) }
            }
        }
        // Persist the reservoir (draws debited it this tick).
        builder.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(grid) }

        for (id in destroy) builder.emit(CellDestroyIntent(id))
        for (id in divide) builder.emit(CellDivisionIntent(id))
    }

    val TIME_STEP = Frac(1, 64)

    // Reused per-cell scratch for the exposure (neighbour diamond-angle raws). Single-threaded reducer.
    private val expoScratch = LongArray(CytoExposure.MAX_NEIGHBOURS)
}
