package org.emerge.demo.scavengers

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.model.*
import org.emerge.sim.core.physics.primitives.*


object DamageSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    private const val DESTRUCTION_BURST_PARTICLE_COUNT = 50
    private val DESTRUCTION_BURST_PARTICLE_RADIUS_FACTOR = Frac(1, 3)
    private val DESTRUCTION_BURST_SPEED_FACTOR = Frac(1, 12)
    private const val DESTRUCTION_BURST_LIFETIME = 42

    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val destructionBursts = ArrayList<DestructionBurstSpec>()
        val playersToRespawn = LinkedHashSet<PlayerId>()

        for ((entityId, damage) in builder.entries<DamageComponent>()) {
            if (damage.accumulated.raw >= cfg.maxHealth.raw) {
                // Cleanup on second pass
                if (cfg.respawnTicks >= 0) {
                    builder.remove<DamageComponent>(entityId)
                } else {
                    builder.removeEntity(entityId)
                }
                continue
            }

            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue
            val motion = builder.getComponent<MotionComponent>(entityId) ?: continue
            val impulse = builder.getComponent<ImpulseComponent>(entityId) ?: ImpulseComponent()

            val total = damage.accumulated + damage.next
            val destroyed = total.raw >= cfg.maxHealth.raw
            if (destroyed) {
                val teamId = builder.getComponent<TeamComponent>(entityId)?.teamId
                val baseRadius = builder.getComponent<ColliderComponent>(entityId)?.radius
                if (teamId != null && baseRadius != null) {
                    destructionBursts += DestructionBurstSpec(
                        pos = transform.pos,
                        vel = motion.vel,
                        recentImpulse = impulse,
                        teamId = teamId,
                        baseRadius = baseRadius,
                    )
                }

                if (cfg.respawnTicks >= 0) {
                    val owner = builder.getComponent<PlayerOwnedComponent>(entityId)?.playerId
                    if (owner != null && !playersToRespawn.contains(owner)) {
                        playersToRespawn += owner
                    }
                }
            }

            builder.emit(CrashImpactAudioEvent(
                entityId = entityId,
                pos = transform.pos,
                damageRaw = damage.next.raw.toInt(),
                destroyed = destroyed,
            ))

            builder.update<DamageComponent>(entityId) { DamageComponent(total, damage.next) }
        }

        for (burst in destructionBursts) {
            spawnDestructionBurst(builder, burst)
        }
        for (playerId in playersToRespawn) {
            builder.queueRespawn(playerId, cfg.respawnTicks)
        }
    }

    private fun spawnDestructionBurst(builder: PhysicsBuilder, burst: DestructionBurstSpec) {
        repeat(DESTRUCTION_BURST_PARTICLE_COUNT) {
            val direction = Norm.fromAngle(Coord(builder.nextRandomInt()))
            val speed = DESTRUCTION_BURST_SPEED_FACTOR*(burst.baseRadius + burst.recentImpulse.vel.len) * Frac(builder.nextRandomInt(until = Int.MAX_VALUE).toLong())
            builder.spawnParticle(
                pos = burst.pos + burst.recentImpulse.pos,
                vel = burst.vel + burst.recentImpulse.vel + direction * speed,
                radius = DESTRUCTION_BURST_PARTICLE_RADIUS_FACTOR*burst.baseRadius,
                shape = BodyShape.CIRCLE,
                lifetime = DESTRUCTION_BURST_LIFETIME,
                teamId = burst.teamId,
            )
        }
    }

    private data class DestructionBurstSpec(
        val pos: Coord2,
        val vel: Coord2,
        val recentImpulse: ImpulseComponent,
        val teamId: TeamId,
        val baseRadius: Frac,
    )
}
