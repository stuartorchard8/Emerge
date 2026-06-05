package org.emerge.demo.cyto.sim.systems

import org.emerge.sim.core.EntityId

/** Two cells should be connected by a spring (a < b). Emitted by the contact system. */
data class WeldIntent(val a: EntityId, val b: EntityId)

/** All of this cell's connections should be cut. Emitted by the Detach tap. */
data class DetachIntent(val id: EntityId)
