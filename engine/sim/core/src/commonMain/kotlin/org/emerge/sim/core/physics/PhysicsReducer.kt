package org.emerge.sim.core.physics

import kotlin.math.abs
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Simple deterministic "arcade physics":
 * - fixed timestep = 1 tick
 * - acceleration from input
 * - damping
 * - true torus topology (wrap-around in X/Y)
 * - naive circle-circle separation + velocity swap-ish
 */
class PhysicsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {

    override fun reduce(cfg: PhysicsConfig, state: PhysicsState, inputs: Map<PlayerId, PhysicsInput>): PhysicsState {
        val next = LinkedHashMap<PlayerId, CircleBody>(state.bodies.size)

        // Integrate
        for ((pid, body) in state.bodies) {
            val inp = inputs[pid] ?: PhysicsInput(0, 0)
            val ax = inp.ax / cfg.accelFactorInv
            val ay = inp.ay / cfg.accelFactorInv
            val acc = Frac2(ax, ay)

            val vel = (body.vel + acc)//maxDamping*damping
            val pos = body.pos + vel

            val ang = Frac(body.ang.raw + body.angVel.raw)
            val angVel = body.angVel

            next[pid] = body.copy(pos = pos, vel = vel, ang = ang, angVel = angVel)
        }

        // Very simple circle-circle collision resolution (pairwise)
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
                if (xPen <= 0 || yPen <= 0) continue

                if (delta >= minDist) continue

                if (delta.len == 0) continue

                val pen = minDist-delta.len
                val push = delta.norm*pen

                val velDelta = b.vel-a.vel
                val velAlongNorm = max(0, velDelta.dot(delta.norm))
                val pushVel = delta.norm*(velAlongNorm/2)
                val pushVelI = Frac2(pushVel.x, pushVel.y)

                val angVelDiff = (a.angVel+b.angVel)
                val velAlongPerp = velDelta.dot(delta.norm.perp)
                val pushAngVel = Frac(velAlongPerp-angVelDiff.raw/32)

                next[aId] = a.copy(vel = a.vel+pushVelI, pos = a.pos+push, angVel = a.angVel+pushAngVel)
                next[bId] = b.copy(vel = b.vel-pushVelI, pos = b.pos-push, angVel = b.angVel+pushAngVel)
            }
        }

        return state.copy(bodies = next)
    }
}
