package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.model.PhysicsState

data class DrocketLineageNode(
    val lineageId: Long,
    val motherLineageId: Long?,
    val fatherLineageId: Long?,
    val birthTick: Long,
    val deathTick: Long? = null,
    val sex: Sex,
    val genome: Map<String, Int>,
)

data class DrocketLineageState(
    val nextLineageId: Long = 1L,
    val nodes: Map<Long, DrocketLineageNode> = emptyMap(),
    val livingLineageIds: Set<Long> = emptySet(),
    val entityToLineageId: Map<Int, Long> = emptyMap(),
) {
    companion object {
        val EMPTY = DrocketLineageState()
    }
}

fun DrocketLineageState.advanceFromPhysics(
    physics: PhysicsState,
    tick: Long,
): DrocketLineageState {
    val seeds = physics.components.getTable<LineageSeedComponent>().asMap()
    val reproducers = physics.components.getTable<ReproducerComponent>().asMap()
    val genomes = physics.components.getTable<GenomeComponent>().asMap()

    var nextId = nextLineageId
    val nodes = LinkedHashMap(this.nodes)
    val entityToLineage = LinkedHashMap(this.entityToLineageId)
    val living = LinkedHashSet(this.livingLineageIds)

    // Births / newly-tracked entities
    for ((entityId, seed) in seeds) {
        if (entityToLineage.containsKey(entityId.value)) continue
        val reproducer = reproducers[entityId] ?: continue
        val genome = genomes[entityId]?.genes ?: emptyMap()
        val motherLineage = seed.motherEntityId?.let(entityToLineage::get)
        val fatherLineage = seed.fatherEntityId?.let(entityToLineage::get)
        val lineageId = nextId
        nextId += 1
        nodes[lineageId] = DrocketLineageNode(
            lineageId = lineageId,
            motherLineageId = motherLineage,
            fatherLineageId = fatherLineage,
            birthTick = tick,
            sex = reproducer.sex,
            genome = LinkedHashMap(genome),
        )
        entityToLineage[entityId.value] = lineageId
        living += lineageId
    }

    // Deaths / removed entities
    val currentEntityIds = seeds.keys.mapTo(LinkedHashSet()) { it.value }
    val knownEntityIds = entityToLineage.keys.toList()
    for (entityId in knownEntityIds) {
        if (currentEntityIds.contains(entityId)) continue
        val lineageId = entityToLineage.remove(entityId) ?: continue
        val existing = nodes[lineageId] ?: continue
        if (existing.deathTick == null) {
            nodes[lineageId] = existing.copy(deathTick = tick)
        }
        living -= lineageId
    }

    return copy(
        nextLineageId = nextId,
        nodes = nodes,
        livingLineageIds = living,
        entityToLineageId = entityToLineage,
    )
}
