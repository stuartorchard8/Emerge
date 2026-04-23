package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.nextRandomInt
import org.emerge.sim.core.physics.primitives.*
import kotlin.math.abs

/**
 * Drives autonomous drocket entities through a walk → charge → thrust → fly → land cycle.
 *
 * Godot reference values (mapped to fixed-point equivalents):
 * - Walk duration: 2–10 seconds (120–600 ticks at 60 tps)
 * - Charge spin-up: 0.3 seconds (18 ticks)
 * - Fuel: 1.4 seconds (84 ticks)
 * - Thrust force: 12000 * impulse / delta (mapped to fixed-point impulse per tick)
 */
object DrocketAISystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {

    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val drocketStates = LinkedHashMap(builder.entries<DrocketStateComponent>())
        if (drocketStates.isEmpty()) return
        val animationStates = LinkedHashMap(builder.entries<SpriteAnimationState>())

        val impulses = LinkedHashMap<EntityId, ImpulseComponent>()
        val nextStates = LinkedHashMap<EntityId, DrocketStateComponent>()
        val landings = LinkedHashMap(builder.entries<LandingAttachmentComponent>())

        for ((entityId, ds) in drocketStates) {
            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue

            when (ds.phase) {
                DrocketPhase.WALKING -> {
                    // Walking is handled by WalkSystem; here we only manage the timer.
                    val remaining = ds.ticksRemaining - 1
                    if (remaining <= 0) {
                        // Transition to CHARGING: spin up before launch
                        nextStates[entityId] = ds.copy(
                            phase = DrocketPhase.CHARGING,
                            ticksRemaining = CHARGE_TICKS,
                        )

                        val motion = builder.getComponent<MotionComponent>(entityId) ?: continue
                        val landing = landings[entityId] ?: continue
                        val parentId = landing.parentEntityId
                        val parentMotion = builder.getComponent<MotionComponent>(parentId) ?: continue
                        val parentTransform = builder.getComponent<TransformComponent>(parentId) ?: continue

                        // Detatch from planet and roll forwards
                        val surfaceVelocity = surfaceVelocityAtAttachment(
                            parentTransform,
                            parentMotion,
                            landing,
                        )
                        landings.remove(entityId)
                        val spinDir = CHARGE_SPIN_SPEED*ds.walkDirection
                        impulses[entityId] = ImpulseComponent(
                            vel = surfaceVelocity-motion.vel,
                            angVel = parentMotion.angVel-motion.angVel + spinDir,
                        )
                    } else {
                        nextStates[entityId] = ds.copy(ticksRemaining = remaining)
                    }
                }

                DrocketPhase.CHARGING -> {
                    val remaining = ds.ticksRemaining - 1
                    if (remaining <= 0) {
                        // Start thrusting
                        nextStates[entityId] = ds.copy(
                            phase = DrocketPhase.THRUSTING,
                            fuel = FUEL_TICKS,
                            ticksRemaining = 0,
                        )
                    } else {
                        nextStates[entityId] = ds.copy(ticksRemaining = remaining)
                    }
                }

                DrocketPhase.THRUSTING -> {
                    val motion = builder.getComponent<MotionComponent>(entityId) ?: continue
                    if (abs(motion.angVel.raw) > Coord(1,16).raw) {
                        nextStates[entityId] = ds.copy(
                            phase = DrocketPhase.FLYING,
                            fuel = 0,
                        )
                        continue
                    }
                    val fuelLeft = ds.fuel - 1
                    // Apply thrust in the entity's forward direction
                    var forward = Norm.fromAngle(transform.ang).cw90
                    if (ds.walkDirection < 0) {
                        forward = -forward
                    }
                    val thrustImpulse = forward * THRUST_STRENGTH
                    impulses[entityId] = ImpulseComponent(vel = thrustImpulse)

                    if (fuelLeft <= 0) {
                        nextStates[entityId] = ds.copy(
                            phase = DrocketPhase.FLYING,
                            fuel = 0,
                        )
                    } else {
                        nextStates[entityId] = ds.copy(fuel = fuelLeft)
                    }
                }

                DrocketPhase.FLYING -> {
                    // Check if the entity has landed (collision system attached it)
                    val landing = builder.getComponent<LandingAttachmentComponent>(entityId)
                    if (landing != null) {
                        // Determine new walk direction (reverse from previous)
                        val newDirection = -ds.walkDirection
                        val walkTicks = MIN_WALK_TICKS + builder.nextRandomInt(until = MAX_WALK_TICKS - MIN_WALK_TICKS)
                        nextStates[entityId] = ds.copy(
                            phase = DrocketPhase.WALKING,
                            planetId = landing.parentEntityId,
                            walkDirection = newDirection,
                            ticksRemaining = walkTicks,
                        )
                    } else {
                        nextStates[entityId] = ds
                    }
                }
            }
        }

        for ((entityId, newState) in nextStates) {
            val oldState = drocketStates[entityId]
            drocketStates[entityId] = newState
            if (oldState == null || oldState.phase != newState.phase) {
                val animIndex = when (newState.phase) {
                    DrocketPhase.WALKING -> ANIM_WALK_RIGHT
                    DrocketPhase.CHARGING -> ANIM_IDLE_RIGHT
                    DrocketPhase.THRUSTING -> ANIM_FIRE
                    DrocketPhase.FLYING -> ANIM_IDLE_RIGHT
                }
                SpriteAnimationSystem.setAnimation(
                    animStates = animationStates,
                    entityId = entityId,
                    sheet = SpriteSheet.DROCKET,
                    animationIndex = animIndex,
                )
            }
        }

        for ((entityId, impulse) in impulses) {
            builder.update<ImpulseComponent>(entityId) { impulse + it }
        }
        builder.setTable<LandingAttachmentComponent>(landings)
        builder.setTable<DrocketStateComponent>(drocketStates)
        builder.setTable<SpriteAnimationState>(animationStates)
    }

    private const val CHARGE_TICKS = 18
    // 84 ticks ≈ 1.4 seconds at 60 tps
    private const val FUEL_TICKS = 220
    // Walk 2–10 seconds → 120–600 ticks
    private const val MIN_WALK_TICKS = 120
    private const val MAX_WALK_TICKS = 600
    private val CHARGE_SPIN_SPEED = Frac(1,120)
    // Thrust tuned relative to the engine's gravity to achieve orbital velocity
    private val THRUST_STRENGTH = Frac(1, 1024 * 256)


    private fun surfaceVelocityAtAttachment(
        parentTransform: TransformComponent,
        parentMotion: MotionComponent,
        landing: LandingAttachmentComponent,
    ): Coord2 {
        val worldOffset = landing.relativePos.rotateByAngle(parentTransform.ang)
        return parentMotion.surfaceVelocityAtOffset(
            worldOffset.norm,
            worldOffset.len,
        )
    }
}
