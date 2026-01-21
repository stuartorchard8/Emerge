package org.emerge.sim.core.physics

import kotlin.math.abs
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.space.Torus2D

/**
 * Simple deterministic "arcade physics":
 * - fixed timestep = 1 tick
 * - acceleration from input
 * - damping
 * - true torus topology (wrap-around in X/Y)
 * - naive circle-circle separation + velocity swap-ish
 */
class PhysicsReducer(
    private val accelPerTick: Fx = Fx.fromRaw(120), // 0.12 units/tick^2 at SCALE=1000
    private val damping: Fx = Fx.fromRaw(985), // ~0.985 per tick
) : SimReducer<PhysicsState, PhysicsInput> {

    override fun reduce(state: PhysicsState, inputs: Map<PlayerId, PhysicsInput>): PhysicsState {
        val torus = Torus2D(width = state.width, height = state.height)
        val next = LinkedHashMap<PlayerId, CircleBody>(state.bodies.size)

        // Integrate
        for ((pid, body) in state.bodies) {
            val inp = inputs[pid] ?: PhysicsInput(0, 0)
            val ax = Fx.fromInt(inp.ax) * accelPerTick
            val ay = Fx.fromInt(inp.ay) * accelPerTick
            val acc = Vec2Fx(ax, ay)

            var vel = (body.vel + acc) * damping
            var pos = torus.wrap(body.pos + vel)

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
                val dx = torus.deltaRaw(a.pos.x.raw, b.pos.x.raw, state.width.raw)
                val dy = torus.deltaRaw(a.pos.y.raw, b.pos.y.raw, state.height.raw)
                val distSq = dx.toLong() * dx.toLong() + dy.toLong() * dy.toLong()
                val minDist = a.radius.raw + b.radius.raw
                val minDistSq = minDist.toLong() * minDist.toLong()
                if (distSq >= minDistSq) continue

                // Separate along dominant axis (cheap + deterministic)
                val overlap = minDist - approxDistRaw(dx, dy, minDist)
                if (overlap <= 0) continue

                if (abs(dx) >= abs(dy)) {
                    val push = overlap / 2
                    val sign = if (dx >= 0) 1 else -1
                    val aPos = torus.wrap(Vec2Fx(Fx.fromRaw(a.pos.x.raw + sign * push), a.pos.y))
                    val bPos = torus.wrap(Vec2Fx(Fx.fromRaw(b.pos.x.raw - sign * push), b.pos.y))
                    next[aId] = a.copy(pos = aPos, vel = Vec2Fx(-a.vel.x, a.vel.y))
                    next[bId] = b.copy(pos = bPos, vel = Vec2Fx(-b.vel.x, b.vel.y))
                } else {
                    val push = overlap / 2
                    val sign = if (dy >= 0) 1 else -1
                    val aPos = torus.wrap(Vec2Fx(a.pos.x, Fx.fromRaw(a.pos.y.raw + sign * push)))
                    val bPos = torus.wrap(Vec2Fx(b.pos.x, Fx.fromRaw(b.pos.y.raw - sign * push)))
                    next[aId] = a.copy(pos = aPos, vel = Vec2Fx(a.vel.x, -a.vel.y))
                    next[bId] = b.copy(pos = bPos, vel = Vec2Fx(b.vel.x, -b.vel.y))
                }
            }
        }

        return state.copy(bodies = next)
    }

    private fun approxDistRaw(dx: Int, dy: Int, fallback: Int): Int {
        // We avoid sqrt for determinism/dep-free. This returns a cheap Manhattan-ish distance in raw units.
        // It is only used to decide overlap magnitude; if it ever returns 0, use fallback to avoid huge overlap.
        val adx = abs(dx)
        val ady = abs(dy)
        val d = adx + ady
        return if (d == 0) fallback else d
    }
}

