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
class PhysicsReducer(
    private val accelPerTick: Int = 1024*1024,
) : SimReducer<PhysicsState, PhysicsInput> {

    override fun reduce(state: PhysicsState, inputs: Map<PlayerId, PhysicsInput>): PhysicsState {
        val next = LinkedHashMap<PlayerId, CircleBody>(state.bodies.size)

        // Integrate
        for ((pid, body) in state.bodies) {
            val inp = inputs[pid] ?: PhysicsInput(0, 0)
            val ax = inp.ax * accelPerTick
            val ay = inp.ay * accelPerTick
            val acc = Vec2Fx(ax, ay)

            val vel = (body.vel + acc)//maxDamping*damping
            val pos = body.pos + vel

            next[pid] = body.copy(pos = pos, vel = vel)
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

                val norm = delta.estNorm()
                val dist = delta.dot(norm)
                if (dist == 0f) continue

                val pen = minDist-dist
                val pushF = norm*pen
                val push = Vec2Fx(pushF.x.roundToInt(), pushF.y.roundToInt())

                val velDelta = b.vel-a.vel
                val velAlongNorm = max(0f, velDelta.dot(norm))*0.999f
                val pushVel = norm*velAlongNorm
                val pushVelI = Vec2Fx(pushVel.x.roundToInt(), pushVel.y.roundToInt())

                next[aId] = a.copy(vel = a.vel+pushVelI, pos = a.pos+push/2)
                next[bId] = b.copy(vel = b.vel-pushVelI, pos = b.pos-push/2)
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
