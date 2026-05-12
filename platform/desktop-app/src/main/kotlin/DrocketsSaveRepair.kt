package org.emerge.desktop

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import java.nio.file.Files
import java.nio.file.Path

private const val STATE_ENTITY_INT_COUNT = 27
private const val STATE_RESPAWN_INT_COUNT = 11
private const val STATE_CRASH_AUDIO_EVENT_INT_COUNT = 5
private const val STATE_INT_BYTES = 4

/**
 * One-off repair tool for bloated drockets saves that retained particle shells.
 *
 * Usage:
 *   ./gradlew :platform:desktop-app:repairDrocketsSave
 *   ./gradlew :platform:desktop-app:repairDrocketsSave --args="path/to/save.bin"
 */
fun main(args: Array<String>) {
    val savePath = Path.of(args.getOrNull(0) ?: "drockets-save.bin")
    require(Files.exists(savePath)) { "Save not found: ${savePath.toAbsolutePath()}" }

    val bytes = Files.readAllBytes(savePath)
    val repaired = repairDrocketsSave(bytes)
    if (repaired.removedEntities == 0) {
        println("No stale entities found in ${savePath.toAbsolutePath()}; no changes made.")
        return
    }

    val backupPath = savePath.resolveSibling("${savePath.fileName}.bak")
    Files.write(backupPath, bytes)
    Files.write(savePath, repaired.bytes)

    println("Repaired Drockets save: ${savePath.toAbsolutePath()}")
    println("Backup written to:      ${backupPath.toAbsolutePath()}")
    println("Physics entities:       ${repaired.oldEntityCount} -> ${repaired.newEntityCount} (removed ${repaired.removedEntities})")
}

private data class RepairResult(
    val bytes: ByteArray,
    val oldEntityCount: Int,
    val newEntityCount: Int,
    val removedEntities: Int,
)

private fun repairDrocketsSave(bytes: ByteArray): RepairResult {
    val cursor = ByteCursor(bytes)
    val formatVersion = cursor.readInt()
    val tick = cursor.readLong()
    val stateBytesSize = cursor.readInt()
    require(stateBytesSize >= 0) { "Invalid physics state payload size: $stateBytesSize" }
    val stateBytes = cursor.readBytes(stateBytesSize)
    val trailingBytes = cursor.readBytes(cursor.remaining())

    val repairedState = repairPhysicsStateBytes(stateBytes)

    val out = ByteWriter(bytes.size)
    out.writeInt(formatVersion)
    out.writeLong(tick)
    out.writeInt(repairedState.bytes.size)
    out.writeBytes(repairedState.bytes)
    out.writeBytes(trailingBytes)

    return RepairResult(
        bytes = out.toByteArray(),
        oldEntityCount = repairedState.oldEntityCount,
        newEntityCount = repairedState.newEntityCount,
        removedEntities = repairedState.oldEntityCount - repairedState.newEntityCount,
    )
}

private data class RepairedPhysicsState(
    val bytes: ByteArray,
    val oldEntityCount: Int,
    val newEntityCount: Int,
)

private fun repairPhysicsStateBytes(stateBytes: ByteArray): RepairedPhysicsState {
    val c = ByteCursor(stateBytes)
    val entityCount = c.readInt()
    val respawnCount = c.readInt()
    val crashAudioEventCount = c.readInt()
    val randomSeed = c.readLong()
    val lastEntityValue = c.readInt()

    val entitySizeBytes = STATE_ENTITY_INT_COUNT * STATE_INT_BYTES
    val entitiesToKeep = ArrayList<ByteArray>(entityCount)
    repeat(entityCount) {
        val encodedEntity = c.readBytes(entitySizeBytes)
        if (isPersistentEntity(encodedEntity)) {
            entitiesToKeep += encodedEntity
        }
    }

    val respawnBytes = c.readBytes(respawnCount * STATE_RESPAWN_INT_COUNT * STATE_INT_BYTES)
    val crashBytes = c.readBytes(crashAudioEventCount * STATE_CRASH_AUDIO_EVENT_INT_COUNT * STATE_INT_BYTES)
    require(c.remaining() == 0) { "Unexpected trailing bytes in physics state: ${c.remaining()}" }

    val out = ByteWriter(stateBytes.size)
    out.writeInt(entitiesToKeep.size)
    out.writeInt(respawnCount)
    out.writeInt(crashAudioEventCount)
    out.writeLong(randomSeed)
    out.writeInt(lastEntityValue)
    for (entityBytes in entitiesToKeep) {
        out.writeBytes(entityBytes)
    }
    out.writeBytes(respawnBytes)
    out.writeBytes(crashBytes)

    return RepairedPhysicsState(
        bytes = out.toByteArray(),
        oldEntityCount = entityCount,
        newEntityCount = entitiesToKeep.size,
    )
}

private fun isPersistentEntity(encodedEntity: ByteArray): Boolean {
    val c = ByteCursor(encodedEntity)
    c.readInt() // entityId
    repeat(7) { c.readInt() } // transform(3), motion(3), collider(1)
    val mass = c.readInt()
    val bounce = c.readInt()
    val rough = c.readInt()
    return mass > 0 && (bounce > 0 || rough > 0)
}
