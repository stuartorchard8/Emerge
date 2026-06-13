package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Mouse-drag, the native analogue of Cyto's kinematic-body + joint grab: while a cell is
 * held, pull it toward the pointer with a stiff, damped velocity impulse (a "mouse joint").
 * Its connected neighbours follow through their springs — which is exactly what makes the
 * spring/contact tuning feel-able. Runs after the impulse reset, alongside the spring solver.
 */
object CytoGrabSystem : EcsSystem<CytoConfig, SimState, CytoInput> {
    override fun update(
        cfg: CytoConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, CytoInput>,
    ) {
        val grab = inputs.values.firstOrNull()?.grab ?: return
        val transform = builder.getComponent<TransformComponent>(grab.entity) ?: return
        val vel = builder.getComponent<MotionComponent>(grab.entity)?.vel?.asFrac2()
            ?: return

        val target = CytoUnits.coord2(grab.x, grab.y)
        val toTarget = target - transform.pos // Frac2, torus-aware
        // Cap the reach: a far/fast pointer would otherwise inject a teleporting one-tick velocity
        // (pull ∝ distance, unclamped) that whips the cell's spring network. Beyond grabMaxReach the
        // cell just follows at a bounded speed (grabStiffness × grabMaxReach per tick).
        val maxReach = CytoUnits.len(cfg.grabMaxReach)
        val reach = if (toTarget.len > maxReach) toTarget.norm * maxReach else toTarget
        // Spring toward the pointer, damped by current velocity.
        val pull = reach * cfg.grabStiffness - vel * cfg.grabDamping
        builder.update<ImpulseComponent>(grab.entity) { ImpulseComponent(vel = pull) + it }

        // Sticky hold mode: the dragged cell welds to whatever it touches. Set the transient
        // stickyTemp (the biology system resets it each tick, so it clears on release); the
        // contact system reads it next tick.
        if (grab.sticky) {
            val cell = builder.getComponent<CytoCellComponent>(grab.entity)
            if (cell != null) {
                builder.update<CytoCellComponent>(grab.entity) { c -> (c ?: cell).copy(stickyTemp = true) }
            }
        }
    }
}
