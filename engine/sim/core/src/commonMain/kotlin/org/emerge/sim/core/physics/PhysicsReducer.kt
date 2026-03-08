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

                val velDelta = b.vel-a.vel
                val velAlongNorm = velDelta.dot(delta.norm)
                val bounce = a.bounce.coerceAtMost(b.bounce)
                val pushVel = if (velAlongNorm.sign > 0) delta.norm*(velAlongNorm*bounce) else Frac2.zero

                val angVelDiff = (a.angVel+b.angVel)
                val rough = a.rough.coerceAtMost(b.rough)
                val velAlongPerp = -velDelta.dot(delta.norm.perp)
                val pushAngVel = (velAlongPerp-angVelDiff)*rough

                next[aId] = a.copy(vel = a.vel+pushVel, pos = a.pos+push, angVel = a.angVel+pushAngVel)
                next[bId] = b.copy(vel = b.vel-pushVel, pos = b.pos-push, angVel = b.angVel+pushAngVel)
            }
        }

        return state.copy(bodies = next)
    }
}
