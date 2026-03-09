package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.EcsSystems

class PhysicsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val systems: List<EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput>> = listOf(
        InputSystem,
        IntegrationSystem,
        CollisionSystem,
    )

    override fun reduce(cfg: PhysicsConfig, state: PhysicsState, inputs: Map<PlayerId, PhysicsInput>): PhysicsState {
        return EcsSystems.runAll(cfg, state, inputs, systems)
    }
}

private object InputSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        var controls = state.controls
        for ((playerId, entityId) in state.playerEntities) {
            val input = inputs[playerId] ?: PhysicsInput.ZERO
            controls = controls.put(
                entityId,
                ControlIntentComponent(
                    thrust = input.thrust,
                    turn = input.turn,
                ),
            )
        }
        return state.copy(controls = controls)
    }
}

private object IntegrationSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        val transforms = LinkedHashMap(state.transforms.asMap())
        val motions = LinkedHashMap(state.motions.asMap())
        for (entityId in state.world.entities) {
            val transform = state.transforms[entityId] ?: continue
            val motion = state.motions[entityId] ?: continue
            val renderShape = state.renderShapes[entityId] ?: continue
            val control = state.controls[entityId] ?: ControlIntentComponent.ZERO
            val thrust = control.thrust / cfg.thrustFactorInv
            val turn = control.turn / cfg.turnFactorInv
            val acc = when (renderShape.shape) {
                BodyShape.TRIANGLE -> Norm.fromAngle(transform.ang) * Frac(thrust)
                BodyShape.CIRCLE -> Frac2.zero
            }

            val vel = motion.vel + acc
            val pos = transform.pos + vel

            val angVel = when (renderShape.shape) {
                BodyShape.TRIANGLE -> motion.angVel + Frac(turn)
                BodyShape.CIRCLE -> motion.angVel
            }
            val ang = Frac(transform.ang.raw + angVel.raw)

            transforms[entityId] = transform.copy(pos = pos, ang = ang)
            motions[entityId] = motion.copy(vel = vel, angVel = angVel)
        }
        return state.copy(
            transforms = state.transforms.putAll(transforms.map { it.key to it.value }),
            motions = state.motions.putAll(motions.map { it.key to it.value }),
        )
    }
}

private object CollisionSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        val transforms = LinkedHashMap(state.transforms.asMap())
        val motions = LinkedHashMap(state.motions.asMap())
        val ids = state.world.entities
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                val aTransform = transforms[aId] ?: continue
                val bTransform = transforms[bId] ?: continue
                val aMotion = motions[aId] ?: continue
                val bMotion = motions[bId] ?: continue
                val aCollider = state.colliders[aId] ?: continue
                val bCollider = state.colliders[bId] ?: continue
                val aMaterial = state.materials[aId] ?: continue
                val bMaterial = state.materials[bId] ?: continue

                // Use shortest torus delta for distance checks + separation.
                val delta = aTransform.pos - bTransform.pos
                val minDist = aCollider.radius + bCollider.radius
                val xPen = minDist - abs(delta.x)
                val yPen = minDist - abs(delta.y)
                if (xPen.sign <= 0 || yPen.sign <= 0) continue

                if (delta >= minDist) continue

                if (delta.lenSq.raw == 0) continue
                delta.capMax(minDist)

                val normal = delta.norm
                val tangent = normal.perp
                val pen = minDist - delta.len

                val massA = aMaterial.mass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
                val massB = bMaterial.mass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
                val totalMass = (massA + massB).coerceAtMost(Int.MAX_VALUE)
                val invMassWeightA = Frac(massB, totalMass)
                val invMassWeightB = Frac(massA, totalMass)

                val pushA = normal * (pen * invMassWeightA)
                val pushB = normal * (pen * invMassWeightB)

                val velDelta = bMotion.vel - aMotion.vel
                val velAlongNorm = velDelta.dot(normal)
                val bounciness = aMaterial.bounce.coerceAtMost(bMaterial.bounce)
                val normResponse = if (velAlongNorm.sign > 0) velAlongNorm * bounciness else Frac(0)

                val roughness = aMaterial.rough.coerceAtMost(bMaterial.rough)
                val circumferenceA = aCollider.radius.toCircumference()
                val circumferenceB = bCollider.radius.toCircumference()
                val spinAlongTangent = aMotion.angVel * circumferenceA + bMotion.angVel * circumferenceB
                val velAlongTangent = velDelta.dot(tangent) - spinAlongTangent
                val tangentResponse = velAlongTangent * roughness

                val pushNormVelA = normal * (normResponse * invMassWeightA)
                val pushNormVelB = normal * (normResponse * invMassWeightB)
                val tangentResponseA = (tangentResponse * invMassWeightA) / 2
                val tangentResponseB = (tangentResponse * invMassWeightB) / 2
                val pushTangentialVelA = tangent * tangentResponseA
                val pushTangentialVelB = tangent * tangentResponseB
                val pushAngVelA = tangentResponseA / circumferenceA
                val pushAngVelB = tangentResponseB / circumferenceB

                transforms[aId] = aTransform.copy(pos = aTransform.pos + pushA)
                transforms[bId] = bTransform.copy(pos = bTransform.pos - pushB)
                motions[aId] = aMotion.copy(
                    vel = aMotion.vel + pushNormVelA + pushTangentialVelA,
                    angVel = aMotion.angVel + pushAngVelA,
                )
                motions[bId] = bMotion.copy(
                    vel = bMotion.vel - pushNormVelB - pushTangentialVelB,
                    angVel = bMotion.angVel + pushAngVelB,
                )
            }
        }
        return state.copy(
            transforms = state.transforms.putAll(transforms.map { it.key to it.value }),
            motions = state.motions.putAll(motions.map { it.key to it.value }),
        )
    }
}
