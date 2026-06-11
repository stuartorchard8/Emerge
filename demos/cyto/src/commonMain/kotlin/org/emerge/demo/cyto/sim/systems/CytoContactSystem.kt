package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.sim.contacts

/**
 * Turns engine contacts into Cyto's behaviour, ported from `MyContactListener`:
 *  - **sticky or close** (overlap past ¾ of the touching distance) non-connected cells
 *    *weld* — a [WeldIntent] for the lifecycle system to spring-join them (no repulsion);
 *  - other overlapping non-connected cells *repel* (a mass-weighted push-apart impulse)
 *    and register *touch* pressure that the Touch gene reads next tick;
 *  - already-connected pairs are left to their spring.
 *
 * Runs after the engine `ContactSystem` and before the biology system (so touch is
 * available this tick).
 */
object CytoContactSystem : EcsSystem<CytoConfig, SimState, CytoInput> {
    override fun update(
        cfg: CytoConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, CytoInput>,
    ) {
        for (contact in builder.contacts) {
            val a = contact.aId
            val b = contact.bId
            val cellA = builder.getComponent<CytoCellComponent>(a) ?: continue
            val cellB = builder.getComponent<CytoCellComponent>(b) ?: continue
            if (springExists(builder, a, b) || springExists(builder, b, a)) continue

            val sticky = cellA.sticky || cellA.stickyTemp || cellB.sticky || cellB.stickyTemp
            // Overlap past ¾ of the touching distance: dist < 0.75·sumRadii  <=>  penetration > 0.25·sumRadii.
            val close = contact.penetration.raw * 4L > contact.minDist.raw
            if (sticky || close) {
                val lo = if (a.value < b.value) a else b
                val hi = if (a.value < b.value) b else a
                builder.emit(WeldIntent(lo, hi))
                continue
            }

            val massA = builder.getComponent<MaterialComponent>(a)?.mass ?: 1u
            val massB = builder.getComponent<MaterialComponent>(b)?.mass ?: 1u
            val total = (massA + massB).toLong()
            if (total <= 0L) continue
            val weightA = Frac(massB.toLong(), total.toInt())
            val weightB = Frac(massA.toLong(), total.toInt())
            val push = contact.penetration * cfg.repulsion
            val normal = contact.normal // points b -> a (delta = aPos - bPos)

            // Push apart: a moves along +normal (away from b), b along -normal.
            builder.update<ImpulseComponent>(a) { ImpulseComponent(vel = normal * (push * weightA)) + it }
            builder.update<ImpulseComponent>(b) { ImpulseComponent(vel = -(normal * (push * weightB))) + it }

            val touchAmount = contact.penetration
            builder.update<CytoCellComponent>(a) { c -> (c ?: cellA).let { it.copy(touch = it.touch + touchAmount) } }
            builder.update<CytoCellComponent>(b) { c -> (c ?: cellB).let { it.copy(touch = it.touch + touchAmount) } }
        }
    }
}
