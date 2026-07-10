package org.emerge.demo.drockets

import org.emerge.sim.core.sim.SimState

data class DrocketLineageNode(
    val lineageId: Long,
    val motherLineageId: Long?,
    val fatherLineageId: Long?,
    val birthTick: Long,
    val deathTick: Long? = null,
    val sex: Sex,
    val genome: Genome,
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
    physics: SimState,
    tick: Long,
): DrocketLineageState {
    val seeds = physics.components.getTable<LineageSeedComponent>().asMap()
    val reproducers = physics.components.getTable<ReproducerComponent>().asMap()
    val genomes = physics.components.getTable<GenomeComponent>().asMap()

    var nextId = nextLineageId
    // The field types are read-only `Map`/`Set`, but at runtime every state
    // instance is built from `LinkedHashMap`/`LinkedHashSet` (default
    // initialisers, codec decode, prior `advanceFromPhysics` calls). Reuse
    // them in place so we don't pay an O(n) copy of `nodes` every tick at
    // sims that have accumulated tens of thousands of historical nodes.
    // The returned state aliases the same instances — fine for the
    // single-threaded controller loop, which discards the previous state
    // immediately. Defensive fallback for genuinely-immutable inputs
    // (`emptyMap()`, `setOf()`).
    @Suppress("UNCHECKED_CAST")
    val nodes = (this.nodes as? MutableMap<Long, DrocketLineageNode>)
        ?: LinkedHashMap(this.nodes)
    @Suppress("UNCHECKED_CAST")
    val entityToLineage = (this.entityToLineageId as? MutableMap<Int, Long>)
        ?: LinkedHashMap(this.entityToLineageId)
    val living = (this.livingLineageIds as? MutableSet<Long>)
        ?: LinkedHashSet(this.livingLineageIds)

    // Births / newly-tracked entities
    for ((entityId, seed) in seeds) {
        if (entityToLineage.containsKey(entityId.value)) continue
        val reproducer = reproducers[entityId] ?: continue
        val genome = genomes[entityId]?.genome ?: Genome()
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
            genome = genome,
        )
        entityToLineage[entityId.value] = lineageId
        living += lineageId
    }

    // Deaths / removed entities. We don't modify [entityToLineage] in this
    // pass (historical mappings stay so future births can still resolve
    // dead-parent lineage ids), so we can iterate it directly instead of
    // snapshotting to a list — saves an O(|entityToLineage|) allocation per
    // tick at sims with tens of thousands of historical entities.
    val currentEntityIds = seeds.keys.mapTo(LinkedHashSet()) { it.value }
    for ((entityId, lineageId) in entityToLineage) {
        if (currentEntityIds.contains(entityId)) continue
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
