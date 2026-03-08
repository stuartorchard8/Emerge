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

                val pen = minDist-delta.len
                val push = delta.norm*(pen/2)

                val normal = delta.norm
                val tangent = normal.perp
                val velDelta = b.vel-a.vel
                val velAlongNorm = velDelta.dot(normal)
                val bounciness = a.bounce.coerceAtMost(b.bounce)
                val pushNormVel = if (velAlongNorm.sign > 0) normal*(velAlongNorm*bounciness) else Frac2.zero

                val roughness = a.rough.coerceAtMost(b.rough)
                // Contact tangential speed includes translational slip and surface speed from spin.
                val spinAlongTangent = (a.angVel*a.radius) + (b.angVel*b.radius)
                val velAlongTangent = velDelta.dot(tangent) - spinAlongTangent
                // Equal-mass circles with simple inertia approximation: partial tangential impulse.
                val pushTangent = (velAlongTangent*roughness)/4
                val pushTangentialVel = tangent*pushTangent
                val pushAngVelA = if (a.radius.raw != 0) pushTangent/a.radius else Frac(0)
                val pushAngVelB = if (b.radius.raw != 0) pushTangent/b.radius else Frac(0)

                next[aId] = a.copy(vel = a.vel+pushNormVel+pushTangentialVel, pos = a.pos+push, angVel = a.angVel+pushAngVelA)
                next[bId] = b.copy(vel = b.vel-pushNormVel-pushTangentialVel, pos = b.pos-push, angVel = b.angVel+pushAngVelB)
            }
        }

        return state.copy(bodies = next)
    }
}
