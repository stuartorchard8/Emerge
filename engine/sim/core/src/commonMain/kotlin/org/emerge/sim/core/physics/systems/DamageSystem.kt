package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.model.CrashImpactAudioEvent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.*


object DamageSystem : EcsSystem<PhysicsConfig, PhysicsInput> {
    private const val DESTRUCTION_BURST_PARTICLE_COUNT = 50
    private val DESTRUCTION_BURST_PARTICLE_RADIUS = Frac(1, 1536)
    private val DESTRUCTION_BURST_BASE_SPEED = Frac(1, 896)
    private const val DESTRUCTION_BURST_LIFETIME = 42

    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val crashImpactAudioEvents = ArrayList<CrashImpactAudioEvent>()
        val destructionBursts = ArrayList<DestructionBurstSpec>()
        val playersToRespawn = LinkedHashSet<PlayerId>()

        for ((entityId, damage) in builder.initial.raw.damages.entries()) {
            if (damage.accumulated.raw >= cfg.shipMaxDamage.raw) {
                // Cleanup on second pass
                builder.remove<DamageComponent>(entityId)
                continue
            }

            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue
            val motion = builder.getComponent<MotionComponent>(entityId) ?: continue
            val impulse = builder.getComponent<ImpulseComponent>(entityId) ?: ImpulseComponent()

            val total = damage.accumulated + damage.next
            if (total.raw >= cfg.shipMaxDamage.raw) {
                val teamId = builder.getComponent<TeamComponent>(entityId)?.teamId
                if (teamId != null) {
                    destructionBursts += DestructionBurstSpec(pos = transform.pos+impulse.pos, vel = motion.vel+impulse.vel, teamId = teamId)
                }

                val owner = builder.initial.raw.playerOwned[entityId]?.playerId
                if (owner != null && !playersToRespawn.contains(owner)) {
                    playersToRespawn += owner
                }

                crashImpactAudioEvents += CrashImpactAudioEvent(
                    entityId = entityId,
                    pos = transform.pos,
                    damageRaw = damage.next.raw.toInt(),
                    destroyed = true,
                )
            } else {
                crashImpactAudioEvents += CrashImpactAudioEvent(
                    entityId = entityId,
                    pos = transform.pos,
                    damageRaw = damage.next.raw.toInt(),
                    destroyed = false,
                )
            }

            builder.update<DamageComponent>(entityId) { DamageComponent(total, damage.next) }
        }

        state.setAudioEvents(crashImpactAudioEvents)

        for (burst in destructionBursts) {
            spawnDestructionBurst(state, burst)
        }
        for (playerId in playersToRespawn) {
            state.queuePlayerRespawn(playerId, cfg.shipRespawnTicks)
        }
    }

    private fun spawnDestructionBurst(state: PhysicsState, burst: DestructionBurstSpec) {
        repeat(DESTRUCTION_BURST_PARTICLE_COUNT) {
            val direction = Norm.fromAngle(Coord(state.nextRandomInt()))
            val speed = DESTRUCTION_BURST_BASE_SPEED * Frac(state.nextRandomInt(until = Int.MAX_VALUE).toLong())
            state.spawnParticle(
                pos = burst.pos,
                vel = burst.vel + direction * speed,
                radius = DESTRUCTION_BURST_PARTICLE_RADIUS,
                shape = BodyShape.CIRCLE,
                lifetime = DESTRUCTION_BURST_LIFETIME,
                teamId = burst.teamId,
            )
        }
    }

    private data class DestructionBurstSpec(
        val pos: Coord2,
        val vel: Coord2,
        val teamId: TeamId,
    )
}