package org.emerge.demo.drockets

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.physics.model.PhysicsState

data class DrocketsSnapshot(
    val tick: Tick,
    val state: PhysicsState,
    val lineage: DrocketLineageState,
)

object DrocketsSaveCodec {
    private const val FORMAT_VERSION = 2
    private const val MAX_GENE_COUNT_PER_ENTITY = 4096
    private const val MAX_TOTAL_GENES = 200_000
    private const val MAX_KEY_LENGTH = 256

    fun encode(snapshot: DrocketsSnapshot): ByteArray {
        val w = ByteWriter()
        val stateBytes = PhysicsNetCodecs.stateCodec.encode(snapshot.state)
        w.writeInt(FORMAT_VERSION)
        w.writeLong(snapshot.tick.value)
        w.writeInt(stateBytes.size)
        w.writeBytes(stateBytes)
        encodeDrocketsComponents(w, snapshot.state.components)
        encodeLineageState(w, snapshot.lineage)
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): DrocketsSnapshot {
        val c = ByteCursor(bytes)
        val version = c.readInt()
        require(version == FORMAT_VERSION) {
            "Unsupported Drockets save format version: $version"
        }
        val tick = Tick(c.readLong())
        val stateBytesSize = c.readInt()
        require(stateBytesSize >= 0) {
            "Invalid physics state payload size: $stateBytesSize"
        }
        val stateBytes = c.readBytes(stateBytesSize)
        val physicsState = PhysicsNetCodecs.stateCodec.decode(stateBytes)
        val mergedComponents = decodeDrocketsComponents(c, physicsState.components)
        require(c.remaining() == 0) {
            "Unexpected trailing bytes in Drockets snapshot: ${c.remaining()}"
        }
        return DrocketsSnapshot(
            tick = tick,
            state = physicsState.copy(components = mergedComponents),
            lineage = decodeLineageState(c),
        )
    }

    private fun encodeDrocketsComponents(w: ByteWriter, store: ComponentStore) {
        encodeDrocketStateTable(w, store.getTable<DrocketStateComponent>().asMap())
        encodeReproducerTable(w, store.getTable<ReproducerComponent>().asMap())
        encodeKnightStateTable(w, store.getTable<KnightStateComponent>().asMap())
        encodeSpriteAnimationStateTable(w, store.getTable<SpriteAnimationState>().asMap())
        encodeGenomeTable(w, store.getTable<GenomeComponent>().asMap())
        encodeLineageSeedTable(w, store.getTable<LineageSeedComponent>().asMap())
    }

    private fun decodeDrocketsComponents(c: ByteCursor, store: ComponentStore): ComponentStore {
        var updated = store
        updated = updated.update { set(ComponentTable.fromMap(decodeDrocketStateTable(c))) }
        updated = updated.update { set(ComponentTable.fromMap(decodeReproducerTable(c))) }
        updated = updated.update { set(ComponentTable.fromMap(decodeKnightStateTable(c))) }
        updated = updated.update { set(ComponentTable.fromMap(decodeSpriteAnimationStateTable(c))) }
        updated = updated.update { set(ComponentTable.fromMap(decodeGenomeTable(c))) }
        updated = updated.update { set(ComponentTable.fromMap(decodeLineageSeedTable(c))) }
        return updated
    }

    private fun encodeDrocketStateTable(
        w: ByteWriter,
        entries: Map<EntityId, DrocketStateComponent>,
    ) {
        w.writeInt(entries.size)
        for ((entityId, component) in entries) {
            w.writeInt(entityId.value)
            w.writeInt(component.phase.ordinal)
            w.writeInt(component.walkDirection)
            w.writeInt(component.ticksRemaining)
            w.writeInt(component.fuel)
        }
    }

    private fun decodeDrocketStateTable(c: ByteCursor): Map<EntityId, DrocketStateComponent> {
        val count = c.readInt()
        require(count >= 0) { "Invalid DrocketStateComponent count: $count" }
        val out = LinkedHashMap<EntityId, DrocketStateComponent>(count)
        repeat(count) {
            val entityId = EntityId(c.readInt())
            val phaseOrdinal = c.readInt()
            val phase = DrocketPhase.entries.getOrNull(phaseOrdinal)
                ?: error("Invalid DrocketPhase ordinal: $phaseOrdinal")
            out[entityId] = DrocketStateComponent(
                phase = phase,
                walkDirection = c.readInt(),
                ticksRemaining = c.readInt(),
                fuel = c.readInt(),
            )
        }
        return out
    }

    private fun encodeReproducerTable(
        w: ByteWriter,
        entries: Map<EntityId, ReproducerComponent>,
    ) {
        w.writeInt(entries.size)
        for ((entityId, component) in entries) {
            w.writeInt(entityId.value)
            encodeReproducerComponent(w, component)
        }
    }

    private fun decodeReproducerTable(c: ByteCursor): Map<EntityId, ReproducerComponent> {
        val count = c.readInt()
        require(count >= 0) { "Invalid ReproducerComponent count: $count" }
        val out = LinkedHashMap<EntityId, ReproducerComponent>(count)
        repeat(count) {
            val entityId = EntityId(c.readInt())
            out[entityId] = decodeReproducerComponent(c)
        }
        return out
    }

    private fun encodeReproducerComponent(w: ByteWriter, component: ReproducerComponent) {
        w.writeLong(component.birthdayMs)
        w.writeInt(component.sex.ordinal)
        w.writeLong(component.maturityAgeMs)
        w.writeLong(component.gestationDuration)
        val hasSpawn = component.spawn != null
        w.writeInt(if (hasSpawn) 1 else 0)
        if (hasSpawn) {
            encodeReproducerComponent(w, component.spawn!!)
        }
        encodeGeneMap(w, component.spawnGenome)
        w.writeInt(component.spawnMotherEntityId ?: Int.MIN_VALUE)
        w.writeInt(component.spawnFatherEntityId ?: Int.MIN_VALUE)
    }

    private fun decodeReproducerComponent(c: ByteCursor): ReproducerComponent {
        val birthdayMs = c.readLong()
        val sexOrdinal = c.readInt()
        val sex = Sex.entries.getOrNull(sexOrdinal) ?: error("Invalid Sex ordinal: $sexOrdinal")
        val maturityAgeMs = c.readLong()
        val gestationDuration = c.readLong()
        val hasSpawn = c.readInt() != 0
        val spawn = if (hasSpawn) decodeReproducerComponent(c) else null
        val spawnGenome = decodeGeneMap(c)
        val spawnMotherEntityIdRaw = c.readInt()
        val spawnFatherEntityIdRaw = c.readInt()
        return ReproducerComponent(
            birthdayMs = birthdayMs,
            sex = sex,
            maturityAgeMs = maturityAgeMs,
            gestationDuration = gestationDuration,
            spawn = spawn,
            spawnGenome = spawnGenome,
            spawnMotherEntityId = if (spawnMotherEntityIdRaw == Int.MIN_VALUE) null else spawnMotherEntityIdRaw,
            spawnFatherEntityId = if (spawnFatherEntityIdRaw == Int.MIN_VALUE) null else spawnFatherEntityIdRaw,
        )
    }

    private fun encodeLineageSeedTable(
        w: ByteWriter,
        entries: Map<EntityId, LineageSeedComponent>,
    ) {
        w.writeInt(entries.size)
        for ((entityId, component) in entries) {
            w.writeInt(entityId.value)
            w.writeInt(component.motherEntityId ?: Int.MIN_VALUE)
            w.writeInt(component.fatherEntityId ?: Int.MIN_VALUE)
        }
    }

    private fun decodeLineageSeedTable(c: ByteCursor): Map<EntityId, LineageSeedComponent> {
        val count = c.readInt()
        require(count >= 0) { "Invalid LineageSeedComponent count: $count" }
        val out = LinkedHashMap<EntityId, LineageSeedComponent>(count)
        repeat(count) {
            val entityId = EntityId(c.readInt())
            val motherRaw = c.readInt()
            val fatherRaw = c.readInt()
            out[entityId] = LineageSeedComponent(
                motherEntityId = if (motherRaw == Int.MIN_VALUE) null else motherRaw,
                fatherEntityId = if (fatherRaw == Int.MIN_VALUE) null else fatherRaw,
            )
        }
        return out
    }

    private fun encodeKnightStateTable(
        w: ByteWriter,
        entries: Map<EntityId, KnightStateComponent>,
    ) {
        w.writeInt(entries.size)
        for ((entityId, component) in entries) {
            w.writeInt(entityId.value)
            w.writeInt(component.phase.ordinal)
            w.writeInt(component.planetId.value)
            w.writeInt(component.walkDirection)
            w.writeInt(component.ticksRemaining)
        }
    }

    private fun decodeKnightStateTable(c: ByteCursor): Map<EntityId, KnightStateComponent> {
        val count = c.readInt()
        require(count >= 0) { "Invalid KnightStateComponent count: $count" }
        val out = LinkedHashMap<EntityId, KnightStateComponent>(count)
        repeat(count) {
            val entityId = EntityId(c.readInt())
            val phaseOrdinal = c.readInt()
            val phase = KnightPhase.entries.getOrNull(phaseOrdinal)
                ?: error("Invalid KnightPhase ordinal: $phaseOrdinal")
            out[entityId] = KnightStateComponent(
                phase = phase,
                planetId = EntityId(c.readInt()),
                walkDirection = c.readInt(),
                ticksRemaining = c.readInt(),
            )
        }
        return out
    }

    private fun encodeSpriteAnimationStateTable(
        w: ByteWriter,
        entries: Map<EntityId, SpriteAnimationState>,
    ) {
        w.writeInt(entries.size)
        for ((entityId, component) in entries) {
            w.writeInt(entityId.value)
            w.writeInt(component.sheet.ordinal)
            w.writeInt(component.animationIndex)
            w.writeInt(component.currentFrame)
            w.writeInt(component.tickCounter)
        }
    }

    private fun decodeSpriteAnimationStateTable(c: ByteCursor): Map<EntityId, SpriteAnimationState> {
        val count = c.readInt()
        require(count >= 0) { "Invalid SpriteAnimationState count: $count" }
        val out = LinkedHashMap<EntityId, SpriteAnimationState>(count)
        repeat(count) {
            val entityId = EntityId(c.readInt())
            val sheetOrdinal = c.readInt()
            val sheet = SpriteSheet.entries.getOrNull(sheetOrdinal)
                ?: error("Invalid SpriteSheet ordinal: $sheetOrdinal")
            out[entityId] = SpriteAnimationState(
                sheet = sheet,
                animationIndex = c.readInt(),
                currentFrame = c.readInt(),
                tickCounter = c.readInt(),
            )
        }
        return out
    }

    private fun encodeGenomeTable(
        w: ByteWriter,
        entries: Map<EntityId, GenomeComponent>,
    ) {
        w.writeInt(entries.size)
        var totalGeneCount = 0
        for ((entityId, component) in entries) {
            w.writeInt(entityId.value)
            w.writeInt(component.genes.size)
            require(component.genes.size <= MAX_GENE_COUNT_PER_ENTITY) {
                "Too many genes for entity ${entityId.value}: ${component.genes.size}"
            }
            totalGeneCount += component.genes.size
            require(totalGeneCount <= MAX_TOTAL_GENES) {
                "Too many total genes in snapshot: $totalGeneCount"
            }
            for ((key, value) in component.genes) {
                writeAsciiString(w, key)
                w.writeInt(value)
            }
        }
    }

    private fun decodeGenomeTable(c: ByteCursor): Map<EntityId, GenomeComponent> {
        val count = c.readInt()
        require(count >= 0) { "Invalid GenomeComponent count: $count" }
        val out = LinkedHashMap<EntityId, GenomeComponent>(count)
        var totalGeneCount = 0
        repeat(count) {
            val entityId = EntityId(c.readInt())
            val geneCount = c.readInt()
            require(geneCount in 0..MAX_GENE_COUNT_PER_ENTITY) {
                "Invalid gene count for entity ${entityId.value}: $geneCount"
            }
            totalGeneCount += geneCount
            require(totalGeneCount <= MAX_TOTAL_GENES) {
                "Too many total genes in snapshot: $totalGeneCount"
            }
            val genes = LinkedHashMap<String, Int>(geneCount)
            repeat(geneCount) {
                val key = readAsciiString(c)
                val value = c.readInt()
                genes[key] = value
            }
            out[entityId] = GenomeComponent(genes = genes)
        }
        return out
    }

    private fun writeAsciiString(w: ByteWriter, value: String) {
        require(value.length <= MAX_KEY_LENGTH) {
            "Gene key too long: ${value.length} > $MAX_KEY_LENGTH"
        }
        val bytes = value.encodeToByteArray()
        require(bytes.all { it >= 0 }) {
            "Gene key must be ASCII-safe: $value"
        }
        w.writeInt(bytes.size)
        w.writeBytes(bytes)
    }

    private fun encodeGeneMap(w: ByteWriter, genes: Map<String, Int>?) {
        if (genes == null) {
            w.writeInt(-1)
            return
        }
        require(genes.size <= MAX_GENE_COUNT_PER_ENTITY) {
            "Too many spawn genes: ${genes.size}"
        }
        w.writeInt(genes.size)
        for ((key, value) in genes) {
            writeAsciiString(w, key)
            w.writeInt(value)
        }
    }

    private fun decodeGeneMap(c: ByteCursor): Map<String, Int>? {
        val count = c.readInt()
        if (count < 0) return null
        require(count <= MAX_GENE_COUNT_PER_ENTITY) {
            "Invalid spawn gene count: $count"
        }
        val genes = LinkedHashMap<String, Int>(count)
        repeat(count) {
            val key = readAsciiString(c)
            val value = c.readInt()
            genes[key] = value
        }
        return genes
    }

    private fun encodeLineageState(w: ByteWriter, lineage: DrocketLineageState) {
        w.writeLong(lineage.nextLineageId)
        w.writeInt(lineage.nodes.size)
        for ((id, node) in lineage.nodes) {
            w.writeLong(id)
            w.writeLong(node.motherLineageId ?: Long.MIN_VALUE)
            w.writeLong(node.fatherLineageId ?: Long.MIN_VALUE)
            w.writeLong(node.birthTick)
            w.writeLong(node.deathTick ?: Long.MIN_VALUE)
            w.writeInt(node.sex.ordinal)
            encodeGeneMapRequired(w, node.genome)
        }
        w.writeInt(lineage.livingLineageIds.size)
        for (id in lineage.livingLineageIds) {
            w.writeLong(id)
        }
        w.writeInt(lineage.entityToLineageId.size)
        for ((entityId, lineageId) in lineage.entityToLineageId) {
            w.writeInt(entityId)
            w.writeLong(lineageId)
        }
    }

    private fun decodeLineageState(c: ByteCursor): DrocketLineageState {
        val nextLineageId = c.readLong()
        val nodeCount = c.readInt()
        require(nodeCount >= 0) { "Invalid lineage node count: $nodeCount" }
        val nodes = LinkedHashMap<Long, DrocketLineageNode>(nodeCount)
        repeat(nodeCount) {
            val id = c.readLong()
            val motherRaw = c.readLong()
            val fatherRaw = c.readLong()
            val birthTick = c.readLong()
            val deathRaw = c.readLong()
            val sexOrdinal = c.readInt()
            val sex = Sex.entries.getOrNull(sexOrdinal) ?: error("Invalid Sex ordinal in lineage node: $sexOrdinal")
            val genome = decodeGeneMapRequired(c)
            nodes[id] = DrocketLineageNode(
                lineageId = id,
                motherLineageId = if (motherRaw == Long.MIN_VALUE) null else motherRaw,
                fatherLineageId = if (fatherRaw == Long.MIN_VALUE) null else fatherRaw,
                birthTick = birthTick,
                deathTick = if (deathRaw == Long.MIN_VALUE) null else deathRaw,
                sex = sex,
                genome = genome,
            )
        }
        val livingCount = c.readInt()
        require(livingCount >= 0) { "Invalid living lineage count: $livingCount" }
        val living = LinkedHashSet<Long>(livingCount)
        repeat(livingCount) { living += c.readLong() }
        val mappingCount = c.readInt()
        require(mappingCount >= 0) { "Invalid entity-to-lineage mapping count: $mappingCount" }
        val entityToLineage = LinkedHashMap<Int, Long>(mappingCount)
        repeat(mappingCount) {
            val entityId = c.readInt()
            val lineageId = c.readLong()
            entityToLineage[entityId] = lineageId
        }
        return DrocketLineageState(
            nextLineageId = nextLineageId,
            nodes = nodes,
            livingLineageIds = living,
            entityToLineageId = entityToLineage,
        )
    }

    private fun encodeGeneMapRequired(w: ByteWriter, genes: Map<String, Int>) {
        require(genes.size <= MAX_GENE_COUNT_PER_ENTITY) {
            "Too many genes: ${genes.size}"
        }
        w.writeInt(genes.size)
        for ((key, value) in genes) {
            writeAsciiString(w, key)
            w.writeInt(value)
        }
    }

    private fun decodeGeneMapRequired(c: ByteCursor): Map<String, Int> {
        val count = c.readInt()
        require(count in 0..MAX_GENE_COUNT_PER_ENTITY) { "Invalid required gene map count: $count" }
        val genes = LinkedHashMap<String, Int>(count)
        repeat(count) {
            val key = readAsciiString(c)
            val value = c.readInt()
            genes[key] = value
        }
        return genes
    }

    private fun readAsciiString(c: ByteCursor): String {
        val len = c.readInt()
        require(len in 0..MAX_KEY_LENGTH) { "Invalid string length: $len" }
        val bytes = c.readBytes(len)
        require(bytes.all { it >= 0 }) {
            "Non-ASCII string payload encountered"
        }
        return bytes.decodeToString()
    }
}
