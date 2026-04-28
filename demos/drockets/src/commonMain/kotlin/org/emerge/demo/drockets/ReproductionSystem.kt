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
    const val POPULATION_CAP = 800

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
                if (spawn != null && spawn.birthdayMs <= nowMs) {
                    // If landed, eject spawn
                    val landingComponent = builder.getComponent<LandingAttachmentComponent>(entityId) ?: continue
                    val planetMotion = builder.getComponent<MotionComponent>(landingComponent.parentEntityId) ?: continue
                    val planetTransform = builder.getComponent<TransformComponent>(landingComponent.parentEntityId) ?: continue
                    val transformComponent = builder.getComponent<TransformComponent>(entityId) ?: continue
                    val teamComponent = builder.getComponent<TeamComponent>(entityId) ?: continue
                    val parentGenome = builder.getComponent<GenomeComponent>(entityId)
                    val planetOffset = transformComponent.pos - planetTransform.pos
                    val planetNorm = planetOffset.norm
                    val planetDist = planetOffset.len
                    val childEntityId = spawnDrocket(
                        builder,
                        transformComponent.pos,
                        planetMotion.surfaceVelocityAtOffset(planetNorm, planetDist),
                        transformComponent.ang,
                        teamComponent.teamId,
                    )
                    val storedSpawnGenome = reproducer.spawnGenome
                    if (storedSpawnGenome != null) {
                        val childGenome = GenomeComponent(encodedGenes = storedSpawnGenome)
                        builder.update<GenomeComponent>(childEntityId) { childGenome }
                    } else if (parentGenome != null) {
                        // Backward-compatible fallback for already-pregnant entities without stored spawn genome.
                        val childGenome = mutateGenome(parentGenome.encodedGenesForReproduction(), builder.nextRandomInt())
                        builder.update<GenomeComponent>(childEntityId) { GenomeComponent(childGenome) }
                    }
                    builder.update<LineageSeedComponent>(childEntityId) {
                        LineageSeedComponent(
                            motherEntityId = reproducer.spawnMotherEntityId,
                            fatherEntityId = reproducer.spawnFatherEntityId,
                        )
                    }
                    val updated = reproducer.copy(
                        spawn = null,
                        spawnGenome = null,
                        spawnMotherEntityId = null,
                        spawnFatherEntityId = null,
                    )
                    builder.update<ReproducerComponent>(entityId) { updated }
                    println("Spawned: ${builder.entries<DrocketStateComponent>().size}")
                } else if (builder.entries<DrocketStateComponent>().size < POPULATION_CAP) {
                    // Go through contacts to determine whether contact with a male reproducer has occurred
                    val contacts = builder.contacts.filter { it.aId == entityId || it.bId == entityId }
                    for (contact in contacts) {
                        val otherId = if (contact.aId != entityId) contact.aId else contact.bId
                        val otherReproducer = reproducers[otherId] ?: continue
                        if (otherReproducer.sex == Sex.MALE && otherReproducer.isMature(nowMs)) {
                            val motherGenome = builder.getComponent<GenomeComponent>(entityId)
                            val fatherGenome = builder.getComponent<GenomeComponent>(otherId)
                            val childSex = if (builder.nextRandomInt()%2 == 0) Sex.FEMALE else Sex.MALE
                            val childGenome = mixParentGenomes(
                                mother = motherGenome,
                                father = fatherGenome,
                                childSex = childSex,
                                randomSeed = builder.nextRandomInt(),
                            )
                            val updated = reproducer.copy(
                                spawn = ReproducerComponent(
                                    birthdayMs = nowMs + reproducer.gestationDuration,
                                    sex = childSex
                                ),
                                spawnGenome = childGenome,
                                spawnMotherEntityId = entityId.value,
                                spawnFatherEntityId = otherId.value,
                            )
                            builder.update<ReproducerComponent>(entityId) { updated }
                            return
                        }
                    }
                }
            }
        }
    }

    private fun mutateGenome(parentGenes: Map<String, Int>, random: Int): Map<String, Int> {
        if (parentGenes.isEmpty()) return parentGenes
        val updated = LinkedHashMap(parentGenes)
        val keys = updated.keys.toList()
        for (key in keys) {
            val delta = (random ushr 8) - (1 shl 7)

            if (key in BODY_COLOR_KEYS) {
                mutateColorGroup(updated, BODY_COLOR_KEYS, delta)
            } else if (key in FIRE_COLOR_KEYS) {
                mutateColorGroup(updated, FIRE_COLOR_KEYS, delta)
            } else {
                val base = updated[key] ?: 0
                updated[key] = base + delta
            }
        }
        return updated
    }

    private fun mixParentGenomes(
        mother: GenomeComponent?,
        father: GenomeComponent?,
        childSex: Sex,
        randomSeed: Int,
    ): Map<String, Int>? {
        val motherGenes = mother?.encodedGenesForReproduction() ?: emptyMap()
        val fatherGenes = father?.encodedGenesForReproduction() ?: emptyMap()
        if (motherGenes.isEmpty() && fatherGenes.isEmpty()) return null

        val keys = LinkedHashSet<String>()
        keys.addAll(motherGenes.keys)
        keys.addAll(fatherGenes.keys)
        val mixed = LinkedHashMap<String, Int>(keys.size)
        var step = randomSeed

        // Body color lineage: male follows father line, female follows mother line.
        val bodySource = if (childSex == Sex.MALE) {
            fatherGenes.ifEmpty { motherGenes }
        } else {
            motherGenes.ifEmpty { fatherGenes }
        }
        copyColorTriplet(bodySource, mixed, BODY_COLOR_KEYS)

        // Fire color lineage: can come from either parent, but as one coupled RGB triplet.
        step = step * 1664525 + 1013904223
        val fireSource = if ((step and 1) == 0) {
            if (motherGenes.isNotEmpty()) motherGenes else fatherGenes
        } else {
            if (fatherGenes.isNotEmpty()) fatherGenes else motherGenes
        }
        copyColorTriplet(fireSource, mixed, FIRE_COLOR_KEYS)

        for (key in keys) {
            if (key in BODY_COLOR_KEYS || key in FIRE_COLOR_KEYS) continue
            step = step * 1664525 + 1013904223
            val takeMother = (step and 1) == 0
            val motherValue = motherGenes[key]
            val fatherValue = fatherGenes[key]
            val value = when {
                motherValue != null && fatherValue != null -> if (takeMother) motherValue else fatherValue
                motherValue != null -> motherValue
                fatherValue != null -> fatherValue
                else -> continue
            }
            mixed[key] = value
        }

        if (mixed.isEmpty()) return null
        return mutateGenome(mixed, step)
    }

    private fun mutateColorGroup(
        genes: MutableMap<String, Int>,
        keys: List<String>,
        delta: Int,
    ) {
        for (k in keys) {
            val base = genes[k] ?: continue
            genes[k] = base + delta
        }
    }

    private fun copyColorTriplet(
        source: Map<String, Int>,
        target: MutableMap<String, Int>,
        keys: List<String>,
    ) {
        if (keys.all { source.containsKey(it) }) {
            for (k in keys) {
                target[k] = source[k] ?: 0
            }
        }
    }

    private val BODY_COLOR_KEYS = listOf(
        GenomeComponent.GenomeKey.COLOR_H.wireName,
        GenomeComponent.GenomeKey.COLOR_S.wireName,
        GenomeComponent.GenomeKey.COLOR_V.wireName,
    )
    private val FIRE_COLOR_KEYS = listOf(
        GenomeComponent.GenomeKey.FIRE_COLOR_H.wireName,
        GenomeComponent.GenomeKey.FIRE_COLOR_S.wireName,
        GenomeComponent.GenomeKey.FIRE_COLOR_V.wireName,
    )
}
