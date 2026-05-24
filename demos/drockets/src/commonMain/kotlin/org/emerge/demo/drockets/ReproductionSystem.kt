package org.emerge.demo.drockets

import kotlinx.datetime.Clock
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.*


object ReproductionSystem : EcsSystem<DrocketsConfig, SimState, DrocketsInput> {
    const val POPULATION_CAP = 800

    override fun update(
        cfg: DrocketsConfig,
        builder: SimBuilder,
        inputs: Map<PlayerId, DrocketsInput>,
    ) {
        val reproducers = builder.entries<ReproducerComponent>()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        for ((entityId, reproducer) in reproducers) {
            if (reproducer.sex != Sex.FEMALE || !reproducer.isMature(nowMs)) continue
            val spawn = reproducer.spawn
            if (spawn != null && spawn.birthdayMs <= nowMs) {
                birthSpawn(builder, entityId, reproducer, spawn)
            } else if (spawn == null && builder.entries<DrocketStateComponent>().size < POPULATION_CAP) {
                tryConceiveOnContact(builder, entityId, reproducer, nowMs)
            }
        }
    }

    private fun birthSpawn(
        builder: SimBuilder,
        motherEntityId: org.emerge.sim.core.EntityId,
        reproducer: ReproducerComponent,
        spawn: ReproducerComponent,
    ) {
        val landingComponent = builder.getComponent<LandingAttachmentComponent>(motherEntityId) ?: return
        val planetMotion = builder.getComponent<MotionComponent>(landingComponent.parentEntityId) ?: return
        val planetTransform = builder.getComponent<TransformComponent>(landingComponent.parentEntityId) ?: return
        val motherTransform = builder.getComponent<TransformComponent>(motherEntityId) ?: return

        val planetOffset = motherTransform.pos - planetTransform.pos
        val childEntityId = spawnDrocket(
            builder = builder,
            position = motherTransform.pos,
            velocity = planetMotion.surfaceVelocityAtOffset(planetOffset.norm, planetOffset.len),
            angle = motherTransform.ang,
            sex = spawn.sex,
            seed = motherEntityId.value,
        )
        reproducer.spawnGenome?.let { childGenome ->
            builder.update<GenomeComponent>(childEntityId) { GenomeComponent(childGenome) }
        }
        builder.update<LineageSeedComponent>(childEntityId) {
            LineageSeedComponent(
                motherEntityId = reproducer.spawnMotherEntityId,
                fatherEntityId = reproducer.spawnFatherEntityId,
            )
        }
        builder.update<ReproducerComponent>(motherEntityId) {
            reproducer.copy(
                spawn = null,
                spawnGenome = null,
                spawnMotherEntityId = null,
                spawnFatherEntityId = null,
            )
        }
    }

    private fun tryConceiveOnContact(
        builder: SimBuilder,
        motherEntityId: org.emerge.sim.core.EntityId,
        reproducer: ReproducerComponent,
        nowMs: Long,
    ) {
        val reproducers = builder.entries<ReproducerComponent>()
        for (contact in builder.contacts) {
            if (contact.aId != motherEntityId && contact.bId != motherEntityId) continue
            val fatherId = if (contact.aId != motherEntityId) contact.aId else contact.bId
            val fatherReproducer = reproducers[fatherId] ?: continue
            if (fatherReproducer.sex != Sex.MALE || !fatherReproducer.isMature(nowMs)) continue

            val motherGenome = builder.getComponent<GenomeComponent>(motherEntityId)?.genome
            val fatherGenome = builder.getComponent<GenomeComponent>(fatherId)?.genome
            val childSex = if (builder.nextRandomInt() % 2 == 0) Sex.FEMALE else Sex.MALE
            val childGenome = mixParentGenomes(
                mother = motherGenome,
                father = fatherGenome,
                childSex = childSex,
                nextRandom = { builder.nextRandomInt() },
            )
            builder.update<ReproducerComponent>(motherEntityId) {
                reproducer.copy(
                    spawn = ReproducerComponent(
                        birthdayMs = nowMs + reproducer.gestationDuration,
                        sex = childSex,
                    ),
                    spawnGenome = childGenome,
                    spawnMotherEntityId = motherEntityId.value,
                    spawnFatherEntityId = fatherId.value,
                )
            }
            return
        }
    }

    /**
     * Per-gene crossover with sex-linked body color (males inherit father's, females mother's)
     * and coin-flip fire color. Single-parent case copies that parent's genes unchanged before
     * mutation. Final step mutates each gene independently.
     */
    private fun mixParentGenomes(
        mother: Genome?,
        father: Genome?,
        childSex: Sex,
        nextRandom: () -> Int,
    ): Genome? {
        if (mother == null && father == null) return null
        val m = mother ?: father!!
        val f = father ?: mother!!
        fun coin(): Boolean = (nextRandom() and 1) == 0

        val bodyParent = if (childSex == Sex.MALE) f else m
        val fireParent = if (coin()) m else f

        return Genome(
            aiWalkMinTicks = if (coin()) m.aiWalkMinTicks else f.aiWalkMinTicks,
            aiWalkMaxTicks = if (coin()) m.aiWalkMaxTicks else f.aiWalkMaxTicks,
            aiChargeTicks = if (coin()) m.aiChargeTicks else f.aiChargeTicks,
            aiFuelTicks = if (coin()) m.aiFuelTicks else f.aiFuelTicks,
            aiSpin = if (coin()) m.aiSpin else f.aiSpin,
            aiThrust = if (coin()) m.aiThrust else f.aiThrust,
            bodyColor = bodyParent.bodyColor,
            fireColor = fireParent.fireColor,
        ).mutated(nextRandom)
    }

}

/**
 * Adds an independent small zero-mean delta in `[-127, 127]` to every gene's raw value,
 * saturating at [Int.MIN_VALUE] / [Int.MAX_VALUE] so a mutation at the extremes cannot
 * wrap around to the opposite end of the decoded range.
 *
 * The delta distribution: take the random byte's natural `[-128, 127]` signed-byte
 * range and shift every negative value up by 1, mapping `[-128, -1]` to `[-127, 0]`.
 * Result spans `[-127, 127]` with `0` appearing twice as often as any other value
 * (256 outcomes over 255 distinct deltas). Mean = 0 exactly — no positive or negative
 * drift over generations; the slight "stay near zero" bias is intentional.
 *
 * Because raw values span the full Int range, a ±127 delta corresponds to a tiny shift
 * in decoded phenotype (~6e-8 of the gene's [min,max] span).
 *
 * Top-level + `internal` so tests can assert saturation + distribution properties.
 */
internal fun Genome.mutated(nextRandom: () -> Int): Genome {
    fun mutateField(rawValue: Int): Int {
        val raw = (nextRandom() and 0xFF) - 128 // [-128, 127], mean -0.5
        val delta = if (raw < 0) raw + 1 else raw // [-127, 127], 0 appears twice, mean 0
        return saturatingAdd(rawValue, delta)
    }
    return copy(
        aiWalkMinTicks = mutateField(aiWalkMinTicks),
        aiWalkMaxTicks = mutateField(aiWalkMaxTicks),
        aiChargeTicks = mutateField(aiChargeTicks),
        aiFuelTicks = mutateField(aiFuelTicks),
        aiSpin = mutateField(aiSpin),
        aiThrust = mutateField(aiThrust),
        bodyColor = bodyColor.copy(
            rawH = mutateField(bodyColor.rawH),
            rawS = mutateField(bodyColor.rawS),
            rawV = mutateField(bodyColor.rawV),
        ),
        fireColor = fireColor.copy(
            rawH = mutateField(fireColor.rawH),
            rawS = mutateField(fireColor.rawS),
            rawV = mutateField(fireColor.rawV),
        ),
    )
}

/** Adds `a + b` clamping at the Int range. Avoids wraparound for values near the boundary. */
internal fun saturatingAdd(a: Int, b: Int): Int {
    val r = a.toLong() + b.toLong()
    return when {
        r > Int.MAX_VALUE -> Int.MAX_VALUE
        r < Int.MIN_VALUE -> Int.MIN_VALUE
        else -> r.toInt()
    }
}
