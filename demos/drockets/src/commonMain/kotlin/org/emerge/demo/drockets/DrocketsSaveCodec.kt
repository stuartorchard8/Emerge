package org.emerge.demo.drockets

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.model.PhysicsState

data class DrocketsSnapshot(
    val tick: Tick,
    val state: PhysicsState,
    val lineage: DrocketLineageState,
)

/**
 * Binary save format for a Drockets simulation snapshot.
 *
 * Layout:
 *   header  : FORMAT_VERSION (Int), tick (Long), physicsBytesSize (Int), physicsBytes
 *   demo    : drockets-specific component tables, in fixed order
 *   lineage : DrocketLineageState
 *
 * Particle entities are stripped at encode time (transient by nature, would
 * bloat saves without contributing to reproducible state).
 *
 * Compatibility: any change to wire layout must bump [FORMAT_VERSION]; the
 * decoder rejects older versions outright (a solo project doesn't need legacy
 * load support — start a fresh sim instead).
 */
object DrocketsSaveCodec {
    private const val FORMAT_VERSION = 5

    fun encode(snapshot: DrocketsSnapshot): ByteArray {
        val w = ByteWriter()
        val stateWithoutParticles = snapshot.state.withoutParticleEntitiesForPersistence()
        val stateBytes = DrocketsCodecs.stateCodec.encode(stateWithoutParticles)
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
            "Unsupported Drockets save format version: $version (expected $FORMAT_VERSION)"
        }
        val tick = Tick(c.readLong())
        val stateBytesSize = c.readInt()
        require(stateBytesSize >= 0) { "Invalid physics state payload size: $stateBytesSize" }
        val stateBytes = c.readBytes(stateBytesSize)
        val physicsState = DrocketsCodecs.stateCodec.decode(stateBytes)
        val mergedComponents = decodeDrocketsComponents(c, physicsState.components)
        val lineage = decodeLineageState(c)
        require(c.remaining() == 0) {
            "Unexpected trailing bytes in Drockets snapshot: ${c.remaining()}"
        }
        return DrocketsSnapshot(
            tick = tick,
            state = physicsState.copy(components = mergedComponents),
            lineage = lineage,
        )
    }

    private fun PhysicsState.withoutParticleEntitiesForPersistence(): PhysicsState {
        val particleEntityIds = components.getTable<ParticleComponent>().keys()
        if (particleEntityIds.isEmpty()) return this
        return copy(
            components = ComponentStore(
                tables = components.tables.mapValues { (_, table) ->
                    table.removeAll(particleEntityIds)
                }
            )
        )
    }

    // ── Component-table boilerplate, abstracted away ────────────────────────────

    private inline fun <reified T : Any> encodeTable(
        w: ByteWriter,
        store: ComponentStore,
        encode: ByteWriter.(T) -> Unit,
    ) {
        val entries = store.getTable<T>().asMap()
        w.writeInt(entries.size)
        for ((entityId, component) in entries) {
            w.writeInt(entityId.value)
            w.encode(component)
        }
    }

    private inline fun <reified T : Any> decodeTable(
        c: ByteCursor,
        store: ComponentStore,
        decode: ByteCursor.() -> T,
    ): ComponentStore {
        val count = c.readInt()
        require(count >= 0) { "Invalid ${T::class.simpleName} count: $count" }
        val out = LinkedHashMap<EntityId, T>(count)
        repeat(count) {
            val entityId = EntityId(c.readInt())
            out[entityId] = c.decode()
        }
        return store.update { set(ComponentTable.fromMap(out)) }
    }

    // ── Per-component encode/decode pairs ───────────────────────────────────────
    //
    // The ordering of encodeTable / decodeTable calls below must match between
    // encode and decode; the wire format relies on positional reads.

    private fun encodeDrocketsComponents(w: ByteWriter, store: ComponentStore) {
        encodeTable<DrocketStateComponent>(w, store) {
            writeInt(it.phase.ordinal)
            writeInt(it.walkDirection)
            writeInt(it.ticksRemaining)
            writeInt(it.fuel)
        }
        encodeTable<ReproducerComponent>(w, store) { writeReproducer(it) }
        encodeTable<KnightStateComponent>(w, store) {
            writeInt(it.phase.ordinal)
            writeInt(it.planetId.value)
            writeInt(it.walkDirection)
            writeInt(it.ticksRemaining)
        }
        encodeTable<SpriteAnimationState>(w, store) {
            writeInt(it.sheet.ordinal)
            writeInt(it.animationIndex)
            writeInt(it.currentFrame)
            writeInt(it.tickCounter)
        }
        encodeTable<GenomeComponent>(w, store) { writeGenome(it.genome) }
        encodeTable<LineageSeedComponent>(w, store) {
            writeNullableInt(it.motherEntityId)
            writeNullableInt(it.fatherEntityId)
        }
    }

    private fun decodeDrocketsComponents(c: ByteCursor, store: ComponentStore): ComponentStore {
        var s = store
        s = decodeTable<DrocketStateComponent>(c, s) {
            DrocketStateComponent(
                phase = enumByOrdinal(readInt(), DrocketPhase.entries),
                walkDirection = readInt(),
                ticksRemaining = readInt(),
                fuel = readInt(),
            )
        }
        s = decodeTable<ReproducerComponent>(c, s) { readReproducer() }
        s = decodeTable<KnightStateComponent>(c, s) {
            KnightStateComponent(
                phase = enumByOrdinal(readInt(), KnightPhase.entries),
                planetId = EntityId(readInt()),
                walkDirection = readInt(),
                ticksRemaining = readInt(),
            )
        }
        s = decodeTable<SpriteAnimationState>(c, s) {
            SpriteAnimationState(
                sheet = enumByOrdinal(readInt(), SpriteSheet.entries),
                animationIndex = readInt(),
                currentFrame = readInt(),
                tickCounter = readInt(),
            )
        }
        s = decodeTable<GenomeComponent>(c, s) { GenomeComponent(readGenome()) }
        s = decodeTable<LineageSeedComponent>(c, s) {
            LineageSeedComponent(
                motherEntityId = readNullableInt(),
                fatherEntityId = readNullableInt(),
            )
        }
        return s
    }

    // ── ReproducerComponent (recursive: spawn may itself be a ReproducerComponent) ──

    private fun ByteWriter.writeReproducer(r: ReproducerComponent) {
        writeLong(r.birthdayMs)
        writeInt(r.sex.ordinal)
        writeLong(r.maturityAgeMs)
        writeLong(r.gestationDuration)
        writeBoolean(r.spawn != null)
        r.spawn?.let { writeReproducer(it) }
        writeBoolean(r.spawnGenome != null)
        r.spawnGenome?.let { writeGenome(it) }
        writeNullableInt(r.spawnMotherEntityId)
        writeNullableInt(r.spawnFatherEntityId)
    }

    private fun ByteCursor.readReproducer(): ReproducerComponent {
        val birthdayMs = readLong()
        val sex = enumByOrdinal(readInt(), Sex.entries)
        val maturityAgeMs = readLong()
        val gestationDuration = readLong()
        val spawn = if (readBoolean()) readReproducer() else null
        val spawnGenome = if (readBoolean()) readGenome() else null
        val spawnMotherEntityId = readNullableInt()
        val spawnFatherEntityId = readNullableInt()
        return ReproducerComponent(
            birthdayMs = birthdayMs,
            sex = sex,
            maturityAgeMs = maturityAgeMs,
            gestationDuration = gestationDuration,
            spawn = spawn,
            spawnGenome = spawnGenome,
            spawnMotherEntityId = spawnMotherEntityId,
            spawnFatherEntityId = spawnFatherEntityId,
        )
    }

    // ── Genome (fixed shape: 8 Ints + 2 HsvColorGene triples = 48 bytes) ────────

    private fun ByteWriter.writeGenome(g: Genome) {
        writeInt(g.aiWalkMinTicks)
        writeInt(g.aiWalkMaxTicks)
        writeInt(g.aiChargeTicks)
        writeInt(g.aiFuelTicks)
        writeInt(g.aiSpin)
        writeInt(g.aiThrust)
        writeHsvColorGene(g.bodyColor)
        writeHsvColorGene(g.fireColor)
    }

    private fun ByteCursor.readGenome() = Genome(
        aiWalkMinTicks = readInt(),
        aiWalkMaxTicks = readInt(),
        aiChargeTicks = readInt(),
        aiFuelTicks = readInt(),
        aiSpin = readInt(),
        aiThrust = readInt(),
        bodyColor = readHsvColorGene(),
        fireColor = readHsvColorGene(),
    )

    private fun ByteWriter.writeHsvColorGene(gene: HsvColorGene) {
        writeInt(gene.rawH)
        writeInt(gene.rawS)
        writeInt(gene.rawV)
    }

    private fun ByteCursor.readHsvColorGene() = HsvColorGene(
        rawH = readInt(),
        rawS = readInt(),
        rawV = readInt(),
    )

    // ── Lineage state ───────────────────────────────────────────────────────────

    private fun encodeLineageState(w: ByteWriter, lineage: DrocketLineageState) {
        w.writeLong(lineage.nextLineageId)
        w.writeMap(lineage.nodes) { id, node ->
            writeLong(id)
            writeNullableLong(node.motherLineageId)
            writeNullableLong(node.fatherLineageId)
            writeLong(node.birthTick)
            writeNullableLong(node.deathTick)
            writeInt(node.sex.ordinal)
            writeGenome(node.genome)
        }
        w.writeCollection(lineage.livingLineageIds) { writeLong(it) }
        w.writeMap(lineage.entityToLineageId) { entityId, lineageId ->
            writeInt(entityId)
            writeLong(lineageId)
        }
    }

    private fun decodeLineageState(c: ByteCursor): DrocketLineageState {
        val nextLineageId = c.readLong()
        val nodes = c.readMap<Long, DrocketLineageNode> {
            val id = readLong()
            val node = DrocketLineageNode(
                lineageId = id,
                motherLineageId = readNullableLong(),
                fatherLineageId = readNullableLong(),
                birthTick = readLong(),
                deathTick = readNullableLong(),
                sex = enumByOrdinal(readInt(), Sex.entries),
                genome = readGenome(),
            )
            id to node
        }
        val living = c.readCollection(::LinkedHashSet) { readLong() }
        val entityToLineage = c.readMap<Int, Long> { readInt() to readLong() }
        return DrocketLineageState(
            nextLineageId = nextLineageId,
            nodes = nodes,
            livingLineageIds = living,
            entityToLineageId = entityToLineage,
        )
    }

    // ── Generic helpers ─────────────────────────────────────────────────────────

    private fun ByteWriter.writeBoolean(v: Boolean) = writeInt(if (v) 1 else 0)
    private fun ByteCursor.readBoolean(): Boolean = readInt() != 0

    /** Stores null as [Int.MIN_VALUE]. Callers must not use that sentinel as a valid value. */
    private fun ByteWriter.writeNullableInt(v: Int?) = writeInt(v ?: Int.MIN_VALUE)
    private fun ByteCursor.readNullableInt(): Int? = readInt().takeUnless { it == Int.MIN_VALUE }

    /** Stores null as [Long.MIN_VALUE]. Callers must not use that sentinel as a valid value. */
    private fun ByteWriter.writeNullableLong(v: Long?) = writeLong(v ?: Long.MIN_VALUE)
    private fun ByteCursor.readNullableLong(): Long? = readLong().takeUnless { it == Long.MIN_VALUE }

    private inline fun <T> ByteWriter.writeCollection(
        items: Collection<T>,
        write: ByteWriter.(T) -> Unit,
    ) {
        writeInt(items.size)
        for (item in items) write(item)
    }

    private inline fun <C : MutableCollection<T>, T> ByteCursor.readCollection(
        factory: (Int) -> C,
        read: ByteCursor.() -> T,
    ): C {
        val count = readInt()
        require(count >= 0) { "Invalid collection count: $count" }
        val out = factory(count)
        repeat(count) { out += read() }
        return out
    }

    private inline fun <K, V> ByteWriter.writeMap(
        map: Map<K, V>,
        writeEntry: ByteWriter.(K, V) -> Unit,
    ) {
        writeInt(map.size)
        for ((k, v) in map) writeEntry(k, v)
    }

    private inline fun <K, V> ByteCursor.readMap(
        readEntry: ByteCursor.() -> Pair<K, V>,
    ): LinkedHashMap<K, V> {
        val count = readInt()
        require(count >= 0) { "Invalid map count: $count" }
        val out = LinkedHashMap<K, V>(count)
        repeat(count) {
            val (k, v) = readEntry()
            out[k] = v
        }
        return out
    }

    private fun <E : Enum<E>> enumByOrdinal(ordinal: Int, entries: List<E>): E =
        entries.getOrNull(ordinal) ?: error("Invalid ordinal $ordinal (max ${entries.size - 1})")
}
