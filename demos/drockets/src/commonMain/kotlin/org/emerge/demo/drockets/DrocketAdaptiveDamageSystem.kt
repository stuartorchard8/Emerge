package org.emerge.demo.drockets

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.model.*
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Drockets-only damage policy:
 * - Dynamic destruction threshold for drockets based on current population.
 * - Non-drocket entities keep using [PhysicsConfig.maxHealth].
 */
object DrocketAdaptiveDamageSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    private const val DESTRUCTION_BURST_PARTICLE_COUNT = 50
    private val DESTRUCTION_BURST_PARTICLE_RADIUS_FACTOR = Frac(1, 3)
    private val DESTRUCTION_BURST_SPEED_FACTOR = Frac(1, 12)
    private const val DESTRUCTION_BURST_LIFETIME = 42

    private const val POPULATION_FLOOR = 100
    private const val POPULATION_CAP = 600
    private val MIN_MAX_HEALTH = Frac(1, 1 shl 15) // at cap
    private val MAX_MAX_HEALTH = Frac(1, 1) // at/below floor

    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val destructionBursts = ArrayList<DestructionBurstSpec>()
        val playersToRespawn = LinkedHashSet<PlayerId>()

        val drocketCount = builder.entries<DrocketStateComponent>().size
        val drocketMaxHealth = effectiveDrocketMaxHealth(drocketCount)

        for ((entityId, damage) in builder.entries<DamageComponent>()) {
            val isDrocket = builder.getComponent<ReproducerComponent>(entityId) != null
            val threshold = if (isDrocket) drocketMaxHealth else cfg.maxHealth

            if (damage.accumulated.raw >= threshold.raw) {
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
            val destroyed = total.raw >= threshold.raw
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

    /**
     * Linearly interpolates max health from [MAX_MAX_HEALTH] at [POPULATION_FLOOR]
     * down to [MIN_MAX_HEALTH] at [POPULATION_CAP], clamping at both ends.
     */
    private fun effectiveDrocketMaxHealth(population: Int): Frac {
        val cap = POPULATION_CAP.coerceAtLeast(POPULATION_FLOOR + 1)
        val clamped = population.coerceIn(POPULATION_FLOOR, cap)
        val t = Frac((cap - clamped).toLong(), cap - POPULATION_FLOOR)
        return MIN_MAX_HEALTH + (MAX_MAX_HEALTH - MIN_MAX_HEALTH) * t
    }

    private fun spawnDestructionBurst(builder: PhysicsBuilder, burst: DestructionBurstSpec) {
        repeat(DESTRUCTION_BURST_PARTICLE_COUNT) {
            val direction = org.emerge.sim.core.physics.primitives.Norm.fromAngle(
                org.emerge.sim.core.physics.primitives.Coord(builder.nextRandomInt())
            )
            val speed = DESTRUCTION_BURST_SPEED_FACTOR * (burst.baseRadius + burst.recentImpulse.vel.len) *
                Frac(builder.nextRandomInt(until = Int.MAX_VALUE).toLong())
            builder.spawnParticle(
                pos = burst.pos + burst.recentImpulse.pos,
                vel = burst.vel + burst.recentImpulse.vel + direction * speed,
                radius = DESTRUCTION_BURST_PARTICLE_RADIUS_FACTOR * burst.baseRadius,
                shape = org.emerge.sim.core.physics.primitives.BodyShape.CIRCLE,
                lifetime = DESTRUCTION_BURST_LIFETIME,
                teamId = burst.teamId,
            )
        }
    }

    private data class DestructionBurstSpec(
        val pos: Coord2,
        val vel: Coord2,
        val recentImpulse: ImpulseComponent,
        val teamId: org.emerge.sim.core.TeamId,
        val baseRadius: Frac,
    )
}
