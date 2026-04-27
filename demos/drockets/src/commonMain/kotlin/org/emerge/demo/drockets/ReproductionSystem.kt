package org.emerge.demo.drockets

import kotlinx.datetime.Clock
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.*
import org.emerge.sim.core.physics.primitives.PhysicsInput

object ReproductionSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {

    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val reproducers = builder.entries<ReproducerComponent>()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        for ((entityId, reproducer) in reproducers) {
            if (reproducer.sex == Sex.FEMALE && reproducer.isMature(nowMs)) {
                val spawn = reproducer.spawn
                if (spawn != null && spawn.birthdayMs >= nowMs) {
                    // If landed, eject spawn
                    val landingComponent = builder.getComponent<LandingAttachmentComponent>(entityId) ?: continue
                    val planetMotion = builder.getComponent<MotionComponent>(landingComponent.parentEntityId) ?: continue
                    val planetTransform = builder.getComponent<TransformComponent>(landingComponent.parentEntityId) ?: continue
                    val transformComponent = builder.getComponent<TransformComponent>(entityId) ?: continue
                    val teamComponent = builder.getComponent<TeamComponent>(entityId) ?: continue
                    val planetOffset = transformComponent.pos - planetTransform.pos
                    val planetNorm = planetOffset.norm
                    val planetDist = planetOffset.len
                    spawnDrocket(
                        builder,
                        transformComponent.pos,
                        planetMotion.surfaceVelocityAtOffset(planetNorm, planetDist),
                        transformComponent.ang,
                        teamComponent.teamId,
                    )
                    val updated = reproducer.copy(
                        spawn = null,
                    )
                    builder.update<ReproducerComponent>(entityId) { updated }
                    println("Spawned: ${builder.entries<DrocketStateComponent>().size}")
                } else if (builder.entries<DrocketStateComponent>().size < MAX_DROCKET_COUNT) {
                    // Go through contacts to determine whether contact with a male reproducer has occurred
                    val contacts = builder.contacts.filter { it.aId == entityId || it.bId == entityId }
                    for (contact in contacts) {
                        val otherId = if (contact.aId != entityId) contact.aId else contact.bId
                        val otherReproducer = reproducers[otherId] ?: continue
                        if (otherReproducer.sex == Sex.MALE && otherReproducer.isMature(nowMs)) {
                            val updated = reproducer.copy(
                                spawn = ReproducerComponent(
                                    birthdayMs = nowMs + reproducer.gestationDuration,
                                    sex = if (builder.nextRandomInt()%2 == 0) Sex.FEMALE else Sex.MALE
                                ),
                            )
                            builder.update<ReproducerComponent>(entityId) { updated }
                            return
                        }
                    }
                }
            }
        }
    }
}
