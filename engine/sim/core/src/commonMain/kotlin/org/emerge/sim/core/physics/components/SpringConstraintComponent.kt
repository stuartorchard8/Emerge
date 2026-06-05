package org.emerge.sim.core.physics.components

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Frac

/**
 * One soft distance constraint pulling this entity toward [other], holding their
 * separation near [restLength]. [stiffness] is the fraction of the length error
 * corrected (as velocity) per tick; [damping] is the fraction of the relative
 * normal velocity cancelled per tick. Both are dimensionless `Frac` in `(0, 1]`.
 *
 * This is a generic physics primitive (a spring/distance joint), alongside the
 * engine's other generic systems (gravity, bounce, contacts). Who *creates and
 * destroys* springs — and any game meaning like connection "damage" — is left to
 * the demo.
 */
data class SpringConstraint(
    val other: EntityId,
    val restLength: Frac,
    val stiffness: Frac,
    val damping: Frac,
)

/**
 * The springs attached to an entity. For correct (non-double-counted) solving,
 * [SpringConstraintSystem] processes a spring only from the lower-id endpoint, so a
 * spring must be registered on (at least) the smaller of the two entity ids. Storing it
 * symmetrically on both endpoints is fine and lets callers read an entity's neighbours
 * directly.
 */
data class SpringConstraintComponent(
    val springs: List<SpringConstraint> = emptyList(),
)
