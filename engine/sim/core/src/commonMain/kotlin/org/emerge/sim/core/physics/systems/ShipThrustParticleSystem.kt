package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.nextRandomInt
import org.emerge.sim.core.physics.model.spawnParticle
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.primitives.PhysicsInput


object ShipThrustParticleSystem : EcsSystem<PhysicsConfig, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        for ((playerId, entityId) in builder.initial.playerEntities) {
            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue
            val motion = builder.getComponent<MotionComponent>(entityId) ?: continue
            val input = inputs[playerId] ?: PhysicsInput.ZERO
            if (input.thrust > builder.nextRandomInt(until = Int.MAX_VALUE)) {
                val team = builder.getComponent<TeamComponent>(entityId) ?: continue
                val angleJitter = Frac(builder.nextRandomInt(until = Int.MAX_VALUE/8).toLong()-Int.MAX_VALUE/16)
                val angleVectoring = Frac(input.turn/-16L + motion.angVel.raw.toLong()*4) // Combined turning & dampening
                val norm = Norm.fromAngle(transform.ang + angleVectoring + angleJitter)
                builder.spawnParticle(
                    pos = transform.pos,
                    vel = motion.vel - norm * Frac(1,1024)*Frac(builder.nextRandomInt(until = Int.MAX_VALUE).toLong()),
                    radius = Frac(1, 2048),
                    shape = BodyShape.CIRCLE,
                    lifetime = 30,
                    teamId = team.teamId,
                )
            }
        }
    }
}
