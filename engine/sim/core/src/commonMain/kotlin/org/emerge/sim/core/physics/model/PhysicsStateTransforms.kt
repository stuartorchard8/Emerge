package org.emerge.sim.core.physics.model

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Pure [PhysicsState]-level transforms used outside the per-tick builder loop:
 *
 *  - Reducer `patchState` (delta replay on thin/semi-thin clients) — [setImpulses], [addDamages].
 *  - Host leave policy — [removePlayerRocket].
 *
 * Each function returns a new snapshot; none mutate the receiver. In-loop mutations should go
 * through [PhysicsBuilder] instead.
 */
fun PhysicsState.setImpulses(impulses: ComponentTable<ImpulseComponent>): PhysicsState =
    copy(components = components.update { set(impulses) })

/**
 * Folds a per-entity delta map into each entity's [DamageComponent.next]. Entities not yet
 * damaged get a fresh component with `accumulated = 0, last = 0`.
 */
fun PhysicsState.addDamages(delta: Map<EntityId, Frac>): PhysicsState {
    if (delta.isEmpty()) return this
    val sums = delta.mapValues { (entityId, damage) ->
        val existing = damages[entityId]
        if (existing == null) DamageComponent(Frac(0), Frac(0), damage)
        else existing.copy(next = existing.next + damage)
    }
    return copy(
        components = components.update {
            set(damages.putAll(sums.toList()))
        },
    )
}

/**
 * Removes a player's rocket (if any) and clears any pending respawn for that player.
 * Used by the lockstep host's leave policy.
 *
 * Implemented as a one-shot [PhysicsBuilder] pass so the landing cascade and cross-table
 * tombstoning stay consistent with per-tick removals.
 */
fun PhysicsState.removePlayerRocket(playerId: PlayerId): PhysicsState {
    val builder = PhysicsBuilder(this)
    builder.clearRespawn(playerId)
    val entityId = playerEntities[playerId]
    if (entityId != null) {
        builder.removeEntityWithLandingCascade(entityId)
    }
    return builder.build()
}
