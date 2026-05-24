@file:OptIn(BypassesStagedView::class)

package org.emerge.demo.scavengers

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.BypassesStagedView
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.nextRandomInt
import org.emerge.sim.core.physics.model.spawnParticle
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Norm


object ShipThrustParticleSystem : EcsSystem<ScavengersConfig, PhysicsState, ScavengersInput> {
    override fun update(
        cfg: ScavengersConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, ScavengersInput>,
    ) {
        for ((playerId, entityId) in builder.playerEntities) {
            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue
            val motion = builder.getComponent<MotionComponent>(entityId) ?: continue
            val input = inputs[playerId] ?: ScavengersInput.ZERO
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
