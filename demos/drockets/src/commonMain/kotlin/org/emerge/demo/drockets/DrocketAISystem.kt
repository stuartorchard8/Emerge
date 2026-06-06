package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimBuilder

import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.sim.nextRandomInt
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
object DrocketAISystem : EcsSystem<DrocketsConfig, SimState, DrocketsInput> {

    override fun update(
        cfg: DrocketsConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, DrocketsInput>,
    ) {
        val drocketStates = LinkedHashMap(builder.entries<DrocketStateComponent>())
        if (drocketStates.isEmpty()) return
        val animationStates = LinkedHashMap(builder.entries<SpriteAnimationState>())

        val impulses = LinkedHashMap<EntityId, ImpulseComponent>()
        val nextStates = LinkedHashMap<EntityId, DrocketStateComponent>()
        val landings = LinkedHashMap(builder.entries<LandingAttachmentComponent>())

        for ((entityId, ds) in drocketStates) {
            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue
            val tuning = tuningFor(builder.getComponent<GenomeComponent>(entityId))

            when (ds.phase) {
                DrocketPhase.WALKING -> {
                    // Walking is handled by WalkSystem; here we only manage the timer.
                    val remaining = ds.ticksRemaining - 1
                    if (remaining <= 0) {
                        val reproducer = builder.getComponent<ReproducerComponent>(entityId) ?: continue
                        if (!reproducer.isMature(builder.nowMs)) {
                            val newDirection = builder.nextRandomInt(until = 2)*2 - 1
                            val walkRange = (tuning.maxWalkTicks - tuning.minWalkTicks).coerceAtLeast(1)
                            val walkTicks = tuning.minWalkTicks + builder.nextRandomInt(until = walkRange)
                            nextStates[entityId] = ds.copy(
                                walkDirection = newDirection,
                                ticksRemaining = walkTicks,
                            )
                            continue
                        }
                        // Transition to CHARGING: spin up before launch
                        nextStates[entityId] = ds.copy(
                            phase = DrocketPhase.CHARGING,
                            ticksRemaining = tuning.chargeTicks,
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
                        val spinDir = tuning.chargeSpinSpeed * ds.walkDirection
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
                            fuel = tuning.fuelTicks,
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
                    val thrustImpulse = forward * tuning.thrustStrength
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
                        val walkRange = (tuning.maxWalkTicks - tuning.minWalkTicks).coerceAtLeast(1)
                        val walkTicks = tuning.minWalkTicks + builder.nextRandomInt(until = walkRange)
                        nextStates[entityId] = ds.copy(
                            phase = DrocketPhase.WALKING,
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
    private const val FUEL_TICKS = 200
    // Walk 2–10 seconds → 120–600 ticks
    private const val MIN_WALK_TICKS = 120
    private const val MAX_WALK_TICKS = 600
    private const val CHARGE_SPIN_NUMERATOR = 1
    private const val CHARGE_SPIN_DENOMINATOR = 120
    private val CHARGE_SPIN_SPEED = Frac(CHARGE_SPIN_NUMERATOR.toLong(), CHARGE_SPIN_DENOMINATOR)
    // Thrust tuned relative to the engine's gravity to achieve orbital velocity
    private const val THRUST_NUMERATOR = 1
    private const val THRUST_DENOMINATOR = 1024 * 256
    private val THRUST_STRENGTH = Frac(THRUST_NUMERATOR.toLong(), THRUST_DENOMINATOR)

    private data class AiTuning(
        val chargeTicks: Int,
        val fuelTicks: Int,
        val minWalkTicks: Int,
        val maxWalkTicks: Int,
        val chargeSpinSpeed: Frac,
        val thrustStrength: Frac,
    )

    private fun tuningFor(genome: GenomeComponent?): AiTuning {
        val p = genome?.genome?.phenotype()
        val minWalk = p?.aiWalkMinTicks ?: MIN_WALK_TICKS
        // maxWalk must exceed minWalk so the AI's walk-duration RNG range is non-empty.
        val maxWalk = (p?.aiWalkMaxTicks ?: MAX_WALK_TICKS).coerceAtLeast(minWalk + 1)
        return AiTuning(
            chargeTicks = p?.aiChargeTicks ?: CHARGE_TICKS,
            fuelTicks = p?.aiFuelTicks ?: FUEL_TICKS,
            minWalkTicks = minWalk,
            maxWalkTicks = maxWalk,
            chargeSpinSpeed = Frac((p?.aiSpin ?: CHARGE_SPIN_SPEED.raw.toInt()).toLong()),
            thrustStrength = Frac((p?.aiThrust ?: THRUST_STRENGTH.raw.toInt()).toLong()),
        )
    }


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
