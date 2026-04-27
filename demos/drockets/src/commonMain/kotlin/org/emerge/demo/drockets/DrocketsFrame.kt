package org.emerge.demo.drockets

import org.emerge.sim.core.physics.model.PhysicsState

data class DrocketsFrame(
    val state: PhysicsState,
    val lineage: DrocketLineageState,
    val cladogramLayout: CladogramLayout,
    val tick: Long,
)
