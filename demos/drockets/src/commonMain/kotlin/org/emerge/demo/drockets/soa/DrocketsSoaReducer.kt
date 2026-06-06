package org.emerge.demo.drockets.soa

import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Struct-of-arrays tick for the drockets simulation on a persistent [DrocketsWorld] — the
 * Phase-2 analogue of cyto's [org.emerge.demo.cyto.sim.soa.CytoSoaReducer], being assembled
 * **one phase at a time**, each gated bit-identical against its array-of-structs counterpart
 * (see `DrocketsSoaPhaseEquivalenceTest`) before the next is added.
 *
 * Why incremental rather than a single rewrite: drockets composes ~20 engine + demo systems,
 * three of its phases are `.isolated()` (fork/merge semantics that `runSequential` honors and
 * the SoA path must reproduce), and its entities are heterogeneous (columns don't align across
 * types). Porting + gating per phase keeps every step verifiable.
 *
 * Math reconstructs the engine value types from column reads and reuses the exact operators, so
 * results are bit-identical by construction (the cyto lesson).
 *
 * Ported so far: `integrate`.
 */
class DrocketsSoaReducer {

    /**
     * `integrate` (engine `IntegrationSystem`): semi-implicit Euler over every Motion-bearing
     * entity. Each entity reads only its own Transform/Motion/Impulse and writes its own
     * Transform/Motion, so the update is order-independent — the SoA ascending-slot sweep is
     * bit-identical to the array-of-structs insertion-order sweep.
     */
    fun integrate(w: DrocketsWorld) {
        val motionCols = w.world.columns(MotionComponent::class)
        val transformCols = w.world.columns(TransformComponent::class)
        val impulseCols = w.world.columns(ImpulseComponent::class)
        val count = motionCols.count
        for (slot in 0 until count) {
            val id = motionCols.entityAt(slot)
            val tSlot = transformCols.slotOf(id)
            if (tSlot < 0) continue
            val motion = motionCols.gatherAt(slot)
            val transform = transformCols.gatherAt(tSlot)
            val iSlot = impulseCols.slotOf(id)
            val impulse = if (iSlot < 0) ImpulseComponent() else impulseCols.gatherAt(iSlot)

            // v1 = v0 + at ; p1 = p0 + impulse.pos + v1 (v1 for gravitational stability)
            val vel = motion.vel + impulse.vel
            val pos = transform.pos + impulse.pos + vel.asFrac2()
            val ang = transform.ang + Frac(motion.angVel.raw.toLong()) + impulse.angVel / 2
            val angVel = motion.angVel + impulse.angVel

            transformCols.put(id, transform.copy(pos = pos, ang = ang))
            motionCols.put(id, motion.copy(vel = vel, angVel = angVel))
        }
    }
}
