package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.model.*
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac


/**
 * Drockets-only damage policy:
 * - Dynamic destruction threshold for drockets based on current population.
 * - Non-drocket entities keep using [DrocketsConfig.maxHealth].
 */
object DrocketAdaptiveDamageSystem : EcsSystem<DrocketsConfig, PhysicsState, DrocketsInput> {
    private const val DESTRUCTION_BURST_PARTICLE_COUNT = 50
    private val DESTRUCTION_BURST_PARTICLE_RADIUS_FACTOR = Frac(1, 3)
    private val DESTRUCTION_BURST_SPEED_FACTOR = Frac(1, 12)
    private const val DESTRUCTION_BURST_LIFETIME = 42

    private const val POPULATION_FLOOR = 100
    private const val POPULATION_CAP = 600
    private val MAX_MAX_HEALTH = Frac(1, 1) // at/below floor

    /**
     * Bit-spread between the at-floor threshold ([MAX_MAX_HEALTH]) and the at-cap threshold:
     * at-cap raw = `MAX_MAX_HEALTH.raw shr MIN_MAX_HEALTH_BITS`. 15 → at-cap ≈ 65 535 raw.
     * Drives the log-shaped curve in [effectiveDrocketMaxHealth].
     */
    private const val MIN_MAX_HEALTH_BITS = 15

    override fun update(
        cfg: DrocketsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, DrocketsInput>,
    ) {
        val destructionBursts = ArrayList<DestructionBurstSpec>()

        var drocketCount = builder.entries<DrocketStateComponent>().size

        // Sort candidates by accumulated DESC, then entityId ASC for stable tie-break.
        // Each drocket removed in the loop below decrements [drocketCount] and the
        // drocket threshold is recomputed inside the loop, turning the cliff at high
        // pop into a slope: we kill the most-damaged drockets first until the
        // rising threshold meets the surviving cohort's damage levels. Without this,
        // a batch of births in [ReproductionSystem] (which runs earlier this tick)
        // can push [drocketCount] high enough that [MIN_MAX_HEALTH] falls below
        // every existing drocket's accumulated damage, wiping the whole population
        // in a single iteration.
        val candidates = builder.entries<DamageComponent>().entries
            .sortedWith(
                compareByDescending<Map.Entry<EntityId, DamageComponent>> { it.value.accumulated.raw }
                    .thenBy { it.key.value }
            )

        for ((entityId, damage) in candidates) {
            val isDrocket = builder.getComponent<ReproducerComponent>(entityId) != null
            val threshold = if (isDrocket) effectiveDrocketMaxHealth(drocketCount) else cfg.maxHealth

            if (damage.accumulated.raw >= threshold.raw) {
                builder.removeEntity(entityId)
                if (isDrocket) drocketCount--
                continue
            }

            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue
            val motion = builder.getComponent<MotionComponent>(entityId) ?: continue
            val impulse = builder.getComponent<ImpulseComponent>(entityId) ?: ImpulseComponent()

            val total = damage.accumulated + damage.next
            val destroyed = total.raw >= threshold.raw
            if (destroyed) {
                val teamId = builder.getComponent<TeamComponent>(entityId)?.teamId
                val baseRadius = builder.getComponent<ColliderComponent>(entityId)?.radius
                if (teamId != null && baseRadius != null) {
                    destructionBursts += DestructionBurstSpec(
                        pos = transform.pos,
                        vel = motion.vel,
                        recentImpulse = impulse,
                        teamId = teamId,
                        baseRadius = baseRadius,
                    )
                }
            }

            // Audio event emission and respawn queueing are Scavengers-only concepts;
            // Move 5 removed the dead respawn branch from this system entirely.

            builder.update<DamageComponent>(entityId) { DamageComponent(total, damage.next) }
        }

        for (burst in destructionBursts) {
            spawnDestructionBurst(builder, burst)
        }
    }

    /**
     * Threshold below which a drocket survives. As population rises from
     * [POPULATION_FLOOR] to [POPULATION_CAP], threshold drops along a piecewise-linear
     * approximation of `MAX_MAX_HEALTH × 2^(-MIN_MAX_HEALTH_BITS × t)`, where
     * `t = (pop − floor) / (cap − floor)`.
     *
     * The fractional bit shift is computed in fixed-point and resolved as a linear
     * interp between adjacent integer shifts (between `MAX >> n` and `MAX >> (n+1)`).
     * That keeps everything in Long arithmetic — no `exp`/`pow`, identical across
     * JVM and JS — while preserving the log shape so the "death pressure" zone
     * spans the whole pop range rather than concentrating in the last few slots
     * near cap. Zero error at every integer-shift pop (every `span/MIN_MAX_HEALTH_BITS`
     * slots); ~6% deviation from true exponential at octave midpoints.
     */
    private fun effectiveDrocketMaxHealth(population: Int): Frac {
        val cap = POPULATION_CAP.coerceAtLeast(POPULATION_FLOOR + 1)
        val clamped = population.coerceIn(POPULATION_FLOOR, cap)
        val span = cap - POPULATION_FLOOR
        val shiftNum = MIN_MAX_HEALTH_BITS * (clamped - POPULATION_FLOOR)
        val intShift = shiftNum / span                  // which octave (0..MIN_MAX_HEALTH_BITS)
        val frac = shiftNum % span                      // where inside the octave (0..span-1)
        val a = MAX_MAX_HEALTH.raw shr intShift         // top of current octave
        val b = a shr 1                                 // bottom of current octave
        val raw = a - (a - b) * frac / span             // linear interp within octave
        return Frac(raw)
    }

    private fun spawnDestructionBurst(builder: PhysicsBuilder, burst: DestructionBurstSpec) {
        repeat(DESTRUCTION_BURST_PARTICLE_COUNT) {
            val direction = org.emerge.sim.core.physics.primitives.Norm.fromAngle(
                org.emerge.sim.core.physics.primitives.Coord(builder.nextRandomInt())
            )
            val speed = DESTRUCTION_BURST_SPEED_FACTOR * (burst.baseRadius + burst.recentImpulse.vel.len) *
                Frac(builder.nextRandomInt(until = Int.MAX_VALUE).toLong())
            builder.spawnParticle(
                pos = burst.pos + burst.recentImpulse.pos,
                vel = burst.vel + burst.recentImpulse.vel + direction * speed,
                radius = DESTRUCTION_BURST_PARTICLE_RADIUS_FACTOR * burst.baseRadius,
                shape = org.emerge.sim.core.physics.primitives.BodyShape.CIRCLE,
                lifetime = DESTRUCTION_BURST_LIFETIME,
                teamId = burst.teamId,
            )
        }
    }

    private data class DestructionBurstSpec(
        val pos: Coord2,
        val vel: Coord2,
        val recentImpulse: ImpulseComponent,
        val teamId: org.emerge.sim.core.TeamId,
        val baseRadius: Frac,
    )
}
