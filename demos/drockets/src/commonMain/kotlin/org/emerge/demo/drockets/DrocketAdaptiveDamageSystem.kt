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
 * - Non-drocket entities keep using [PhysicsConfig.maxDamage].
 */
object DrocketAdaptiveDamageSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    private const val DESTRUCTION_BURST_PARTICLE_COUNT = 50
    private val DESTRUCTION_BURST_PARTICLE_RADIUS_FACTOR = Frac(1, 3)
    private val DESTRUCTION_BURST_SPEED_FACTOR = Frac(1, 12)
    private const val DESTRUCTION_BURST_LIFETIME = 42

    private const val POPULATION_FLOOR = 100
    private val MIN_MAX_DAMAGE = Frac(1, Int.MAX_VALUE) // at cap
    private val MAX_MAX_DAMAGE = Frac(1, 1) // at/below floor

    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val destructionBursts = ArrayList<DestructionBurstSpec>()
        val playersToRespawn = LinkedHashSet<PlayerId>()

        val drocketCount = builder.entries<DrocketStateComponent>().size
        val drocketMaxDamage = effectiveDrocketMaxDamage(drocketCount)

        for ((entityId, damage) in builder.entries<DamageComponent>()) {
            val threshold = if (builder.getComponent<DrocketStateComponent>(entityId) != null) {
                drocketMaxDamage
            } else {
                cfg.maxDamage
            }

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

    private fun effectiveDrocketMaxDamage(population: Int): Frac {
        val cap = ReproductionSystem.POPULATION_CAP.coerceAtLeast(POPULATION_FLOOR + 1)
        if (population <= POPULATION_FLOOR) return MAX_MAX_DAMAGE
        if (population >= cap) return MIN_MAX_DAMAGE

        val span = (cap - POPULATION_FLOOR).toDouble()
        val t = ((population - POPULATION_FLOOR).toDouble() / span).coerceIn(0.0, 1.0)
        val raw = MAX_MAX_DAMAGE.raw + ((MIN_MAX_DAMAGE.raw - MAX_MAX_DAMAGE.raw) * t).toLong()
        return Frac(raw.coerceIn(MIN_MAX_DAMAGE.raw, MAX_MAX_DAMAGE.raw))
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
