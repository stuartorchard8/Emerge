package org.emerge.demo.physics.audio

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.random.Random
import org.emerge.demo.physics.PhysicsFrame
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.primitives.Coord2

data class CrashSfxRequest(
    val clipIndex: Int,
    val volume: Float,
    val pitch: Float,
    val isCrushing: Boolean,
)

interface CrashAudioEngine {
    val clangClipCount: Int
    val crushClipCount: Int

    fun playCrash(request: CrashSfxRequest)

    fun release()
}

class CrashAudioSystem(
    private val engine: CrashAudioEngine,
    private val random: Random = Random.Default,
    private val shipMaxDamageRaw: Long = PhysicsConfig().shipMaxDamage.raw,
    private val worldSize: Float = 2f,
    private val maxAudibleDistance: Float = 0.5f,
    private val minTicksBetweenEntityCrashes: Long = 2L,
) {
    private val lastCrashTickByEntity = LinkedHashMap<EntityId, Long>()

    fun onFrame(frame: PhysicsFrame) {
        if (engine.clangClipCount <= 0 && engine.crushClipCount <= 0) return
        val focus = frame.myId?.let { frame.state.playerViewFocus(it) } ?: Coord2.zero
        val liveEntityIds = HashSet<EntityId>(frame.state.transforms.keys().size)
        liveEntityIds.addAll(frame.state.transforms.keys())
        for (event in frame.state.crashImpactAudioEvents) {
            liveEntityIds += event.entityId
            val entityId = event.entityId
            if (!isEntityReadyForPlayback(entityId, frame.tick)) continue

            val damageVolume = mapDamageToVolume(event.damageRaw)
            val distance = wrappedDistance(event.pos, focus, worldSize)
            val attenuation = distanceAttenuation(distance)
            val useCrushClip = event.destroyed && engine.crushClipCount > 0
            val typeGain = if (useCrushClip) 1.0f else 0.75f
            val finalVolume = (damageVolume * attenuation * typeGain).coerceIn(0f, 1f)
            if (finalVolume <= 0.005f) continue

            val clipCount = if (useCrushClip) engine.crushClipCount else engine.clangClipCount
            if (clipCount <= 0) continue
            val request = CrashSfxRequest(
                clipIndex = random.nextInt(clipCount),
                volume = finalVolume,
                pitch =
                    if (useCrushClip) {
                        random.nextFloat().let { 0.88f + it * (1.02f - 0.88f) }
                    } else {
                        random.nextFloat().let { 0.92f + it * (1.08f - 0.92f) }
                    },
                isCrushing = useCrushClip,
            )
            engine.playCrash(request)
            lastCrashTickByEntity[entityId] = frame.tick
        }
        if (lastCrashTickByEntity.size != liveEntityIds.size) {
            lastCrashTickByEntity.keys.retainAll(liveEntityIds)
        }
    }

    fun release() {
        lastCrashTickByEntity.clear()
        engine.release()
    }

    private fun isEntityReadyForPlayback(entityId: EntityId, tick: Long): Boolean {
        val last = lastCrashTickByEntity[entityId] ?: return true
        return (tick - last) >= minTicksBetweenEntityCrashes
    }

    private fun mapDamageToVolume(deltaRaw: Int): Float {
        if (shipMaxDamageRaw <= 0L) return 0f
        val normalized = (deltaRaw.toDouble() / shipMaxDamageRaw.toDouble()).toFloat().coerceIn(0f, 1f)
        if (normalized <= 0f) return 0f
        return 0.05f + 0.95f * normalized
    }

    private fun distanceAttenuation(distance: Float): Float {
        if (maxAudibleDistance <= 0f) return 0f
        val linear = (1f - (distance / maxAudibleDistance)).coerceIn(0f, 1f)
        return linear * linear * linear
    }

    private fun wrappedDistance(a: Coord2, b: Coord2, size: Float): Float {
        val dx = wrapDelta(a.x.toFloat() - b.x.toFloat(), size)
        val dy = wrapDelta(a.y.toFloat() - b.y.toFloat(), size)
        return hypot(dx, dy)
    }

    private fun wrapDelta(d: Float, size: Float): Float {
        if (size <= 0f) return d
        val half = 0.5f * size
        val shifted = d + half
        val wrapped = shifted - floor(shifted / size) * size
        return wrapped - half
    }
}
