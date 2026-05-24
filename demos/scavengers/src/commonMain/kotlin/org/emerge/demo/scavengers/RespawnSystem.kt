@file:OptIn(BypassesStagedView::class)

package org.emerge.demo.scavengers

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.BypassesStagedView
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimBuilder

import org.emerge.sim.core.sim.spawnBody
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Norm


/**
 * Advances each pending player respawn by one tick. When the countdown hits zero we
 * attempt to place a fresh rocket on the team's home planet; if no home planet is
 * available we hold the respawn at zero and retry next tick.
 *
 * Respawn entries are queued elsewhere (typically by [DamageSystem] via [queueRespawn]);
 * this system is only responsible for draining the queue.
 */
object RespawnSystem : EcsSystem<ScavengersConfig, SimState, ScavengersInput> {
    override fun update(
        cfg: ScavengersConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, ScavengersInput>,
    ) {
        if (builder.pendingRespawns.isEmpty()) return
        for ((playerId, respawn) in builder.pendingRespawns.toMap()) {
            if (builder.getComponent<DamageComponent>(respawn.entityId) != null) {
                builder.removeEntity(respawn.entityId)
            }
            val nextTicks = (respawn.ticksRemaining - 1).coerceAtLeast(0)
            if (nextTicks > 0) {
                builder.updateRespawn(playerId) { it.copy(ticksRemaining = nextTicks) }
                continue
            }
            val respawned = tryRespawnPlayer(builder, playerId, respawn)
            if (respawned) {
                builder.clearRespawn(playerId)
            } else {
                builder.updateRespawn(playerId) { it.copy(ticksRemaining = 0) }
            }
        }
    }

    private fun tryRespawnPlayer(
        builder: SimBuilder,
        playerId: PlayerId,
        respawn: PlayerRespawnState,
    ): Boolean {
        val homePlanetId = builder.initial.homePlanetEntity(respawn.teamId) ?: return false
        val planetTransform = builder.getComponent<TransformComponent>(homePlanetId) ?: return false
        val planetMotion = builder.getComponent<MotionComponent>(homePlanetId) ?: return false
        val planetCollider = builder.getComponent<ColliderComponent>(homePlanetId) ?: return false
        val localAngle = Coord(playerId.value, Int.MAX_VALUE)
        val localNormal = Norm.fromAngle(localAngle)
        val relativePos = localNormal * (planetCollider.radius + respawn.rocket.radius)
        val worldPos = planetTransform.pos + relativePos.rotateByAngle(planetTransform.ang)
        val worldAng = Coord(planetTransform.ang.raw + localAngle.raw)
        val entityId = builder.spawnBody(
            pos = worldPos,
            vel = planetMotion.vel,
            ang = worldAng,
            angVel = planetMotion.angVel,
            mass = respawn.rocket.mass,
            radius = respawn.rocket.radius,
            bounce = respawn.rocket.bounce,
            rough = respawn.rocket.rough,
            shape = respawn.rocket.shape,
        )
        builder.update<PlayerOwnedComponent>(entityId) { PlayerOwnedComponent(playerId) }
        builder.update<TeamComponent>(entityId) { TeamComponent(respawn.teamId) }
        builder.update<LandingAttachmentComponent>(entityId) {
            LandingAttachmentComponent(
                parentEntityId = homePlanetId,
                relativePos = relativePos,
                relativeAng = Frac(localAngle.raw.toLong()),
            )
        }
        return true
    }
}
