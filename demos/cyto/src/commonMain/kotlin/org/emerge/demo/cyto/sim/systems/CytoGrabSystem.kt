package org.emerge.demo.cyto.sim.systems

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
        // Spring toward the pointer, damped by current velocity.
        val pull = toTarget * cfg.grabStiffness - vel * cfg.grabDamping
        builder.update<ImpulseComponent>(grab.entity) { ImpulseComponent(vel = pull) + it }
    }
}
