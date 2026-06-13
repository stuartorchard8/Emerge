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
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Sequential-impulse distance-constraint solver (Box2D-style), with **split-impulse** position
 * correction. Each [SpringConstraint] holds two bodies near its rest length. Per tick the solver runs
 * two independent Gauss–Seidel passes ([iterations] each) over the unique pairs, applied **in place**
 * so each constraint sees the previous one's result:
 *
 *   1. **Velocity** — cancel the relative *normal* velocity (scaled by `damping`), mass-weighted.
 *      Equal-and-opposite ⇒ momentum-conserving; pure velocity cancellation ⇒ dissipative, never
 *      energy-adding. Emitted on [ImpulseComponent.vel]. The working velocity is seeded with the
 *      impulse already accumulated this tick (contacts, grab, drag), so a dragged organism's
 *      neighbours are pulled along the same tick — no grab special-casing.
 *   2. **Position** — correct the length error (scaled by `stiffness`) by moving *working positions*
 *      toward rest, mass-weighted. Emitted on [ImpulseComponent.pos], which [IntegrationSystem] adds
 *      straight to position **without touching velocity**.
 *
 * Splitting the two channels is the whole point: position correction is a pseudo-velocity that never
 * becomes real velocity, so it can't pump kinetic energy. (A single combined `stiffness·error +
 * damping·relVel` velocity impulse — the prior design — injected velocity to fix position error,
 * which the asymmetric drag then rectified into runaway locomotion.)
 *
 * In-place (Gauss–Seidel) application makes it stable at any connectivity — a body's springs see each
 * other within the pass, so none gets a summed over-correction — with no relaxation/fudge factor.
 * Pairs are processed in a fixed `(loId, hiId)` order, so the integer-[Frac] result is deterministic
 * regardless of component-table iteration order. Sequential by design — the only user (cyto) is small
 * enough that the parallel Jacobi solver this replaced wasn't worth its complexity. A spring must be
 * registered on (at least) the smaller of its two endpoint ids.
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
        val pos0 = ArrayList<Frac2>() // start positions: velocity-solve normals + displacement reference
        val pos = ArrayList<Frac2>()  // working positions, moved by the position (pseudo-velocity) solve
        val baseVel = ArrayList<Frac2>()
        val vel = ArrayList<Frac2>()
        val mass = ArrayList<Long>()

        fun ensure(id: EntityId): Int {
            index[id]?.let { return it }
            val i = ids.size
            index[id] = i
            val p: Frac2 = builder.getComponent<TransformComponent>(id)?.pos?.asFrac2() ?: Frac2.zero
            val bv: Frac2 = builder.getComponent<MotionComponent>(id)?.vel?.asFrac2() ?: Frac2.zero
            val imp: Frac2 = builder.getComponent<ImpulseComponent>(id)?.vel ?: Frac2.zero
            val m: Long = (builder.getComponent<MaterialComponent>(id)?.mass ?: 1u).toLong()
            ids.add(id)
            pos0.add(p)
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

        // Split-impulse, Box2D-style: solve the velocity and position constraints on SEPARATE channels,
        // so position correction never becomes real velocity (and so can't pump kinetic energy — the
        // bug where a spring's position bias fed the asymmetric drag's ratchet).

        // 1) Velocity solve (Gauss–Seidel): cancel the relative NORMAL velocity (damping only — no
        //    position bias), mass-weighted, in place. Equal-and-opposite ⇒ momentum-conserving; pure
        //    velocity cancellation ⇒ dissipative, never energy-adding. Normals from the start positions.
        repeat(iterations) {
            for (con in constraints) {
                val a = con.a
                val b = con.b
                val delta = pos0[b] - pos0[a]
                val dist = delta.len
                if (dist.raw == 0L) continue
                val normal = delta.normFromLen(dist)
                val totalMass = mass[a] + mass[b]
                if (totalMass <= 0L) continue
                val relVel = (vel[b] - vel[a]).dot(normal) // +ve = separating
                val vCorr = relVel * con.spring.damping
                val weightA = Frac(mass[b], totalMass.toInt())
                val weightB = Frac(mass[a], totalMass.toInt())
                vel[a] = vel[a] + normal * (vCorr * weightA)
                vel[b] = vel[b] - normal * (vCorr * weightB)
            }
        }

        // 2) Position solve (pseudo-velocity): correct the length error by moving the working POSITIONS
        //    toward rest (stiffness = fraction of the error closed per tick), mass-weighted, in place.
        //    Emitted as a position-only impulse below — it shifts position without touching velocity.
        repeat(iterations) {
            for (con in constraints) {
                val a = con.a
                val b = con.b
                val delta = pos[b] - pos[a]
                val dist = delta.len
                if (dist.raw == 0L) continue
                val normal = delta.normFromLen(dist)
                val totalMass = mass[a] + mass[b]
                if (totalMass <= 0L) continue
                val lengthError = dist - con.spring.restLength // +ve = stretched
                val pCorr = lengthError * con.spring.stiffness
                val weightA = Frac(mass[b], totalMass.toInt())
                val weightB = Frac(mass[a], totalMass.toInt())
                pos[a] = pos[a] + normal * (pCorr * weightA)
                pos[b] = pos[b] - normal * (pCorr * weightB)
            }
        }

        // Emit: the real velocity change on .vel (this includes the contact/grab impulse we read in
        // plus the constraint's contribution); the position correction on .pos (added, so we don't
        // clobber any prior position impulse). IntegrationSystem applies .pos to position only.
        for (i in ids.indices) {
            val vNet = vel[i] - baseVel[i]
            val pNet = pos[i] - pos0[i]
            builder.update<ImpulseComponent>(ids[i]) { cur ->
                val c = cur ?: ImpulseComponent()
                c.copy(vel = vNet, pos = c.pos + pNet)
            }
        }
    }

    private class Constraint(val a: Int, val b: Int, val spring: SpringConstraint)
}
