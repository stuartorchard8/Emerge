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
            val acc = Vec2i(ax, ay)

            val vel = (body.vel + acc)//maxDamping*damping
            val pos = body.pos + vel

            val ang = body.ang + body.angVel
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

                val distSq = delta.distSq()
                val minDistSq = minDist.toLong() * minDist.toLong()
                if (distSq >= minDistSq) continue

                val dist = longISqrt(distSq).toInt()
                if (dist == 0) continue
                val norm = delta/dist.toFloat()

                val perp = Vec2(norm.y, -norm.x)

                val pen = minDist-dist
                val push = norm*pen

                val velDelta = b.vel-a.vel
                val velAlongNorm = max(0f, velDelta.dot(norm))*0.9f
                val pushVel = norm*velAlongNorm
                val pushVelI = Vec2i(pushVel.x.roundToInt(), pushVel.y.roundToInt())

                val angVelDiff = (a.angVel+b.angVel)
                val velAlongPerp = velDelta.dot(perp)*0.9f
                val pushAngVel = velAlongPerp.roundToInt()-angVelDiff/100

                next[aId] = a.copy(vel = a.vel+pushVelI, pos = a.pos+push/2, angVel = a.angVel+pushAngVel)
                next[bId] = b.copy(vel = b.vel-pushVelI, pos = b.pos-push/2, angVel = b.angVel+pushAngVel)
            }
        }

        return state.copy(bodies = next)
    }

    fun longISqrt(n: Long): Long {
        if (n < 2) return n
        var low = 1L
        var high = n / 2
        var result = 1L
        while (low <= high) {
            val mid = low + (high - low) / 2
            if (mid <= n / mid) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
