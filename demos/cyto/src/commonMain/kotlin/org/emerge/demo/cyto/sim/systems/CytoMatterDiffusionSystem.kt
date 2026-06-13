package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Slow diffusion of the matter reservoir: one [CytoMatterGrid.diffused] step per tick, so matter creeps
 * down-gradient between neighbouring grid cells — bleeding out of the source clumps toward wherever cells
 * are depleting it, instead of sitting locked in cells the population can't reach. A pure environment
 * process (no input); matter is conserved (every move is grid-cell→grid-cell). Runs in the interact phase
 * so the diffused reservoir is in place before biology reads it this tick.
 */
object CytoMatterDiffusionSystem : EcsSystem<CytoConfig, SimState, CytoInput> {
    override fun update(
        cfg: CytoConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, CytoInput>,
    ) {
        val grid = builder.getComponent<CytoMatterGridComponent>(GRID_SINGLETON)?.grid ?: return
        builder.update<CytoMatterGridComponent>(GRID_SINGLETON) {
            CytoMatterGridComponent(grid.diffused(CytoMatterGrid.DIFFUSE_NUM, CytoMatterGrid.DIFFUSE_DEN))
        }
    }
}
