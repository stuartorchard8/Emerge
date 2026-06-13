package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.min

/**
 * Exposed-surface viscous drag — Cyto's port of fesh's asymmetric Aerobody drag.
 *
 * Drag is a surface phenomenon: a connection shields the axis it covers, so a cell only drags on
 * its EXPOSED directions (those not pointing at a connected neighbour). A fully-surrounded interior
 * cell feels ~none; a surface cell drags against its exposed side; a lone cell drags isotropically.
 * Because shielding follows body shape, an elongated organism is slippery along its length and
 * draggy across — the fesh asymmetry, emergent from connectivity rather than a per-cell heading.
 *
 * The drag law is fesh's (`Aerobody.cs`): quadratic in the exposed speed, but capped at the impulse
 * that exactly cancels that speed (`min(s, C·s²)`), so it can slow but never reverse —
 * unconditionally stable, no fudge factor. It reads the start-of-tick velocity, so it's independent
 * of the grab/contact/solve impulses accumulated this tick and is order-insensitive; it runs before
 * the constraint solver, which then propagates a surface cell's deceleration through the organism.
 *
 * (Locomotion — an internal flex against this drag — is a separate, future mechanic.)
 */
object CytoDragSystem : EcsSystem<CytoConfig, SimState, CytoInput> {
    override fun update(
        cfg: CytoConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, CytoInput>,
    ) {
        // The pointer-grabbed cell is under direct kinematic control (a mouse joint), not free-
        // swimming, so environmental drag must not act on it: drag opposing the grab's pull would
        // fight it every tick, and at a high drag coefficient (which cancels a fast cell's velocity in
        // one tick) that fight oscillates — the held cell slings 2,0,2,0 when pulled a long distance.
        // Its neighbours still drag; drag resumes on release.
        val grabbed = inputs.values.firstOrNull()?.grab?.entity

        for ((id, cell) in builder.entries<CytoCellComponent>()) {
            if (id == grabbed) continue
            val transform = builder.getComponent<TransformComponent>(id) ?: continue
            val motion = builder.getComponent<MotionComponent>(id) ?: continue

            // Project out the velocity pointing toward each connected neighbour — those axes are
            // shielded. What remains is motion through the cell's exposed surface (full velocity for
            // a lone, unconnected cell).
            var exposed = motion.vel.asFrac2()
            val springs = builder.getComponent<SpringConstraintComponent>(id)?.springs
            if (springs != null) {
                for (spring in springs) {
                    val neighbour = builder.getComponent<TransformComponent>(spring.other) ?: continue
                    val normal = (neighbour.pos - transform.pos).norm // cell -> neighbour
                    val toward = exposed.dot(normal)
                    if (toward.raw > 0L) exposed -= normal * toward
                }
            }

            val speed = CytoUnits.toLogical(exposed.len) // exposed speed, logical units/tick
            if (speed == 0f) continue
            // fesh: quadratic drag, capped at the speed itself so the impulse can cancel but never
            // reverse the exposed motion. The effective coefficient is the base surface drag plus a
            // width term (cells have cross-sectional width — a wider cell pushes more fluid), scaled
            // by the cell's radius and lower than the base. Both act on the same exposed velocity, so
            // the width drag is shielded by neighbours exactly like the surface drag; capping the
            // combined coefficient keeps the total from ever reversing.
            val coefficient = cfg.dragCoefficient + cfg.cellWidthDragCoefficient * cell.logicalRadius.toFloat()
            val dragSpeed = min(speed, coefficient * speed * speed)
            val impulse = exposed.norm * CytoUnits.len(-dragSpeed) // opposes the exposed velocity
            builder.update<ImpulseComponent>(id) { ImpulseComponent(vel = impulse) + it }
        }
    }
}
