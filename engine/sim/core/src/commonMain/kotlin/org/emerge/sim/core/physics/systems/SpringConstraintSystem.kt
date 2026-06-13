package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimInput
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Sequential-impulse distance-constraint solver (Box2D-style). Each [SpringConstraint] holds two
 * bodies near its rest length. Per tick the solver:
 *
 *   1. reads each body's *working velocity* = its motion velocity plus any impulse already
 *      accumulated this tick (contacts, grab, …), so the constraints resolve against those;
 *   2. runs [iterations] Gauss–Seidel passes over the unique pairs. Each pass applies, per pair,
 *      the mass-weighted impulse that drives the relative normal velocity toward
 *      `-stiffness·lengthError` (a gentle position bias) and damps it by `damping` — applied
 *      **in place**, so the next constraint in the pass sees it;
 *   3. writes back each body's net impulse = workingVelocity − motionVelocity.
 *
 * Applying impulses in place (rather than summing every spring's correction against the same
 * frozen velocities, Jacobi-style) is what makes it stable at any connectivity: a body's springs
 * see each other within the solve, so none receives a summed over-correction. There is therefore
 * no relaxation / under-damping fudge factor — the solver is stable by default. Likewise, because
 * the grab impulse is read in at step 1, a dragged organism's neighbours are pulled along within
 * the same tick (no lag-stretch), with no grab special-casing anywhere.
 *
 * Positions are frozen during the solve (semi-implicit: solve velocities, then [IntegrationSystem]
 * integrates once). Pairs are processed in a fixed `(loId, hiId)` order, so the integer-[Frac]
 * result is deterministic regardless of component-table iteration order. Sequential by design —
 * the only user (cyto) is small enough that the parallel Jacobi solver this replaced wasn't worth
 * its complexity. A spring must be registered on (at least) the smaller of its two endpoint ids.
 */
class SpringConstraintSystem(
    private val iterations: Int = 4,
) : EcsSystem<PhysicsTuning, SimState, SimInput> {

    override fun update(
        cfg: PhysicsTuning,
        builder: SimBuilder,
        inputs: Map<PlayerId, SimInput>,
    ) {
        val springComps = builder.entries<SpringConstraintComponent>()
        if (springComps.isEmpty()) return

        // Working set: every entity that bears or is referenced by a spring, in a dense index.
        // `vel` is the mutable working velocity; `baseVel` is the motion velocity it's measured
        // against when we emit the net impulse.
        val index = HashMap<EntityId, Int>()
        val ids = ArrayList<EntityId>()
        val pos = ArrayList<Coord2>()
        val baseVel = ArrayList<Frac2>()
        val vel = ArrayList<Frac2>()
        val mass = ArrayList<Long>()

        fun ensure(id: EntityId): Int {
            index[id]?.let { return it }
            val i = ids.size
            index[id] = i
            val p: Coord2 = builder.getComponent<TransformComponent>(id)?.pos ?: Coord2.zero
            val bv: Frac2 = builder.getComponent<MotionComponent>(id)?.vel?.asFrac2() ?: Frac2.zero
            val imp: Frac2 = builder.getComponent<ImpulseComponent>(id)?.vel ?: Frac2.zero
            val m: Long = (builder.getComponent<MaterialComponent>(id)?.mass ?: 1u).toLong()
            ids.add(id)
            pos.add(p)
            baseVel.add(bv)
            vel.add(bv + imp)
            mass.add(m)
            return i
        }

        // Each pair once, owned by the lower-id endpoint.
        val constraints = ArrayList<Constraint>()
        for ((id, comp) in springComps) {
            for (spring in comp.springs) {
                if (spring.other.value <= id.value) continue
                constraints.add(Constraint(ensure(id), ensure(spring.other), spring))
            }
        }
        if (constraints.isEmpty()) return
        constraints.sortWith(compareBy({ ids[it.a].value }, { ids[it.b].value }))

        repeat(iterations) {
            for (con in constraints) {
                val a = con.a
                val b = con.b
                val delta = pos[b] - pos[a] // A -> B
                val dist = delta.len
                if (dist.raw == 0L) continue
                val normal = delta.normFromLen(dist)

                val lengthError = dist - con.spring.restLength // +ve = stretched
                val relVel = (vel[b] - vel[a]).dot(normal) // +ve = separating
                // Drive relVel toward the position-bias target and damp it (same per-pair gains
                // as the old penalty form), then split by mass — the lighter body moves more.
                val closing = lengthError * con.spring.stiffness + relVel * con.spring.damping
                val totalMass = mass[a] + mass[b]
                if (totalMass <= 0L) continue
                val weightA = Frac(mass[b], totalMass.toInt())
                val weightB = Frac(mass[a], totalMass.toInt())

                // A accelerates toward B (+normal); B toward A (-normal). In place.
                vel[a] = vel[a] + normal * (closing * weightA)
                vel[b] = vel[b] - normal * (closing * weightB)
            }
        }

        // Net impulse = working − motion velocity (this includes the contact/grab impulse we read
        // in plus the constraint's contribution), set with one write each (preserving pos/angVel).
        for (i in ids.indices) {
            val net = vel[i] - baseVel[i]
            builder.update<ImpulseComponent>(ids[i]) { (it ?: ImpulseComponent()).copy(vel = net) }
        }
    }

    private class Constraint(val a: Int, val b: Int, val spring: SpringConstraint)
}
