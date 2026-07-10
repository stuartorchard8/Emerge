package org.emerge.demo.drockets

import org.emerge.sim.core.sim.SimState

data class DrocketsFrame(
    val state: SimState,
    val lineage: DrocketLineageState,
    val cladogramLayout: CladogramLayout,
    val tick: Long,
)
