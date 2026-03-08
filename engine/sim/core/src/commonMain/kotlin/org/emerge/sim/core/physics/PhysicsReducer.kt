package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer

class PhysicsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {

    override fun reduce(cfg: PhysicsConfig, state: PhysicsState, inputs: Map<PlayerId, PhysicsInput>): PhysicsState {
        val next = LinkedHashMap<PlayerId, CircleBody>(state.bodies.size)

        // Integrate
        for ((pid, body) in state.bodies) {
            val inp = inputs[pid] ?: PhysicsInput(0, 0)
            val ax = inp.ax / cfg.accelFactorInv
            val ay = inp.ay / cfg.accelFactorInv
            val acc = Frac2(Frac(ax), Frac(ay))

            val vel = (body.vel + acc)//maxDamping*damping
            val pos = body.pos + vel

            val ang = Frac(body.ang.raw + body.angVel.raw)
            val angVel = body.angVel

            next[pid] = body.copy(pos = pos, vel = vel, ang = ang, angVel = angVel)
        }

        // Circle-circle collision resolution (pairwise)
        val ids = next.keys.toList()
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                val a = next[aId]!!
                val b = next[bId]!!

                // Use shortest torus delta for distance checks + separation.
                val delta = a.pos - b.pos
                val minDist = a.radius + b.radius
                val xPen = minDist-abs(delta.x)
                val yPen = minDist-abs(delta.y)
                if (xPen.sign <= 0 || yPen.sign <= 0) continue

                if (delta >= minDist) continue

                if (delta.lenSq.raw == 0) continue
                delta.capMax(minDist)

                val normal = delta.norm
                val tangent = normal.perp
                val pen = minDist-delta.len

                val massA = a.mass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
                val massB = b.mass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
                val totalMass = (massA + massB).coerceAtMost(Int.MAX_VALUE)
                val invMassWeightA = Frac(massB, totalMass) // mB/(mA+mB)
                val invMassWeightB = Frac(massA, totalMass) // mA/(mA+mB)

                val pushA = normal*(pen*invMassWeightA)
                val pushB = normal*(pen*invMassWeightB)

                val velDelta = b.vel-a.vel
                val velAlongNorm = velDelta.dot(normal)
                val bounciness = a.bounce.coerceAtMost(b.bounce)
                val normResponse = if (velAlongNorm.sign > 0) velAlongNorm*bounciness else Frac(0)

                val roughness = a.rough.coerceAtMost(b.rough)
                // Contact tangential speed includes translational slip and surface speed from spin.
                val circumferenceA = a.radius.toCircumference()
                val circumferenceB = b.radius.toCircumference()
                val spinAlongTangent = a.angVel*circumferenceA + b.angVel*circumferenceB
                val velAlongTangent = velDelta.dot(tangent) - spinAlongTangent
                // Thin-hoop inertia gives tangential response split by inverse-mass weights, then halved.
                val tangentResponse = velAlongTangent*roughness

                val pushNormVelA = normal*(normResponse*invMassWeightA)
                val pushNormVelB = normal*(normResponse*invMassWeightB)
                val tangentResponseA = (tangentResponse*invMassWeightA)/2
                val tangentResponseB = (tangentResponse*invMassWeightB)/2
                val pushTangentialVelA = tangent*tangentResponseA
                val pushTangentialVelB = tangent*tangentResponseB
                val pushAngVelA = tangentResponseA/circumferenceA
                val pushAngVelB = tangentResponseB/circumferenceB

                next[aId] = a.copy(
                    vel = a.vel+pushNormVelA+pushTangentialVelA,
                    pos = a.pos+pushA,
                    angVel = a.angVel+pushAngVelA,
                )
                next[bId] = b.copy(
                    vel = b.vel-pushNormVelB-pushTangentialVelB,
                    pos = b.pos-pushB,
                    angVel = b.angVel+pushAngVelB,
                )
            }
        }

        return state.copy(bodies = next)
    }
}
