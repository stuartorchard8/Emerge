package org.emerge.sim.core.physics.components

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

data class LandingAttachmentComponent(
    val parentEntityId: EntityId,
    val relativePos: Frac2,
    val relativeAng: Frac,
)
